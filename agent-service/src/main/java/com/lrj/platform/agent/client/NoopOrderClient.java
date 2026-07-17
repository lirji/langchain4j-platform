package com.lrj.platform.agent.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

// 与 HttpOrderClient 互补：Http 在 agent.enabled && order.enabled 时注册，
// 本兜底覆盖 agent.enabled && !order.enabled 的缺口（order 默认关，所以默认走这里）。
// 这样只要 agent.enabled（含缺省 true），OrderClient 恒有唯一实现，OrderQueryAction 注入不会缺 bean。
// 不用 @ConditionalOnMissingBean：其在组件扫描下对注册顺序敏感、不可靠（见 NoopAnalyticsClient 同款注释）。
@Component
@ConditionalOnExpression("${app.agent.enabled:true} and !${app.agent.order.enabled:false}")
public class NoopOrderClient implements OrderClient {

    private static final String DISABLED = "order lookup disabled";

    @Override
    public Outcome getByNo(String orderNo) {
        return new Outcome(null, DISABLED);
    }
}
