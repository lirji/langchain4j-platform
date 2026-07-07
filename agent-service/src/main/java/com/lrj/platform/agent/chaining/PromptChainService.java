package com.lrj.platform.agent.chaining;

import com.lrj.platform.protocol.agent.ChainRunReply;
import com.lrj.platform.protocol.agent.ChainStepResult;
import com.lrj.platform.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Prompt Chaining 编排：把输入依次喂过一串 {@link ChainStep}，每步一次 LLM 调用、只处理上一步输出，
 * 步间执行确定性 gate；gate 不过就<strong>短路</strong>终止（Anthropic Prompt Chaining 模式）。
 *
 * <p>这是「预定义代码路径编排 LLM 调用」——步骤顺序与 gate 写死在配置/代码里，不由模型决定流程，
 * 因此可重复、可单测、可控。作为 {@code DeepAgentService} 的同级 sibling 编排器（不塞进 ReAct 内部），
 * 与固定 DAG 的 {@code AgentDagService}（Orchestrator-Workers）正交：链是<strong>顺序</strong>依赖、
 * DAG 是<strong>并行</strong>分层。
 */
@Service
@ConditionalOnBean(ChainLink.class)
public class PromptChainService {

    private static final Logger log = LoggerFactory.getLogger(PromptChainService.class);

    private final ChainLink link;

    public PromptChainService(ChainLink link) {
        this.link = link;
    }

    public ChainRunReply run(String input, List<ChainStep> steps) {
        String current = safe(input);
        List<ChainStepResult> results = new ArrayList<>();
        String tenantId = TenantContext.current().tenantId();
        for (ChainStep step : steps) {
            String output = safe(link.transform(step.instruction(), current));
            String gateReason = gateFailure(step, output);
            boolean passed = gateReason == null;
            results.add(new ChainStepResult(step.name(), output, passed, gateReason));
            if (!passed) {
                log.info("prompt chain stopped at step '{}' gate: {}", step.name(), gateReason);
                return new ChainRunReply(input, results, output, false, tenantId);
            }
            current = output;
        }
        return new ChainRunReply(input, results, current, true, tenantId);
    }

    /** 确定性 gate 判定：返回失败原因；null = 通过。 */
    private String gateFailure(ChainStep s, String out) {
        if (s.gateMinLength() > 0 && out.length() < s.gateMinLength()) {
            return "输出过短（" + out.length() + " < " + s.gateMinLength() + " 字符）";
        }
        if (notBlank(s.gateMustContain()) && !out.contains(s.gateMustContain())) {
            return "缺少必需内容：" + s.gateMustContain();
        }
        if (notBlank(s.gateMustMatch())) {
            try {
                if (!Pattern.compile(s.gateMustMatch()).matcher(out).find()) {
                    return "未命中模式：" + s.gateMustMatch();
                }
            } catch (RuntimeException e) {
                // 坏正则不该炸整条链，记警告当作未配置该 gate
                log.warn("invalid gate regex '{}' on step '{}', skipping this gate", s.gateMustMatch(), s.name());
            }
        }
        return null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
