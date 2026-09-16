package com.lrj.platform.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lrj.platform.protocol.agent.AgentDagAttempt;
import com.lrj.platform.protocol.agent.AgentDagCritique;
import com.lrj.platform.protocol.agent.AgentDagRunReply;
import com.lrj.platform.protocol.agent.AgentDagRunRequest;
import com.lrj.platform.protocol.agent.AgentDagTask;
import com.lrj.platform.protocol.agent.AgentDagTaskResult;
import com.lrj.platform.protocol.agent.AgentRunReply;
import com.lrj.platform.protocol.agent.AgentRunRequest;
import com.lrj.platform.protocol.agent.AgentStep;
import com.lrj.platform.protocol.agent.AgentTaskView;
import com.lrj.platform.protocol.agent.ChainRunReply;
import com.lrj.platform.protocol.agent.ChainRunRequest;
import com.lrj.platform.protocol.agent.ChainStepResult;
import com.lrj.platform.protocol.agent.ReflexionAttempt;
import com.lrj.platform.protocol.agent.ReflexionReply;
import com.lrj.platform.protocol.agent.ReflexionRequest;
import com.lrj.platform.protocol.agent.VoteReply;
import com.lrj.platform.protocol.agent.VoteRequest;
import com.lrj.platform.protocol.analytics.AnalyticsSqlPlanReply;
import com.lrj.platform.protocol.analytics.AnalyticsSqlPlanRequest;
import com.lrj.platform.protocol.conversation.ConversationGenerationRequest;
import com.lrj.platform.protocol.conversation.ConversationGenerationResponse;
import com.lrj.platform.protocol.conversation.ConversationHistoryMessage;
import com.lrj.platform.protocol.conversation.ConversationStreamEvent;
import com.lrj.platform.protocol.conversation.TicketDraftRequest;
import com.lrj.platform.protocol.conversation.TicketDraftResponse;
import com.lrj.platform.protocol.conversation.WorkflowReplyRequest;
import com.lrj.platform.protocol.conversation.WorkflowReplyResponse;
import com.lrj.platform.protocol.interop.AgentCapabilityRegistry;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验跨语言 DTO 与 agentscope-platform 导出的语言中立契约一致。
 *
 * <p>两个方向的失败语义不同，所以分别断言：
 * <ul>
 *   <li><b>生产方向</b>：Java 序列化结果必须通过 schema 校验。契约多数带
 *       {@code additionalProperties:false}，所以字段改名、多字段、类型/枚举/长度越界都会被抓到。</li>
 *   <li><b>消费方向</b>：Java record 的每个字段名都必须出现在 schema 的 properties 里。上游改名或
 *       删字段会让 Java 静默读到 null，这是最危险的漂移；反之 Java 有意只读子集（如
 *       {@link AgentTaskView}）是允许的，因此不要求覆盖 schema 的全部字段。</li>
 * </ul>
 *
 * <p>契约语义属于上游，本测试只证明消费/生产side 与之匹配，不在本仓重新定义契约。
 */
class AgentScopeContractConformanceTest {

    private static final Path CONTRACTS = Path.of("src/main/resources/contracts/agentscope");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonSchemaFactory FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    @Test
    void agent_run_and_dag_requests_match_the_legacy_contract() {
        assertProduces("legacy/agent-run-request.schema.json", null,
                new AgentRunRequest("查询订单 A1001 的状态", null));
        assertProduces("legacy/agent-dag-run-request.schema.json", null,
                new AgentDagRunRequest("对账并汇总",
                        List.of(new AgentDagTask("t1", "拉取订单", List.of()),
                                new AgentDagTask("t2", "汇总差异", List.of("t1"))),
                        null));
        assertProduces("legacy/agent-dag-task.schema.json", null,
                new AgentDagTask("t1", "拉取订单", List.of("t0")));
    }

    @Test
    void sibling_orchestration_requests_match_the_legacy_contract() {
        assertProduces("legacy/chain-run-request.schema.json", null,
                new ChainRunRequest("把这段对话整理成工单"));
        assertProduces("legacy/vote-request.schema.json", null, new VoteRequest("要不要退款", 3));
        assertProduces("legacy/reflexion-request.schema.json", null,
                new ReflexionRequest("这张发票是否合规"));
    }

    @Test
    void agent_reply_views_only_read_fields_the_contract_still_publishes() {
        assertConsumes("legacy/agent-run-reply.schema.json", null, AgentRunReply.class);
        assertConsumes("legacy/agent-run-reply.schema.json", "AgentStep", AgentStep.class);
        assertConsumes("legacy/agent-step.schema.json", null, AgentStep.class);
        assertConsumes("legacy/agent-async-task.schema.json", null, AgentTaskView.class);
    }

    @Test
    void dag_reply_views_only_read_fields_the_contract_still_publishes() {
        String dagReply = "legacy/agent-dag-run-reply.schema.json";
        assertConsumes(dagReply, null, AgentDagRunReply.class);
        assertConsumes(dagReply, "AgentDagTaskResult", AgentDagTaskResult.class);
        assertConsumes(dagReply, "AgentDagAttempt", AgentDagAttempt.class);
        assertConsumes(dagReply, "AgentDagCritique", AgentDagCritique.class);
    }

