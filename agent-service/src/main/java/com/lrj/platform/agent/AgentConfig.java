package com.lrj.platform.agent;

import com.lrj.platform.observability.OutboundTraceForwarder;
import com.lrj.platform.agent.analyst.DataAnalystPlanner;
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
