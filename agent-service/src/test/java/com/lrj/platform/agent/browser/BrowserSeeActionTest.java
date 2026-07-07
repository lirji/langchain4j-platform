package com.lrj.platform.agent.browser;

import com.lrj.platform.agent.client.VisionClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrowserSeeActionTest {

    private final BrowserSession session = mock(BrowserSession.class);
    private final VisionClient vision = mock(VisionClient.class);
    private final BrowserSeeAction action = new BrowserSeeAction(session, vision);

    @Test
    void sends_screenshot_bytes_and_trimmed_instruction_to_vision() {
        byte[] shot = "PNG".getBytes();
        when(session.screenshotBytes()).thenReturn(shot);
        when(vision.caption(any(), eq("image/png"), eq("图里有什么？"))).thenReturn("一个登录表单");

        String out = action.run("  图里有什么？  ");

        assertThat(out).isEqualTo("一个登录表单");
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(vision).caption(bytes.capture(), eq("image/png"), eq("图里有什么？"));
        assertThat(bytes.getValue()).isEqualTo(shot);
    }

    @Test
    void blank_input_passed_as_null_for_default_caption() {
        when(session.screenshotBytes()).thenReturn("x".getBytes());
        when(vision.caption(any(), any(), eq(""))).thenReturn("整页描述");

        String out = action.run("   ");

        assertThat(out).isEqualTo("整页描述");
        verify(vision).caption(any(), eq("image/png"), eq(""));
    }

    @Test
    void no_screenshot_returns_hint_without_calling_vision() {
        when(session.screenshotBytes()).thenReturn(new byte[0]);

        String out = action.run("q");

        assertThat(out).contains("browser_open");
        verify(vision, never()).caption(any(), any(), any());
    }

    @Test
    void vision_failure_is_caught_and_reported() {
        when(session.screenshotBytes()).thenReturn("x".getBytes());
        when(vision.caption(any(), any(), any())).thenThrow(new RuntimeException("connect timeout"));

        String out = action.run("q");

        assertThat(out).contains("视觉理解失败").contains("connect timeout");
    }

    @Test
    void empty_caption_returns_fallback_hint() {
        when(session.screenshotBytes()).thenReturn("x".getBytes());
        when(vision.caption(any(), any(), any())).thenReturn("   ");

        String out = action.run("q");

        assertThat(out).contains("未返回内容");
    }

    @Test
    void action_metadata() {
        assertThat(action.name()).isEqualTo("browser_see");
        assertThat(action.description()).contains("视觉");
    }
}
