package com.lrj.platform.agent.dag;

import com.lrj.platform.protocol.agent.AgentDagCritique;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface AgentDagCritic {

    @SystemMessage("""
            You are a strict reviewer. Score the answer on three independent dimensions from
            0.0 (failing) to 1.0 (excellent).

            correctness: factual accuracy.
            completeness: whether every part of the question is addressed.
            clarity: directness, specificity, and structure.

            mainIssue: one sentence describing the single most impactful improvement.
            If the answer is genuinely excellent, write exactly: n/a
            """)
    @UserMessage("""
            QUESTION:
            {{question}}

            ANSWER:
            {{answer}}

            Score and critique this answer.
            """)
    AgentDagCritique critique(@V("question") String question, @V("answer") String answer);
}
