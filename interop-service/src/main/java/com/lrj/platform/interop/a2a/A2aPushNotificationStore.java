package com.lrj.platform.interop.a2a;

import com.lrj.platform.interop.a2a.MessageSendParams.PushNotificationConfig;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * A2A push 配置存储：{@code (tenantId, taskId) -> PushNotificationConfig}。**刻意跟 agent-service 的
 * 原生 webhook 分开**：interop 让 agent 任务的 webhook 回到 interop 自己的回调端点，再由 {@link A2aPushForwarder}
 * 按 A2A Task 信封格式回推客户端。登记过配置的 task 才回推，未登记的回调直接忽略。
 *
 * <p>进程内 map，重启即丢、单副本；多副本换 Redis/JDBC（接口不变）——与 {@code A2aPushNotificationStore}
 * 在单体里的演进路径一致。租户维度键隔离，避免跨租户串号。
 */
@Component
public class A2aPushNotificationStore {

    private final ConcurrentMap<String, PushNotificationConfig> configs = new ConcurrentHashMap<>();

    public void put(String tenantId, String taskId, PushNotificationConfig config) {
        if (taskId == null || config == null) {
            return;
        }
        configs.put(key(tenantId, taskId), config);
    }

    public Optional<PushNotificationConfig> get(String tenantId, String taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(configs.get(key(tenantId, taskId)));
    }

    public void remove(String tenantId, String taskId) {
        if (taskId != null) {
            configs.remove(key(tenantId, taskId));
        }
    }

    private static String key(String tenantId, String taskId) {
        return (tenantId == null ? "" : tenantId) + "::" + taskId;
    }
}
