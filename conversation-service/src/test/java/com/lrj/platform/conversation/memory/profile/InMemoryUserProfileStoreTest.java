package com.lrj.platform.conversation.memory.profile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InMemoryUserProfileStoreTest：验证 {@link InMemoryUserProfileStore} 的按租户+用户分桶增删列、子串包含与去标点/大小写
 * 归一化去重、超容量淘汰最旧、清空计数，以及忽略空白文本。
 */
class InMemoryUserProfileStoreTest {

    private static MemoryItem item(String text) {
        return new MemoryItem(Integer.toHexString(text.hashCode()), text, "attribute", 0L, "c1");
    }

    @Test
    void add_and_list_perTenantUser() {
        InMemoryUserProfileStore store = new InMemoryUserProfileStore(50);
        store.add("acme", "alice", item("偏好邮件联系"));
        store.add("acme", "bob", item("是 Pro 用户"));

        assertThat(store.list("acme", "alice")).extracting(MemoryItem::text).containsExactly("偏好邮件联系");
        assertThat(store.list("acme", "bob")).extracting(MemoryItem::text).containsExactly("是 Pro 用户");
        // 跨租户隔离
        assertThat(store.list("other", "alice")).isEmpty();
    }

    @Test
    void add_dedupsBySubstringContainment() {
        InMemoryUserProfileStore store = new InMemoryUserProfileStore(50);
        store.add("acme", "alice", item("偏好邮件"));
        store.add("acme", "alice", item("偏好邮件联系")); // 包含已有 → 视为重复，不追加

        assertThat(store.list("acme", "alice")).hasSize(1);
    }

    @Test
    void add_dedupsIgnoringPunctuationAndCase() {
        InMemoryUserProfileStore store = new InMemoryUserProfileStore(50);
        store.add("acme", "alice", item("Prefers Email"));
        store.add("acme", "alice", item("prefers, email.")); // 归一化后相同

        assertThat(store.list("acme", "alice")).hasSize(1);
    }

    @Test
    void add_evictsOldestBeyondCapacity() {
        InMemoryUserProfileStore store = new InMemoryUserProfileStore(2);
        store.add("acme", "alice", item("f1"));
        store.add("acme", "alice", item("f2"));
        store.add("acme", "alice", item("f3")); // 超容量 → 淘汰最旧 f1

        assertThat(store.list("acme", "alice")).extracting(MemoryItem::text).containsExactly("f2", "f3");
    }

    @Test
    void clear_removesAndReturnsCount() {
        InMemoryUserProfileStore store = new InMemoryUserProfileStore(50);
        store.add("acme", "alice", item("f1"));
        store.add("acme", "alice", item("f2"));

        assertThat(store.clear("acme", "alice")).isEqualTo(2);
        assertThat(store.list("acme", "alice")).isEmpty();
        assertThat(store.clear("acme", "alice")).isZero();
    }

    @Test
    void add_blankText_ignored() {
        InMemoryUserProfileStore store = new InMemoryUserProfileStore(50);
        store.add("acme", "alice", item("   "));
        assertThat(store.list("acme", "alice")).isEmpty();
    }
}
