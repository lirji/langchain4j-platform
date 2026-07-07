package com.lrj.platform.agent.actions;

/**
 * code_exec 的代码执行沙箱抽象。
 *
 * <p>把「怎么隔离地跑一段 Java 源码」从 {@link CodeExecAction} 的门控/denylist/超时/截断逻辑里剥离出来，
 * 便于在同 JVM 的 JShell（{@link JShellCodeSandbox}）与独立子进程
 * （{@link SubprocessCodeSandbox}，默认）之间切换。
 *
 * <p>约定：实现<strong>绝不</strong>把异常抛给调用方——任何失败（编译错误、运行时异常、超时、
 * 子进程不可用等）都要收口成一个明确的 {@link Outcome}，让 agent 主循环拿到可读的错误文案而不是崩溃。
 */
public interface CodeSandbox {

    /**
     * 执行一段 Java 源码。签名与历史上的 {@code JShellRunner.run} 对齐。
     *
     * @param source         要执行的 Java 源码（JShell 片段语义：可为裸表达式/语句/声明）
     * @param timeoutMs      墙钟超时上限，超时后强制结束执行
     * @param maxOutputChars 输出字符上限，超出即截断并置位 {@link Outcome#truncated()}
     * @return 收口后的执行结果，永不为 {@code null}
     */
    Outcome run(String source, long timeoutMs, int maxOutputChars);

    /**
     * 执行结果。
     *
     * @param output    捕获到的 stdout/表达式值（已按 maxOutputChars 截断）
     * @param error     出错时的可读错误文案；成功为 {@code null}
     * @param timedOut  是否因超时被强制结束
     * @param truncated 输出是否被截断
     */
    record Outcome(String output, String error, boolean timedOut, boolean truncated) {}
}
