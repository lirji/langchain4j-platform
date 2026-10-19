package com.lrj.platform.knowledge;

import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;

/** 最终分块使用文档内唯一编号，避免嵌套 splitter 的局部编号覆盖 ES 记录和引用。 */
public final class ChunkIndexing {

    /** LangChain4j 与 ES 共用的分块顺序协议字段。 */
    public static final String INDEX = "index";

    private ChunkIndexing() {
    }

    /** 所有子切分完成后重新编号；复制 metadata，保留父块关联且不改写输入对象。 */
    public static List<TextSegment> sequential(List<TextSegment> segments) {
        List<TextSegment> result = new ArrayList<>(segments.size());
        for (int index = 0; index < segments.size(); index++) {
            TextSegment segment = segments.get(index);
            var metadata = segment.metadata().copy();
            metadata.put(INDEX, Integer.toString(index));
            result.add(TextSegment.from(segment.text(), metadata));
        }
        return result;
    }
}
