package com.lrj.platform.conversation.memory.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存用户画像存储（迁移单体 {@code InMemoryUserProfileStore}）：按 {@code (tenant, user)} 键，
 * 追加时按归一化文本去重（相等或子串包含）+ 容量上限淘汰最旧；每个 key 上锁串行化「读→去重→写」。
 * 归一化：小写 + 去空白/标点。
 */
public class InMemoryUserProfileStore implements UserProfileStore {

    private final Map<String, List<MemoryItem>> byKey = new ConcurrentHashMap<>();
    private final int maxItems;

    public InMemoryUserProfileStore(int maxItems) {
        this.maxItems = Math.max(1, maxItems);
    }

    private static String key(String tenant, String user) {
        return tenant + "::" + user;
    }

    static String norm(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase().replaceAll("[\\s\\p{Punct}，。、；：！？（）【】「」]", "");
    }

    @Override
    public void add(String tenant, String user, MemoryItem item) {
        if (item == null || item.text() == null || item.text().isBlank()) {
            return;
        }
        String k = key(tenant, user);
        List<MemoryItem> list = byKey.computeIfAbsent(k, x -> new ArrayList<>());
        synchronized (list) {
            String normNew = norm(item.text());
            for (MemoryItem existing : list) {
                String normOld = norm(existing.text());
                // 相等或互相包含 → 视为重复，不再追加（避免「偏好邮件」与「偏好邮件联系」并存）
                if (normOld.equals(normNew) || normOld.contains(normNew) || normNew.contains(normOld)) {
                    return;
                }
            }
            list.add(item);
            while (list.size() > maxItems) {
                list.remove(0); // 淘汰最旧
            }
        }
    }

    @Override
    public List<MemoryItem> list(String tenant, String user) {
        List<MemoryItem> list = byKey.get(key(tenant, user));
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            return List.copyOf(list);
        }
    }

    @Override
    public int clear(String tenant, String user) {
        List<MemoryItem> removed = byKey.remove(key(tenant, user));
        return removed == null ? 0 : removed.size();
    }
}
