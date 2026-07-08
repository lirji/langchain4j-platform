package com.lrj.platform.agent.browser;

import com.lrj.platform.agent.AgentAction;
import com.lrj.platform.agent.AgentRunListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

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
