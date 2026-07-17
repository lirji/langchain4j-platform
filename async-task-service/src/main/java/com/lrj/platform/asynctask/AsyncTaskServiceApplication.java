package com.lrj.platform.asynctask;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * async-task-service 启动入口（:8086）——通用异步任务中心。排除 {@link DataSourceAutoConfiguration}，
 * 数据源仅在 {@code app.async-task.store=jdbc} 时由 {@link AsyncTaskJdbcConfig} 按需装配，默认内存实现无需外部 DB。
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class AsyncTaskServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AsyncTaskServiceApplication.class, args);
    }
}
