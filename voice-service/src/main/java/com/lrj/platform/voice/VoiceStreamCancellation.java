package com.lrj.platform.voice;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Downstream voice SSE cancellation plus the currently active conversation response body. */
final class VoiceStreamCancellation {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final AtomicReference<Closeable> upstream = new AtomicReference<>();

    boolean isCancelled() {
        return cancelled.get();
    }

    void cancel() {
        if (finished.get()) {
            return;
        }
        cancelled.set(true);
        closeQuietly(upstream.getAndSet(null));
    }

    void finish() {
        finished.set(true);
        upstream.set(null);
    }

    boolean register(Closeable resource) {
        if (resource == null || cancelled.get()) {
            closeQuietly(resource);
            return false;
        }
        if (!upstream.compareAndSet(null, resource)) {
            throw new IllegalStateException("a voice upstream stream is already registered");
        }
        if (cancelled.get()) {
            closeQuietly(upstream.getAndSet(null));
            return false;
        }
        return true;
    }

    void clear(Closeable resource) {
        upstream.compareAndSet(resource, null);
    }

    private static void closeQuietly(Closeable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (IOException ignored) {
            // Best-effort cancellation; callers expose only stable error metadata.
        }
    }
}
