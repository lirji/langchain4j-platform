package com.lrj.platform.knowledge.search;

import com.lrj.platform.security.TenantContext;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 有界并发召回：独立于公共线程池，限制下游并发与排队内存；由 KnowledgeQueryService 关闭。
 * 完成队列用于及时发现失败，结果槽位用于保持融合所需的源顺序。
 */
public final class ParallelRetrievalExecutor implements AutoCloseable {

    private final ThreadPoolExecutor executor;

    /** 虚拟工作线程降低阻塞 I/O 的线程成本；固定工作线程数仍限制实际并发。 */
    public ParallelRetrievalExecutor(int parallelism, int queueCapacity) {
        if (parallelism < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("retrieval parallelism and queue capacity must be positive");
        }
        executor = new ThreadPoolExecutor(parallelism, parallelism, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofVirtual().name("rag-retrieval-", 0).inheritInheritableThreadLocals(false).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
    }

    /**
     * 调用方传入已启用且按融合优先级排列的源；身份与 MDC 在提交前快照，工作线程退出时清理。
     * 源内部的降级逻辑保持有效；未处理异常原样抛出，并尽力中断本请求的其他任务。
     * 队列饱和直接拒绝，避免把下游过载转成无界排队或占用请求线程执行召回。
     */
    public List<List<RetrievalHit>> retrieve(List<RetrievalSource> sources, RetrievalRequest request) {
        var tenant = TenantContext.captureRaw();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        var completed = new LinkedBlockingQueue<FutureTask<IndexedHits>>();
        List<Future<IndexedHits>> futures = new ArrayList<>();
        List<List<RetrievalHit>> groups = new ArrayList<>(Collections.nCopies(sources.size(), null));
        try {
            for (int i = 0; i < sources.size(); i++) {
                int index = i;
                RetrievalSource source = sources.get(i);
                var task = new FutureTask<IndexedHits>(() -> {
                    try {
                        if (tenant == null) {
                            TenantContext.clear();
                        } else {
                            TenantContext.set(tenant);
                        }
                        if (mdc == null) {
                            MDC.clear();
                        } else {
                            MDC.setContextMap(mdc);
                        }
                        return new IndexedHits(index, source.retrieve(request));
                    } finally {
                        TenantContext.clear();
                        MDC.clear();
                    }
                }) {
                    @Override
                    protected void done() {
                        completed.add(this);
                    }
                };
                futures.add(task);
                executor.execute(task);
            }
            for (int i = 0; i < sources.size(); i++) {
                IndexedHits result = completed.take().get();
                groups.set(result.index(), result.hits());
            }
            return groups;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("retrieval interrupted", ex);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (ex.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("retrieval failed", ex.getCause());
        } finally {
            futures.forEach(future -> future.cancel(true));
            // 清除取消但尚未运行的任务，及时归还有界队列容量。
            executor.purge();
        }
    }

    /** 停止接收任务并中断在途召回；实际网络调用是否立即结束取决于客户端中断/超时支持。 */
    @Override
    public void close() {
        // 排队任务也必须完成 Future 状态，否则等待完成队列的请求无法退出。
        executor.shutdownNow().forEach(task -> ((Future<?>) task).cancel(true));
    }

    private record IndexedHits(int index, List<RetrievalHit> hits) {}
}
