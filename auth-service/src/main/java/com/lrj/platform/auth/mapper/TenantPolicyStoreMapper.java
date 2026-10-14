package com.lrj.platform.auth.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 类型化持久化入口；SQL在同名XML，调用者不能传入SQL文本。 */
public interface TenantPolicyStoreMapper {
    /** 保留原readInit1操作的条件与影响行数契约。 */
    List<TenantPolicyStoreRow> readInit1();
    /** 保留原readInit2操作的条件与影响行数契约。 */
    List<TenantPolicyStoreRow> readInit2();
    /** 保留原readRolesOf1操作的条件与影响行数契约。 */
    List<String> readRolesOf1(@Param("tenant") String tenant);
    /** 保留原readVersionOf1操作的条件与影响行数契约。 */
    List<Long> readVersionOf1(@Param("tenant") String tenant);
    /** 保留原writeReplaceRoles1操作的条件与影响行数契约。 */
    int writeReplaceRoles1(@Param("updatedAt") Long updatedAt, @Param("tenant") String tenant);
    /** 保留原writeReplaceRoles2操作的条件与影响行数契约。 */
    default int writeReplaceRoles2(@Param("tenant") String tenant, @Param("updatedAt") Long updatedAt) {
        if (restorewriteReplaceRoles2(tenant, updatedAt) == 1) return 1;
        return writeReplaceRoles2New(tenant, updatedAt);
    }
    /** 墓碑条件恢复，不覆盖现有正常记录。 */
    int restorewriteReplaceRoles2(@Param("tenant") String tenant, @Param("updatedAt") Long updatedAt);
    /** 不存在墓碑时插入，由数据库唯一约束处理并发创建。 */
    int writeReplaceRoles2New(@Param("tenant") String tenant, @Param("updatedAt") Long updatedAt);
    /** 保留原writeReplaceRolesIfVersion1操作的条件与影响行数契约。 */
    default int writeReplaceRolesIfVersion1(@Param("tenant") String tenant, @Param("updatedAt") Long updatedAt) {
        if (restorewriteReplaceRolesIfVersion1(tenant, updatedAt) == 1) return 1;
        return writeReplaceRolesIfVersion1New(tenant, updatedAt);
    }
    /** 墓碑条件恢复，不覆盖现有正常记录。 */
    int restorewriteReplaceRolesIfVersion1(@Param("tenant") String tenant, @Param("updatedAt") Long updatedAt);
    /** 不存在墓碑时插入，由数据库唯一约束处理并发创建。 */
    int writeReplaceRolesIfVersion1New(@Param("tenant") String tenant, @Param("updatedAt") Long updatedAt);
    /** 保留原writeReplaceRolesIfVersion2操作的条件与影响行数契约。 */
    int writeReplaceRolesIfVersion2(@Param("updatedAt") Long updatedAt, @Param("tenant") String tenant, @Param("version") Long version);
    /** 保留原writeClear1操作的条件与影响行数契约。 */
    int writeClear1(@Param("tenant") String tenant);
    /** 保留原writeClear2操作的条件与影响行数契约。 */
    int writeClear2(@Param("tenant") String tenant);
    /** 保留原readListPolicyTenants1操作的条件与影响行数契约。 */
    List<String> readListPolicyTenants1();
    /** 保留原readTenantsUsingRole1操作的条件与影响行数契约。 */
    List<String> readTenantsUsingRole1(@Param("roleName") String roleName);
    /** 保留原writeRebuildRoles1操作的条件与影响行数契约。 */
    int writeRebuildRoles1(@Param("tenant") String tenant);
    /** 保留原writeRebuildRoles2操作的条件与影响行数契约。 */
    default int writeRebuildRoles2(@Param("tenant") String tenant, @Param("roleName") String roleName, @Param("createdAt") Long createdAt) {
        if (restorewriteRebuildRoles2(tenant, roleName, createdAt) == 1) return 1;
        return writeRebuildRoles2New(tenant, roleName, createdAt);
    }
    /** 墓碑条件恢复，不覆盖现有正常记录。 */
    int restorewriteRebuildRoles2(@Param("tenant") String tenant, @Param("roleName") String roleName, @Param("createdAt") Long createdAt);
    /** 不存在墓碑时插入，由数据库唯一约束处理并发创建。 */
    int writeRebuildRoles2New(@Param("tenant") String tenant, @Param("roleName") String roleName, @Param("createdAt") Long createdAt);
}
