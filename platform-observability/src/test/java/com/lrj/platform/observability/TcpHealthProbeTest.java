package com.lrj.platform.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

class TcpHealthProbeTest {

    @Test
    void up_whenPortIsListening() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            Health health = TcpHealthProbe.probeTcp("http://127.0.0.1:" + port, "target", "test");

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry("host", "127.0.0.1")
                    .containsEntry("port", port)
                    .containsEntry("target", "test")
                    .containsKey("tcpConnectMs");
        }
    }

    @Test
    void down_whenNothingListening() {
        // 端口 1（保留、几乎不会有监听）→ 连接失败 → DOWN
        Health health = TcpHealthProbe.probeTcp("http://127.0.0.1:1");
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("url", "http://127.0.0.1:1");
    }

    @Test
    void down_whenHostUnparseable() {
        Health health = TcpHealthProbe.probeTcp("not a url");
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void infersHttpsPort443WhenNoExplicitPort() {
        // 无端口的 https → 推断 443；解析成功（连不连得上取决于网络，这里只验证不抛异常并给出结论）
        Health health = TcpHealthProbe.probeTcp("https://127.0.0.1");
        // 127.0.0.1:443 通常无监听 → DOWN，但不应是解析错误
        assertThat(health.getDetails()).doesNotContainKey("reason");
    }
}