    @Test
    void sibling_orchestration_reply_views_match_the_legacy_contract() {
        assertConsumes("legacy/chain-run-reply.schema.json", null, ChainRunReply.class);
        assertConsumes("legacy/chain-run-reply.schema.json", "ChainStepResult", ChainStepResult.class);
        assertConsumes("legacy/vote-reply.schema.json", null, VoteReply.class);
        assertConsumes("legacy/reflexion-reply.schema.json", null, ReflexionReply.class);
        assertConsumes("legacy/reflexion-reply.schema.json", "ReflexionAttempt", ReflexionAttempt.class);
    }

    @Test
    void analytics_sql_plan_boundary_keeps_planner_input_and_executor_output_aligned() {
        String plan = "boundaries/analytics-sql-plan.schema.json";
        // Python 提交候选 SQL，Java 是消费方；执行结果由 Java 生产。
        assertConsumes(plan, "request", AnalyticsSqlPlanRequest.class);
        assertProduces(plan, "response",
                new AnalyticsSqlPlanReply("上月退款金额", "select 1", 1,
                        List.of(Map.of("amount", 12)), true, null));
    }

    @Test
    void conversation_generation_boundary_stays_stateless_and_identity_free() {
        String generation = "boundaries/conversation-generation.schema.json";
        assertProduces(generation, "request",
                new ConversationGenerationRequest("1", "帮我查订单", "订单 A1001 已发货",
                        new ConversationGenerationRequest.Style("zh-CN", "neutral", "inline", ""),
                        List.of(new ConversationHistoryMessage("user", "上次那个订单呢"))));
        assertProduces(generation, "historyMessage", new ConversationHistoryMessage("assistant", "已发货"));
        assertConsumes(generation, "response", ConversationGenerationResponse.class);
    }

    @Test
    void conversation_candidate_stream_event_matches_the_boundary_envelope() {
        assertConsumes("boundaries/conversation-stream-event.schema.json", null,
                ConversationStreamEvent.class);
    }

    @Test
    void workflow_ai_draft_boundary_keeps_drafts_advisory_only() {
        String draft = "boundaries/workflow-ai-draft.schema.json";
        assertProduces(draft, "ticketRequest", new TicketDraftRequest("打印机坏了"));
        assertProduces(draft, "replyRequest", new WorkflowReplyRequest("chat-1", "进度如何"));
        assertConsumes(draft, "ticketResponse", TicketDraftResponse.class);
        assertConsumes(draft, "replyResponse", WorkflowReplyResponse.class);
    }

    @Test
    void published_capability_registry_deserializes_without_losing_fields() throws IOException {
        // 这个文件是能力目录实例而非 schema：interop 直接反序列化它，所以要证明字段没被丢。
        JsonNode published = document("capabilities/agent-capabilities.v1.json");
        AgentCapabilityRegistry registry =
                MAPPER.treeToValue(published, AgentCapabilityRegistry.class);

        assertThat(registry.schemaVersion()).isEqualTo(published.get("schemaVersion").asText());
        assertThat(registry.revision()).matches("[0-9a-f]{64}");
        assertThat(registry.capabilities()).hasSize(published.get("capabilities").size());
        assertThat(registry.capabilities()).allSatisfy(capability -> {
            assertThat(capability.name()).isNotBlank();
            assertThat(capability.description()).isNotBlank();
            assertThat(capability.inputSchema()).isNotEmpty();
        });
        JsonNode roundTripped = MAPPER.valueToTree(registry);
        assertThat(roundTripped).isEqualTo(published);
    }

    /** 生产方向：Java 序列化出来的 JSON 必须通过 schema 校验。 */
    private static void assertProduces(String contract, String definition, Object payload) {
        Set<ValidationMessage> violations =
                schema(contract, definition).validate(MAPPER.valueToTree(payload));

        assertThat(violations)
                .as("%s serialized by %s violates %s%s", payload.getClass().getSimpleName(),
                        "Jackson", contract, definition == null ? "" : "#" + definition)
                .isEmpty();
    }

    /** 消费方向：Java 读取的每个字段都必须仍然存在于契约中。 */
    private static void assertConsumes(String contract, String definition, Class<?> view) {
        JsonNode properties = node(contract, definition).get("properties");
        assertThat(properties).as("%s has no properties", contract).isNotNull();

        List<String> readFields = Arrays.stream(view.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        List<String> published = new ArrayList<>();
        properties.fieldNames().forEachRemaining(published::add);

        assertThat(published)
                .as("%s reads fields that %s%s no longer publishes", view.getSimpleName(),
                        contract, definition == null ? "" : "#" + definition)
                .containsAll(readFields);
    }

    private static JsonSchema schema(String contract, String definition) {
        if (definition == null) {
            return FACTORY.getSchema(document(contract));
        }
        // $ref 指回 $defs，才能让嵌套定义里的引用照常解析；直接把子节点单独喂给校验器会解析失败。
        JsonNode root = document(contract);
        ObjectNode wrapper = MAPPER.createObjectNode();
        wrapper.put("$ref", "#/$defs/" + definition);
        wrapper.set("$defs", root.get("$defs"));
        return FACTORY.getSchema(wrapper);
    }

    private static JsonNode node(String contract, String definition) {
        JsonNode root = document(contract);
        return definition == null ? root : root.get("$defs").get(definition);
    }

    private static JsonNode document(String contract) {
        try {
            return MAPPER.readTree(Files.readString(CONTRACTS.resolve(contract)));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read vendored contract " + contract, e);
        }
    }
}
