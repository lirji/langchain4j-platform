package com.lrj.platform.knowledge.lifecycle;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static com.lrj.platform.knowledge.lifecycle.ParsedDocument.*;

/** 注入确定性识别器验证选择/预算/去重；真实 OCR 质量由 Linux 语料探针单独验证。 */
class PdfPageOcrTest {
    @Test void skipsNativeAndBlankPagesAndAddsMixedPageProvenance() throws Exception {
        try(var document=new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(200,300)));
            imagePage(document,false);imagePage(document,true);
            var pages=List.of(page(1,"Native reference retained for the entire document."),page(2,"Approved header"),page(3,""));
            var properties=new DocumentParsingProperties();var calls=new AtomicInteger();var warnings=new ArrayList<Warning>();
            var result=new PdfPageOcr(properties,(image,work,deadline)->{calls.incrementAndGet();return new PdfPageOcr.Result("Approved header\n中文付款周期30天",92.5);})
                    .apply(document,pages,warnings::add);
            assertThat(calls).hasValue(1);assertThat(result.get(0).text()).isEqualTo(pages.get(0).text());
            assertThat(result.get(1).text()).isEqualTo("Approved header\n中文付款周期30天");
            assertThat(result.get(1).location().metadata()).containsEntry("source_page","2")
                    .containsEntry("source_extraction_method",PdfPageOcr.MIXED).containsEntry("source_ocr_confidence","92.5");
            assertThat(result.get(2).location().extractionMethod()).isEqualTo(PdfPageOcr.BLANK);
            assertThat(warnings).extracting(Warning::code).contains(ParsingWarnings.PDF_OCR,ParsingWarnings.PDF_BLANK);
        }
    }
    @Test void wholeDocumentPageAndPixelBudgetsRejectBeforeFirstOcrCall() throws Exception {
        try(var document=new PDDocument()) {
            imagePage(document,false);imagePage(document,false);
            var properties=new DocumentParsingProperties();properties.getOcr().setMaxPages(1);var calls=new AtomicInteger();
            PdfPageOcr.Recognizer engine=(i,w,d)->{calls.incrementAndGet();return new PdfPageOcr.Result("text",90);};
            assertThatThrownBy(()->new PdfPageOcr(properties,engine).apply(document,List.of(page(1,""),page(2,"")),w->{}))
                    .isInstanceOfSatisfying(DocumentParsingException.class,e->assertThat(e.error()).isEqualTo(DocumentParsingError.OCR_LIMIT));
            properties.getOcr().setMaxPages(5);properties.getOcr().setMaxPagePixels(10);
            assertThatThrownBy(()->new PdfPageOcr(properties,engine).apply(document,List.of(page(1,""),page(2,"")),w->{})).isInstanceOf(DocumentParsingException.class);
            assertThat(calls).hasValue(0);
        }
    }
    @Test void lowConfidenceOrEmptyOcrDoesNotPublishPartialDocument() throws Exception {
        for(var result:List.of(new PdfPageOcr.Result("wrong",10),new PdfPageOcr.Result("",100))) {
            try(var document=new PDDocument()) {
                imagePage(document,false);
                assertThatThrownBy(()->new PdfPageOcr(new DocumentParsingProperties(),(i,w,d)->result).apply(document,List.of(page(1,"")),w->{}))
                        .isInstanceOfSatisfying(DocumentParsingException.class,e->assertThat(e.error()).isEqualTo(DocumentParsingError.OCR_QUALITY));
            }
        }
    }
    @Test void mergePreservesRepeatedOcrValuesAndNormalizesOnlyNativeOverlap() {
        assertThat(PdfPageOcr.merge("Approved Header", "APPROVED   HEADER\nValue 10\nValue 10"))
                .isEqualTo("Approved Header\nValue 10\nValue 10");
        assertThat(PdfPageOcr.merge("Invoice 100", "100\nInvoice 100\nInvoice 100"))
                .isEqualTo("Invoice 100\n100\nInvoice 100");
    }
    private static Block page(int page,String text) {return new Block(PAGE,text,new Location(page,0,null,null,null,null,null,null,null),List.of(),List.of());}
    private static void imagePage(PDDocument document,boolean blank) throws Exception {
        var page=new PDPage(new PDRectangle(200,300));document.addPage(page);var image=new BufferedImage(200,300,BufferedImage.TYPE_INT_RGB);
        var graphics=image.createGraphics();graphics.setColor(java.awt.Color.WHITE);graphics.fillRect(0,0,200,300);
        if(!blank){graphics.setColor(java.awt.Color.BLACK);graphics.fillRect(20,20,120,10);}graphics.dispose();
        try(var content=new PDPageContentStream(document,page)) {content.drawImage(LosslessFactory.createFromImage(document,image),0,0,200,300);}
    }
}
