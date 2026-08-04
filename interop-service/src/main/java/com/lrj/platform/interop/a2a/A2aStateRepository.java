package com.lrj.platform.interop.a2a;

import java.util.Optional;

/** Atomic persistence boundary for language-neutral A2A task/context records. */
public interface A2aStateRepository {

    Optional<A2aTaskContextRecord> get(String tenantId, String taskId);

    boolean compareAndSet(A2aTaskContextRecord record, Long expectedRevision);
}
