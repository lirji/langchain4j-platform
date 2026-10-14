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

    /** 在对话开始前捕获代次，覆盖生成回复期间发生的清空；旧开发实现保留原语义。 */
    public long beginObservation(String tenant, String user) {
        return store instanceof GovernedProfileStore governed ? governed.generation(tenant, user) : Long.MIN_VALUE;
    }

    /** 观察一轮对话；独立调用同样在提交异步任务之前捕获代次。 */
    public void observe(String tenant, String user, String chatId, String userMessage, String assistantReply) {
        observe(tenant, user, chatId, userMessage, assistantReply, beginObservation(tenant, user));
    }

    /** 抽取不持有数据库锁；写候选时再次验证代次，失效结果直接丢弃。 */
    public void observe(String tenant, String user, String chatId, String userMessage, String assistantReply,
                        long generation) {
        if (generation == -1) return;
        Runnable task = () -> {
            try {
                ExtractedMemories extracted = extractor.extract(userMessage, assistantReply);
                if (extracted == null || extracted.facts() == null) {
                    return;
                }
                long now = clock.getAsLong();
                // 模型输出也是不可信输入，每轮有界，不能占满持久化和审计容量。
                for (MemoryFact fact : extracted.facts().stream().limit(20).toList()) {
                    if (fact == null || fact.text() == null || fact.text().isBlank()) {
                        continue;
                    }
                    String id = Integer.toHexString(ProfileMemoryText.normalize(fact.text()).hashCode());
                    MemoryItem item = new MemoryItem(id, fact.text().trim(), fact.type(), now, chatId);
                    if (store instanceof GovernedProfileStore governed) {
                        governed.offer(tenant, user, item, generation);
                    } else {
                        store.add(tenant, user, item);
                    }
                }
            } catch (RuntimeException e) {
                log.warn("profile observe failed: {}", e.getClass().getSimpleName());
            }
        };
        if (async) {
            try {
                executor.execute(task);
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                // 后台队列满时不拖垮已完成的对话；不执行无界重试。
                log.warn("profile observation queue is full");
            }
        } else {
            task.run();
        }
    }
}
