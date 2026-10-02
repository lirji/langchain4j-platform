package com.lrj.platform.metering.budget;

import com.lrj.platform.security.TenantContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BudgetModelDecoratorTest {
    private final BudgetLedger ledger = mock(BudgetLedger.class);
    private final BudgetModelDecorator decorator = new BudgetModelDecorator(ledger, new ReservationBudgetProperties());
    private final ChatRequest request = ChatRequest.builder().messages(UserMessage.from("hello")).build();
    private final BudgetLedger.Reservation reservation = new BudgetLedger.Reservation("t1", "u1", "op", "2026-10-01", 100);
    @AfterEach void clear() { TenantContext.clear(); }
    private void identity() {
        TenantContext.set(new TenantContext.Tenant("t1", "u1", Set.of("chat")));
        when(ledger.reserve(eq("t1"), eq("u1"), anyString(), anyLong())).thenReturn(reservation);
    }
    private ChatResponse response() {
        return ChatResponse.builder().aiMessage(AiMessage.from("answer")).tokenUsage(new TokenUsage(10, 20)).build();
    }

    @Test void admissionRunsBeforeProviderAndOutputIsBounded() {
        identity();
        var delegate = mock(ChatModel.class);
        when(delegate.defaultRequestParameters()).thenReturn(ChatRequestParameters.builder().build());
        when(delegate.chat(any(), any())).thenReturn(response());
        decorator.decorate(delegate).chat(request, ChatRequestOptions.EMPTY);
        var captured = ArgumentCaptor.forClass(ChatRequest.class);
        verify(delegate).chat(captured.capture(), any());
        assertThat(captured.getValue().parameters().maxOutputTokens()).isEqualTo(4096);
        verify(ledger).settle(reservation, 30);
        var ordered = inOrder(ledger, delegate);
        ordered.verify(delegate).defaultRequestParameters();
        ordered.verify(ledger).reserve(eq("t1"), eq("u1"), anyString(), anyLong());
        ordered.verify(delegate).chat(any(), any());
        ordered.verify(ledger).settle(reservation, 30);
    }

    @Test void deniedAdmissionNeverCallsProviderAndUnknownUsageDoesNotRelease() {
        identity();
        var delegate = mock(ChatModel.class);
        when(delegate.defaultRequestParameters()).thenReturn(ChatRequestParameters.builder().build());
        when(ledger.reserve(anyString(), anyString(), anyString(), anyLong())).thenThrow(new BudgetLedger.Exceeded());
        assertThatThrownBy(() -> decorator.decorate(delegate).chat(request)).isInstanceOf(BudgetLedger.Exceeded.class);
        verify(delegate, never()).chat(any(), any());
        doReturn(reservation).when(ledger).reserve(anyString(), anyString(), anyString(), anyLong());
        when(delegate.chat(any(), any())).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("unknown")).build());
        decorator.decorate(delegate).chat(request);
        verify(ledger, never()).settle(any(), anyLong());
    }

    @Test void streamingCapturesOwnerAndHasSingleTerminalSettlement() {
        identity();
        var delegate = mock(StreamingChatModel.class);
        when(delegate.defaultRequestParameters()).thenReturn(ChatRequestParameters.builder().build());
        var downstream = mock(StreamingChatResponseHandler.class);
        var callbacks = new AtomicReference<StreamingChatResponseHandler>();
        doAnswer(invocation -> { callbacks.set(invocation.getArgument(2)); return null; })
                .when(delegate).chat(any(), any(), any());
        decorator.decorate(delegate).chat(request, ChatRequestOptions.EMPTY, downstream);
        TenantContext.clear();
        callbacks.get().onPartialResponse("answer");
        callbacks.get().onCompleteResponse(response());
        callbacks.get().onCompleteResponse(response());
        callbacks.get().onError(new RuntimeException());
        verify(ledger, times(1)).settle(reservation, 30);
        verify(downstream, times(1)).onCompleteResponse(any());
        verify(downstream, never()).onError(any());
    }
}
