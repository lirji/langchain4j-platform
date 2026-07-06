package com.lrj.platform.agent.browser;

public interface BrowserSession {

    String open(String url);

    String click(String linkText);

    String clickAt(double x, double y);

    String type(String selector, String text);

    String screenshot();

    byte[] screenshotBytes();

    void closeForThread();
}
