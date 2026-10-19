package com.lrj.platform.knowledge.lifecycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.image.BufferedImage;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** 受控本地脚本模拟 native 引擎，不连接外部模型，验证输出协议/错误/真实进程退出。 */
class TesseractOcrEngineTest {
    @TempDir Path root;
    @Test void validatesEngineLanguagesAndRebuildsChineseWithoutInsertedSpaces() throws Exception {
        var policy=policy("""
                printf 'level\\tpage_num\\tblock_num\\tpar_num\\tline_num\\tword_num\\tleft\\ttop\\twidth\\theight\\tconf\\ttext\\n5\\t1\\t1\\t1\\t1\\t1\\t0\\t0\\t1\\t1\\t90\\t知识\\n5\\t1\\t1\\t1\\t1\\t2\\t1\\t0\\t1\\t1\\t100\\t库\\n' > "$2.tsv"
                """);
        var result=new TesseractOcrEngine(policy).recognize(image(),root,deadline());
        assertThat(result.text()).isEqualTo("知识库");assertThat(result.confidence()).isBetween(93.0,94.0);
    }
    @Test void timeoutKillsNativeProcessBeforeReturning() throws Exception {
        var policy=policy("echo $$ > native.pid\nexec /bin/sleep 10\n");
        assertThatThrownBy(()->new TesseractOcrEngine(policy).recognize(image(),root,System.nanoTime()+TimeUnit.SECONDS.toNanos(2)))
                .isInstanceOfSatisfying(DocumentParsingException.class,e->assertThat(e.error()).isEqualTo(DocumentParsingError.OCR_TIMEOUT));
        long pid=Long.parseLong(Files.readString(root.resolve("native.pid")).trim());
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }
    @Test void forkedParserTransfersOcrPolicyAndReturnsPageEvidence() throws Exception {
        var engine=policy("printf 'level\\tpage_num\\tblock_num\\tpar_num\\tline_num\\tword_num\\tleft\\ttop\\twidth\\theight\\tconf\\ttext\\n5\\t1\\t1\\t1\\t1\\t1\\t0\\t0\\t1\\t1\\t95\\tSCANNED_REFERENCE\\n' > \"$2.tsv\"\n");
        Path pdf=scannedPdf();
        var properties=new DocumentParsingProperties();properties.setTempRoot(root.resolve("parser"));
        properties.getOcr().setEnabled(true);properties.getOcr().setExecutable(engine.getExecutable());
        try(var extractor=new DocumentTextExtractor(properties);var input=Files.newInputStream(pdf)) {
            var result=extractor.parse(input,"scan.pdf");
            assertThat(result.text()).isEqualTo("SCANNED_REFERENCE");
            assertThat(result.blocks().getFirst().location().metadata()).containsEntry("source_extraction_method",PdfPageOcr.OCR)
                    .containsEntry("source_ocr_confidence","95.0");
            assertThat(result.warnings()).noneMatch(w->ParsingWarnings.PDF_NO_TEXT.equals(w.code()));
        }
        try(var files=Files.list(properties.getTempRoot())) {assertThat(files).isEmpty();}
    }
    @Test void closingForkedParserStopsItsRunningNativeOcrBeforeCleaningFiles() throws Exception {
        Path pidFile=root.resolve("running.pid"), pdf=scannedPdf();
        var engine=policy("echo $$ > '"+pidFile+"'\nexec /bin/sleep 30\n");
        var properties=new DocumentParsingProperties();properties.setTempRoot(root.resolve("parser"));
        properties.getOcr().setEnabled(true);properties.getOcr().setExecutable(engine.getExecutable());
        try(var extractor=new DocumentTextExtractor(properties);
            var callers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var call=callers.submit(()-> {
                try(var input=Files.newInputStream(pdf)) {return extractor.parse(input,"scan.pdf");}
            });
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(!Files.exists(pidFile) && System.nanoTime()<until) Thread.sleep(25);
            assertThat(pidFile).exists();
            long pid=Long.parseLong(Files.readString(pidFile).trim());
            extractor.close();
            assertThatThrownBy(()->call.get(8,TimeUnit.SECONDS)).hasCauseInstanceOf(DocumentParsingException.class);
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        }
        try(var files=Files.list(properties.getTempRoot())) {assertThat(files).isEmpty();}
    }
    private Path scannedPdf() throws Exception {
        Path pdf=root.resolve("scan.pdf");
        try(var document=new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page=new org.apache.pdfbox.pdmodel.PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(100,100));document.addPage(page);
            try(var content=new org.apache.pdfbox.pdmodel.PDPageContentStream(document,page)) {
                content.drawImage(org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(document,image()),0,0,100,100);
            }
            document.save(pdf.toFile());
        }
        return pdf;
    }
    @Test void rejectsMissingEngineAndExcessiveOutput() throws Exception {
        var policy=new DocumentOcrProperties();policy.setExecutable(root.resolve("missing").toString());
        assertThatThrownBy(()->new TesseractOcrEngine(policy).recognize(image(),root,deadline()))
                .isInstanceOfSatisfying(DocumentParsingException.class,e->assertThat(e.error()).isEqualTo(DocumentParsingError.OCR_UNAVAILABLE));
        var oversized=policy("i=0; while [ $i -lt 1000 ]; do printf 'oversized output'; i=$((i+1)); done > \"$2.tsv\"\n");
        oversized.setMaxOutputBytes(100);
        assertThatThrownBy(()->new TesseractOcrEngine(oversized).recognize(image(),root,deadline()))
                .isInstanceOfSatisfying(DocumentParsingException.class,e->assertThat(e.error()).isEqualTo(DocumentParsingError.OCR_LIMIT));
    }
    private DocumentOcrProperties policy(String body) throws Exception {
        Path script=root.resolve("engine.sh");
        Files.writeString(script,"#!/bin/sh\nif [ \"$1\" = \"--list-langs\" ]; then printf 'eng\\nchi_sim\\nosd\\n'; exit 0; fi\n"+body);
        Files.setPosixFilePermissions(script,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        var properties=new DocumentOcrProperties();properties.setExecutable(script.toString());return properties;
    }
    private static BufferedImage image() {return new BufferedImage(20,20,BufferedImage.TYPE_INT_RGB);}
    private static long deadline() {return System.nanoTime()+TimeUnit.SECONDS.toNanos(3);}
}
