package com.lrj.platform.agent.actions;

import jdk.jshell.JShell;
import jdk.jshell.Snippet;
import jdk.jshell.SnippetEvent;
import jdk.jshell.SourceCodeAnalysis;
import jdk.jshell.SourceCodeAnalysis.CompletionInfo;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

final class JShellRunner {

    record Outcome(String output, String error, boolean timedOut, boolean truncated) {}

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "code-exec-jshell");
        thread.setDaemon(true);
        return thread;
    });

    private JShellRunner() {
    }

    static Outcome run(String source, long timeoutMs, int maxOutputChars) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(captured, true, StandardCharsets.UTF_8);
        JShell jshell = JShell.builder()
                .out(sink)
                .err(sink)
                .executionEngine("local")
                .build();
        try {
            Future<String> future = POOL.submit(() -> evalAll(jshell, source));
            String values;
            try {
                values = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ex) {
                future.cancel(true);
                bestEffortStop(jshell);
                return finish(captured.toString(StandardCharsets.UTF_8), null, true, maxOutputChars);
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                return finish(captured.toString(StandardCharsets.UTF_8), errText(cause), false, maxOutputChars);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                return finish(captured.toString(StandardCharsets.UTF_8), "执行被中断", false, maxOutputChars);
            }
            String stdout = captured.toString(StandardCharsets.UTF_8);
            String combined = stdout;
            if (values != null && !values.isBlank()) {
                combined = combined.isBlank() ? values : stdout + (stdout.endsWith("\n") ? "" : "\n") + values;
            }
            return finish(combined, null, false, maxOutputChars);
        } catch (Throwable ex) {
            return finish(captured.toString(StandardCharsets.UTF_8), errText(ex), false, maxOutputChars);
        } finally {
            try {
                jshell.close();
            } catch (Exception ignore) {
                // best effort
            }
            sink.close();
        }
    }

    private static String evalAll(JShell jshell, String source) {
        StringBuilder values = new StringBuilder();
        SourceCodeAnalysis analysis = jshell.sourceCodeAnalysis();
        String remaining = source;
        int guard = 0;
        while (remaining != null && !remaining.isBlank() && guard++ < 500) {
            CompletionInfo info = analysis.analyzeCompletion(remaining);
            String unit = info.source();
            if (unit == null || unit.isBlank()) {
                break;
            }
            List<SnippetEvent> events = jshell.eval(unit);
            for (SnippetEvent event : events) {
                if (event.status() == Snippet.Status.REJECTED) {
                    throw new SnippetException("编译错误：" + diagnostics(jshell, event.snippet()));
                }
                if (event.exception() != null) {
                    throw new SnippetException("运行时异常：" + shortException(event.exception()));
                }
                if (event.value() != null && !event.value().isEmpty()) {
                    values.append(event.value()).append('\n');
                }
            }
            remaining = info.remaining();
        }
        return values.toString().stripTrailing();
    }

    private static String diagnostics(JShell jshell, Snippet snippet) {
        try {
            String diagnostics = jshell.diagnostics(snippet)
                    .map(diagnostic -> diagnostic.getMessage(Locale.getDefault()))
                    .collect(Collectors.joining("; "));
            return diagnostics.isBlank() ? "语法不合法" : diagnostics;
        } catch (Exception ex) {
            return "语法不合法";
        }
    }

    private static String shortException(Exception ex) {
        String message = ex.getMessage();
        String simpleName = ex.getClass().getSimpleName();
        return message == null || message.isBlank() ? simpleName : simpleName + ": " + message;
    }

    private static void bestEffortStop(JShell jshell) {
        try {
            jshell.stop();
        } catch (Throwable ignore) {
            // local execution cannot always stop tight loops
        }
    }

    private static String errText(Throwable throwable) {
        if (throwable instanceof SnippetException) {
            return throwable.getMessage();
        }
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private static Outcome finish(String output, String error, boolean timedOut, int maxOutputChars) {
        String text = output == null ? "" : output;
        boolean truncated = false;
        if (maxOutputChars > 0 && text.length() > maxOutputChars) {
            text = text.substring(0, maxOutputChars);
            truncated = true;
        }
        return new Outcome(text, error, timedOut, truncated);
    }

    static final class SnippetException extends RuntimeException {
        SnippetException(String message) {
            super(message);
        }
    }
}
