package com.lrj.platform.agent.browser;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserActionsTest {

    @Test
    void openDelegatesToSessionAndClosesOnRunEnd() {
        FakeSession session = new FakeSession();
        BrowserOpenAction action = new BrowserOpenAction(session);

        assertThat(action.name()).isEqualTo("browser_open");
        assertThat(action.run("example.com")).isEqualTo("opened:example.com");
        action.onRunEnd();

        assertThat(session.opened).containsExactly("example.com");
        assertThat(session.closed).isEqualTo(1);
    }

    @Test
    void clickDelegatesToSession() {
        FakeSession session = new FakeSession();

        String observation = new BrowserClickAction(session).run("Docs");

        assertThat(observation).isEqualTo("clicked:Docs");
        assertThat(session.clicked).containsExactly("Docs");
    }

    @Test
    void clickXyParsesCoordinates() {
        FakeSession session = new FakeSession();

        String observation = new BrowserClickXyAction(session).run("120, 340");

        assertThat(observation).isEqualTo("clickedAt:120.0,340.0");
        assertThat(session.clickedXy).containsExactly("120.0,340.0");
    }

    @Test
    void clickXyRejectsInvalidInput() {
        FakeSession session = new FakeSession();

        String observation = new BrowserClickXyAction(session).run("left,top");

        assertThat(observation).contains("数字");
        assertThat(session.clickedXy).isEmpty();
    }

    @Test
    void typeSplitsSelectorAndText() {
        FakeSession session = new FakeSession();

        String observation = new BrowserTypeAction(session).run("input[name=q]=>langchain4j");

        assertThat(observation).isEqualTo("typed:input[name=q]=langchain4j");
        assertThat(session.typed).containsExactly("input[name=q]|langchain4j");
    }

    @Test
    void typeRejectsMissingSeparator() {
        FakeSession session = new FakeSession();

        String observation = new BrowserTypeAction(session).run("just text");

        assertThat(observation).contains("分隔符");
        assertThat(session.typed).isEmpty();
    }

    @Test
    void screenshotDelegatesToSession() {
        FakeSession session = new FakeSession();

        String observation = new BrowserScreenshotAction(session).run("");

        assertThat(observation).isEqualTo("shot#1");
        assertThat(session.shots).isEqualTo(1);
    }

    static class FakeSession implements BrowserSession {
        final List<String> opened = new ArrayList<>();
        final List<String> clicked = new ArrayList<>();
        final List<String> clickedXy = new ArrayList<>();
        final List<String> typed = new ArrayList<>();
        int shots;
        int closed;

        @Override
        public String open(String url) {
            opened.add(url);
            return "opened:" + url;
        }

        @Override
        public String click(String linkText) {
            clicked.add(linkText);
            return "clicked:" + linkText;
        }

        @Override
        public String clickAt(double x, double y) {
            clickedXy.add(x + "," + y);
            return "clickedAt:" + x + "," + y;
        }

        @Override
        public String type(String selector, String text) {
            typed.add(selector + "|" + text);
            return "typed:" + selector + "=" + text;
        }

        @Override
        public String screenshot() {
            shots++;
            return "shot#" + shots;
        }

        @Override
        public byte[] screenshotBytes() {
            return new byte[] {1, 2, 3};
        }

        @Override
        public void closeForThread() {
            closed++;
        }
    }
}
