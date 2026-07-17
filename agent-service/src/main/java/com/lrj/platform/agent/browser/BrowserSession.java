package com.lrj.platform.agent.browser;

/**
 * 无头浏览器会话抽象，为各 {@code browser_*} 动作提供打开网址、点击链接/坐标、输入文本、
 * 截图（存文件或取字节）与关闭等操作。会话按线程绑定，Agent 运行结束时经
 * {@link #closeForThread()} 释放。默认实现为 {@link PlaywrightBrowserSession}。
 */
public interface BrowserSession {

    String open(String url);

    String click(String linkText);

    String clickAt(double x, double y);

    String type(String selector, String text);

    String screenshot();

    byte[] screenshotBytes();

    void closeForThread();
}
