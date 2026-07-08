package com.lrj.platform.conversation.grounding;

import com.lrj.platform.protocol.knowledge.KnowledgeHit;

import java.util.List;

/**
 * 默认实现：直通，不校验。{@code app.conversation.grounding.enabled=false}（默认）时装配，行为与未引入本特性一致。
 */
public class NoopGroundingChecker implements GroundingChecker {

    @Override
    public GroundingResult verify(String answer, List<KnowledgeHit> sources) {
        return GroundingResult.passthrough(answer);
    }
}
