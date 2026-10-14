#!/usr/bin/env python3
"""阻止业务持久化退回直接 JDBC 或任意 SQL 入口；专用适配器例外逐文件审查。"""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
# 迁移历史、查询沙箱/元数据、Doris ANN 不属于固定关系表 Mapper。
EXCEPTIONS = {
    "analytics-service/src/main/java/com/lrj/platform/analytics/Nl2SqlConfig.java",
    "analytics-service/src/main/java/com/lrj/platform/analytics/GuardedSqlExecutor.java",
    "analytics-service/src/main/java/com/lrj/platform/analytics/SqlQueryTool.java",
    "analytics-service/src/main/java/com/lrj/platform/analytics/SchemaProvider.java",
    "knowledge-service/src/main/java/com/lrj/platform/knowledge/store/doris/DorisEmbeddingStore.java",
    "database-migrations/src/main/java/com/lrj/platform/migrations/AnalyticsDemoDataMigration.java",
    "database-migrations/src/main/java/com/lrj/platform/migrations/LegacyExpandMigration.java",
    "database-migrations/src/main/java/com/lrj/platform/migrations/SchemaMigrationRunner.java",
}
errors = []
for path in ROOT.glob("*/src/main/java/**/*.java"):
    text = path.read_text()
    relative = path.relative_to(ROOT).as_posix()
    if re.search(r"import (?:org.springframework.jdbc.core\.|java.sql.(?:Connection|DriverManager|PreparedStatement|Statement|ResultSet))", text):
        if relative not in EXCEPTIONS:
            errors.append(f"unreviewed direct JDBC: {relative}")
    if "/mapper/" in relative:
        if "Object[]" in text or re.search(r"@(?:Select|Update|Insert|Delete)(?:Provider)?\b", text):
            errors.append(f"Mapper must use named typed parameters and reviewed XML: {relative}")

xmls = list(ROOT.glob("*/src/main/resources/com/**/*Mapper.xml"))
if len(xmls) != 22:
    errors.append(f"expected 22 owned Mapper XMLs; update inventory when adding one, found {len(xmls)}")
for path in xmls:
    text = path.read_text()
    relative = path.relative_to(ROOT).as_posix()
    if "${" in text or re.search(r"\b(?:CREATE|ALTER|DROP)\s+(?:TABLE|INDEX)\b", text, re.I):
        errors.append(f"runtime XML must not interpolate SQL or evolve schemas: {relative}")
    if re.search(r"<delete\b|\bDELETE\s+FROM\b|\bTRUNCATE\b", text, re.I):
        errors.append(f"business deletion must use DELETED=1: {relative}")
    for parameter in re.findall(r"#\{([^}]+)}", text):
        if not re.fullmatch(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*", parameter):
            errors.append(f"invalid named binding {parameter}: {relative}")
    root = ET.fromstring(text)
    # 这些读仅用于去重、单调序号或首次种子判定，不可用作业务列表/详情。
    including_tombstones = {
        ("AsyncTaskEventJournalMapper.xml", "readAppend2"),
        ("AsyncTaskEventJournalMapper.xml", "readFindByEventKey1"),
        ("InboundInboxStoreMapper.xml", "readReceive1"),
        ("UserAccountStoreMapper.xml", "readInit3"),
        ("RoleStoreMapper.xml", "readInit3"),
        ("OrderStoreMapper.xml", "readSeedDemoDataIfEmpty1"),
    }
    for statement in root:
        if statement.tag != "select":
            continue
        sql = " ".join(statement.itertext())
        if (path.name, statement.attrib.get("id")) not in including_tombstones and not re.search(r"\bDELETED\s*=\s*0", sql, re.I):
            errors.append(f"normal read must exclude tombstones: {relative}#{statement.attrib.get('id')}")
    expected = path.relative_to(ROOT / path.parts[len(ROOT.parts)] / "src/main/resources").with_suffix("").as_posix().replace("/", ".")
    if root.attrib.get("namespace") != expected:
        errors.append(f"namespace/resource mismatch: {relative}")
if errors:
    raise SystemExit("\n".join(errors))
print(f"MyBatis persistence boundary PASS: {len(xmls)} XMLs; {len(EXCEPTIONS)} reviewed JDBC exceptions")
