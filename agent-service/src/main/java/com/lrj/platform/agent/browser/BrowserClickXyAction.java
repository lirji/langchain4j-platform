package com.lrj.platform.agent.browser;

import com.lrj.platform.agent.AgentAction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(BrowserSession.class)
@ConditionalOnProperty(name = {"app.agent.enabled", "app.agent.browser.enabled"}, havingValue = "true")
public class BrowserClickXyAction implements AgentAction {

    private final BrowserSession session;

    public BrowserClickXyAction(BrowserSession session) {
        this.session = session;
    }

    @Override
    public String name() {
        return "browser_click_xy";
    }

    @Override
    public String description() {
        return "按像素坐标点击当前页面；actionInput 格式 `x,y`（如 `120,340`）。"
                + "链接文本点不到（图标/canvas/无文字按钮）时用。先 browser_open 打开页面。";
    }

    @Override
    public String run(String input) {
        if (input == null || input.isBlank()) {
            return "入参为空：actionInput 格式 `x,y`（如 `120,340`）。";
        }
        String[] parts = input.trim().split(",", 2);
        if (parts.length != 2) {
            return "格式应为 `x,y`（逗号分隔两个数）；收到：" + input.trim();
        }
        double x;
        double y;
        try {
            x = Double.parseDouble(parts[0].trim());
            y = Double.parseDouble(parts[1].trim());
        } catch (NumberFormatException ex) {
            return "坐标必须是数字；收到：" + input.trim();
        }
        return session.clickAt(x, y);
    }
}
