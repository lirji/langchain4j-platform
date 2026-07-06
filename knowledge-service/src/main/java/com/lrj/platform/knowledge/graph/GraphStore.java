package com.lrj.platform.knowledge.graph;

import java.util.List;
import java.util.Set;

public interface GraphStore {

    void add(List<Triple> triples);

    List<Triple> neighbors(Set<String> seedSurfaces, int maxHops, String tenantId, String category);

    Set<String> entities(String tenantId, String category);

    int removeBySourcePrefix(String tenantId, String sourceIdPrefix);

    int size();
}
