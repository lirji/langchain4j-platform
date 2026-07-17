package com.lrj.platform.agent.actions;

import com.lrj.platform.agent.AgentAction;
import com.lrj.platform.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code code_exec} 动作：执行一段 Java 源码做精确计算/数据转换。先按 {@link CodeExecProperties} 做长度限制与
 * denylist（{@code UNSAFE_TOKENS} 拦截网络/文件/进程/反射等受限 API），再交给注入的 {@link CodeSandbox} 实现
 * （默认独立子进程 {@code SubprocessCodeSandbox}，可选同 JVM 的 {@code JShellCodeSandbox}）执行并处理超时/截断/出错。
 * 是 {@link AgentAction} 的可插拔实现，双门控 {@code app.agent.enabled} + {@code app.agent.code-exec.enabled}（默认关）。
 */
@Component
@ConditionalOnProperty(name = {"app.agent.enabled", "app.agent.code-exec.enabled"}, havingValue = "true")
public class CodeExecAction implements AgentAction {

    private static final Logger log = LoggerFactory.getLogger(CodeExecAction.class);

    private static final List<String> UNSAFE_TOKENS = List.of(
            "java.net", "socket", "url(", "urlconnection", "httpclient", "inetaddress",
            "java.io.file", "fileinputstream", "fileoutputstream", "filewriter", "filereader",
            "randomaccessfile", "java.nio.file", "files.", "paths.",
            "runtime.getruntime", "runtime.exec", "processbuilder", ".exec(",
            "system.exit", "runtime.halt", ".halt(", "shutdown",
            "reflect", "class.forname", "getdeclared", "setaccessible",
            "system.load", "loadlibrary", "unsafe");

    private final CodeExecProperties properties;
    private final CodeSandbox sandbox;

    @org.springframework.beans.factory.annotation.Autowired
    public CodeExecAction(CodeExecProperties properties, CodeSandbox sandbox) {
        this.properties = properties;
        this.sandbox = sandbox;
    }

    /**
     * 便捷构造：默认用同 JVM 的 {@link JShellCodeSandbox}。仅供单测/嵌入式使用；
     * Spring 运行时按 {@code app.agent.code-exec.sandbox} 注入选定的沙箱实现。
     */
    public CodeExecAction(CodeExecProperties properties) {
        this(properties, new JShellCodeSandbox());
    }

    @Override
    public String name() {
        return "code_exec";
    }

    @Override
    public String description() {
        return "执行一段 Java 代码做精确计算/数据转换/确定性逻辑；actionInput 直接填 Java 源码。"
                + "仅用于纯计算，禁止访问网络/文件/进程。";
    }

    @Override
    public String run(String input) {
        if (!properties.isEnabled()) {
            return "code_exec 已禁用（需 app.agent.code-exec.enabled=true），请改走其他动作。";
        }
        if (input == null || input.isBlank()) {
            return "代码为空：actionInput 请填要执行的 Java 源码。";
        }
        String source = input.trim();
        if (properties.getMaxSourceChars() > 0 && source.length() > properties.getMaxSourceChars()) {
            return "代码过长（" + source.length() + " 字符，上限 " + properties.getMaxSourceChars()
                    + "），请精简后重试。";
        }
        if (properties.isBlockUnsafeApis()) {
            String unsafeToken = firstUnsafeToken(source);
            if (unsafeToken != null) {
                log.warn("code_exec blocked tenant={} token='{}'", TenantContext.current().tenantId(), unsafeToken);
                return "代码被安全策略拦截：检测到疑似受限 API（'" + unsafeToken + "'）。"
                        + "code_exec 只允许纯计算/转换，禁止网络/文件/进程/退出等操作。";
            }
        }

        CodeSandbox.Outcome outcome;
        try {
            outcome = sandbox.run(source, properties.getTimeoutMs(), properties.getMaxOutputChars());
        } catch (Throwable ex) {
            return "执行失败：" + ex.getClass().getSimpleName()
                    + (ex.getMessage() == null ? "" : "（" + ex.getMessage() + "）") + "，请检查代码后重试。";
        }

        if (outcome.timedOut()) {
            return "执行超时（超过 " + properties.getTimeoutMs() + "ms 未完成）。"
                    + "避免死循环/长时间阻塞，或减小计算规模后重试。";
        }
        if (outcome.error() != null) {
            String output = outcome.output();
            String prefix = output == null || output.isBlank() ? "" : "已输出：" + output + "\n";
            return prefix + "执行出错：" + outcome.error() + "，请修正代码后重试。";
        }

        String output = outcome.output();
        if (output == null || output.isBlank()) {
            return "执行成功，但没有任何输出。用表达式（如 2+3*4）或 System.out.println(...) 产出结果。";
        }
        if (outcome.truncated()) {
            return output + "\n...（输出超过 " + properties.getMaxOutputChars() + " 字符已截断）";
        }
        return output;
    }

    private static String firstUnsafeToken(String source) {
        String lower = source.toLowerCase();
        for (String token : UNSAFE_TOKENS) {
            if (lower.contains(token)) {
                return token;
            }
        }
        return null;
    }
}
