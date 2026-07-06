package com.lrj.platform.workflow;

import java.util.List;

/** Workflow 内部工单抽取模型。 */
public record Ticket(String title, Priority priority, String category, String summary, List<String> tags) {

    public enum Priority {
        LOW, MEDIUM, HIGH, CRITICAL
    }
}
