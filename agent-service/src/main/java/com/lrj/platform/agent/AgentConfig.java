package com.lrj.platform.agent;

import com.lrj.platform.observability.OutboundTraceForwarder;
import com.lrj.platform.agent.analyst.DataAnalystPlanner;
import com.lrj.platform.agent.process.ProcessPlanner;
import com.lrj.platform.agent.chaining.ChainLink;
import com.lrj.platform.agent.chaining.ChainingProperties;
import com.lrj.platform.agent.dag.AgentDagCritic;
import com.lrj.platform.agent.dag.AgentDagProperties;
import com.lrj.platform.agent.dag.AgentDagPlanner;
import com.lrj.platform.agent.dag.AgentDagReplanner;
import com.lrj.platform.agent.reflexion.ReflexionAnswerer;
import com.lrj.platform.agent.reflexion.ReflexionProperties;
import com.lrj.platform.agent.voting.VoteAggregator;
import com.lrj.platform.agent.voting.Voter;
import com.lrj.platform.agent.voting.VotingProperties;
import com.lrj.platform.gateway.GatewayChatModelFactory;
import com.lrj.platform.security.OutboundTenantForwarder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

/**
 * agent-service 的核心装配。用 {@code AiServices.builder} 把各类 LLM 接口（{@link AgentBrain} ReAct 决策核心、
 * 链式 {@code ChainLink}、投票 {@code Voter}/{@code VoteAggregator}、反思 {@code ReflexionAnswerer}、
 * DAG {@code AgentDagPlanner}/{@code AgentDagCritic}/{@code AgentDagReplanner}、数据分析/业务流程 planner）
 * 绑定网关 ChatModel 构建为 Bean，并组装 {@link DeepAgentService}。还提供指向 knowledge/analytics/vision/workflow
 * 各下游服务的 {@link RestTemplate}——均装配 {@code OutboundTenantForwarder} + {@code OutboundTraceForwarder}
 * 拦截器以跨服务传播租户与 traceId。整体由 {@code app.agent.enabled} 门控（默认开）。
 */
