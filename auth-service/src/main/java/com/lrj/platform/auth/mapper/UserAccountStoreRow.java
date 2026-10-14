package com.lrj.platform.auth.mapper;

/** 数据库读取投影，与领域/API对象分离；包装类型保留SQL NULL。 */
public class UserAccountStoreRow {
    /** USERNAME 的数据库值。 */
    public String username;
    /** PASSWORD_HASH 的数据库值。 */
    public String passwordHash;
    /** TENANT 的数据库值。 */
    public String tenant;
    /** USER_ID 的数据库值。 */
    public String userId;
    /** SCOPES 的数据库值。 */
    public String scopes;
    /** ENABLED 的数据库值。 */
    public Boolean enabled;
}
