package com.lrj.platform.auth.mapper;

/** 数据库读取投影，与领域/API对象分离；包装类型保留SQL NULL。 */
public class RefreshSessionStoreRow {
    /** TOKEN_HASH 的数据库值。 */
    public String tokenHash;
    /** USERNAME 的数据库值。 */
    public String username;
    /** CREATED_AT 的数据库值。 */
    public Long createdAt;
    /** EXPIRES_AT 的数据库值。 */
    public Long expiresAt;
    /** REVOKED 的数据库值。 */
    public Boolean revoked;
}