@Configuration
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true", matchIfMissing = true)
public class AgentConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.agent")
    AgentProperties agentProperties() {
        return new AgentProperties();
    }

    @Bean
    @ConfigurationProperties(prefix = "app.agent.dag")
    AgentDagProperties agentDagProperties() {
        return new AgentDagProperties();
    }

    @Bean
    AgentBrain agentBrain(ChatModel chatModel) {
        return AiServices.builder(AgentBrain.class).chatModel(chatModel).build();
    }

    @Bean
    @ConfigurationProperties(prefix = "app.agent.chaining")
    ChainingProperties chainingProperties() {
        return new ChainingProperties();
    }

    @Bean
    ChainLink chainLink(ChatModel chatModel) {
        return AiServices.builder(ChainLink.class).chatModel(chatModel).build();
    }

    @Bean
    @ConfigurationProperties(prefix = "app.agent.voting")
    VotingProperties votingProperties() {
        return new VotingProperties();
    }

    @Bean
    Voter voter(ChatModel chatModel) {
        return AiServices.builder(Voter.class).chatModel(chatModel).build();
    }

    @Bean
    VoteAggregator voteAggregator(GatewayChatModelFactory chatModelFactory) {
        // synthesis 收口是确定性收敛任务，用 temp=0 判官变体；不注册为第二个 ChatModel Bean。
        return AiServices.builder(VoteAggregator.class)
                .chatModel(chatModelFactory.buildDeterministic())
                .build();
    }

    @Bean
    @ConfigurationProperties(prefix = "app.agent.reflexion")
    ReflexionProperties reflexionProperties() {
        return new ReflexionProperties();
    }

    @Bean
    ReflexionAnswerer reflexionAnswerer(ChatModel chatModel) {
        return AiServices.builder(ReflexionAnswerer.class).chatModel(chatModel).build();
    }

    @Bean
    AgentDagPlanner agentDagPlanner(ChatModel chatModel) {
        return AiServices.builder(AgentDagPlanner.class).chatModel(chatModel).build();
    }

    @Bean
    DataAnalystPlanner dataAnalystPlanner(ChatModel chatModel) {
        // 数据分析智能体专用 planner：把数据问题拆成「探表→取数→计算→解读」子任务，喂给现有 DAG 引擎。
        return AiServices.builder(DataAnalystPlanner.class).chatModel(chatModel).build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.agent.workflow.enabled", havingValue = "true")
    ProcessPlanner processPlanner(ChatModel chatModel) {
        // 业务流程智能体专用 planner：把流程诉求拆成「发起→查询→汇报」子任务（人在环），喂给现有 DAG 引擎。
        // 类级已门控 app.agent.enabled，这里补 workflow.enabled 单门控即等价于默认关双门控。
        return AiServices.builder(ProcessPlanner.class).chatModel(chatModel).build();
    }

    @Bean
    AgentDagCritic agentDagCritic(ChatModel chatModel) {
        return AiServices.builder(AgentDagCritic.class).chatModel(chatModel).build();
    }

    @Bean
    AgentDagReplanner agentDagReplanner(ChatModel chatModel) {
        return AiServices.builder(AgentDagReplanner.class).chatModel(chatModel).build();
    }

    @Bean
    DeepAgentService deepAgentService(AgentBrain brain,
                                      List<AgentAction> actions,
                                      AgentProperties props,
                                      ObjectProvider<ScratchpadSummarizer> summarizer) {
        return new DeepAgentService(brain, actions, props, summarizer.getIfAvailable());
    }

    @Bean
    RestTemplate knowledgeRestTemplate(RestTemplateBuilder builder,
                                       OutboundTenantForwarder tenantForwarder,
                                       OutboundTraceForwarder traceForwarder,
                                       @Value("${app.agent.knowledge.base-url:http://localhost:8084}") String baseUrl,
                                       @Value("${app.agent.http.connect-timeout:1s}") Duration connectTimeout,
                                       @Value("${app.agent.http.read-timeout:5s}") Duration readTimeout) {
        return serviceRestTemplate(builder, tenantForwarder, traceForwarder, baseUrl, connectTimeout, readTimeout);
    }

    @Bean
    RestTemplate analyticsRestTemplate(RestTemplateBuilder builder,
                                       OutboundTenantForwarder tenantForwarder,
                                       OutboundTraceForwarder traceForwarder,
                                       @Value("${app.agent.analytics.base-url:http://localhost:8083}") String baseUrl,
                                       @Value("${app.agent.http.connect-timeout:1s}") Duration connectTimeout,
                                       @Value("${app.agent.http.read-timeout:5s}") Duration readTimeout) {
        return serviceRestTemplate(builder, tenantForwarder, traceForwarder, baseUrl, connectTimeout, readTimeout);
    }

    /** vision-service 客户端（browser_see 提交截图字节）。读超时放宽——视觉调用比检索/SQL 慢。 */
    @Bean
    @ConditionalOnProperty(name = "app.agent.vision.enabled", havingValue = "true")
    RestTemplate visionRestTemplate(RestTemplateBuilder builder,
                                    OutboundTenantForwarder tenantForwarder,
                                    OutboundTraceForwarder traceForwarder,
                                    @Value("${app.agent.vision.base-url:http://localhost:8090}") String baseUrl,
                                    @Value("${app.agent.http.connect-timeout:1s}") Duration connectTimeout,
                                    @Value("${app.agent.vision.read-timeout:60s}") Duration readTimeout) {
        return serviceRestTemplate(builder, tenantForwarder, traceForwarder, baseUrl, connectTimeout, readTimeout);
    }

    /**
     * workflow-service 客户端（业务流程智能体发起/查询退款审批）。读超时放宽到 60s——
     * {@code /workflow/refund/start} 同步跑 assess/resolve 两次 LLM ServiceTask，比普通 REST 慢。
     * 默认关（workflow.enabled）；类级已门控 agent.enabled。
     */
    @Bean
    @ConditionalOnProperty(name = "app.agent.workflow.enabled", havingValue = "true")
    RestTemplate workflowRestTemplate(RestTemplateBuilder builder,
                                      OutboundTenantForwarder tenantForwarder,
                                      OutboundTraceForwarder traceForwarder,
                                      @Value("${app.agent.workflow.base-url:http://localhost:8082}") String baseUrl,
                                      @Value("${app.agent.http.connect-timeout:1s}") Duration connectTimeout,
                                      @Value("${app.agent.workflow.read-timeout:60s}") Duration readTimeout) {
        return serviceRestTemplate(builder, tenantForwarder, traceForwarder, baseUrl, connectTimeout, readTimeout);
    }

    /**
     * order-service 客户端（order_query 动作按订单号只读查订单）。默认关（order.enabled）；
     * 类级已门控 agent.enabled。透传租户 → order-service 按租户隔离。
     */
    @Bean
    @ConditionalOnProperty(name = "app.agent.order.enabled", havingValue = "true")
    RestTemplate orderRestTemplate(RestTemplateBuilder builder,
                                   OutboundTenantForwarder tenantForwarder,
                                   OutboundTraceForwarder traceForwarder,
                                   @Value("${app.agent.order.base-url:http://localhost:8093}") String baseUrl,
                                   @Value("${app.agent.http.connect-timeout:1s}") Duration connectTimeout,
                                   @Value("${app.agent.http.read-timeout:5s}") Duration readTimeout) {
        return serviceRestTemplate(builder, tenantForwarder, traceForwarder, baseUrl, connectTimeout, readTimeout);
    }

    private static RestTemplate serviceRestTemplate(RestTemplateBuilder builder,
                                                    OutboundTenantForwarder tenantForwarder,
                                                    OutboundTraceForwarder traceForwarder,
                                                    String baseUrl,
                                                    Duration connectTimeout,
                                                    Duration readTimeout) {
        return builder
                .rootUri(baseUrl)
                .additionalInterceptors(tenantForwarder, traceForwarder)
                .setConnectTimeout(connectTimeout)
                .setReadTimeout(readTimeout)
                .build();
    }
}
