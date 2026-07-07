package com.lrj.platform.agent.actions;

public class CodeExecProperties {

    private boolean enabled = false;
    private long timeoutMs = 3000;
    private int maxOutputChars = 2000;
    private int maxSourceChars = 4000;
    private boolean blockUnsafeApis = true;

    /** 沙箱实现：{@code subprocess}（默认，独立子进程隔离）或 {@code jshell}（同 JVM，仅 denylist）。 */
    private String sandbox = "subprocess";

    /** 子进程沙箱的 {@code -Xmx} 堆上限（MB）；{@code <=0} 表示不加 -Xmx。 */
    private int maxHeapMb = 64;

    /** 起子进程用的 java 可执行文件；留空则用当前 JVM 的 {@code java.home}/bin/java。 */
    private String javaExecutable = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getMaxOutputChars() {
        return maxOutputChars;
    }

    public void setMaxOutputChars(int maxOutputChars) {
        this.maxOutputChars = maxOutputChars;
    }

    public int getMaxSourceChars() {
        return maxSourceChars;
    }

    public void setMaxSourceChars(int maxSourceChars) {
        this.maxSourceChars = maxSourceChars;
    }

    public boolean isBlockUnsafeApis() {
        return blockUnsafeApis;
    }

    public void setBlockUnsafeApis(boolean blockUnsafeApis) {
        this.blockUnsafeApis = blockUnsafeApis;
    }

    public String getSandbox() {
        return sandbox;
    }

    public void setSandbox(String sandbox) {
        this.sandbox = sandbox;
    }

    public int getMaxHeapMb() {
        return maxHeapMb;
    }

    public void setMaxHeapMb(int maxHeapMb) {
        this.maxHeapMb = maxHeapMb;
    }

    public String getJavaExecutable() {
        return javaExecutable;
    }

    public void setJavaExecutable(String javaExecutable) {
        this.javaExecutable = javaExecutable;
    }
}
