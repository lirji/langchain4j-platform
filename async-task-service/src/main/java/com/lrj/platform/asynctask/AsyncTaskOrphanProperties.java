package com.lrj.platform.asynctask;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

public class AsyncTaskOrphanProperties {

    static final Set<String> SUPPORTED_KINDS = Set.of(
            "agent.task", "agent.run", "agent.dag", "agent.dag-plan",
            "agent.analyst", "agent.process");

    private boolean enabled;
    private Duration pendingTimeout = Duration.ofMinutes(2);
    private Duration leaseGrace = Duration.ofSeconds(30);
    private int batchSize = 100;
    private Set<String> kinds = new LinkedHashSet<>(SUPPORTED_KINDS);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getPendingTimeout() {
        return pendingTimeout;
    }

    public void setPendingTimeout(Duration pendingTimeout) {
        this.pendingTimeout = pendingTimeout;
    }

    public Duration getLeaseGrace() {
        return leaseGrace;
    }

    public void setLeaseGrace(Duration leaseGrace) {
        this.leaseGrace = leaseGrace;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = Math.max(1, batchSize);
    }

    public Set<String> getKinds() {
        return Set.copyOf(kinds);
    }

    public void setKinds(Set<String> kinds) {
        Set<String> configured = kinds == null || kinds.isEmpty() ? SUPPORTED_KINDS : Set.copyOf(kinds);
        if (!SUPPORTED_KINDS.containsAll(configured)) {
            throw new IllegalArgumentException("orphan reaper kinds must be Agent async kinds");
        }
        this.kinds = new LinkedHashSet<>(configured);
    }
}
