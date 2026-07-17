package com.lrj.platform.agent.actions;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code code_exec} 沙箱装配。绑定 {@link CodeExecProperties}，并按 {@code app.agent.code-exec.sandbox} 选择注入
 * 的 {@code CodeSandbox} 实现：默认/{@code subprocess} 用独立子进程隔离的 {@code SubprocessCodeSandbox}，
 * {@code jshell} 用同 JVM 的 {@code JShellCodeSandbox}。双门控 {@code app.agent.enabled} + {@code app.agent.code-exec.enabled}（默认关）。
 */
@Configuration
@ConditionalOnProperty(name = {"app.agent.enabled", "app.agent.code-exec.enabled"}, havingValue = "true")
public class CodeExecConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.agent.code-exec")
    CodeExecProperties codeExecProperties() {
        return new CodeExecProperties();
    }

    /**
     * 默认沙箱：独立子进程隔离（B7 决策）。缺省或显式 {@code subprocess} 时启用。
     */
    @Bean
    @ConditionalOnProperty(name = "app.agent.code-exec.sandbox", havingValue = "subprocess", matchIfMissing = true)
    CodeSandbox subprocessCodeSandbox(CodeExecProperties properties) {
        return new SubprocessCodeSandbox(properties);
    }

    /**
     * 可选沙箱：同 JVM 的 JShell（仅 denylist+超时+截断，隔离弱）。
     * {@code app.agent.code-exec.sandbox=jshell} 时启用。
     */
    @Bean
    @ConditionalOnProperty(name = "app.agent.code-exec.sandbox", havingValue = "jshell")
    CodeSandbox jshellCodeSandbox() {
        return new JShellCodeSandbox();
    }
}
