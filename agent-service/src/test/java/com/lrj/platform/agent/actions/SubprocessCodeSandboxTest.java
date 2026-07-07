package com.lrj.platform.agent.actions;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 独立子进程沙箱的行为测试。用真实短子进程验证：表达式求值/输出截断/超时强杀/退出码/不可用降级。
 * 跨平台（依赖当前 JDK 的 java.home，不依赖网络）。
 */
class SubprocessCodeSandboxTest {

    @BeforeAll
    static void jdkAvailable() {
        // 需要当前 JVM 的 java.home 下有可执行的 java（Maven 跑在 JDK 上，正常满足）。
        String javaHome = System.getProperty("java.home");
        assumeTrue(javaHome != null && !javaHome.isBlank(), "java.home not set");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path java = Path.of(javaHome, "bin", windows ? "java.exe" : "java");
        assumeTrue(Files.isExecutable(java), "java executable not found: " + java);
    }

    @Test
    void arithmeticExpressionReturnsValue() {
        CodeSandbox.Outcome outcome = sandbox().run("2 + 3 * 4", 20_000, 2000);

        assertThat(outcome.error()).isNull();
        assertThat(outcome.timedOut()).isFalse();
        assertThat(outcome.output()).contains("14");
    }

    @Test
    void printlnOutputIsCaptured() {
        CodeSandbox.Outcome outcome = sandbox().run("System.out.println(\"v=\" + (6 * 7));", 20_000, 2000);

        assertThat(outcome.error()).isNull();
        assertThat(outcome.output()).contains("v=42");
    }

    @Test
    void oversizeOutputIsTruncated() {
        CodeSandbox.Outcome outcome = sandbox().run("\"X\".repeat(500)", 20_000, 8);

        assertThat(outcome.timedOut()).isFalse();
        assertThat(outcome.truncated()).isTrue();
        assertThat(outcome.output()).hasSizeLessThanOrEqualTo(8);
        assertThat(outcome.output()).contains("X");
    }

    @Test
    void slowSnippetIsKilledByWallClock() {
        CodeSandbox.Outcome outcome = sandbox().run("Thread.sleep(60000)", 1000, 2000);

        assertThat(outcome.timedOut()).isTrue();
    }

    @Test
    void compileErrorIsReportedAsError() {
        CodeSandbox.Outcome outcome = sandbox().run("int x = ;", 20_000, 2000);

        assertThat(outcome.timedOut()).isFalse();
        assertThat(outcome.error()).isNotNull();
        assertThat(outcome.error()).contains("编译");
    }

    @Test
    void nonZeroExitCodeBecomesError() {
        CodeSandbox.Outcome outcome = sandbox().run("System.exit(7);", 20_000, 2000);

        assertThat(outcome.timedOut()).isFalse();
        assertThat(outcome.error()).isNotNull();
        assertThat(outcome.error()).contains("7");
    }

    @Test
    void missingJavaExecutableDegradesToError() {
        CodeExecProperties properties = new CodeExecProperties();
        properties.setJavaExecutable("/definitely/not/here/java");
        CodeSandbox.Outcome outcome = new SubprocessCodeSandbox(properties).run("2 + 2", 5000, 2000);

        assertThat(outcome.timedOut()).isFalse();
        assertThat(outcome.error()).isNotNull();
        assertThat(outcome.error()).containsAnyOf("不可用", "找不到");
    }

    private static SubprocessCodeSandbox sandbox() {
        CodeExecProperties properties = new CodeExecProperties();
        properties.setMaxHeapMb(64);
        return new SubprocessCodeSandbox(properties);
    }
}
