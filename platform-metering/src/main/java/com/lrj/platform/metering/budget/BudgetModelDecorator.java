package com.lrj.platform.metering.budget;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.gateway.ChatModelDecorator;
import com.lrj.platform.metering.TokenBudgetChatModelListener;
import com.lrj.platform.security.TenantContext;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** 准入异常必须在 listener 外抛出；成功结算与流式回调共享发起时的预留身份。 */
public final class BudgetModelDecorator implements ChatModelDecorator {
    private final BudgetLedger ledger;
    private final ReservationBudgetProperties props;
    private final ObjectMapper json = new ObjectMapper().setVisibility(
            com.fasterxml.jackson.annotation.PropertyAccessor.FIELD, com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
            .registerModule(new com.fasterxml.jackson.databind.module.SimpleModule()
                    .addSerializer(dev.langchain4j.data.message.ImageContent.class,
                            new com.fasterxml.jackson.databind.JsonSerializer<dev.langchain4j.data.message.ImageContent>() {
                                @Override public void serialize(dev.langchain4j.data.message.ImageContent image,
                                        com.fasterxml.jackson.core.JsonGenerator output,
                                        com.fasterxml.jackson.databind.SerializerProvider provider) throws java.io.IOException {
                                    // 图像按显式 token allowance 预留，base64 字节不是文本 token。
                                    output.writeStartObject(); output.writeStringField("type", "image"); output.writeEndObject();
                                }
                            }));

    public BudgetModelDecorator(BudgetLedger ledger, ReservationBudgetProperties props) {
        this.ledger = ledger; this.props = props;
    }

    @Override public boolean replaces(ChatModelListener listener) { return listener instanceof TokenBudgetChatModelListener; }

    @Override
    public ChatModel decorate(ChatModel delegate) {
        return new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request, ChatRequestOptions options) {
                var prepared = prepare(request, delegate.defaultRequestParameters());
                var reservation = reserve(prepared);
                // 调用异常可能发生在提供商已计费之后；保持预留，禁止按零 usage 自动退款。
                var response = delegate.chat(prepared.request(), options);
                settle(reservation, response);
                return response;
            }
            @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
            @Override public ModelProvider provider() { return delegate.provider(); }
            @Override public Set<Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }
            @Override public List<ChatModelListener> listeners() { return delegate.listeners(); }
        };
    }

    @Override
    public StreamingChatModel decorate(StreamingChatModel delegate) {
        return new StreamingChatModel() {
            @Override public void chat(ChatRequest request, ChatRequestOptions options, StreamingChatResponseHandler handler) {
                var prepared = prepare(request, delegate.defaultRequestParameters());
                var reservation = reserve(prepared);
                var terminal = new AtomicBoolean();
                delegate.chat(prepared.request(), options, new StreamingChatResponseHandler() {
                    @Override public void onPartialResponse(String value) { if (!terminal.get()) handler.onPartialResponse(value); }
                    @Override public void onPartialResponse(PartialResponse value, PartialResponseContext context) {
                        if (!terminal.get()) handler.onPartialResponse(value, context);
                    }
                    @Override public void onPartialThinking(PartialThinking value) { if (!terminal.get()) handler.onPartialThinking(value); }
                    @Override public void onPartialThinking(PartialThinking value, PartialThinkingContext context) {
                        if (!terminal.get()) handler.onPartialThinking(value, context);
                    }
                    @Override public void onPartialToolCall(PartialToolCall value) { if (!terminal.get()) handler.onPartialToolCall(value); }
                    @Override public void onPartialToolCall(PartialToolCall value, PartialToolCallContext context) {
                        if (!terminal.get()) handler.onPartialToolCall(value, context);
                    }
                    @Override public void onCompleteToolCall(CompleteToolCall value) { if (!terminal.get()) handler.onCompleteToolCall(value); }
                    @Override public void onCompleteResponse(ChatResponse response) {
                        if (!terminal.compareAndSet(false, true)) return;
                        try { settle(reservation, response); }
                        catch (RuntimeException e) { handler.onError(e); return; }
                        handler.onCompleteResponse(response);
                    }
                    @Override public void onError(Throwable error) {
                        if (terminal.compareAndSet(false, true)) handler.onError(error);
                    }
                });
            }
            @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
            @Override public ModelProvider provider() { return delegate.provider(); }
            @Override public Set<Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }
            @Override public List<ChatModelListener> listeners() { return delegate.listeners(); }
        };
    }

    private Prepared prepare(ChatRequest request, ChatRequestParameters defaults) {
        var merged = defaults.overrideWith(request.parameters());
        int output = merged.maxOutputTokens() == null ? props.getMaxOutputTokens() : merged.maxOutputTokens();
        if (output <= 0 || output > props.getMaxOutputTokens()) throw new IllegalArgumentException("model output exceeds budget limit");
        try {
            // UTF-8 字节给文本 token 的保守预算，加上角色/工具封装余量；图片另外预留上限。
            long bytes = json.writeValueAsBytes(request.messages()).length
                    + json.writeValueAsBytes(merged.toolSpecifications()).length;
            if (bytes > props.getMaxInputBytes()) throw new IllegalArgumentException("model input exceeds budget limit");
            long images = request.messages().stream().filter(m -> m instanceof dev.langchain4j.data.message.UserMessage)
                    .map(m -> (dev.langchain4j.data.message.UserMessage) m)
                    .flatMap(m -> m.contents().stream()).filter(c -> c instanceof dev.langchain4j.data.message.ImageContent).count();
            var parameters = OpenAiChatRequestParameters.builder().overrideWith(merged).maxOutputTokens(output).build();
            return new Prepared(request.toBuilder().parameters(parameters).build(),
                    bytes + request.messages().size() * 256L + images * props.getImageTokenAllowance() + output);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("unable to bound model input", e);
        }
    }

    private BudgetLedger.Reservation reserve(Prepared p) {
        var tenant = TenantContext.current();
        if (TenantContext.ANONYMOUS.equals(tenant)) throw new IllegalStateException("authenticated model identity required");
        try { return ledger.reserve(tenant.tenantId(), tenant.userId(), UUID.randomUUID().toString(), p.tokens()); }
        catch (BudgetLedger.Exceeded | BudgetLedger.Conflict e) { throw e; }
        catch (RuntimeException unavailable) { throw new BudgetLedger.Unavailable(unavailable); }
    }

    private void settle(BudgetLedger.Reservation reservation, ChatResponse response) {
        if (response == null || response.tokenUsage() == null) return;
        var usage = response.tokenUsage();
        if (usage.inputTokenCount() == null || usage.outputTokenCount() == null) return;
        if (usage.inputTokenCount() < 0 || usage.outputTokenCount() < 0)
            throw new IllegalStateException("model returned invalid token usage");
        try { ledger.settle(reservation, (long) usage.inputTokenCount() + usage.outputTokenCount()); }
        catch (BudgetLedger.Conflict e) { throw e; }
        catch (RuntimeException unavailable) { throw new BudgetLedger.Unavailable(unavailable); }
    }

    private record Prepared(ChatRequest request, long tokens) {}
}
