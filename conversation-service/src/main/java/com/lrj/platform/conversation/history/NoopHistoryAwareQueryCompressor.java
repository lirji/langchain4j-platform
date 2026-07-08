package com.lrj.platform.conversation.history;

/**
 * 默认实现：直通，原样返回追问。{@code app.conversation.history-aware.enabled=false}（默认）时装配，
 * 检索行为与未引入本特性完全一致。
 */
public class NoopHistoryAwareQueryCompressor implements HistoryAwareQueryCompressor {

    @Override
    public String compress(String memoryKey, String followUp) {
        return followUp;
    }
}
