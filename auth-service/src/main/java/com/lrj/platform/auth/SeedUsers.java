package com.lrj.platform.auth;

import java.util.List;
import java.util.Set;

/**
 * 演示种子账号：镜像 {@code edge-gateway/application.yml} 里既有的 api-key → 租户绑定，
 * 使"登录"与"手输 api-key"拿到完全一致的租户与 scope，行为可平滑替换。
 *
 * <p>内存 store 直接用；JDBC store 在表为空时用它初始化（demo 友好）。生产应改为落库自管账号。
 */
final class SeedUsers {

    private SeedUsers() {}

    static List<UserAccount> defaults(PasswordHasher hasher, String demoPassword) {
        String hash = hasher.hash(demoPassword);
        return List.of(
                new UserAccount("alice", hash, "acme", "alice",
                        Set.of("chat", "ingest", "approve", "agent", "channel", "eval", "vision", "voice"), true),
                new UserAccount("bob", hash, "globex", "bob",
                        Set.of("chat"), true),
                new UserAccount("analyst-a", hash, "tenantA", "analyst-a",
                        Set.of("chat", "analytics"), true));
    }
}
