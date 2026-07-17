package com.lrj.platform.agent.browser;

import com.lrj.platform.agent.AgentAction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Browser-use 动作 {@code browser_click}：点击当前页面上文本包含给定串的第一个链接。
 * 委托 {@link BrowserSession#click(String)} 执行，供 ReAct Agent 在浏览网页时点选跳转。
 * 双门控装配：{@code app.agent.enabled} 与 {@code app.agent.browser.enabled} 同时为 true 才注册。
 */
@Component
@ConditionalOnProperty(name = {"app.agent.enabled", "app.agent.browser.enabled"}, havingValue = "true")
public class BrowserClickAction implements AgentAction {

    private final BrowserSession session;

    public BrowserClickAction(BrowserSession session) {
        this.session = session;
    }

    @Override
    public String name() {
        return "browser_click";
    }

    @Override
    public String description() {
        return "点击当前页面上文本包含给定串的第一个链接；actionInput 填链接文本（用 browser_open 返回的链接列表里的文本）";
    }

    @Override
    public String run(String input) {
        return session.click(input);
    }
}
