package com.lrj.platform.asynctask;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * AsyncTaskServiceApplicationTest：以默认内存存储（{@code app.async-task.store=in-memory}）启动
 * {@link AsyncTaskServiceApplication} 的 Spring 上下文冒烟测试，验证在无外部 DB 时应用能正常装配加载。
 */
@SpringBootTest(properties = {
        "app.async-task.store=in-memory",
        "app.async-task.cleanup-initial-delay-ms=600000"
})
class AsyncTaskServiceApplicationTest {

    @Test
    void contextLoadsWithDefaultInMemoryStore() {
    }
}
