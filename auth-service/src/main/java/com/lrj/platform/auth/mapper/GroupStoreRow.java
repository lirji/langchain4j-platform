package com.lrj.platform.auth.mapper;

/** 数据库读取投影，与领域/API对象分离；包装类型保留SQL NULL。 */
public class GroupStoreRow {
    /** NAME 的数据库值。 */
    public String name;
    /** DESCRIPTION 的数据库值。 */
    public String description;
}
