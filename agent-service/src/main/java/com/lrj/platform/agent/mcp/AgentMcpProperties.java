package com.lrj.platform.agent.mcp;

import java.util.List;

/**
 * agent-service MCP 客户端的配置项（前缀 {@code app.agent.mcp}）：开关、传输类型
 * （{@code stdio}/{@code http}）、日志开关，以及 {@link Stdio} 命令行与 {@link Http} 端点地址。
 * 由 {@link AgentMcpConfig} 绑定并据此构建 MCP 传输。
 */
public class AgentMcpProperties {

    private boolean enabled = false;
    private String transport = "stdio";
    private boolean logEvents = false;
    private Stdio stdio = new Stdio();
    private Http http = new Http();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public boolean isLogEvents() {
        return logEvents;
    }

    public void setLogEvents(boolean logEvents) {
        this.logEvents = logEvents;
    }

    public Stdio getStdio() {
        return stdio;
    }

    public void setStdio(Stdio stdio) {
        this.stdio = stdio;
    }

    public Http getHttp() {
        return http;
    }

    public void setHttp(Http http) {
        this.http = http;
    }

    public static class Stdio {
        private List<String> command = List.of();

        public List<String> getCommand() {
            return command;
        }

        public void setCommand(List<String> command) {
            this.command = command == null ? List.of() : List.copyOf(command);
        }
    }

    public static class Http {
        private String url = "http://localhost:3001/mcp";

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }
}
