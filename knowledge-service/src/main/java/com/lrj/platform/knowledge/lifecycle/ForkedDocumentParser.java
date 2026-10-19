package com.lrj.platform.knowledge.lifecycle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

/**
 * 服务 JVM 只搬运有界字节并管理子进程。超时后先确认进程退出，再清理目录和释放额度；
 * 不能以 Future.cancel 代替真正终止不响应 Java 中断的解析库。
 */
final class ForkedDocumentParser implements AutoCloseable {
    private static final long POLL_MILLIS = 50;
    private final DocumentParsingProperties limits;
    private final Semaphore slots;
    private final ProcessStarter starter;
    private final java.util.Set<Process> active = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private boolean closed;

    ForkedDocumentParser(DocumentParsingProperties limits) {
        this(limits, ProcessBuilder::start);
    }

    /** 测试只替换启动的子进程行为；生产路径固定专用 main 和当前 artifact。 */
    ForkedDocumentParser(DocumentParsingProperties limits, ProcessStarter starter) {
        this.limits = limits;
        this.slots = new Semaphore(limits.getMaxProcesses());
        this.starter = starter;
    }

    String extract(InputStream input) {
        byte[] bytes = execute(input, "", false);
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            if (text.length() > limits.getMaxTextChars()) throw failure(DocumentParsingError.TEXT_LIMIT);
            if (text.isBlank()) throw failure(DocumentParsingError.EMPTY);
            return text;
        } catch (java.nio.charset.CharacterCodingException ex) {
            throw new DocumentParsingException(DocumentParsingError.PROCESS_FAILED, ex);
        }
    }

    ParsedDocument parse(InputStream input, String filename) {
        return ParsedDocumentCodec.read(execute(input, filename, true), limits);
    }

    private byte[] execute(InputStream input, String filename, boolean structured) {
        if (!slots.tryAcquire()) throw failure(DocumentParsingError.BUSY);
        Path directory = null;
        Process process = null;
        boolean stopped = true;
        try {
            Files.createDirectories(limits.getTempRoot());
            directory = Files.createTempDirectory(limits.getTempRoot(), "parse-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            copyInput(input, directory.resolve(ParsingProcessMain.INPUT));
            ProcessBuilder builder = command(directory, filename, structured);
            synchronized (active) {
                if (closed) throw failure(DocumentParsingError.PROCESS_UNAVAILABLE);
                process = starter.start(builder);
                active.add(process);
            }
            stopped = false;
            long deadline = System.nanoTime() + limits.getTimeout().toNanos();
            while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() >= deadline) throw failure(DocumentParsingError.TIMEOUT);
                checkDirectory(directory);
            }
            stopped = true;
            checkDirectory(directory);
            if (process.exitValue() != 0) {
                // 服务主动停机杀掉的健康解析属于可恢复中断，不能永久标记成坏输入/人工处置。
                synchronized (active) { if (closed) throw failure(DocumentParsingError.PROCESS_UNAVAILABLE); }
                throw failure(DocumentParsingError.PROCESS_FAILED);
            }
            Path error = directory.resolve(ParsingProcessMain.ERROR);
            if (Files.exists(error, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(error, LinkOption.NOFOLLOW_LINKS) || Files.size(error) > 128) {
                    throw failure(DocumentParsingError.PROCESS_FAILED);
                }
                String code = Files.readString(error, StandardCharsets.US_ASCII);
                for (var value : DocumentParsingError.values()) if (value.code().equals(code)) throw failure(value);
                throw failure(DocumentParsingError.PROCESS_FAILED);
            }
            Path output = directory.resolve(ParsingProcessMain.OUTPUT);
            if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) throw failure(DocumentParsingError.PROCESS_FAILED);
            if (Files.size(output) > limits.getMaxResultBytes()) throw failure(DocumentParsingError.RESOURCE_LIMIT);
            byte[] bytes;
            try (var result = Files.newInputStream(output, LinkOption.NOFOLLOW_LINKS)) {
                bytes = result.readNBytes(limits.getMaxResultBytes() + 1);
            }
            if (bytes.length > limits.getMaxResultBytes()) throw failure(DocumentParsingError.RESOURCE_LIMIT);
            return bytes;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new DocumentParsingException(DocumentParsingError.PROCESS_UNAVAILABLE, ex);
        } catch (IOException ex) {
            throw new DocumentParsingException(DocumentParsingError.PROCESS_UNAVAILABLE, ex);
        } finally {
            if (process != null && !stopped) stopped = terminate(process, limits.getTerminationTimeout());
            // 若操作系统尚未确认退出，保留目录和额度；宁可降低可用容量，也不放大失控任务。
            if (stopped) {
                if (process != null) active.remove(process);
                try { if (directory != null) removeOwnedDirectory(directory); }
                finally { slots.release(); }
            } else {
                throw failure(DocumentParsingError.TERMINATION_FAILED);
            }
        }
    }

    private void copyInput(InputStream input, Path target) throws IOException {
        try (var output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192]; long total = 0;
            long deadline = System.nanoTime() + limits.getInputTimeout().toNanos();
            while (true) {
                if (System.nanoTime() >= deadline) throw failure(DocumentParsingError.TIMEOUT);
                int count;
                try { count = input.read(buffer); }
                catch (IOException ex) { throw new DocumentParsingException(DocumentParsingError.SOURCE_READ, ex); }
                if (System.nanoTime() >= deadline) throw failure(DocumentParsingError.TIMEOUT);
                if (count < 0) break;
                total += count;
                if (total > limits.getMaxInputBytes()) throw failure(DocumentParsingError.INPUT_LIMIT);
                if (total > limits.getMaxTemporaryBytes()) throw failure(DocumentParsingError.RESOURCE_LIMIT);
                output.write(buffer, 0, count);
            }
        }
    }

    private ProcessBuilder command(Path directory, String filename, boolean structured) throws IOException {
        var args = new ArrayList<String>();
        args.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        args.add("-Xmx" + limits.getHeapMiB() + "m");
        args.add("-XX:MaxMetaspaceSize=128m"); args.add("-XX:MaxDirectMemorySize=64m");
        args.add("-XX:+ExitOnOutOfMemoryError"); args.add("-Djava.awt.headless=true");
        args.add("-Djava.io.tmpdir=" + directory); args.add("-Duser.home=" + directory);
        String classpath = System.getProperty("java.class.path");
        // Boot -jar 的 classpath 只有 fat jar；PropertiesLauncher 负责其 BOOT-INF 嵌套依赖。
        boolean bootJar = isBootJar(classpath);
        if (bootJar) args.add("-Dloader.main=" + ParsingProcessMain.class.getName());
        args.add("-cp"); args.add(absoluteClasspath(classpath));
        args.add(bootJar ? "org.springframework.boot.loader.launch.PropertiesLauncher" : ParsingProcessMain.class.getName());
        args.add(directory.toString()); args.add(Integer.toString(limits.getMaxTextChars()));
        args.add(Integer.toString(limits.getMaxPages()));
        args.add(Long.toString(ProcessHandle.current().pid()));
        args.add(Boolean.toString(structured));
        // 只传用于文本格式消歧的已知后缀，不把外部文件名带入进程参数/路径。
        String suffix = filename == null ? "" : filename.toLowerCase(java.util.Locale.ROOT);
        args.add(suffix.endsWith(".csv") ? ".csv" : suffix.endsWith(".md") || suffix.endsWith(".markdown") ? ".md" : "");
        args.add(Integer.toString(limits.getMaxStructureUnits()));
        var builder = new ProcessBuilder(args).directory(directory.toFile());
        builder.environment().clear();
        builder.environment().putAll(Map.of("LANG", "C.UTF-8", "TMPDIR", directory.toString()));
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        return builder;
    }

    private static boolean isBootJar(String classpath) throws IOException {
        if (classpath.contains(java.io.File.pathSeparator) || !classpath.endsWith(".jar")) return false;
        try (var jar = new JarFile(classpath)) { return jar.getEntry("BOOT-INF/classes/") != null; }
    }

    private static String absoluteClasspath(String classpath) {
        return java.util.Arrays.stream(classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .map(entry -> Path.of(entry).toAbsolutePath().toString())
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
    }

    private void checkDirectory(Path directory) throws IOException {
        Files.walkFileTree(directory, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), 33,
                new java.nio.file.SimpleFileVisitor<>() {
                    private long bytes;
                    private int count;
                    private void count(Path path, java.nio.file.attribute.BasicFileAttributes attributes) {
                        if (++count > limits.getMaxTemporaryFiles() || directory.relativize(path).getNameCount() > 32) {
                            throw failure(DocumentParsingError.RESOURCE_LIMIT);
                        }
                        if (attributes.isSymbolicLink()) throw failure(DocumentParsingError.PROCESS_FAILED);
                        if (attributes.isRegularFile()) bytes += attributes.size();
                        if (bytes > limits.getMaxTemporaryBytes()
                                || (path.getFileName().toString().equals(ParsingProcessMain.OUTPUT)
                                    && attributes.size() > limits.getMaxResultBytes())) throw failure(DocumentParsingError.RESOURCE_LIMIT);
                    }
                    @Override public java.nio.file.FileVisitResult preVisitDirectory(Path path, java.nio.file.attribute.BasicFileAttributes attrs) {
                        count(path, attrs); return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    @Override public java.nio.file.FileVisitResult visitFile(Path path, java.nio.file.attribute.BasicFileAttributes attrs) {
                        count(path, attrs); return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    @Override public java.nio.file.FileVisitResult visitFileFailed(Path path, IOException error) throws IOException {
                        // Tika 可在两次目录事件之间删除自己的临时文件，不能把这种正常竞态当成坏文档。
                        if (error instanceof java.nio.file.NoSuchFileException) return java.nio.file.FileVisitResult.CONTINUE;
                        throw error;
                    }
                });
    }

    private static boolean terminate(Process process, Duration timeout) {
        // 固定解析 main 禁止自动 OCR/外部子命令，仍清理可见后代，防止库错误拉起进程后遗留。
        List<ProcessHandle> children = process.descendants().toList();
        children.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        boolean interrupted = Thread.interrupted();
        try {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (!process.isAlive() && children.stream().noneMatch(ProcessHandle::isAlive)) return true;
                try { TimeUnit.MILLISECONDS.sleep(POLL_MILLIS); }
                catch (InterruptedException ex) { interrupted = true; }
            }
            return !process.isAlive() && children.stream().noneMatch(ProcessHandle::isAlive);
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private static void removeOwnedDirectory(Path directory) {
        try {
            // 逐项删除，不能把恶意文档产生的全部路径先收集到父 JVM 内存。
            Files.walkFileTree(directory, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), 64,
                    new java.nio.file.SimpleFileVisitor<>() {
                        @Override public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs)
                                throws IOException { Files.deleteIfExists(file); return java.nio.file.FileVisitResult.CONTINUE; }
                        @Override public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException error)
                                throws IOException { Files.deleteIfExists(dir); return java.nio.file.FileVisitResult.CONTINUE; }
                    });
        } catch (IOException ex) {
            // 不记录带文件名的异常，不清理整个根目录；残留由受控 tmpfs/运维检查处理。
            org.slf4j.LoggerFactory.getLogger(ForkedDocumentParser.class).warn("parser temporary directory cleanup failed");
        }
    }
    /** 容器停止时先禁止新启动，再终止已有子进程；各调用线程负责释放自己的目录与额度。 */
    @Override public void close() {
        synchronized (active) { closed = true; }
        for (Process process : active) terminate(process, limits.getTerminationTimeout());
    }
    private static DocumentParsingException failure(DocumentParsingError error) { return new DocumentParsingException(error, null); }
    @FunctionalInterface
    interface ProcessStarter { Process start(ProcessBuilder builder) throws IOException; }
}
