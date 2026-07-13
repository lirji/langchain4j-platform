package com.lrj.platform.knowledge;

/**
 * 公共/共享知识库的保留"租户"标识。公共库并不是一个新的物理存储，而是一个**保留的 tenantId**：
 * 文档以此 id 落进现有的四个 sink（Qdrant 集合 {@code knowledge_segments___public__}、内存
 * DocumentMirror 分区、ES {@code tenantId} term、图谱 Triple），查询时把它与调用方真实租户一起并入。
 *
 * <p>安全约束：该 id 为保留值，禁止任何真实租户/注册用户占用（入库与建户路径需校验）；
 * 公共库的**写**由 {@code public-ingest} scope 控制，**读**并入不改变"看不到别的真实租户"的隔离。
 */
public final class PublicKb {

    /** 公共库保留租户 id。选用不含普通租户命名字符的形态，降低与真实租户冲突概率。 */
    public static final String TENANT_ID = "__public__";

    private PublicKb() {}

    /** 是否为公共库保留 id。 */
    public static boolean isPublic(String tenantId) {
        return TENANT_ID.equals(tenantId);
    }
}
