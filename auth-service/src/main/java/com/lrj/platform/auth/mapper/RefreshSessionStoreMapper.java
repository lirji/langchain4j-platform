package com.lrj.platform.auth.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 类型化持久化入口；SQL在同名XML，调用者不能传入SQL文本。 */
public interface RefreshSessionStoreMapper {
    /** 保留原readInit1操作的条件与影响行数契约。 */
    List<RefreshSessionStoreRow> readInit1();
    /** 保留原writeCreate1操作的条件与影响行数契约。 */
    int writeCreate1(
        @Param("tokenHash") String tokenHash,
        @Param("username") String username,
        @Param("createdAt") Long createdAt,
        @Param("expiresAt") Long expiresAt,
        @Param("revoked") Boolean revoked);
    /** 保留原readFindByTokenHash1操作的条件与影响行数契约。 */
    List<RefreshSessionStoreRow> readFindByTokenHash1(@Param("tokenHash") String tokenHash);
    /** 保留原writeRevoke1操作的条件与影响行数契约。 */
    int writeRevoke1(@Param("tokenHash") String tokenHash);
    /** 保留原writeRevokeByUsername1操作的条件与影响行数契约。 */
    int writeRevokeByUsername1(@Param("username") String username);
    /** 保留原writeDeleteByUsername1操作的条件与影响行数契约。 */
    int writeDeleteByUsername1(@Param("username") String username);
}
