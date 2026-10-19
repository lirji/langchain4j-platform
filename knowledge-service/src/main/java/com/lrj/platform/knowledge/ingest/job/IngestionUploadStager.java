package com.lrj.platform.knowledge.ingest.job;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/** 流式 hash + 私有临时文件；本地上传准入与实例临时盘预算配套，不冒充跨副本限流。 */
final class IngestionUploadStager {
    private final IngestionResourceProperties limits;
    private final Map<String,Integer> tenants = new HashMap<>();
    private int active;
    IngestionUploadStager(IngestionResourceProperties limits) { this.limits=limits; }

    StagedUpload stage(String tenant,InputStream input,long declaredSize) {
        if(declaredSize>limits.getMaxInputBytes()) throw new IngestionResourceException(IngestionResourceError.INPUT_LIMIT);
        synchronized(this) {
            int tenantActive=tenants.getOrDefault(tenant,0);
            if(active>=limits.getMaxUploads() || tenantActive>=limits.getMaxUploadsPerTenant())
                throw new IngestionResourceException(IngestionResourceError.UPLOAD_BUSY);
            active++;tenants.put(tenant,tenantActive+1);
        }
        Path file=null;
        try {
            Files.createDirectories(limits.getUploadTempRoot());
            file=Files.createTempFile(limits.getUploadTempRoot(),"source-",".input");
            MessageDigest hash=MessageDigest.getInstance("SHA-256");long total=0;
            long deadline=System.nanoTime()+limits.getUploadTimeout().toNanos();
            try(var output=Files.newOutputStream(file)) {
                byte[] buffer=new byte[8192];int count;
                while(true) {
                    if(System.nanoTime()>deadline) throw new IngestionResourceException(IngestionResourceError.DEADLINE);
                    count=input.read(buffer);
                    if(System.nanoTime()>deadline) throw new IngestionResourceException(IngestionResourceError.DEADLINE);
                    if(count<0) break;
                    total+=count;
                    if(total>limits.getMaxInputBytes()) throw new IngestionResourceException(IngestionResourceError.INPUT_LIMIT);
                    hash.update(buffer,0,count);output.write(buffer,0,count);
                }
            }
            if(total==0 || (declaredSize>=0 && total!=declaredSize)) throw new IngestionResourceException(IngestionResourceError.INPUT_SIZE);
            return new StagedUpload(file,total,"sha256:"+HexFormat.of().formatHex(hash.digest()),tenant);
        } catch(Exception failure) {
            cleanup(file); release(tenant);
            if(failure instanceof IngestionResourceException resource) throw resource;
            throw new IngestionResourceException(IngestionResourceError.INPUT_UNAVAILABLE,failure);
        }
    }
    private synchronized void release(String tenant) { active--; tenants.computeIfPresent(tenant,(key,count)->count==1?null:count-1); }
    private static void cleanup(Path file) {
        if(file!=null) try {Files.deleteIfExists(file);} catch(IOException failure) {
            org.slf4j.LoggerFactory.getLogger(IngestionUploadStager.class).warn("ingestion upload temporary file cleanup failed");
        }
    }
    final class StagedUpload implements AutoCloseable {
        private final Path path;private final long size;private final String hash,tenant;private boolean closed;
        StagedUpload(Path path,long size,String hash,String tenant){this.path=path;this.size=size;this.hash=hash;this.tenant=tenant;}
        Path path(){return path;} long size(){return size;} String hash(){return hash;}
        @Override public void close(){if(!closed){closed=true;cleanup(path);release(tenant);}}
    }
}
