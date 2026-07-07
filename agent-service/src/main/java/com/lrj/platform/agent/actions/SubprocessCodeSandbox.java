package com.lrj.platform.agent.actions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 独立子进程沙箱（默认实现，见 B7 决策）。
 *
 * <p>用 {@link ProcessBuilder} 起一个受限的 JDK 子进程执行源码，隔离手段：
 * <ul>
 *   <li>独立进程 + 独立堆（{@code -Xmx} 限内存）；</li>
 *   <li>{@code environment().clear()} 不继承任何环境变量；</li>
 *   <li>临时<strong>空目录</strong>作为 cwd（用后递归删除）；</li>
 *   <li>墙钟超时后 {@link Process#destroyForcibly()} 强杀；</li>
 *   <li>stdout/stderr 收口并按字符上限截断。</li>
 * </ul>
 *
 * <p>子进程内跑一段自包含的 {@code java --source} 驱动程序（{@code Sandbox.java}），
 * 它用 JShell 编程式 API（{@code executionEngine("local")}）逐片段执行源码，
 * 语义与 {@link JShellRunner} 一致（裸表达式也会产出值），因此从 {@code jshell} 切到
 * {@code subprocess} 对使用方是透明的。源码通过 <em>stdin</em> 传入以避免转义问题。
 *
 * <p><strong>隔离边界</strong>：这是「中等隔离」而非容器级强隔离——子进程仍与宿主共享
 * 内核/文件系统/网络命名空间（denylist 作为纵深防御仍在 {@code CodeExecAction} 层保留）。
 * 面向<strong>不可信输入</strong>的生产场景，仍建议一次性容器或远程 sandbox-service。
 *
 * <p>任何失败（子进程起不来、JDK 不可用、编译/运行时错误、超时）都收口成明确的
 * {@link Outcome}，绝不把异常抛到 agent 主循环。
 */
final class SubprocessCodeSandbox implements CodeSandbox {

    private static final Logger log = LoggerFactory.getLogger(SubprocessCodeSandbox.class);

    /** 子进程结束后再多等这么久，让 destroyForcibly 生效并让流关闭。 */
    private static final long DRAIN_GRACE_MS = 2000;

    /** 子进程驱动程序退出码：源码编译/运行时错误。 */
    private static final int EXIT_SNIPPET_ERROR = 3;

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "code-exec-subprocess");
        thread.setDaemon(true);
        return thread;
    });

    private final CodeExecProperties properties;

    SubprocessCodeSandbox(CodeExecProperties properties) {
        this.properties = properties;
    }

    @Override
    public Outcome run(String source, long timeoutMs, int maxOutputChars) {
        Path javaExe = resolveJavaExecutable();
        if (javaExe == null || !Files.isExecutable(javaExe)) {
            return new Outcome(null, "代码执行子进程沙箱不可用：找不到可用的 java 可执行文件（java.home 异常）。",
                    false, false);
        }

        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("code-exec-");
            Path driver = workDir.resolve("Sandbox.java");
            Files.writeString(driver, DRIVER_SOURCE, StandardCharsets.UTF_8);
            Path cwd = workDir.resolve("run");
            Files.createDirectories(cwd);

            List<String> cmd = new ArrayList<>();
            cmd.add(javaExe.toString());
            int heapMb = properties.getMaxHeapMb();
            if (heapMb > 0) {
                cmd.add("-Xmx" + heapMb + "m");
            }
            cmd.add("--source");
            cmd.add(Integer.toString(Runtime.version().feature()));
            cmd.add("--add-modules");
            cmd.add("jdk.jshell");
            cmd.add(driver.toString());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(cwd.toFile());
            pb.environment().clear(); // 不继承任何环境变量
            pb.redirectErrorStream(false);

            Process process;
            try {
                process = pb.start();
            } catch (IOException ex) {
                return new Outcome(null, "代码执行子进程启动失败：" + safeMessage(ex), false, false);
            }

            // 先把源码写进 stdin 并关闭；驱动程序会 readAllBytes 后再执行，故不会与后续 drain 死锁。
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(source.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ex) {
                // 子进程可能已退出（如 JDK 参数不被支持），忽略；由退出码/stderr 兜底。
                log.debug("code_exec subprocess stdin write failed: {}", ex.toString());
            }

            Future<Capped> outFuture = POOL.submit(() -> drain(process.getInputStream(), maxOutputChars));
            Future<Capped> errFuture = POOL.submit(() -> drain(process.getErrorStream(), 4000));

            boolean exited;
            try {
                exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                return new Outcome(null, "代码执行被中断。", false, false);
            }

            if (!exited) {
                process.destroyForcibly();
                waitForQuietly(process, DRAIN_GRACE_MS);
                Capped partial = get(outFuture);
                return new Outcome(partial.text, null, true, partial.truncated);
            }

            int code = process.exitValue();
            Capped out = get(outFuture);
            Capped err = get(errFuture);

            if (code == 0) {
                return new Outcome(out.text, null, false, out.truncated);
            }
            if (code == EXIT_SNIPPET_ERROR) {
                String error = err.text.isBlank() ? "代码执行出错" : err.text.strip();
                return new Outcome(out.text, error, false, out.truncated);
            }
            // 其他非零退出码：如用户代码 System.exit(n)、JVM 启动失败等。
            String error = err.text.isBlank()
                    ? "代码执行子进程以退出码 " + code + " 结束。"
                    : err.text.strip();
            return new Outcome(out.text, error, false, out.truncated);
        } catch (Exception ex) {
            log.warn("code_exec subprocess sandbox error: {}", ex.toString());
            return new Outcome(null, "代码执行子进程沙箱异常：" + safeMessage(ex), false, false);
        } finally {
            deleteRecursively(workDir);
        }
    }

    /** 解析用于起子进程的 java 可执行文件：优先配置，其次当前 JVM 的 java.home。 */
    private Path resolveJavaExecutable() {
        String configured = properties.getJavaExecutable();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isBlank()) {
            return null;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return Path.of(javaHome, "bin", windows ? "java.exe" : "java");
    }

    /** 读到流结束（避免子进程因管道写满而阻塞），但只保留前 maxChars 个字符。 */
    private static Capped drain(InputStream in, int maxChars) {
        // 以字节为单位设上限，避免多字节字符被截半时的溢出；UTF-8 每字符至多 4 字节。
        long capBytes = maxChars > 0 ? (long) maxChars * 4 + 1024 : Long.MAX_VALUE;
        var buffer = new java.io.ByteArrayOutputStream();
        boolean droppedBytes = false;
        byte[] chunk = new byte[8192];
        try (in) {
            int n;
            while ((n = in.read(chunk)) != -1) {
                if (buffer.size() < capBytes) {
                    int allow = (int) Math.min(n, capBytes - buffer.size());
                    buffer.write(chunk, 0, allow);
                    if (allow < n) {
                        droppedBytes = true;
                    }
                } else {
                    droppedBytes = true; // 继续读并丢弃，让子进程能正常结束
                }
            }
        } catch (IOException ex) {
            // 流被强杀关闭属正常路径，忽略。
        }
        String text = buffer.toString(StandardCharsets.UTF_8);
        boolean truncated = droppedBytes;
        if (maxChars > 0 && text.length() > maxChars) {
            text = text.substring(0, maxChars);
            truncated = true;
        }
        return new Capped(text, truncated);
    }

    private static Capped get(Future<Capped> future) {
        try {
            return future.get(DRAIN_GRACE_MS, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            return new Capped("", false);
        }
    }

    private static void waitForQuietly(Process process, long millis) {
        try {
            process.waitFor(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // best effort
                }
            });
        } catch (IOException | UncheckedIOException ignore) {
            // best effort
        }
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    private record Capped(String text, boolean truncated) {}

    /**
     * 子进程里跑的自包含驱动程序源码，通过 {@code java --source <feature>} 运行。
     * 从 stdin 读取用户源码，用 JShell 编程式 API 逐片段执行，把捕获到的
     * stdout/表达式值打到真实 stdout；出错时把错误文案打到真实 stderr 并以退出码 3 结束。
     */
    private static final String DRIVER_SOURCE = """
            import jdk.jshell.JShell;
            import jdk.jshell.Snippet;
            import jdk.jshell.SnippetEvent;
            import jdk.jshell.SourceCodeAnalysis;
            import jdk.jshell.SourceCodeAnalysis.CompletionInfo;
            import java.io.ByteArrayOutputStream;
            import java.io.FileDescriptor;
            import java.io.FileOutputStream;
            import java.io.PrintStream;
            import java.nio.charset.StandardCharsets;
            import java.util.Locale;

            public class Sandbox {
                public static void main(String[] args) throws Exception {
                    String source = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
                    ByteArrayOutputStream captured = new ByteArrayOutputStream();
                    PrintStream sink = new PrintStream(captured, true, StandardCharsets.UTF_8);
                    PrintStream realOut = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
                    PrintStream realErr = new PrintStream(new FileOutputStream(FileDescriptor.err), true, "UTF-8");
                    JShell js = JShell.builder().out(sink).err(sink).executionEngine("local").build();
                    StringBuilder values = new StringBuilder();
                    String error = null;
                    try {
                        SourceCodeAnalysis analysis = js.sourceCodeAnalysis();
                        String remaining = source;
                        int guard = 0;
                        outer:
                        while (remaining != null && !remaining.isBlank() && guard++ < 500) {
                            CompletionInfo info = analysis.analyzeCompletion(remaining);
                            String unit = info.source();
                            if (unit == null || unit.isBlank()) {
                                break;
                            }
                            for (SnippetEvent event : js.eval(unit)) {
                                if (event.status() == Snippet.Status.REJECTED) {
                                    error = "编译错误：" + diagnostics(js, event.snippet());
                                    break outer;
                                }
                                if (event.exception() != null) {
                                    String m = event.exception().getMessage();
                                    error = "运行时异常：" + event.exception().getClass().getSimpleName()
                                            + (m == null || m.isBlank() ? "" : ": " + m);
                                    break outer;
                                }
                                if (event.value() != null && !event.value().isEmpty()) {
                                    values.append(event.value()).append('\\n');
                                }
                            }
                            remaining = info.remaining();
                        }
                    } catch (Throwable t) {
                        String m = t.getMessage();
                        error = t.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : ": " + m);
                    }
                    sink.flush();
                    String out = captured.toString(StandardCharsets.UTF_8);
                    if (values.length() > 0) {
                        out = out + values;
                    }
                    realOut.print(out);
                    realOut.flush();
                    if (error != null) {
                        realErr.print(error);
                        realErr.flush();
                        try { js.close(); } catch (Exception ignore) {}
                        System.exit(3);
                    }
                    try { js.close(); } catch (Exception ignore) {}
                }

                private static String diagnostics(JShell js, Snippet snippet) {
                    try {
                        String d = js.diagnostics(snippet)
                                .map(x -> x.getMessage(Locale.ROOT))
                                .reduce((a, b) -> a + "; " + b)
                                .orElse("语法不合法");
                        return d.isBlank() ? "语法不合法" : d;
                    } catch (Exception e) {
                        return "语法不合法";
                    }
                }
            }
            """;
}
