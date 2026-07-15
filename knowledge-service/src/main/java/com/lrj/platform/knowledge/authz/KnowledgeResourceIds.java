package com.lrj.platform.knowledge.authz;

/**
 * SpiceDB 资源 id 编码（{@code <tenantId>_<id>} 约定）的唯一入口，避免多处手写拼接产生漂移。
 * 由服务端构造，不接受客户端传入完整 tenant-prefixed id。
 */
public final class KnowledgeResourceIds {

    private KnowledgeResourceIds() {}

    /** document 资源 id：{@code <tenantId>_<docId>}。 */
    public static String document(String tenantId, String docId) {
        return join(tenantId, docId);
    }

    /** space 资源 id：{@code <tenantId>_<spaceId>}。 */
    public static String space(String tenantId, String spaceId) {
        return join(tenantId, spaceId);
    }

    /** department 资源 id：{@code <tenantId>_<deptId>}（与 {@code CasdoorGroupIds.encode} 同构）。 */
    public static String department(String tenantId, String deptId) {
        return join(tenantId, deptId);
    }

    /** organization 资源 id：{@code <tenantId>}。 */
    public static String organization(String tenantId) {
        return tenantId;
    }

    private static String join(String tenantId, String id) {
        return tenantId + "_" + id;
    }
}
