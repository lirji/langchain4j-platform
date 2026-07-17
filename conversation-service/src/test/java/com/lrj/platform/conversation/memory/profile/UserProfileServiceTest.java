package com.lrj.platform.conversation.memory.profile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * UserProfileServiceTest：验证 {@link UserProfileService} 的画像回忆（渲染要点、按 recall 限额取最近）与
 * observe 抽取入库（空事实不写、抽取异常被吞不外抛）。
 */
class UserProfileServiceTest {

    /** 同步执行器，便于确定性断言 observe 结果。 */
    private static UserProfileService service(UserProfileStore store, ProfileExtractor extractor) {
        return new UserProfileService(store, extractor, Runnable::run, 12, false, () -> 123L);
    }

    @Test
    void recall_empty_returnsBlank() {
        UserProfileService svc = service(new InMemoryUserProfileStore(50), mock(ProfileExtractor.class));
        assertThat(svc.recall("acme", "alice")).isEmpty();
    }

    @Test
    void recall_rendersBulletsMostRecentWithinLimit() {
        InMemoryUserProfileStore store = new InMemoryUserProfileStore(50);
        store.add("acme", "alice", new MemoryItem("1", "偏好邮件联系", "preference", 0L, "c1"));
        store.add("acme", "alice", new MemoryItem("2", "是 Pro 用户", "attribute", 0L, "c1"));
        UserProfileService svc = new UserProfileService(store, mock(ProfileExtractor.class),
                Runnable::run, 12, false, () -> 0L);

        assertThat(svc.recall("acme", "alice")).isEqualTo("- 偏好邮件联系\n- 是 Pro 用户");
    }

    @Test
    void recall_respectsRecallLimit_takesMostRecent() {
        InMemoryUserProfileStore store = new InMemoryUserProfileStore(50);
        store.add("acme", "alice", new MemoryItem("1", "旧-1", "attribute", 0L, "c1"));
        store.add("acme", "alice", new MemoryItem("2", "旧-2", "attribute", 0L, "c1"));
        store.add("acme", "alice", new MemoryItem("3", "新-3", "attribute", 0L, "c1"));
        UserProfileService svc = new UserProfileService(store, mock(ProfileExtractor.class),
                Runnable::run, 2, false, () -> 0L); // 只回忆最近 2 条

        assertThat(svc.recall("acme", "alice")).isEqualTo("- 旧-2\n- 新-3");
    }

    @Test
    void observe_extractsAndStoresFacts() {
        InMemoryUserProfileStore store = new InMemoryUserProfileStore(50);
        ProfileExtractor extractor = mock(ProfileExtractor.class);
        when(extractor.extract("我只用邮件", "好的"))
                .thenReturn(new ExtractedMemories(List.of(new MemoryFact("偏好邮件联系", "preference"))));
        UserProfileService svc = service(store, extractor);

        svc.observe("acme", "alice", "c1", "我只用邮件", "好的");

        List<MemoryItem> items = store.list("acme", "alice");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).text()).isEqualTo("偏好邮件联系");
        assertThat(items.get(0).createdAtEpochMs()).isEqualTo(123L);
        assertThat(items.get(0).sourceChatId()).isEqualTo("c1");
    }

    @Test
    void observe_emptyFacts_storesNothing() {
        InMemoryUserProfileStore store = new InMemoryUserProfileStore(50);
        ProfileExtractor extractor = mock(ProfileExtractor.class);
        when(extractor.extract("现在几点", "10 点")).thenReturn(new ExtractedMemories(List.of()));
        UserProfileService svc = service(store, extractor);

        svc.observe("acme", "alice", "c1", "现在几点", "10 点");

        assertThat(store.list("acme", "alice")).isEmpty();
    }

    @Test
    void observe_extractorException_swallowed() {
        InMemoryUserProfileStore store = new InMemoryUserProfileStore(50);
        ProfileExtractor extractor = mock(ProfileExtractor.class);
        when(extractor.extract("x", "y")).thenThrow(new RuntimeException("judge down"));
        UserProfileService svc = service(store, extractor);

        svc.observe("acme", "alice", "c1", "x", "y"); // 不抛
        assertThat(store.list("acme", "alice")).isEmpty();
    }
}
