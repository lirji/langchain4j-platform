package com.lrj.platform.agent.browser;

import com.lrj.platform.agent.AgentAction;
import com.lrj.platform.agent.client.VisionClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Browser-use 动作：截当前页面图并提交给独立 vision-service 理解——让深度 Agent 能「看」页面，
 * 而不只读 {@code browser_open} 抽出的纯文本（图表/布局/验证码/纯图片页等文本抽不出的内容）。
 * 串联 {@code browser_screenshot}（出截图）与 vision-service（看图），闭合「截图 → 理解」回路。
 *
 * <p><strong>双门控 + BrowserSession 存在时才装配</strong>：{@code app.agent.browser.enabled} 且
 * {@code app.agent.vision.enabled} 同时为 true（{@code @ConditionalOnProperty} 要求全部命中），
 * 且 {@link BrowserSession} Bean 存在。视觉后端关闭时这个动作不出现在工具清单里。
 * 视觉 token 由 vision-service 的 ChatModelListener 按透传的租户归因，正确纳入配额。
 */
@Component
@ConditionalOnBean(BrowserSession.class)
@ConditionalOnProperty(name = {"app.agent.enabled", "app.agent.browser.enabled", "app.agent.vision.enabled"},
        havingValue = "true")
public class BrowserSeeAction implements AgentAction {

    private static final String PNG = "image/png";

    private final BrowserSession session;
    private final VisionClient vision;

    public BrowserSeeAction(BrowserSession session, VisionClient vision) {
        this.session = session;
        this.vision = vision;
    }

    @Override
    public String name() {
        return "browser_see";
    }

    @Override
    public String description() {
        return "截当前页面图并用视觉模型「看」它；actionInput 填想问的问题（留空则整体描述页面）。"
                + "页面文本抽不出的内容（图表/布局/纯图片）用它。先用 browser_open 打开页面。";
    }

    @Override
    public String run(String input) {
        byte[] image = session.screenshotBytes();
        if (image == null || image.length == 0) {
            return "no page open yet; use browser_open first（或截图为空）";
        }
        try {
            String instruction = input == null ? null : input.trim();
            String caption = vision.caption(image, PNG, instruction);
            return caption == null || caption.isBlank()
                    ? "视觉模型未返回内容（可改用 browser_open 读文本，或换问法重试）"
                    : caption;
        } catch (Exception e) {
            return "视觉理解失败：" + e.getMessage() + "（可改用 browser_open 读文本，或换问法重试）";
        }
    }
}
