package com.lrj.platform.agent.actions;

/**
 * 同 JVM 的 JShell 沙箱（历史实现），把 {@link JShellRunner} 包装成 {@link CodeSandbox}。
 *
 * <p>隔离仅靠 {@code CodeExecAction} 的 denylist + 超时 + 输出截断——<strong>不是真正的隔离</strong>：
 * 代码与本服务共享同一 JVM/堆/类加载器。仅在
 * {@code app.agent.code-exec.sandbox=jshell} 时启用；默认走
 * {@link SubprocessCodeSandbox}（独立子进程，隔离更强）。
 */
final class JShellCodeSandbox implements CodeSandbox {

    @Override
    public Outcome run(String source, long timeoutMs, int maxOutputChars) {
        return JShellRunner.run(source, timeoutMs, maxOutputChars);
    }
}
