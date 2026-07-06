package com.lrj.platform.asynctask;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class AsyncTaskServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AsyncTaskServiceApplication.class, args);
    }
}
