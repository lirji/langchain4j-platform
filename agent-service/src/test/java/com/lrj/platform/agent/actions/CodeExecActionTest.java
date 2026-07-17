package com.lrj.platform.agent.actions;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CodeExecActionTest：验证 {@link CodeExecAction} 的代码执行动作——正常算术求值、输出截断、超时、
 * 编译错误、不安全 API 拦截、源码过长/为空以及未启用时的提示文案。
 */
class CodeExecActionTest {

    @Test
    void metadataIsStable() {
        CodeExecAction action = action(20_000, 2000);

        assertThat(action.name()).isEqualTo("code_exec");
        assertThat(action.description()).contains("Java");
    }

    @Test
    void arithmeticExpressionReturnsResult() {
        String output = action(20_000, 2000).run("2 + 3 * 4");

        assertThat(output).contains("14");
        assertThat(output).doesNotContain("出错");
    }

    @Test
    void oversizeOutputIsTruncated() {
        String output = action(20_000, 10).run("\"X\".repeat(50)");

        assertThat(output).contains("截断");
        assertThat(output).contains("X");
    }

    @Test
    void slowSnippetTimesOut() {
        String output = action(400, 2000).run("Thread.sleep(60000)");

        assertThat(output).contains("超时");
    }

    @Test
    void compileErrorReturnsCorrectableText() {
        String output = action(20_000, 2000).run("int x = ;");

        assertThat(output).containsAnyOf("出错", "编译");
    }

    @Test
    void unsafeApiIsBlocked() {
        String output = action(20_000, 2000).run("new java.net.Socket(\"example.com\", 80)");

        assertThat(output).contains("拦截");
    }

    @Test
    void sourceTooLongIsRejected() {
        CodeExecProperties properties = enabledProperties(20_000, 2000);
        properties.setMaxSourceChars(20);

        String output = new CodeExecAction(properties).run("1 + 1 + 1 + 1 + 1 + 1 + 1 + 1 + 1 + 1");

        assertThat(output).contains("过长");
    }

    @Test
    void disabledFlagReturnsHint() {
        String output = new CodeExecAction(new CodeExecProperties()).run("2 + 2");

        assertThat(output).contains("已禁用");
    }

    @Test
    void blankInputReturnsHint() {
        String output = action(20_000, 2000).run(" ");

        assertThat(output).contains("为空");
    }

    private static CodeExecAction action(long timeoutMs, int maxOutputChars) {
        return new CodeExecAction(enabledProperties(timeoutMs, maxOutputChars));
    }

    private static CodeExecProperties enabledProperties(long timeoutMs, int maxOutputChars) {
        CodeExecProperties properties = new CodeExecProperties();
        properties.setEnabled(true);
        properties.setTimeoutMs(timeoutMs);
        properties.setMaxOutputChars(maxOutputChars);
        return properties;
    }
}
