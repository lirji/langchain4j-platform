package com.lrj.platform.knowledge.ingest.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.assertj.core.api.Assertions.*;

/** 以生成流验证有界读取/摘要和资源释放，不需要分配一个同等大小的输入数组。 */
class IngestionUploadStagerTest {
    @TempDir Path root;
    private IngestionResourceProperties limits() { var value=new IngestionResourceProperties();value.setUploadTempRoot(root);return value; }
    @Test void streamsMegabytesWithStableDigestAndCleansTemporaryFile() throws Exception {
        var stager=new IngestionUploadStager(limits());
        var digest=MessageDigest.getInstance("SHA-256");int total=5*1024*1024;
        byte[] buffer=new byte[8192];java.util.Arrays.fill(buffer,(byte)'a');for(int i=0;i<total;i+=buffer.length)digest.update(buffer);
        var source=new InputStream(){int remaining=total;@Override public int read(){return remaining-->0?'a':-1;}
            @Override public int read(byte[] bytes,int offset,int length){if(remaining==0)return -1;int count=Math.min(length,remaining);java.util.Arrays.fill(bytes,offset,offset+count,(byte)'a');remaining-=count;return count;}
            @Override public byte[] readAllBytes(){throw new AssertionError("unbounded read");}};
        try(var staged=stager.stage("alpha",source,total)) {
            assertThat(staged.size()).isEqualTo(total);assertThat(Files.size(staged.path())).isEqualTo(total);
            assertThat(staged.hash()).isEqualTo("sha256:"+HexFormat.of().formatHex(digest.digest()));
        }
        assertEmpty();
    }
    @Test void enforcesTenantAndGlobalCapacityAndRecoversAfterClose() throws Exception {
        var limits=limits();limits.setMaxUploads(2);limits.setMaxUploadsPerTenant(1);var stager=new IngestionUploadStager(limits);
        try(var first=stager.stage("alpha",input(),5)) {
            assertError(()->stager.stage("alpha",input(),5),IngestionResourceError.UPLOAD_BUSY);
            try(var other=stager.stage("beta",input(),5)){assertError(()->stager.stage("gamma",input(),5),IngestionResourceError.UPLOAD_BUSY);}
            try(var next=stager.stage("gamma",input(),5)){assertThat(next.size()).isEqualTo(5);}
        }
        try(var next=stager.stage("alpha",input(),5)){assertThat(next.size()).isEqualTo(5);}
        assertEmpty();
    }
    @Test void rejectsUnknownSizeOverflowMismatchAndReadFailureWithoutLeakingCapacity() throws Exception {
        var limits=limits();limits.setMaxInputBytes(4);var stager=new IngestionUploadStager(limits);
        assertError(()->stager.stage("alpha",input(),-1),IngestionResourceError.INPUT_LIMIT);
        assertError(()->stager.stage("alpha",new ByteArrayInputStream(new byte[]{1}),2),IngestionResourceError.INPUT_SIZE);
        assertError(()->stager.stage("alpha",new InputStream(){@Override public int read() throws IOException{throw new IOException("private URL");}},-1),IngestionResourceError.INPUT_UNAVAILABLE);
        assertEmpty();
        try(var staged=stager.stage("alpha",new ByteArrayInputStream(new byte[]{1}),1)){assertThat(staged.size()).isEqualTo(1);}
        assertEmpty();
    }
    private static InputStream input(){return new ByteArrayInputStream("hello".getBytes());}
    private void assertEmpty() throws IOException{try(var files=Files.list(root)){assertThat(files).isEmpty();}}
    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,IngestionResourceError error){
        assertThatThrownBy(action).isInstanceOfSatisfying(IngestionResourceException.class,ex->assertThat(ex.error()).isEqualTo(error));
    }
}
