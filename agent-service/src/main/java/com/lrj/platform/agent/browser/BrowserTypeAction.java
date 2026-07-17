package com.lrj.platform.agent.browser;

import com.lrj.platform.agent.AgentAction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Browser-use 动作 {@code browser_type}：向当前页面的输入框/文本域填入文本，入参格式为
 * {@code CSS选择器=>要填的文本}。解析分隔符 {@link #SEP} 后委托 {@link BrowserSession#type(String, String)}。
 * 双门控 {@code app.agent.enabled} 与 {@code app.agent.browser.enabled} 同时为 true 才装配。
 */
@Component
@ConditionalOnProperty(name = {"app.agent.enabled", "app.agent.browser.enabled"}, havingValue = "true")
public class BrowserTypeAction implements AgentAction {

    static final String SEP = "=>";

    private final BrowserSession session;

    public BrowserTypeAction(BrowserSession session) {
        this.session = session;
    }

    @Override
    public String name() {
        return "browser_type";
    }

    @Override
    public String description() {
        return "在当前页面的输入框/文本域里填文本；actionInput 格式 `CSS选择器" + SEP + "要填的文本`"
                + "（如 `input[name=q]" + SEP + "langchain4j`）。先用 browser_open 打开页面。";
    }

    @Override
    public String run(String input) {
        if (input == null || input.isBlank()) {
            return "入参为空：actionInput 格式 `CSS选择器" + SEP + "要填的文本`。";
        }
        int at = input.indexOf(SEP);
        if (at < 0) {
            return "缺少分隔符 '" + SEP + "'：actionInput 格式 `CSS选择器" + SEP + "要填的文本`。";
        }
        String selector = input.substring(0, at).trim();
        String text = input.substring(at + SEP.length());
        if (selector.isBlank()) {
            return "选择器为空：actionInput 格式 `CSS选择器" + SEP + "要填的文本`。";
        }
        return session.type(selector, text);
    }
}
