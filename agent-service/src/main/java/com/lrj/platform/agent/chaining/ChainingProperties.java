package com.lrj.platform.agent.chaining;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code app.agent.chaining.*} 绑定。{@code steps} 是预定义的默认链（顺序 + 步间 gate），
 * 由 yml 定义；链的编排是「预定义代码路径」，不由请求方决定流程。
 *
 * <p>沿用平台 {@code AgentDagProperties} 的可变 JavaBean 绑定风格（{@code @Bean @ConfigurationProperties}）。
 */
public class ChainingProperties {

    /** 默认链的步骤（顺序执行，步间按 gate 校验短路）。 */
    private List<ChainStep> steps = new ArrayList<>();

    public List<ChainStep> getSteps() {
        return steps;
    }

    public void setSteps(List<ChainStep> steps) {
        this.steps = steps;
    }
}
