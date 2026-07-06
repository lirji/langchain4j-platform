package com.lrj.platform.knowledge.graph;

import java.util.Set;

public interface EntityLinker {

    Set<String> link(String query, String tenantId, String category);
}
