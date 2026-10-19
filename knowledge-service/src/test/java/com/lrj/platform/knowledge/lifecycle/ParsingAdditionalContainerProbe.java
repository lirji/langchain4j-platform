package com.lrj.platform.knowledge.lifecycle;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 受限 Linux 中使用真实 Boot 父子进程验证其他格式；只处理自有夹具，不启动业务服务。 */
public final class ParsingAdditionalContainerProbe {
    private ParsingAdditionalContainerProbe() { }

    /** 测试类和夹具从只读 probe 目录加载，生产解析器及依赖来自本次打包的 jar。 */
    public static void main(String[] args) throws Exception {
        var properties=new DocumentParsingProperties();
        var reports=new java.util.ArrayList<java.util.Map<String,Object>>();
        try(var extractor=new DocumentTextExtractor(properties)) {
            for(var sample:AdditionalDocumentCorpus.samples()) {
                long started=System.nanoTime();
                var parsed=extractor.parse(new ByteArrayInputStream(sample.bytes()),sample.filename());
                require(parsed.format().equals(sample.format()),"content format mismatch",sample.id());
                for(String marker:sample.expected()) require(parsed.text().contains(marker),"content missing",sample.id());
                if(sample.id().startsWith("ppt")) require(parsed.text().indexOf("SLIDE_ONE")<parsed.text().indexOf("SLIDE_TWO"),"slide order changed",sample.id());
                if(sample.id().startsWith("html")) require(!parsed.text().contains("UNEXECUTED_SCRIPT_MARKER"),"script text leaked",sample.id());
                if(sample.id().startsWith("xls")) require(parsed.blocks().stream().allMatch(b->ParsedDocument.TABLE.equals(b.kind())
                        && "旧版表".equals(b.location().sheet())),"spreadsheet structure missing",sample.id());
                else if(!sample.id().startsWith("txt")) require(parsed.warnings().stream().anyMatch(w->ParsingWarnings.STRUCTURE_UNAVAILABLE.equals(w.code())),"fallback warning missing",sample.id());
                long elapsed=(System.nanoTime()-started)/1_000_000;
                reports.add(java.util.Map.of("case",sample.id(),"format",parsed.format(),"bytes",sample.bytes().length,
                        "chars",parsed.text().length(),"elapsedMs",elapsed,"markers",sample.expected().size(),
                        "warnings",parsed.warnings().stream().map(ParsedDocument.Warning::code).toList()));
                System.out.println("PASS "+sample.id()+" elapsedMs="+elapsed);
            }
        }
        try(var files=Files.list(properties.getTempRoot())) {require(files.findAny().isEmpty(),"parser temporary files leaked","cleanup");}
        System.out.println("REPORT "+new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(reports));
        for(String metric:List.of("memory.peak","memory.max","pids.max","cpu.max")) {
            var path=Path.of("/sys/fs/cgroup",metric);if(Files.exists(path)) System.out.println(metric+"="+Files.readString(path).strip());
        }
    }

    private static void require(boolean value,String message,String fixture) {if(!value) throw new AssertionError(fixture+": "+message);}
}
