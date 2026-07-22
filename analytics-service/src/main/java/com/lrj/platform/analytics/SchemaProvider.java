package com.lrj.platform.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把白名单表的结构内省成一段紧凑的 schema 文本，喂进 {@link SqlAssistant} 的 system prompt。
 * 只暴露 {@code app.nl2sql.allow-tables}，控制 prompt token 成本 + 缩小攻击面（与 {@link SqlGuard} L3 同一份白名单）。
 *
 * <p>用 JDBC {@link DatabaseMetaData#getColumns} 内省（DB-agnostic：H2 / MySQL / PG 通吃），
 * 拿列名 / 类型 / 注释（{@code REMARKS}）。对 {@code app.nl2sql.enum-columns} 标注的列，额外查 distinct 值
 * 带进 schema —— 这是坑3：业务库常有中文枚举（{@code status='已退款'}），不给模型看实际值它会猜英文。
 *
 * <p>schema 文本在构造时算一次并缓存（表结构启动后不变）。
 */
public class SchemaProvider {

    private static final Logger log = LoggerFactory.getLogger(SchemaProvider.class);

    private final String schemaText;
    /** 内省成功的白名单表名（保持配置顺序），供探表端点「列表」用。 */
    private final List<String> tableNames = new ArrayList<>();
    /** 表名（小写）→ 该表字段块文本，供探表端点「按需 describe 单表」用。 */
    private final Map<String, String> perTableBlock = new LinkedHashMap<>();

    public SchemaProvider(DataSource adminDataSource, Nl2SqlProperties props) {
        // build() 顺带填充 tableNames / perTableBlock（字段初始化器已先于此运行）。
        this.schemaText = build(adminDataSource, props);
        log.info("NL2SQL schema 内省完成，暴露 {} 张表", tableNames.size());
    }

    public String schemaText() {
        return schemaText;
    }

    /** 当前可查询（内省成功的白名单）表名。 */
    public List<String> tableNames() {
        return List.copyOf(tableNames);
    }

    /** 单张表的字段块文本；非白名单/未内省到的表返回 {@code null}（探表端点据此 404）。 */
    public String describe(String table) {
        return table == null ? null : perTableBlock.get(table.toLowerCase(Locale.ROOT));
    }

    private String build(DataSource ds, Nl2SqlProperties props) {
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        StringBuilder all = new StringBuilder();
        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            String catalog = conn.getCatalog();
            String schema;
            try {
                schema = conn.getSchema();
            } catch (Exception | AbstractMethodError ignored) {
                schema = null; // JDBC 4.0/旧驱动兼容；catalog 仍限制 MySQL 当前库。
            }
            for (String table : props.getAllowTables()) {
                List<String[]> cols = columns(meta, catalog, schema, table);  // [name, type, remark]
                if (cols.isEmpty()) {
                    log.warn("NL2SQL 白名单表 '{}' 内省不到列（不存在或大小写不匹配），schema 跳过", table);
                    continue;
                }
                StringBuilder block = new StringBuilder();
                block.append("Table ").append(table).append('\n');
                Set<String> colNames = new LinkedHashSet<>();
                for (String[] c : cols) colNames.add(c[0].toLowerCase());
                for (String[] c : cols) {
                    block.append("  - ").append(c[0]).append(' ').append(c[1]);
                    if (c[2] != null && !c[2].isBlank()) {
                        block.append("  -- ").append(c[2].trim());
                    }
                    appendEnumValues(block, jdbc, props, table, c[0], colNames);
                    block.append('\n');
                }
                String blockText = block.toString().strip();
                tableNames.add(table);
                perTableBlock.put(table.toLowerCase(Locale.ROOT), blockText);
                all.append(blockText).append("\n\n");
            }
        } catch (Exception e) {
            log.error("NL2SQL schema 内省失败，返回空 schema（端点会因此无法生成有效 SQL）", e);
            tableNames.clear();
            perTableBlock.clear();
            return "";
        }
        return all.toString().strip();
    }

    private List<String[]> columns(DatabaseMetaData meta, String catalog, String schema, String table) throws Exception {
        // H2/CASE_INSENSITIVE 默认存大写；MySQL 常小写。依次尝试原名 / 大写 / 小写。
        for (String candidate : new LinkedHashSet<>(List.of(table, table.toUpperCase(), table.toLowerCase()))) {
            Map<String, String[]> unique = new LinkedHashMap<>();
            // 必须限定当前连接 catalog/schema。catalog=null 在 MySQL 会扫描所有库，同名 orders 会混入另一套冲突列。
            try (ResultSet rs = meta.getColumns(catalog, schema, candidate, null)) {
                while (rs.next()) {
                    String[] column = new String[]{
                            rs.getString("COLUMN_NAME"),
                            rs.getString("TYPE_NAME"),
                            rs.getString("REMARKS")};
                    if (column[0] != null) {
                        unique.putIfAbsent(column[0].toLowerCase(Locale.ROOT), column);
                    }
                }
            }
            List<String[]> out = new ArrayList<>(unique.values());
            if (!out.isEmpty()) return out;
        }
        return List.of();
    }

    private void appendEnumValues(StringBuilder sb, JdbcTemplate jdbc, Nl2SqlProperties props,
                                  String table, String column, Set<String> colNames) {
        List<String> enumCols = props.getEnumColumns().get(table);
        if (enumCols == null || !enumCols.contains(column.toLowerCase())) {
            return;
        }
        // table/column 都来自可信配置 + 已与内省列名核对，不构成注入面
        if (!colNames.contains(column.toLowerCase())) {
            return;
        }
        try {
            List<String> values = jdbc.query(
                    "SELECT DISTINCT " + column + " FROM " + table + " LIMIT 20",
                    (rs, i) -> rs.getString(1));
            values.removeIf(v -> v == null || v.isBlank());
            if (!values.isEmpty()) {
                sb.append("  (allowed values: ").append(String.join(", ", values)).append(')');
            }
        } catch (Exception e) {
            log.warn("NL2SQL 取 {}.{} distinct 值失败，schema 略过枚举提示", table, column, e);
        }
    }
}
