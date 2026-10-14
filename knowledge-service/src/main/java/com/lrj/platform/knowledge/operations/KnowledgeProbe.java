package com.lrj.platform.knowledge.operations;

import com.lrj.platform.security.TenantContext;
import java.util.List;
import static com.lrj.platform.knowledge.operations.KnowledgeOperations.*;

/** 真实检索探针端口；身份和入站凭证只用于本次调用，不写入候选快照。 */
@FunctionalInterface
public interface KnowledgeProbe {
    List<CheckResult> evaluate(TenantContext.Tenant actor, String internalToken, Candidate candidate);
}
