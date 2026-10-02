package com.lrj.platform.conversation.shadow;

import com.lrj.platform.protocol.conversation.ConversationGenerationRequest;

/** 流式影子会话. 完成只比较指标, 取消不能影响primary状态或响应. */
public interface ConversationStreamShadowObserver {
    boolean enabled();
    Observation open(ConversationGenerationRequest request);

    interface Observation {
        void finish(String primaryReply);
        void cancel();
    }

    /** 禁用时不读取额外历史、不创建线程或调用候选. */
    static ConversationStreamShadowObserver disabled() {
        return new ConversationStreamShadowObserver() {
            public boolean enabled() { return false; }
            public Observation open(ConversationGenerationRequest request) { return noop(); }
        };
    }

    static Observation noop() {
        return new Observation() {
            public void finish(String primaryReply) { }
            public void cancel() { }
        };
    }
}
