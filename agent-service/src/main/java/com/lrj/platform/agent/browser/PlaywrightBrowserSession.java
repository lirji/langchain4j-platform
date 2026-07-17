package com.lrj.platform.agent.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@link BrowserSession} 的 Playwright（Chromium 无头）实现。每个线程通过 {@link ThreadLocal} 独立持有
 * Playwright/Browser/Page，惰性创建、由 {@link #closeForThread()} 释放；页面渲染结果会截断可见文本
 * （{@code MAX_TEXT_CHARS}）并汇总前若干可点击链接（{@code MAX_LINKS}）返回给 Agent。
 * 双门控 {@code app.agent.enabled} 与 {@code app.agent.browser.enabled} 同时为 true 才装配；
 * 首次使用需在联网环境安装 Chromium 二进制。
 */
@Component
@ConditionalOnProperty(name = {"app.agent.enabled", "app.agent.browser.enabled"}, havingValue = "true")
public class PlaywrightBrowserSession implements BrowserSession {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightBrowserSession.class);

    private static final int MAX_TEXT_CHARS = 2000;
    private static final int MAX_LINKS = 25;
    private static final double NAV_TIMEOUT_MS = 15_000;

    private final ThreadLocal<Holder> holder = new ThreadLocal<>();

    private record Holder(Playwright playwright, Browser browser, Page page) {}

    @Override
    public String open(String url) {
        if (url == null || url.isBlank()) {
            return "no url given";
        }
        String target = url.trim();
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            target = "https://" + target;
        }
        try {
            Page page = page();
            page.navigate(target);
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            return render(page);
        } catch (Exception ex) {
            return "navigation failed for '" + target + "': " + ex.getMessage();
        }
    }

    @Override
    public String click(String linkText) {
        if (linkText == null || linkText.isBlank()) {
            return "no link text given";
        }
        Holder current = holder.get();
        if (current == null) {
            return "no page open yet; use browser_open first";
        }
        String needle = linkText.trim().toLowerCase();
        try {
            Page page = current.page();
            for (ElementHandle link : page.querySelectorAll("a")) {
                String text = safe(link.innerText());
                if (!text.isBlank() && text.toLowerCase().contains(needle)) {
                    link.click();
                    page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                    return render(page);
                }
            }
            return "no link matching '" + linkText + "'. " + linksLine(page);
        } catch (Exception ex) {
            return "click failed for '" + linkText + "': " + ex.getMessage();
        }
    }

    @Override
    public String clickAt(double x, double y) {
        Holder current = holder.get();
        if (current == null) {
            return "no page open yet; use browser_open first";
        }
        try {
            Page page = current.page();
            page.mouse().click(x, y);
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            return "clicked at (" + x + ", " + y + ").\n" + render(page);
        } catch (Exception ex) {
            return "click-at failed for (" + x + ", " + y + "): " + ex.getMessage();
        }
    }

    @Override
    public String type(String selector, String text) {
        if (selector == null || selector.isBlank()) {
            return "no selector given";
        }
        Holder current = holder.get();
        if (current == null) {
            return "no page open yet; use browser_open first";
        }
        try {
            Page page = current.page();
            page.fill(selector.trim(), text == null ? "" : text);
            return "filled '" + selector.trim() + "'.\n" + render(page);
        } catch (Exception ex) {
            return "type failed for selector '" + selector + "': " + ex.getMessage()
                    + "（确认选择器命中一个可输入控件）";
        }
    }

    @Override
    public String screenshot() {
        Holder current = holder.get();
        if (current == null) {
            return "no page open yet; use browser_open first";
        }
        try {
            Page page = current.page();
            Path output = Files.createTempFile("agent-screenshot-", ".png");
            byte[] bytes = page.screenshot(new Page.ScreenshotOptions().setPath(output).setFullPage(true));
            return "截图已保存：" + output.toAbsolutePath() + " (" + bytes.length + " bytes, " + safe(page.url()) + ")";
        } catch (Exception ex) {
            return "screenshot failed: " + ex.getMessage();
        }
    }

    @Override
    public byte[] screenshotBytes() {
        Holder current = holder.get();
        if (current == null) {
            return new byte[0];
        }
        try {
            return current.page().screenshot(new Page.ScreenshotOptions().setFullPage(true));
        } catch (Exception ex) {
            log.debug("screenshotBytes failed: {}", ex.toString());
            return new byte[0];
        }
    }

    @Override
    public void closeForThread() {
        Holder current = holder.get();
        if (current == null) {
            return;
        }
        holder.remove();
        try {
            current.browser().close();
        } catch (Exception ex) {
            log.debug("browser close failed: {}", ex.toString());
        }
        try {
            current.playwright().close();
        } catch (Exception ex) {
            log.debug("playwright close failed: {}", ex.toString());
        }
    }

    private Page page() {
        Holder current = holder.get();
        if (current == null) {
            Playwright playwright = Playwright.create();
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.setDefaultTimeout(NAV_TIMEOUT_MS);
            current = new Holder(playwright, browser, page);
            holder.set(current);
        }
        return current.page();
    }

    private static String render(Page page) {
        StringBuilder builder = new StringBuilder();
        builder.append("title: ").append(safe(page.title())).append('\n');
        builder.append("url: ").append(safe(page.url())).append('\n');
        String text = safe(page.innerText("body")).replaceAll("\\n{3,}", "\n\n").trim();
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS) + "...(truncated)";
        }
        builder.append("text:\n").append(text).append('\n');
        builder.append(linksLine(page));
        return builder.toString();
    }

    private static String linksLine(Page page) {
        List<ElementHandle> anchors = page.querySelectorAll("a");
        StringBuilder builder = new StringBuilder("links: ");
        int shown = 0;
        for (ElementHandle anchor : anchors) {
            String text = safe(anchor.innerText()).replaceAll("\\s+", " ").trim();
            if (text.isBlank()) {
                continue;
            }
            if (shown > 0) {
                builder.append(" | ");
            }
            builder.append('"').append(text.length() > 40 ? text.substring(0, 40) : text).append('"');
            if (++shown >= MAX_LINKS) {
                break;
            }
        }
        if (shown == 0) {
            builder.append("(none)");
        }
        return builder.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
