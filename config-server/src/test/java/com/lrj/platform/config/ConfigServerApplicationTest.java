package com.lrj.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 冒烟测试：Config Server 用 native 后端能加载 Spring 上下文（不依赖 git/外部基础设施）。
 * 参考 async-task-service 的 ApplicationTest 风格：纯 context-load，不绑定端口。
 */
@SpringBootTest(properties = {
        "spring.profiles.active=native",
        "spring.cloud.config.server.native.search-locations=classpath:/config/"
})
class ConfigServerApplicationTest {

    @Test
    void contextLoadsWithNativeBackend() {
    }
}
