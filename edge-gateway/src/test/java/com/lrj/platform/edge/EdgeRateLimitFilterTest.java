package com.lrj.platform.edge;

import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.InternalToken;
import com.lrj.platform.security.TenantContext;
import com.lrj.platform.security.ratelimit.InMemoryRateLimiterRegistry;
import com.lrj.platform.security.ratelimit.RateLimitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EdgeRateLimitFilterTest：验证边缘限流对<b>免鉴权入口</b>的保护——{@code /auth/login} 与渠道回调按客户端 IP
 * 限桶（超限 429 + Retry-After，不同 IP 互不影响），健康探针不限流，已鉴权业务路径仍按租户限桶；
 * 并验证默认<b>不信任</b> {@code X-Forwarded-For}（伪造该头不能换到新桶），只有显式配置
 * {@code client-ip-header} 后才按该头分桶。
 */
class EdgeRateLimitFilterTest {

    private static final String CLIENT_A = "203.0.113.10";
    private static final String CLIENT_B = "203.0.113.11";

    private final InternalSecurityProperties securityProps = new InternalSecurityProperties();
    private final InternalToken tokens =
            new InternalToken(new InternalSecurityProperties().getJwtSecret(), Duration.ofMinutes(5));

    private static RateLimitProperties props(int qpm) {
        RateLimitProperties p = new RateLimitProperties();
        p.getDefaults().put(EdgeOpenPaths.FAMILY_AUTH, qpm);
        p.getDefaults().put(EdgeOpenPaths.FAMILY_CHANNEL_CALLBACK, qpm);
        p.getDefaults().put("chat", qpm);
        return p;
    }

    private EdgeRateLimitFilter filter(RateLimitProperties props) {
        return new EdgeRateLimitFilter(props, new InMemoryRateLimiterRegistry(props), securityProps, tokens);
    }

