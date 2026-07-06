package com.lrj.platform.asynctask;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "app.async-task.store=in-memory",
        "app.async-task.cleanup-initial-delay-ms=600000"
})
class AsyncTaskServiceApplicationTest {

    @Test
    void contextLoadsWithDefaultInMemoryStore() {
    }
}
