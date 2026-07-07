package com.lrj.platform.gateway.cascade;

import java.util.ArrayList;
import java.util.List;

/**
 * Model Cascade / 成本路由配置（可变 JavaBean，由使用方以 {@code @ConfigurationProperties} 绑定，
 * 例如 conversation-service 的 {@code app.chat.cascade.*}）。
 *
 * <p>核心思路：先用<strong>便宜模型</strong>（{@link #cheapModel}）作答，仅当 {@link ConfidenceGate}
 * 判「低置信」时才升级到<strong>强模型</strong>（{@link #strongModel}）重答。绝大多数简单问题便宜模型即可
 * 搞定，强模型只在需要时才烧钱。cheap/strong 都是 LiteLLM {@code model_list} 里的逻辑模型名，经
 * {@code GatewayChatModelFactory} 程序化构造；provider/failover 仍下沉在网关。
 *
 * <p>默认关（{@code enabled=false}）：使用方按此开关条件化装配，关闭时零装配、零开销。
 */
public class CascadeProperties {

    /** 总开关（默认关）。 */
    private boolean enabled = false;

    /** 便宜模型的逻辑模型名（LiteLLM model_list）。留空则退化为网关默认模型名。 */
    private String cheapModel;

    /** 强模型的逻辑模型名。留空则退化为网关默认模型名（此时级联退化为「便宜=强」，仅演示不省钱）。 */
    private String strongModel;

    /** 便宜模型作答温度；留空用网关默认温度。 */
    private Double cheapTemperature;

    /** 强模型作答温度；留空用网关默认温度。 */
    private Double strongTemperature;

    /**
     * 自评置信阈值：仅当 {@link #selfRating}=true 时生效。便宜模型自评分 {@code < threshold} 触发升级。
     * 启发式（拒答 / 不确定 / 过短）不受此值影响，命中即升级。
     */
    private double confidenceThreshold = 0.6;

    /** 便宜模型答案短于此字符数视为「信息不足」→ 升级。 */
    private int minAnswerChars = 8;

    /**
     * 是否在启发式之外再做一次 temp=0 自评（多一次便宜模型调用）。默认关——启发式已能拦住绝大多数明显
     * 低质答案，自评是精度换成本的可选增强。
     */
    private boolean selfRating = false;

    /** 不确定 / 拒答标记：便宜模型答案（小写后）命中任一即判低置信 → 升级。中英混排，覆盖典型措辞。 */
    private List<String> uncertaintyMarkers = new ArrayList<>(List.of(
            "我不确定", "不确定", "无法确定", "不知道", "无法回答", "没有足够", "资料里没有", "抱歉，我",
            "i'm not sure", "i am not sure", "not sure", "i don't know", "i do not know",
            "cannot answer", "can't answer", "unable to", "insufficient information", "as an ai"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCheapModel() {
        return cheapModel;
    }

    public void setCheapModel(String cheapModel) {
        this.cheapModel = cheapModel;
    }

    public String getStrongModel() {
        return strongModel;
    }

    public void setStrongModel(String strongModel) {
        this.strongModel = strongModel;
    }

    public Double getCheapTemperature() {
        return cheapTemperature;
    }

    public void setCheapTemperature(Double cheapTemperature) {
        this.cheapTemperature = cheapTemperature;
    }

    public Double getStrongTemperature() {
        return strongTemperature;
    }

    public void setStrongTemperature(Double strongTemperature) {
        this.strongTemperature = strongTemperature;
    }

    public double getConfidenceThreshold() {
        return confidenceThreshold;
    }

    public void setConfidenceThreshold(double confidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
    }

    public int getMinAnswerChars() {
        return minAnswerChars;
    }

    public void setMinAnswerChars(int minAnswerChars) {
        this.minAnswerChars = minAnswerChars;
    }

    public boolean isSelfRating() {
        return selfRating;
    }

    public void setSelfRating(boolean selfRating) {
        this.selfRating = selfRating;
    }

    public List<String> getUncertaintyMarkers() {
        return uncertaintyMarkers;
    }

    public void setUncertaintyMarkers(List<String> uncertaintyMarkers) {
        this.uncertaintyMarkers = uncertaintyMarkers;
    }
}
