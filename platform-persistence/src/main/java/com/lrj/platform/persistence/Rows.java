package com.lrj.platform.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import org.springframework.dao.IncorrectResultSizeDataAccessException;

/** 将类型化数据库投影转换为领域对象；保留SQL NULL与原有单行查询的数量约束。 */
public final class Rows {
    private Rows() {}
    /** 不向领域模型暴露持久化行类型，也不在Mapper中注入领域服务。 */
    public static <R, T> List<T> map(List<R> rows, BiFunction<R, Integer, T> convert) {
        List<T> result = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) result.add(convert.apply(rows.get(i), i));
        return result;
    }
    /** SQL聚合可返回null，但缺行/多行不能静默当作成功。 */
    public static <T> T one(List<T> rows) {
        if (rows.isEmpty()) throw new org.springframework.dao.EmptyResultDataAccessException(1);
        if (rows.size() != 1) throw new IncorrectResultSizeDataAccessException(1, rows.size());
        return rows.getFirst();
    }
    /** 保留原ResultSet.getLong读取NULL为0的语义；需要可空语义的字段直接使用包装类型。 */
    public static long longValue(Number value) { return value == null ? 0L : value.longValue(); }
    /** 保留原ResultSet.getInt语义，不改变已有状态统计。 */
    public static int intValue(Number value) { return value == null ? 0 : value.intValue(); }
}
