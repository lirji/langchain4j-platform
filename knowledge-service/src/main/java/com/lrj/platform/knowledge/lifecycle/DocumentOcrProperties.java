package com.lrj.platform.knowledge.lifecycle;

import java.nio.file.Path;
import java.time.Duration;

/** 可选本地 OCR 的独立成本/质量预算；仅在选中页面时执行引擎，不自动下载语言包。 */
public final class DocumentOcrProperties {
    private boolean enabled;
    private String executable = "/usr/bin/tesseract", languages = "chi_sim+eng";
    private int dpi = 200, maxPages = 50, minNativeChars = 40, maxOutputBytes = 2 * 1024 * 1024;
    private long maxPagePixels = 12_000_000, maxTotalPixels = 120_000_000;
    private double imageCoverage = 0.35, minConfidence = 55;
    private Duration pageTimeout = Duration.ofSeconds(20), totalTimeout = Duration.ofSeconds(90);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getExecutable() { return executable; }
    public void setExecutable(String value) {
        if (value == null || !Path.of(value).isAbsolute()) invalid();
        executable = value;
    }
    public String getLanguages() { return languages; }
    public void setLanguages(String value) {
        if (value == null || !value.matches("(?:eng|chi_sim|chi_tra)(?:\\+(?:eng|chi_sim|chi_tra))*")) invalid();
        languages = value;
    }
    /** 提高 DPI 会平方增加内存与处理量，像素预算始终在渲染前验证。 */
    public int getDpi() { return dpi; }
    public void setDpi(int value) { if(value<100 || value>400) invalid(); dpi=value; }
    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int value) { positive(value); maxPages=value; }
    public int getMinNativeChars() { return minNativeChars; }
    public void setMinNativeChars(int value) { positive(value); minNativeChars=value; }
    public int getMaxOutputBytes() { return maxOutputBytes; }
    public void setMaxOutputBytes(int value) { positive(value); maxOutputBytes=value; }
    public long getMaxPagePixels() { return maxPagePixels; }
    public void setMaxPagePixels(long value) { positive(value); maxPagePixels=value; }
    public long getMaxTotalPixels() { return maxTotalPixels; }
    public void setMaxTotalPixels(long value) { positive(value); maxTotalPixels=value; }
    public double getImageCoverage() { return imageCoverage; }
    public void setImageCoverage(double value) { if(!Double.isFinite(value)||value<=0||value>1) invalid(); imageCoverage=value; }
    public double getMinConfidence() { return minConfidence; }
    public void setMinConfidence(double value) { if(!Double.isFinite(value)||value<0||value>100) invalid(); minConfidence=value; }
    public Duration getPageTimeout() { return pageTimeout; }
    public void setPageTimeout(Duration value) { positive(value.toMillis()); pageTimeout=value; }
    public Duration getTotalTimeout() { return totalTimeout; }
    public void setTotalTimeout(Duration value) { positive(value.toMillis()); totalTimeout=value; }

    /** 子进程只接收无凭据的数字/允许列表策略，不读取父环境或整个应用配置。 */
    Settings snapshot() { return new Settings(enabled,executable,languages,dpi,maxPages,minNativeChars,maxOutputBytes,
            maxPagePixels,maxTotalPixels,imageCoverage,minConfidence,pageTimeout.toMillis(),totalTimeout.toMillis()); }
    void apply(Settings value) {
        setEnabled(value.enabled());setExecutable(value.executable());setLanguages(value.languages());setDpi(value.dpi());
        setMaxPages(value.maxPages());setMinNativeChars(value.minNativeChars());setMaxOutputBytes(value.maxOutputBytes());
        setMaxPagePixels(value.maxPagePixels());setMaxTotalPixels(value.maxTotalPixels());setImageCoverage(value.imageCoverage());
        setMinConfidence(value.minConfidence());setPageTimeout(Duration.ofMillis(value.pageTimeoutMillis()));setTotalTimeout(Duration.ofMillis(value.totalTimeoutMillis()));
    }
    /** 进程协议只含简单字段，避免时间模块或多态类型反序列化依赖。 */
    record Settings(boolean enabled,String executable,String languages,int dpi,int maxPages,int minNativeChars,int maxOutputBytes,
                    long maxPagePixels,long maxTotalPixels,double imageCoverage,double minConfidence,long pageTimeoutMillis,long totalTimeoutMillis) { }
    private static void positive(long value) { if(value<1) invalid(); }
    private static void invalid() { throw new IllegalArgumentException("invalid document OCR configuration"); }
}
