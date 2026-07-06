package com.lrj.platform.agent;

public interface AgentAction {

    String name();

    String description();

    String run(String input);
}
