package com.lrj.platform.knowledge.lifecycle;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 其他已注册格式的自有真实文件；生成无需网络、Office 服务或计费模型。 */
final class AdditionalDocumentCorpus {
    record Sample(String id,String filename,byte[] bytes,List<String> expected) {
        String format() {
            if(id.startsWith("txt")) return "text";
            return id.substring(0,id.indexOf('-'));
        }
    }
    static List<Sample> samples() throws Exception {
        byte[] doc;
        try(var input=AdditionalDocumentCorpus.class.getResourceAsStream("/parsing/enterprise-reference.doc")) {
            doc=java.util.Objects.requireNonNull(input).readAllBytes();
        }
        String html="<html><head><meta charset='UTF-8'><title>企业技术资料</title><script>UNEXECUTED_SCRIPT_MARKER</script></head><body><h1>HTML_REFERENCE</h1><p>中文退款说明</p><table><tr><th>编号</th><th>数量</th></tr><tr><td>00123</td><td>42</td></tr></table></body></html>";
        return List.of(
                new Sample("doc-bilingual","legacy.doc",doc,List.of("LEGACY_DOC_001","退款周期为三十天","Operations Team")),
                new Sample("xls-table","legacy.xls",spreadsheet(),List.of("00001","00100","中文手册")),
                new Sample("ppt-slides","legacy.ppt",slides(false),List.of("SLIDE_ONE","中文技术文档","SLIDE_TWO","Rollback procedure")),
                new Sample("pptx-slides","modern.pptx",slides(true),List.of("SLIDE_ONE","中文技术文档","SLIDE_TWO","Rollback procedure")),
                new Sample("html-utf8","page.html",html.getBytes(StandardCharsets.UTF_8),List.of("HTML_REFERENCE","中文退款说明","00123")),
                new Sample("html-cp1252","latin.html","<html><head><meta charset='windows-1252'></head><body>LATIN_REFERENCE café coût</body></html>".getBytes(Charset.forName("windows-1252")),List.of("LATIN_REFERENCE","café","coût")),
                new Sample("rtf-bilingual","legacy.rtf",rtf("RTF_REFERENCE 中文退款说明 café"),List.of("RTF_REFERENCE","中文退款说明","café")),
                new Sample("odt-bilingual","document.odt",odt(),List.of("ODT_REFERENCE","中文技术手册","APPENDIX_MARKER")),
                new Sample("txt-utf16le","unicode.txt",("\uFEFFUTF16_REFERENCE 中文部署手册").getBytes(StandardCharsets.UTF_16LE),List.of("UTF16_REFERENCE","中文部署手册")),
                new Sample("txt-utf16be","unicode.txt",("\uFEFFUTF16_REFERENCE 中文部署手册").getBytes(StandardCharsets.UTF_16BE),List.of("UTF16_REFERENCE","中文部署手册")));
    }
    private static byte[] spreadsheet() throws Exception {
        try(var book=new HSSFWorkbook();var output=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("旧版表");var header=sheet.createRow(0);
            header.createCell(0).setCellValue("编号");header.createCell(1).setCellValue("名称");header.createCell(2).setCellValue("数量");
            var style=book.createCellStyle();style.setDataFormat(book.createDataFormat().getFormat("00000"));
            for(int i=1;i<=100;i++) {
                var row=sheet.createRow(i);var id=row.createCell(0);id.setCellValue(i);id.setCellStyle(style);
                row.createCell(1).setCellValue("中文手册");row.createCell(2).setCellValue(i*10);
            }
            book.write(output);return output.toByteArray();
        }
    }
    private static byte[] slides(boolean modern) throws Exception {
        var text=List.of("SLIDE_ONE 中文技术文档","SLIDE_TWO Rollback procedure");
        try(var output=new ByteArrayOutputStream()) {
            if(modern) {
                try(var book=new XMLSlideShow()) {
                    for(String value:text) {var box=book.createSlide().createTextBox();box.setAnchor(new java.awt.geom.Rectangle2D.Double(30,30,600,100));box.setText(value);}
                    book.write(output);
                }
            } else {
                try(var book=new HSLFSlideShow()) {
                    for(String value:text) {var slide=book.createSlide();var box=new HSLFTextBox();box.setAnchor(new java.awt.geom.Rectangle2D.Double(30,30,600,100));box.setText(value);slide.addShape(box);}
                    book.write(output);
                }
            }
            return output.toByteArray();
        }
    }
    private static byte[] rtf(String text) {
        var body=new StringBuilder("{\\rtf1\\ansi\\ansicpg1252\\deff0{\\fonttbl{\\f0 Arial;}}\\uc1\\pard\\f0 ");
        for(char value:text.toCharArray()) {
            if(value>127) body.append("\\u").append((short)value).append('?');
            else body.append(value);
        }
        return body.append("\\par}").toString().getBytes(StandardCharsets.US_ASCII);
    }
    private static byte[] odt() throws Exception {
        String content="""
                <?xml version="1.0" encoding="UTF-8"?>
                <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" office:version="1.2">
                <office:body><office:text><text:h text:outline-level="1">ODT_REFERENCE</text:h><text:p>中文技术手册</text:p><text:p>APPENDIX_MARKER</text:p></office:text></office:body></office:document-content>
                """;
        String manifest="""
                <?xml version="1.0" encoding="UTF-8"?>
                <manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.2">
                <manifest:file-entry manifest:full-path="/" manifest:media-type="application/vnd.oasis.opendocument.text"/>
                <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>
                </manifest:manifest>
                """;
        try(var out=new ByteArrayOutputStream();var zip=new ZipOutputStream(out)) {
            byte[] mime="application/vnd.oasis.opendocument.text".getBytes(StandardCharsets.US_ASCII);
            var crc=new CRC32();crc.update(mime);var entry=new ZipEntry("mimetype");entry.setMethod(ZipEntry.STORED);entry.setSize(mime.length);entry.setCompressedSize(mime.length);entry.setCrc(crc.getValue());
            zip.putNextEntry(entry);zip.write(mime);zip.closeEntry();
            for(var file:java.util.Map.of("content.xml",content,"META-INF/manifest.xml",manifest).entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            }
            zip.finish();return out.toByteArray();
        }
    }
}
