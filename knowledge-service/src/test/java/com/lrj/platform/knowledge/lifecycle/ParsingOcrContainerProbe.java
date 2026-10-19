package com.lrj.platform.knowledge.lifecycle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Linux 专项：真实 Boot 父进程 fork 解析，再执行已安装 Tesseract；只输出无正文的验收摘要。 */
public final class ParsingOcrContainerProbe {
    private ParsingOcrContainerProbe() { }
    public static void main(String[] args) throws Exception {
        var properties=new DocumentParsingProperties();
        require(properties.getOcr().isEnabled(),"OCR must be enabled by default","configuration");
        var reports=new java.util.ArrayList<java.util.Map<String,Object>>();
        try(var extractor=new DocumentTextExtractor(properties)) {
            for(String name:List.of("native-only.pdf","scan-en.pdf","scan-zh.pdf","mixed-native-scan.pdf","scan-rotated.pdf","mixed-one-page.pdf","native-blank.pdf")) {
                long started=System.nanoTime();ParsedDocument parsed;
                try(var input=Files.newInputStream(Path.of(args[0]).resolve(name))) {parsed=extractor.parse(input,name);}
                String normalized=parsed.text().replaceAll("\\s+","");
                if(name.contains("scan-en") || name.contains("rotated") || name.contains("one-page")) require(normalized.contains("KDP20261019"),"English reference missing",name);
                if(name.contains("scan-zh") || name.equals("mixed-native-scan.pdf")) require(normalized.contains("企业知识库") && normalized.contains("付款周期"),"Chinese markers missing",name);
                if(name.startsWith("native-") || name.equals("mixed-native-scan.pdf")) require(parsed.text().contains("NATIVE_REFERENCE_001"),"native text changed",name);
                if(name.equals("native-only.pdf")) require(parsed.blocks().stream().allMatch(b->PdfPageOcr.NATIVE.equals(b.location().extractionMethod())),"native page OCR performed",name);
                if(name.equals("mixed-one-page.pdf")) require(parsed.text().split(OcrCorpusFixtures.HEADER,-1).length==2,"duplicated native header",name);
                if(name.equals("native-blank.pdf")) require(PdfPageOcr.BLANK.equals(parsed.blocks().get(1).location().extractionMethod()),"blank page missing",name);
                var row=new java.util.LinkedHashMap<String,Object>();row.put("fixture",name);row.put("elapsedMs",(System.nanoTime()-started)/1_000_000);
                row.put("pages",parsed.blocks().stream().map(b->b.location().metadata()).toList());row.put("warnings",parsed.warnings().stream().map(ParsedDocument.Warning::code).toList());reports.add(row);
                System.out.println("PASS "+name+" elapsedMs="+row.get("elapsedMs"));
            }
        }
        try(var files=Files.list(properties.getTempRoot())) {require(files.findAny().isEmpty(),"parser temporary files leaked","cleanup");}
        System.out.println("REPORT "+new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(reports));
        for(String metric:List.of("memory.peak","memory.max","pids.max","cpu.max")) {
            var path=Path.of("/sys/fs/cgroup",metric);if(Files.exists(path))System.out.println(metric+"="+Files.readString(path).strip());
        }
    }
    private static void require(boolean value,String message,String fixture) {if(!value)throw new AssertionError(fixture+": "+message);}
}
