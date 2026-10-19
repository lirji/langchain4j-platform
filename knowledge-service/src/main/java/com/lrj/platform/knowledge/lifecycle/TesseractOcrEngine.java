package com.lrj.platform.knowledge.lifecycle;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/** 固定 CLI 参数的本地 OCR 适配器；原文/日志都留在解析私有目录，不经 shell 或外部服务。 */
final class TesseractOcrEngine implements PdfPageOcr.Recognizer {
    private static final Object PROCESS_LOCK = new Object();
    private static final Set<Process> ACTIVE = new HashSet<>();
    private static boolean stopping;
    private static final long TERMINATION_MILLIS = 3000;
    private final DocumentOcrProperties limits;
    private boolean verified;

    TesseractOcrEngine(DocumentOcrProperties limits) { this.limits = limits; }

    /** PSM 1 使用引擎的自动版面/方向检测；结果不伪造引擎未返回的旋转角度。 */
    @Override
    public PdfPageOcr.Result recognize(BufferedImage image, Path work, long deadline) throws IOException {
        verify(work,deadline);
        Path input=work.resolve("page.png"), base=work.resolve("result"), output=work.resolve("result.tsv");
        if (!ImageIO.write(image,"png",input.toFile())) fail(DocumentParsingError.OCR_FAILED,null);
        run(List.of(limits.getExecutable(),input.toString(),base.toString(),"-l",limits.getLanguages(),"--psm","1","tsv"),
                work,output,deadline,DocumentParsingError.OCR_FAILED);
        return readResult(output);
    }

    private void verify(Path work,long deadline) throws IOException {
        if (verified) return;
        if (!Files.isExecutable(Path.of(limits.getExecutable()))) fail(DocumentParsingError.OCR_UNAVAILABLE,null);
        Path languages=work.resolve("languages.txt");
        run(List.of(limits.getExecutable(),"--list-langs"),work,languages,deadline,DocumentParsingError.OCR_UNAVAILABLE);
        Set<String> installed = new HashSet<>(Files.readAllLines(languages,StandardCharsets.UTF_8));
        // PSM 1 需要 osd，缺失时拒绝而不是悄悄退回未经方向处理的识别。
        var required=new ArrayList<>(List.of(limits.getLanguages().split("\\+"))); required.add("osd");
        if (!installed.containsAll(required)) fail(DocumentParsingError.OCR_UNAVAILABLE,null);
        verified=true;
    }

    private void run(List<String> command,Path work,Path output,long deadline,DocumentParsingError failureCode) throws IOException {
        if(System.nanoTime()>deadline) fail(DocumentParsingError.OCR_TIMEOUT,null);
        var builder=new ProcessBuilder(command).directory(work.toFile());
        builder.environment().clear(); builder.environment().put("LANG","C.UTF-8");
        builder.environment().put("TMPDIR",work.toString());builder.environment().put("OMP_THREAD_LIMIT","1");
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.redirectOutput(command.contains("--list-langs") ? ProcessBuilder.Redirect.to(output.toFile()) : ProcessBuilder.Redirect.DISCARD);
        Process process;
        synchronized(PROCESS_LOCK) {
            if(stopping) { fail(DocumentParsingError.OCR_UNAVAILABLE,null); }
            try { process=builder.start(); } catch(IOException error) { throw new DocumentParsingException(DocumentParsingError.OCR_UNAVAILABLE,error); }
            ACTIVE.add(process);
        }
        try {
            while(true) {
                if(System.nanoTime()>deadline) fail(DocumentParsingError.OCR_TIMEOUT,null);
                checkOutput(output);
                if(process.waitFor(50,TimeUnit.MILLISECONDS)) break;
            }
            if(System.nanoTime()>deadline) fail(DocumentParsingError.OCR_TIMEOUT,null);
            checkOutput(output);
            if(process.exitValue()!=0 || !Files.isRegularFile(output)) fail(failureCode,null);
        } catch(InterruptedException error) {
            Thread.currentThread().interrupt();throw new DocumentParsingException(DocumentParsingError.OCR_TIMEOUT,error);
        } finally {
            if(!terminate(process)) fail(DocumentParsingError.TERMINATION_FAILED,null);
            synchronized(PROCESS_LOCK) { ACTIVE.remove(process); }
        }
    }
    private void checkOutput(Path output) throws IOException {
        if(Files.exists(output) && Files.size(output)>limits.getMaxOutputBytes()) fail(DocumentParsingError.OCR_LIMIT,null);
    }

    /** TSV 单词与行标识重建正文；置信度按字符加权，仅作为引擎质量信号。 */
    private PdfPageOcr.Result readResult(Path output) throws IOException {
        StringBuilder text=new StringBuilder(); String lastLine=null; double confidence=0;long count=0;
        try(var reader=Files.newBufferedReader(output,StandardCharsets.UTF_8)) {
            String line=reader.readLine();
            if(line==null || !line.startsWith("level\tpage_num\t")) fail(DocumentParsingError.OCR_FAILED,null);
            while((line=reader.readLine())!=null) {
                String[] parts=line.split("\t",12);
                if(parts.length!=12) fail(DocumentParsingError.OCR_FAILED,null);
                if(!"5".equals(parts[0]) || parts[11].isBlank()) continue;
                double score;
                try { score=Double.parseDouble(parts[10]); } catch(NumberFormatException error) { throw new DocumentParsingException(DocumentParsingError.OCR_FAILED,error); }
                if(!Double.isFinite(score) || score<0 || score>100) fail(DocumentParsingError.OCR_FAILED,null);
                String word=parts[11].strip(), key=parts[2]+"/"+parts[3]+"/"+parts[4];
                if(!text.isEmpty()) {
                    if(!key.equals(lastLine)) text.append('\n');
                    else if(!han(text.codePointBefore(text.length())) || !han(word.codePointAt(0))) text.append(' ');
                }
                text.append(word);lastLine=key;
                int length=word.codePointCount(0,word.length());confidence+=score*length;count+=length;
            }
        }
        return new PdfPageOcr.Result(text.toString(),count==0?0:confidence/count);
    }
    private static boolean han(int codePoint) { return Character.UnicodeScript.of(codePoint)==Character.UnicodeScript.HAN; }

    /** 父 JVM 消失/正常退出时先禁止新 native 进程，再终止已登记进程，避免常见孤儿执行。 */
    static void stopActive() {
        synchronized(PROCESS_LOCK) { stopping=true; ACTIVE.forEach(TesseractOcrEngine::terminate); }
    }
    private static boolean terminate(Process process) {
        var handles=new ArrayList<ProcessHandle>(process.descendants().toList());handles.add(process.toHandle());
        handles.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(TERMINATION_MILLIS);
        boolean interrupted=Thread.interrupted();
        try {
            while(handles.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime()<until) {
                try {Thread.sleep(10);} catch(InterruptedException ignored) {interrupted=true;}
            }
            return handles.stream().noneMatch(ProcessHandle::isAlive);
        } finally { if(interrupted) Thread.currentThread().interrupt(); }
    }
    private static void fail(DocumentParsingError error,Throwable cause) { throw new DocumentParsingException(error,cause); }
}
