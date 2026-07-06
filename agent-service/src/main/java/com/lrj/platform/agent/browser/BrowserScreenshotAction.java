package com.lrj.platform.agent.browser;

import com.lrj.platform.agent.AgentAction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(BrowserSession.class)
@ConditionalOnProperty(name = {"app.agent.enabled", "app.agent.browser.enabled"}, havingValue = "true")
public class BrowserScreenshotAction implements AgentAction {

    private final BrowserSession session;

    public BrowserScreenshotAction(BrowserSession session) {
        this.session = session;
    }

    @Override
    public String name() {
        return "browser_screenshot";
    }

    @Override
    public String description() {
        return "截当前页面整页图存到文件，返回保存路径（actionInput 留空）。先用 browser_open 打开页面。";
    }

    @Override
    public String run(String input) {
        return session.screenshot();
    }
}
