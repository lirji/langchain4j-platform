package com.lrj.platform.protocol.asynctask;

public record AsyncTaskLeaseRequest(String workerId,
                                    Long leaseSeconds) {
}
