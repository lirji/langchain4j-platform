package com.lrj.platform.conversation.memory.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * 用户画像编排（迁移单体 {@code UserProfileService}）：
 * <ul>
 *   <li>{@link #recall} —— 取该用户最近 {@code recallLimit} 条，渲染为 {@code - text} 项目符号块（无则空串）；</li>
 *   <li>{@link #observe} —— （默认异步）抽取本轮用户长期事实并入库，失败吞掉不影响对话。</li>
 * </ul>
 */
public class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);

    private final UserProfileStore store;
    private final ProfileExtractor extractor;
    private final Executor executor;
    private final int recallLimit;
    private final boolean async;
    private final LongSupplier clock;

    public UserProfileService(UserProfileStore store, ProfileExtractor extractor, Executor executor,
                              int recallLimit, boolean async, LongSupplier clock) {
        this.store = store;
        this.extractor = extractor;
        this.executor = executor;
        this.recallLimit = Math.max(1, recallLimit);
        this.async = async;
        this.clock = clock;
    }

    public List<MemoryItem> list(String tenant, String user) {
        return store.list(tenant, user);
    }

    public int clear(String tenant, String user) {
        return store.clear(tenant, user);
    }

    /** 渲染最近 recallLimit 条为项目符号块，供对话前缀注入；无记忆返回空串。 */
    public String recall(String tenant, String user) {
        List<MemoryItem> all = store.list(tenant, user);
        if (all.isEmpty()) {
            return "";
        }
        int from = Math.max(0, all.size() - recallLimit);
        StringBuilder sb = new StringBuilder();
        for (MemoryItem item : all.subList(from, all.size())) {
            sb.append("- ").append(item.text()).append('\n');
        }
        return sb.toString().trim();
    }

    /** 观察一轮对话：抽取用户长期事实入库（默认异步）。失败静默。 */
    public void observe(String tenant, String user, String chatId, String userMessage, String assistantReply) {
        Runnable task = () -> {
            try {
                ExtractedMemories extracted = extractor.extract(userMessage, assistantReply);
                if (extracted == null || extracted.facts() == null) {
                    return;
                }
                long now = clock.getAsLong();
                for (MemoryFact fact : extracted.facts()) {
                    if (fact == null || fact.text() == null || fact.text().isBlank()) {
                        continue;
                    }
                    String id = Integer.toHexString(InMemoryUserProfileStore.norm(fact.text()).hashCode());
                    store.add(tenant, user, new MemoryItem(id, fact.text().trim(), fact.type(), now, chatId));
                }
            } catch (RuntimeException e) {
                log.warn("profile observe failed (swallowed): {}", e.toString());
            }
        };
        if (async) {
            executor.execute(task);
        } else {
            task.run();
        }
    }
}
