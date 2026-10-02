package com.lrj.platform.gateway;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;

/** 按真实模型调用边界接入预算等规则，避免依赖会吞异常的 listener 做准入。 */
public interface ChatModelDecorator {
    /** 同步调用包装；工厂所有出口统一应用。 */
    ChatModel decorate(ChatModel model);
    /** 流式包装必须保存发起时身份，不能在异步回调线程重新取租户。 */
    StreamingChatModel decorate(StreamingChatModel model);
    /** 装饰器接管某种记账后，排除旧 listener 防止同一调用重复扣账。 */
    default boolean replaces(ChatModelListener listener) { return false; }
}
