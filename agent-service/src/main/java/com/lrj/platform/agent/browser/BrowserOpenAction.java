package com.lrj.platform.agent.browser;

import com.lrj.platform.agent.AgentAction;
import com.lrj.platform.agent.AgentRunListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Browser-use 动作 {@code browser_open}：用无头浏览器打开一个网址（会执行页面 JS），
 * 返回标题、可见文本与可点击链接列表，是浏览类工具链的入口。委托 {@link BrowserSession#open(String)}；
 * 并实现 {@link AgentRunListener}，在一次 Agent 运行结束时通过 {@link BrowserSession#closeForThread()}
 * 释放线程绑定的浏览器资源。双门控 {@code app.agent.enabled} 与 {@code app.agent.browser.enabled} 才装配。
 */
@Component
@ConditionalOnProperty(name = {"app.agent.enabled", "app.agent.browser.enabled"}, havingValue = "true")
public class BrowserOpenAction implements AgentAction, AgentRunListener {

    private final BrowserSession session;

    public BrowserOpenAction(BrowserSession session) {
        this.session = session;
    }

    @Override
    public String name() {
        return "browser_open";
    }

    @Override
    public String description() {
        return "用无头浏览器打开一个网址（会执行页面 JS）；actionInput 填 URL。返回标题、可见文本、可点击的链接列表";
    }

    @Override
    public String run(String input) {
        return session.open(input);
    }

    @Override
    public void onRunEnd() {
        session.closeForThread();
    }
}
