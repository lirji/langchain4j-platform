package com.lrj.platform.observability;

import org.springframework.boot.actuate.health.Health;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * 共享 TCP 连通性探测（迁移单体 {@code LlmHealthIndicator.probeTcp}）：对 base-url 做 1s 超时 TCP 连接，
 * <strong>不发 LLM/embedding 请求</strong> —— 不烧 token、不需 api-key 有效、亚秒回结果，适合挂 K8s readiness。
 *
 * <p>反映网络层健康（后端 pod 就绪 / DNS / 路由），不反映模型可推理（后者靠真实流量的错误率指标）。
 */
public final class TcpHealthProbe {

    private static final int CONNECT_TIMEOUT_MS = 1000;

    private TcpHealthProbe() {
    }

    /** 探测 {@code url} 的 host:port（端口按 scheme 推断）。{@code extraDetails} 为成对的 key/value 附加明细。 */
    public static Health probeTcp(String url, String... extraDetails) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null) {
                return Health.down()
                        .withDetail("reason", "could not parse host from url")
                        .withDetail("url", url)
                        .build();
            }
            int port = uri.getPort();
            if (port < 0) {
                port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            }
            long start = System.nanoTime();
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            Health.Builder b = Health.up()
                    .withDetail("host", host)
                    .withDetail("port", port)
                    .withDetail("tcpConnectMs", ms);
            addExtras(b, extraDetails);
            return b.build();
        } catch (URISyntaxException e) {
            return Health.down()
                    .withDetail("reason", "invalid url syntax")
                    .withDetail("url", url)
                    .withException(e)
                    .build();
        } catch (Exception e) {
            Health.Builder b = Health.down().withDetail("url", url).withException(e);
            addExtras(b, extraDetails);
            return b.build();
        }
    }

    private static void addExtras(Health.Builder b, String... extraDetails) {
        for (int i = 0; i + 1 < extraDetails.length; i += 2) {
            b.withDetail(extraDetails[i], extraDetails[i + 1]);
        }
    }
}
