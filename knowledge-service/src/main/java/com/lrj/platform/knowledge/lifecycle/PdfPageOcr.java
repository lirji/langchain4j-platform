package com.lrj.platform.knowledge.lifecycle;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.ImageType;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import static com.lrj.platform.knowledge.lifecycle.ParsedDocument.*;

/** 按页选择 OCR：原生页免调用；扫描/大图页在渲染前统一检查预算，结果保留来源与策略。 */
final class PdfPageOcr {
    static final String NATIVE = "native", OCR = "tesseract-auto-osd", MIXED = "native+tesseract-auto-osd", BLANK = "blank";
    private final DocumentParsingProperties limits;
    private final Recognizer recognizer;
    PdfPageOcr(DocumentParsingProperties limits,Recognizer recognizer) {this.limits=limits;this.recognizer=recognizer;}

    List<Block> apply(PDDocument document,List<Block> nativePages,Consumer<Warning> warnings) throws IOException {
        var policy=limits.getOcr();
        long deadline=System.nanoTime()+policy.getTotalTimeout().toNanos(), totalPixels=0;
        var selected=new boolean[nativePages.size()]; int pages=0;
        // 在第一个 native OCR 调用前检查整份工作量，拒绝超预算，不能处理前几页后静默丢掉尾页。
        for(int index=0;index<nativePages.size();index++) {
            checkDeadline(deadline);
            var page=document.getPage(index);var text=nativePages.get(index).text();
            var coverage=new ImageCoverage(page);coverage.processPage(page);
            int nativeChars=visibleChars(text);
            selected[index]=coverage.fraction()>=policy.getImageCoverage()
                    || (nativeChars==0 && page.hasContents())
                    || (nativeChars<policy.getMinNativeChars() && coverage.fraction()>0);
            if(selected[index]) {
                long pixels=pixels(page,policy.getDpi());
                if(++pages>policy.getMaxPages() || pixels>policy.getMaxPagePixels()
                        || coverage.maxRasterPixels>policy.getMaxPagePixels()) fail(DocumentParsingError.OCR_LIMIT);
                if(pixels>policy.getMaxTotalPixels()-totalPixels) fail(DocumentParsingError.OCR_LIMIT);
                totalPixels+=pixels;
            }
        }
        var renderer=new PDFRenderer(document); var result=new ArrayList<Block>(nativePages.size());long totalChars=0;
        for(int index=0;index<nativePages.size();index++) {
            checkDeadline(deadline);
            var original=nativePages.get(index);int nativeCount=visibleChars(original.text());
            String text=original.text();var location=original.location().withExtraction(NATIVE,nativeCount,0,null);
            if(selected[index]) {
                BufferedImage image=renderer.renderImageWithDPI(index,policy.getDpi(),ImageType.RGB);
                Path work=null;boolean retain=false;
                try {
                    if((long)image.getWidth()*image.getHeight()>policy.getMaxPagePixels()) fail(DocumentParsingError.OCR_LIMIT);
                    if(blank(image)) {
                        location=original.location().withExtraction(BLANK,nativeCount,0,null);
                        warnings.accept(new Warning(ParsingWarnings.PDF_BLANK,location));
                    } else {
                        work=Files.createTempDirectory("knowledge-ocr-");
                        Result recognized=recognizer.recognize(image,work,Math.min(deadline,System.nanoTime()+policy.getPageTimeout().toNanos()));
                        checkDeadline(deadline);
                        if(recognized.text().isBlank() || !Double.isFinite(recognized.confidence())
                                || recognized.confidence()<policy.getMinConfidence()) fail(DocumentParsingError.OCR_QUALITY);
                        text=merge(original.text(),recognized.text());
                        location=original.location().withExtraction(nativeCount==0?OCR:MIXED,nativeCount,
                                visibleChars(recognized.text()),Math.round(recognized.confidence()*100.0)/100.0);
                        warnings.accept(new Warning(ParsingWarnings.PDF_OCR,location));
                        if(nativeCount>0) warnings.accept(new Warning(ParsingWarnings.PDF_OCR_MERGED,location));
                    }
                } catch(DocumentParsingException failure) {
                    retain=failure.error()==DocumentParsingError.TERMINATION_FAILED;throw failure;
                } finally {
                    image.flush();
                    if(work!=null && !retain) cleanup(work);
                }
            }
            totalChars+=text.length();
            if(totalChars>limits.getMaxTextChars()) fail(DocumentParsingError.TEXT_LIMIT);
            result.add(new Block(PAGE,text,location,original.headings(),original.rows()));
        }
        return result;
    }
    /** 仅去掉已经存在于原生层的 OCR 行；不去重 OCR 自身，避免删掉表格重复值。 */
    static String merge(String nativeText,String ocrText) {
        if(nativeText.isBlank()) return ocrText;
        var nativeLines=new java.util.HashMap<String,Integer>();
        nativeText.lines().map(PdfPageOcr::normalize).forEach(line->nativeLines.merge(line,1,Integer::sum));
        // 只抵消整行匹配的相同次数；子串/重复数字可能是另一行表值，不能猜测后丢弃。
        var extra=ocrText.lines().filter(line->{
            String value=normalize(line);int matches=nativeLines.getOrDefault(value,0);
            if(value.length()<3 || matches==0) return true;
            nativeLines.put(value,matches-1);return false;
        }).toList();
        return extra.isEmpty()?nativeText:nativeText.stripTrailing()+"\n"+String.join("\n",extra);
    }
    private static String normalize(String text) { return text.replaceAll("\\s+","").toLowerCase(Locale.ROOT); }
    private static int visibleChars(String text) {return (int)text.codePoints().filter(Character::isLetterOrDigit).count();}
    private static long pixels(PDPage page,int dpi) {
        double width=Math.ceil(page.getCropBox().getWidth()*dpi/72.0),height=Math.ceil(page.getCropBox().getHeight()*dpi/72.0);
        if(!Double.isFinite(width*height) || width<1 || height<1 || width*height>Long.MAX_VALUE) fail(DocumentParsingError.OCR_LIMIT);
        return (long)width*(long)height;
    }
    /** 只把无暗像素页面当空白；噪声/低对比度页不猜成空白丢弃。 */
    private static boolean blank(BufferedImage image) {
        for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++) {
            int rgb=image.getRGB(x,y);
            if((rgb & 0xFFFFFF) != 0xFFFFFF) return false;
        }
        return true;
    }
    private static void cleanup(Path work) {
        try(var files=Files.walk(work)) {
            for(Path path:files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch(IOException failure) {org.slf4j.LoggerFactory.getLogger(PdfPageOcr.class).warn("OCR temporary cleanup failed");}
    }
    private static void checkDeadline(long deadline) {if(System.nanoTime()>deadline) fail(DocumentParsingError.OCR_TIMEOUT);}
    private static void fail(DocumentParsingError error) {throw new DocumentParsingException(error,null);}
    @FunctionalInterface interface Recognizer { Result recognize(BufferedImage image,Path work,long deadline) throws IOException; }
    record Result(String text,double confidence) { }

    /** 图像变换矩阵估计覆盖面积，不解码图像；同页重叠/裁剪只作保守触发，不能当版面准确率。 */
    private static final class ImageCoverage extends PDFGraphicsStreamEngine {
        private double area;private long maxRasterPixels;private Point2D point=new Point2D.Float();
        ImageCoverage(PDPage page) {super(page);}
        double fraction() {return Math.min(1,area/(getPage().getCropBox().getWidth()*getPage().getCropBox().getHeight()));}
        @Override public void drawImage(PDImage image) {
            var transform=getGraphicsState().getCurrentTransformationMatrix();
            area+=Math.abs(transform.getScaleX()*transform.getScaleY()-transform.getShearX()*transform.getShearY());
            maxRasterPixels=Math.max(maxRasterPixels,(long)image.getWidth()*image.getHeight());
        }
        @Override public void appendRectangle(Point2D a,Point2D b,Point2D c,Point2D d) {point=a;}
        @Override public void clip(int rule) { }
        @Override public void moveTo(float x,float y) {point=new Point2D.Float(x,y);}
        @Override public void lineTo(float x,float y) {point=new Point2D.Float(x,y);}
        @Override public void curveTo(float a,float b,float c,float d,float e,float f) {point=new Point2D.Float(e,f);}
        @Override public Point2D getCurrentPoint() {return point;}
        @Override public void closePath() { }
        @Override public void endPath() { }
        @Override public void strokePath() { }
        @Override public void fillPath(int rule) { }
        @Override public void fillAndStrokePath(int rule) { }
        @Override public void shadingFill(COSName name) { }
    }
}
