package com.lrj.platform.knowledge.quality;

import com.lrj.platform.protocol.knowledge.KnowledgeQueryReply;
import com.lrj.platform.protocol.knowledge.RetrievalQuality.Settings;
import com.lrj.platform.security.TenantContext;

/** 沿用生产检索和认证上下文；测试替身不得进入正式装配。 */
@FunctionalInterface
public interface QualityRetriever {
    /** 凭据仅供本次请求使用，不能进入评测快照。 */
    KnowledgeQueryReply retrieve(TenantContext.Tenant actor, String token, String question, Settings settings);
}
