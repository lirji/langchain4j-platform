package com.lrj.platform.knowledge.lifecycle;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/** 只生成无敏感数据的企业印刷体合成样本；不把识别结果当真实业务准确率。 */
public final class OcrCorpusFixtures {
    private OcrCorpusFixtures() { }
    static final String NATIVE = "NATIVE_REFERENCE_001 retains original searchable text and page evidence.";
    static final String HEADER = "ENTERPRISE APPROVAL PAGE";
    private static final String[] ENGLISH = {
            "ENTERPRISE KNOWLEDGE MANUAL", "Security review and access control.",
            "Upload documents to the knowledge base.", "Each version retains its source pages.",
            "Retry processing preserves document data.", "Customer records remain tenant isolated.",
            "Incident reference KDP20261019.", "Approval contact engineering team."
    };
    private static final String[] CHINESE = {
            "企业知识库技术文档", "文档解析保留原始页面位置", "权限控制按照租户进行隔离",
            "合同编号二零二六一零一九", "付款周期为三十天", "异常任务支持可靠恢复",
            "项目负责人负责审批流程", "扫描文档需要质量校验"
    };

    /** 在带中文字体的构建主机生成 PDF，Linux 识别容器无需依赖系统中文字库。 */
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]);Files.createDirectories(root);
        var english=scan(ENGLISH,false);var chinese=scan(CHINESE,true);
        try(var document=new PDDocument()) { imagePage(document,english,0,null); document.save(root.resolve("scan-en.pdf").toFile()); }
        try(var document=new PDDocument()) { imagePage(document,chinese,0,null); document.save(root.resolve("scan-zh.pdf").toFile()); }
        try(var document=new PDDocument()) { nativePage(document);imagePage(document,chinese,0,null);document.save(root.resolve("mixed-native-scan.pdf").toFile()); }
        try(var document=new PDDocument()) { imagePage(document,english,90,null);document.save(root.resolve("scan-rotated.pdf").toFile()); }
        try(var document=new PDDocument()) { imagePage(document,english,0,HEADER);document.save(root.resolve("mixed-one-page.pdf").toFile()); }
        try(var document=new PDDocument()) {
            nativePage(document);imagePage(document,scan(new String[0],false),0,null);document.save(root.resolve("native-blank.pdf").toFile());
        }
        try(var document=new PDDocument()) {nativePage(document);document.save(root.resolve("native-only.pdf").toFile());}
        System.out.println("Generated 7 synthetic OCR PDF cases");
    }
    private static BufferedImage scan(String[] lines,boolean chinese) {
        var image=new BufferedImage(1200,1600,BufferedImage.TYPE_INT_RGB);var graphics=image.createGraphics();
        graphics.setColor(Color.WHITE);graphics.fillRect(0,0,1200,1600);graphics.setColor(Color.BLACK);
        var font=new Font(Font.SANS_SERIF,Font.PLAIN,40);
        if(chinese && !font.canDisplay('知')) throw new IllegalStateException("fixture generator requires a Chinese font");
        graphics.setFont(font);graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int y=230;for(String line:lines) {graphics.drawString(line,75,y);y+=105;}
        graphics.dispose();return image;
    }
    private static void nativePage(PDDocument document) throws Exception {
        var page=new PDPage(PDRectangle.LETTER);document.addPage(page);
        try(var content=new PDPageContentStream(document,page)) {
            content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
            content.newLineAtOffset(30,700);content.showText(NATIVE);content.endText();
        }
    }
    private static void imagePage(PDDocument document,BufferedImage image,int rotation,String header) throws Exception {
        var page=new PDPage(PDRectangle.LETTER);page.setRotation(rotation);document.addPage(page);
        try(var content=new PDPageContentStream(document,page)) {
            content.drawImage(LosslessFactory.createFromImage(document,image),0,0,page.getMediaBox().getWidth(),page.getMediaBox().getHeight());
            if(header!=null) {
                content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),14);
                content.newLineAtOffset(35,750);content.showText(header);content.endText();
            }
        }
    }
}
