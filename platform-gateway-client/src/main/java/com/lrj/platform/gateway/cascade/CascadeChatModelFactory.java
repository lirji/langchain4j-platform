package com.lrj.platform.gateway.cascade;

import com.lrj.platform.gateway.GatewayChatModelFactory;
import com.lrj.platform.gateway.GatewayClientProperties;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 级联变体 {@link CascadeChatModel} 的程序化工厂——cascade 的归属点在 platform-gateway-client。
 *
 * <p>用 {@link GatewayChatModelFactory} 分别构造 cheap / strong 两个内部 {@link ChatModel}（两条都自动
 * 挂上全部 {@code ChatModelListener}，token/成本/审计照常计量），以及可选的 temp=0 自评模型（便宜模型自评，
 * 省钱且被评的就是它的答案）。<strong>本工厂只「产出」一个 {@code CascadeChatModel} 实例，绝不把它或
 * cheap/strong 注册成 Spring 的第二个 ChatModel Bean</strong>——langchain4j {@code AiServicesAutoConfig}
 * 按 {@code getBeanNamesForType(ChatModel.class)} 枚举，数量 &gt;1 直接抛 conflict、整套 {@code @AiService}
 * 装配崩掉。使用方应把产出物作为某个<strong>非 ChatModel 类型</strong> Bean 的私有字段持有。
 */
public class CascadeChatModelFactory {

    private static final Logger log = LoggerFactory.getLogger(CascadeChatModelFactory.class);

    private final GatewayChatModelFactory gateway;
    private final GatewayClientProperties gatewayProps;

    public CascadeChatModelFactory(GatewayChatModelFactory gateway, GatewayClientProperties gatewayProps) {
        this.gateway = gateway;
        this.gatewayProps = gatewayProps;
    }

    /** 若开了自评，构造 temp=0 的便宜模型作 rater；否则返回 null。 */
    public ChatModel buildRater(CascadeProperties props) {
        if (!props.isSelfRating()) {
            return null;
        }
        return gateway.build(pick(props.getCheapModel(), gatewayProps.getModelName()), 0.0);
    }

    /** 按配置构造级联模型：cheap 先答、低置信才升级 strong；指标经 {@code metrics} 回调。 */
    public CascadeChatModel build(CascadeProperties props, ConfidenceGate gate, CascadeMetrics metrics) {
        String cheapName = pick(props.getCheapModel(), gatewayProps.getModelName());
        String strongName = pick(props.getStrongModel(), gatewayProps.getModelName());
        double cheapTemp = props.getCheapTemperature() != null
                ? props.getCheapTemperature() : gatewayProps.getTemperature();
        double strongTemp = props.getStrongTemperature() != null
                ? props.getStrongTemperature() : gatewayProps.getTemperature();
        ChatModel cheap = gateway.build(cheapName, cheapTemp);
        ChatModel strong = gateway.build(strongName, strongTemp);
        log.info("Model cascade enabled: cheap-model={} (temp={}), strong-model={} (temp={}), self-rating={}",
                cheapName, cheapTemp, strongName, strongTemp, props.isSelfRating());
        return new CascadeChatModel(cheap, strong, gate, metrics);
    }

    private static String pick(String override, String fallback) {
        return (override != null && !override.isBlank()) ? override : fallback;
    }
}
