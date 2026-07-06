package com.lrj.platform.agent;

public class AgentProperties {

    private boolean enabled = true;
    private int maxSteps = 8;
    private long maxWallClockMs = 0;
    private int maxTokens = 0;
    private int brainMaxRetries = 1;
    private long brainRetryBackoffMs = 0;
    private int maxRepeats = 3;
    private int loopWindow = 6;
    private int maxScratchpadChars = 4000;
    private int historyWindow = 6;
    private boolean allowDelegation = true;
    private int maxDepth = 1;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxSteps() { return maxSteps; }
    public void setMaxSteps(int maxSteps) { this.maxSteps = maxSteps; }
    public long getMaxWallClockMs() { return maxWallClockMs; }
    public void setMaxWallClockMs(long maxWallClockMs) { this.maxWallClockMs = maxWallClockMs; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    public int getBrainMaxRetries() { return brainMaxRetries; }
    public void setBrainMaxRetries(int brainMaxRetries) { this.brainMaxRetries = brainMaxRetries; }
    public long getBrainRetryBackoffMs() { return brainRetryBackoffMs; }
    public void setBrainRetryBackoffMs(long brainRetryBackoffMs) { this.brainRetryBackoffMs = brainRetryBackoffMs; }
    public int getMaxRepeats() { return maxRepeats; }
    public void setMaxRepeats(int maxRepeats) { this.maxRepeats = maxRepeats; }
    public int getLoopWindow() { return loopWindow; }
    public void setLoopWindow(int loopWindow) { this.loopWindow = loopWindow; }
    public int getMaxScratchpadChars() { return maxScratchpadChars; }
    public void setMaxScratchpadChars(int maxScratchpadChars) { this.maxScratchpadChars = maxScratchpadChars; }
    public int getHistoryWindow() { return historyWindow; }
    public void setHistoryWindow(int historyWindow) { this.historyWindow = historyWindow; }
    public boolean isAllowDelegation() { return allowDelegation; }
    public void setAllowDelegation(boolean allowDelegation) { this.allowDelegation = allowDelegation; }
    public int getMaxDepth() { return maxDepth; }
    public void setMaxDepth(int maxDepth) { this.maxDepth = maxDepth; }
}
