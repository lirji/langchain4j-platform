package com.lrj.platform.knowledge.ingest.job;

import org.junit.jupiter.api.Test;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import static org.assertj.core.api.Assertions.*;

/** TCP 已连接但不返回数据库握手，验证连接读等待有界，而非仅检查存在配置键。 */
class IngestionDatasourceBudgetTest {
    @Test void zeroMustNotDisablePoolOrStatementTimeout() {
        var properties = new IngestionJobProperties();
        properties.getDatasource().setConnectionTimeout(Duration.ZERO);
        assertThatThrownBy(() -> new IngestionJobConfig().ingestionJobDataSource(properties))
                .isInstanceOf(IngestionResourceException.class);
        assertThatThrownBy(() -> new JdbcIngestionJobStore(new org.springframework.jdbc.datasource.DriverManagerDataSource(),
                new com.fasterxml.jackson.databind.ObjectMapper(),0)).isInstanceOf(IngestionResourceException.class);
    }
    @Test void silentDatabaseHandshakeCannotHangApplicationStartup() throws Exception {
        try(var socket=new ServerSocket(0);var executor=Executors.newSingleThreadExecutor()) {
            var stop=new CountDownLatch(1);
            var server=executor.submit(()-> {
                try(var connection=socket.accept()) { stop.await(5,java.util.concurrent.TimeUnit.SECONDS); }
                catch(Exception ignored) { }
            });
            var properties=new IngestionJobProperties();var source=properties.getDatasource();
            source.setUrl("jdbc:mysql://127.0.0.1:"+socket.getLocalPort()+"/test?useSSL=false");
            source.setUsername("test");source.setPassword("test-only");
            source.setConnectTimeout(Duration.ofMillis(200));source.setSocketTimeout(Duration.ofMillis(200));
            long start=System.nanoTime();
            try {
                assertThatThrownBy(()->new IngestionJobConfig().ingestionJobDataSource(properties))
                        .isInstanceOf(com.zaxxer.hikari.pool.HikariPool.PoolInitializationException.class);
                assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(4));
            } finally { stop.countDown();server.get(); }
        }
    }
}
