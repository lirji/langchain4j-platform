package com.lrj.platform.knowledge.lifecycle;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.io.IOException;

/** 固定 schema 的子进程 JSON 协议，无多态类加载；外层执行器另限制结果字节及临时盘。 */
final class ParsedDocumentCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static {
        MAPPER.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(32).maxStringLength(16 * 1024 * 1024).build());
    }
    private ParsedDocumentCodec() { }
    static void write(Path target, ParsedDocument document) throws IOException { MAPPER.writeValue(target.toFile(), document); }
    static ParsedDocument read(byte[] bytes, DocumentParsingProperties limits) {
        try { return MAPPER.readValue(bytes, ParsedDocument.class).validate(limits); }
        catch (DocumentParsingException failure) { throw failure; }
        catch (IOException | IllegalArgumentException failure) {
            throw new DocumentParsingException(DocumentParsingError.PROCESS_FAILED, failure);
        }
    }
}
