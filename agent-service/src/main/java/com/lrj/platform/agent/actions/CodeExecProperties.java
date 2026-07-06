package com.lrj.platform.agent.actions;

public class CodeExecProperties {

    private boolean enabled = false;
    private long timeoutMs = 3000;
    private int maxOutputChars = 2000;
    private int maxSourceChars = 4000;
    private boolean blockUnsafeApis = true;

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
}
