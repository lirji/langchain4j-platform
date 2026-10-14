package com.lrj.platform.protocol.knowledge;

import java.util.List;

/** 外部知识源增量协议：来源保证每条记录版本单调、页可重放，游标只作为不透明数据。 */
public final class KnowledgeSourceFeed {
    private KnowledgeSourceFeed() {}
    /** deleted=true明确表达删除事件；处理完成前不会推进来源游标，缺失记录不隐含删除。 */
    public record Item(String id, long revision, String title, String content, boolean deleted) {}
    /** 非空页必须推进nextCursor；空页可以保留原游标等待下次增量。 */
    public record Page(List<Item> items, String nextCursor) {}
}