    private static MockServerWebExchange request(String path, String clientIp, HttpHeaders extra) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.post(path)
                .remoteAddress(new InetSocketAddress(clientIp, 12345));
        if (extra != null) {
            builder.headers(extra);
        }
        return MockServerWebExchange.from(builder.build());
    }

    private static GatewayFilterChain counting(AtomicInteger passed) {
        return exchange -> {
            passed.incrementAndGet();
            return Mono.empty();
        };
    }

    @Test
    void unauthenticatedLoginIsRateLimitedPerClientIp() {
        // 免鉴权入口早期完全跳过限流 → 暴力破解无速率成本。这里第 2 次同 IP 请求就应被拒。
        EdgeRateLimitFilter filter = filter(props(1));
        AtomicInteger passed = new AtomicInteger();

        filter.filter(request("/auth/login", CLIENT_A, null), counting(passed)).block();
        MockServerWebExchange rejected = request("/auth/login", CLIENT_A, null);
        filter.filter(rejected, counting(passed)).block();

        assertThat(passed.get()).isEqualTo(1);
        assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
    }

    @Test
    void differentClientIpsGetIndependentBuckets() {
        EdgeRateLimitFilter filter = filter(props(1));
        AtomicInteger passed = new AtomicInteger();

        filter.filter(request("/auth/login", CLIENT_A, null), counting(passed)).block();
        MockServerWebExchange other = request("/auth/login", CLIENT_B, null);
        filter.filter(other, counting(passed)).block();

        assertThat(passed.get()).as("一个 IP 打满不该影响其他用户").isEqualTo(2);
        assertThat(other.getResponse().getStatusCode()).isNull();
    }

    @Test
    void channelCallbackIsRateLimited() {
        // 每条入站事件都会触发下游检索 + LLM 花费，渠道重投洪峰必须在边缘挡住
        EdgeRateLimitFilter filter = filter(props(1));
        AtomicInteger passed = new AtomicInteger();

        filter.filter(request("/channel/dingtalk/events", CLIENT_A, null), counting(passed)).block();
        MockServerWebExchange rejected = request("/channel/feishu/events", CLIENT_A, null);
        filter.filter(rejected, counting(passed)).block();

        assertThat(passed.get()).as("两个回调路径共享同一 channel-callback 桶").isEqualTo(1);
        assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void healthProbesAreNotRateLimited() {
        EdgeRateLimitFilter filter = filter(props(1));
        AtomicInteger passed = new AtomicInteger();

        filter.filter(request("/actuator/health", CLIENT_A, null), counting(passed)).block();
        filter.filter(request("/actuator/health", CLIENT_A, null), counting(passed)).block();
        filter.filter(request("/health", CLIENT_A, null), counting(passed)).block();

        assertThat(passed.get()).as("探针被限流会误伤存活检查").isEqualTo(3);
    }

    @Test
    void forwardedForHeaderIsIgnoredByDefault() {
        // 默认信任该头 = 每个请求换一个假 IP 就能绕开限流
        EdgeRateLimitFilter filter = filter(props(1));
        AtomicInteger passed = new AtomicInteger();

        HttpHeaders spoofed = new HttpHeaders();
        spoofed.set("X-Forwarded-For", "198.51.100.7");
        filter.filter(request("/auth/login", CLIENT_A, null), counting(passed)).block();
        MockServerWebExchange rejected = request("/auth/login", CLIENT_A, spoofed);
        filter.filter(rejected, counting(passed)).block();

        assertThat(passed.get()).isEqualTo(1);
        assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void configuredClientIpHeaderTakesFirstHop() {
        // 边缘前面有会覆写该头的可信 LB 时，必须按真实客户端而不是 LB 的地址分桶
        RateLimitProperties props = props(1);
        props.setClientIpHeader("X-Forwarded-For");
        EdgeRateLimitFilter filter = filter(props);
        AtomicInteger passed = new AtomicInteger();

        HttpHeaders first = new HttpHeaders();
        first.set("X-Forwarded-For", "198.51.100.7, 10.0.0.1");
        HttpHeaders second = new HttpHeaders();
        second.set("X-Forwarded-For", "198.51.100.8, 10.0.0.1");
        filter.filter(request("/auth/login", CLIENT_A, first), counting(passed)).block();
        filter.filter(request("/auth/login", CLIENT_A, second), counting(passed)).block();

        assertThat(passed.get()).as("同一 LB 后的两个客户端各自一个桶").isEqualTo(2);

        MockServerWebExchange repeat = request("/auth/login", CLIENT_A, first);
        filter.filter(repeat, counting(passed)).block();
        assertThat(repeat.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void authenticatedBusinessPathStillUsesTenantBucket() {
        RateLimitProperties props = props(1);
        EdgeRateLimitFilter filter = filter(props);
        AtomicInteger passed = new AtomicInteger();
        String jwt = tokens.mint(new TenantContext.Tenant("acme", "alice", Set.of("chat")));
        HttpHeaders authorized = new HttpHeaders();
        authorized.set(securityProps.getInternalHeader(), jwt);

        // 同租户不同 IP：仍共享租户桶，第二次被拒（证明业务路径没被 open-path 分支接管）
        filter.filter(request("/chat", CLIENT_A, authorized), counting(passed)).block();
        MockServerWebExchange rejected = request("/chat", CLIENT_B, authorized);
        filter.filter(rejected, counting(passed)).block();

        assertThat(passed.get()).isEqualTo(1);
        assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void disabledRateLimitPassesEverythingThrough() {
        RateLimitProperties props = props(1);
        props.setEnabled(false);
        EdgeRateLimitFilter filter = filter(props);
        AtomicInteger passed = new AtomicInteger();

        filter.filter(request("/auth/login", CLIENT_A, null), counting(passed)).block();
        filter.filter(request("/auth/login", CLIENT_A, null), counting(passed)).block();

        assertThat(passed.get()).isEqualTo(2);
    }

    @Test
    void unknownRemoteAddressFallsBackToSharedBucketInsteadOfBypassing() {
        EdgeRateLimitFilter filter = filter(props(1));
        // 没有对端地址（例如某些嵌入式测试/代理场景）不能整类放行
        MockServerWebExchange noRemote = MockServerWebExchange.from(MockServerHttpRequest.post("/auth/login"));

        assertThat(filter.clientBucketKey(noRemote)).isEqualTo("ip:" + EdgeRateLimitFilter.UNKNOWN_CLIENT);
    }

    @Test
    void tenantsAreUnaffectedByOpenPathBuckets() {
        // open-path 桶主体是 "ip:..."，与租户 id 不会撞（否则某个 IP 打满会连带拖垮同名租户）
        EdgeRateLimitFilter filter = filter(props(1));
        assertThat(filter.clientBucketKey(request("/auth/login", CLIENT_A, null)))
                .isEqualTo("ip:" + CLIENT_A)
                .doesNotContain("acme");
        assertThat(List.of("acme")).doesNotContain("ip:" + CLIENT_A);
    }
}
