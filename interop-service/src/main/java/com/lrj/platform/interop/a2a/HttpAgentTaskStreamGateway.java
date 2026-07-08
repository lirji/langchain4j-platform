package com.lrj.platform.interop.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.protocol.agent.AgentTaskView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.function.Consumer;

/**
 * {@link AgentTaskStreamGateway} 的 HTTP 实现。用 RestTemplate 的 {@code execute} + 流式
 * {@code ResponseExtractor} 消费 agent {@code /agent/tasks/{taskId}/stream} 的 SSE（复用 interopAgentRestTemplate
 * 上已装的租户/trace 拦截器）。agent SSE 的状态帧 {@code data:} 是 {@code AgentAsyncTask} JSON（与
 * {@link AgentTaskView} 字段兼容）；progress 帧无 {@code status} 字段，反序列化后 {@code status()==null} 即跳过。
 */
@Component
public class HttpAgentTaskStreamGateway implements AgentTaskStreamGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpAgentTaskStreamGateway.class);

    private final RestTemplate interopAgentRestTemplate;
    private final ObjectMapper json;

    public HttpAgentTaskStreamGateway(RestTemplate interopAgentRestTemplate, ObjectMapper json) {
        this.interopAgentRestTemplate = interopAgentRestTemplate;
        this.json = json;
    }

    @Override
    public void streamTask(String taskId,
                           Consumer<AgentTaskView> onUpdate, Runnable onDone, Consumer<Throwable> onError) {
        try {
            interopAgentRestTemplate.execute(
                    "/agent/tasks/{taskId}/stream",
                    HttpMethod.GET,
                    request -> request.getHeaders().setAccept(List.of(MediaType.TEXT_EVENT_STREAM)),
                    response -> {
                        SseEvents.read(response.getBody(), (eventName, data) -> {
                            AgentTaskView view = parseTask(data);
                            if (view != null && view.status() != null) {
                                onUpdate.accept(view);
                            }
                        });
                        return null;
                    },
                    taskId);
        } catch (Exception e) {
            onError.accept(e);
            return;
        }
        onDone.run();
    }

    private AgentTaskView parseTask(String data) {
        if (data == null || data.isBlank()) {
            return null;
        }
        try {
            return json.readValue(data, AgentTaskView.class);
        } catch (Exception e) {
            log.debug("agent task stream frame not a task snapshot: {}", e.toString());
            return null;
        }
    }
}
