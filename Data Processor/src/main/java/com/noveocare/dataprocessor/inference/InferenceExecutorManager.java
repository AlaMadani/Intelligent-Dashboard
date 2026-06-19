package com.noveocare.dataprocessor.inference;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
@Slf4j
public class InferenceExecutorManager {

    private final Map<String, ModelExecutor> executors = new ConcurrentHashMap<>();
    private final Map<String, CircuitBreakerState> circuitBreakers = new ConcurrentHashMap<>();
    private final Set<String> permanentlyDisabledModels = ConcurrentHashMap.newKeySet();

    private final AtomicLong sequenceTaskIdCounter = new AtomicLong();
    private final ConcurrentHashMap<Long, SequenceTaskMeta> sequenceTasks = new ConcurrentHashMap<>();

    public InferenceExecutorManager() {
        createExecutor("tabular", 2, 50);
        createExecutor("sequence", 1, 10);
        createExecutor("churn", 1, 20);
    }

    @PostConstruct
    public void logConfig() {
        ModelExecutor seq = executors.get("sequence");
        if (seq != null) {
            int threads = seq.executor.getCorePoolSize();
            int queueSize = seq.executor.getQueue().remainingCapacity() + seq.executor.getQueue().size();
            log.info("SEQUENCE_EXECUTOR_CONFIG threads={} queue={}", threads, queueSize);
        }
    }

    public int getSequenceThreads() {
        ModelExecutor seq = executors.get("sequence");
        return seq == null ? 1 : seq.executor.getCorePoolSize();
    }

    public int getSequenceQueueSize() {
        ModelExecutor seq = executors.get("sequence");
        return seq == null ? 10 : seq.executor.getQueue().remainingCapacity() + seq.executor.getQueue().size();
    }

    private void createExecutor(String name, int threads, int queueSize) {
        ModelExecutor me = new ModelExecutor(name, null);
        me.executor = new ThreadPoolExecutor(
                threads, threads,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueSize),
                r -> {
                    Thread t = new Thread(r, "inference-" + name);
                    t.setDaemon(true);
                    return t;
                },
                (r, executor) -> {
                    me.rejectedCount.incrementAndGet();
                    log.warn("Inference executor {} queue full, rejecting task", name);
                }
        );
        executors.put(name, me);
    }

    public <T> Future<T> submitAsync(String modelGroup, String modelName, Callable<T> task) {
        ModelExecutor executor = executors.get(modelGroup);
        if (executor == null) {
            log.warn("No executor for model group {}", modelGroup);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        try {
            executor.activeTasks.incrementAndGet();
            Future<T> future = executor.executor.submit(() -> {
                try {
                    return task.call();
                } finally {
                    executor.activeTasks.decrementAndGet();
                }
            });
            return future;
        } catch (RejectedExecutionException e) {
            executor.rejectedCount.incrementAndGet();
            executor.activeTasks.decrementAndGet();
            log.warn("Model {} task rejected in group {} (queue full)", modelName, modelGroup);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
    }

    public <T> T submitWithTimeout(String modelGroup, String modelName, Callable<T> task, long timeoutMs, T fallback) {
        if (isCircuitOpen(modelName)) {
            log.debug("Circuit open for model {}, skipping", modelName);
            return fallback;
        }

        ModelExecutor executor = executors.get(modelGroup);
        if (executor == null) {
            log.warn("No executor for model group {}", modelGroup);
            return fallback;
        }

        boolean isSeq = "sequence".equals(modelGroup);
        long taskId = isSeq ? sequenceTaskIdCounter.incrementAndGet() : 0L;
        long submitNs = System.nanoTime();

        if (isSeq) {
            int qSize = executor.executor.getQueue().size();
            int active = executor.activeTasks.get();
            log.info("SEQUENCE_EXECUTOR_SUBMIT taskId={} model={} group={} queueSize={} activeTasks={}",
                    taskId, modelName, modelGroup, qSize, active);
        }

        int preQueueSize = executor.executor.getQueue().size();
        int preActive = executor.activeTasks.get();

        executor.activeTasks.incrementAndGet();
        try {
            SequenceTaskMeta meta = isSeq ? new SequenceTaskMeta(taskId, modelName, submitNs) : null;
            if (isSeq) {
                sequenceTasks.put(taskId, meta);
            }

            long enqueueNs = System.nanoTime();

            Callable<T> wrapped = isSeq ? wrapSequenceTask(taskId, modelName, meta, task) : task;
            Future<T> future = executor.executor.submit(wrapped);
            long queueWaitMs = (System.nanoTime() - enqueueNs) / 1_000_000L;

            try {
                T result = timeoutMs > 0 ? future.get(timeoutMs, TimeUnit.MILLISECONDS) : future.get();
                long execMs = (System.nanoTime() - enqueueNs) / 1_000_000L;
                if (isSeq && meta != null) {
                    meta.finishedNs = System.nanoTime();
                    if (meta.timeoutFired) {
                        log.warn("SEQUENCE_EXECUTOR_LATE_FINISH taskId={} finishedAfterTimeout=true execMs={}",
                                taskId, execMs);
                    }
                }
                if (execMs > 100 || queueWaitMs > 50) {
                    log.info("EXECUTOR_TASK pool={} model={} queueWaitMs={} execMs={} queueSize={} active={}",
                            modelGroup, modelName, queueWaitMs, execMs, preQueueSize, preActive);
                    executor.recordTaskTiming(queueWaitMs, execMs);
                }
                return result;
            } catch (TimeoutException e) {
                long waitedMs = (System.nanoTime() - submitNs) / 1_000_000L;
                if (isSeq && meta != null) {
                    meta.timeoutFired = true;
                    log.warn("SEQUENCE_EXECUTOR_TIMEOUT taskId={} waitedMs={} timeoutMs={} model={}",
                            taskId, waitedMs, timeoutMs, modelName);
                }
                future.cancel(true);
                executor.timeoutCount.incrementAndGet();
                log.warn("Model {} timed out after {}ms in group {}", modelName, timeoutMs, modelGroup);
                if (isSeq) {
                    log.warn("SEQUENCE_EXECUTOR_CANCEL taskId={} cancelRequested=true timeoutMs={}",
                            taskId, timeoutMs);
                }
                recordTimeout(modelName);
                return fallback;
            } catch (ExecutionException e) {
                if (isSeq && meta != null) {
                    log.warn("SEQUENCE_EXECUTOR_FINISH taskId={} execMs={} status=FAILED error=\"{}\"",
                            taskId, (System.nanoTime() - meta.startedNs) / 1_000_000L, e.getCause().getMessage());
                }
                log.warn("Model {} execution failed in group {}", modelName, modelGroup, e.getCause());
                return fallback;
            } catch (InterruptedException e) {
                if (isSeq && meta != null) {
                    log.warn("SEQUENCE_EXECUTOR_FINISH taskId={} status=INTERRUPTED", taskId);
                }
                Thread.currentThread().interrupt();
                return fallback;
            }
        } catch (RejectedExecutionException e) {
            if (isSeq) {
                log.warn("SEQUENCE_EXECUTOR_TIMEOUT taskId={} waitedMs=0 timeoutMs={} model={} reason=queue_full",
                        taskId, timeoutMs, modelName);
                log.warn("SEQUENCE_EXECUTOR_CANCEL taskId={} cancelRequested=true reason=rejected", taskId);
            }
            executor.rejectedCount.incrementAndGet();
            log.warn("Model {} task rejected in group {} (queue full)", modelName, modelGroup);
            recordTimeout(modelName);
            return fallback;
        } finally {
            executor.activeTasks.decrementAndGet();
            if (isSeq) {
                sequenceTasks.remove(taskId);
            }
        }
    }

    private <T> Callable<T> wrapSequenceTask(long taskId, String modelName, SequenceTaskMeta meta, Callable<T> task) {
        return () -> {
            long startedNs = System.nanoTime();
            meta.startedNs = startedNs;
            long queueWaitMs = (startedNs - meta.submitNs) / 1_000_000L;
            log.info("SEQUENCE_EXECUTOR_START taskId={} model={} queueWaitMs={}",
                    taskId, modelName, queueWaitMs);
            try {
                T result = task.call();
                long finishedNs = System.nanoTime();
                meta.finishedNs = finishedNs;
                long execMs = (finishedNs - startedNs) / 1_000_000L;
                if (meta.timeoutFired) {
                    log.warn("SEQUENCE_EXECUTOR_LATE_FINISH taskId={} finishedAfterTimeout=true execMs={} model={}",
                            taskId, execMs, modelName);
                }
                log.info("SEQUENCE_EXECUTOR_FINISH taskId={} execMs={} status=OK model={}",
                        taskId, execMs, modelName);
                return result;
            } catch (Exception e) {
                long finishedNs = System.nanoTime();
                meta.finishedNs = finishedNs;
                long execMs = (finishedNs - startedNs) / 1_000_000L;
                log.warn("SEQUENCE_EXECUTOR_FINISH taskId={} execMs={} status=FAILED model={} error=\"{}\"",
                        taskId, execMs, modelName, e.getMessage());
                throw e;
            }
        };
    }

    public void recordTimeout(String modelName) {
        CircuitBreakerState cb = circuitBreakers.computeIfAbsent(modelName, k -> new CircuitBreakerState());
        cb.timeoutCount.incrementAndGet();
        cb.lastTimedOutAt = Instant.now();
    }

    public boolean isCircuitOpen(String modelName) {
        if (permanentlyDisabledModels.contains(modelName)) {
            return true;
        }
        CircuitBreakerState cb = circuitBreakers.get(modelName);
        if (cb == null) return false;
        if (cb.cooldownUntil == null) return false;
        if (Instant.now().isAfter(cb.cooldownUntil)) {
            cb.timeoutCount.set(0);
            cb.cooldownUntil = null;
            log.info("Circuit closed for model {} after cooldown", modelName);
            return false;
        }
        return true;
    }

    public void disablePermanently(String modelName) {
        permanentlyDisabledModels.add(modelName);
        CircuitBreakerState cb = circuitBreakers.computeIfAbsent(modelName, k -> new CircuitBreakerState());
        cb.cooldownUntil = Instant.MAX;
        log.warn("Model {} permanently disabled for this run due to circuit breaker", modelName);
    }

    public boolean isPermanentlyDisabled(String modelName) {
        return permanentlyDisabledModels.contains(modelName);
    }

    public void checkAndOpenCircuit(String modelName, int threshold, long cooldownMs) {
        if (threshold <= 0) return;
        CircuitBreakerState cb = circuitBreakers.computeIfAbsent(modelName, k -> new CircuitBreakerState());
        if (cb.timeoutCount.get() >= threshold && cb.cooldownUntil == null) {
            cb.cooldownUntil = Instant.now().plusMillis(cooldownMs);
            log.warn("Circuit OPEN for model {} after {} timeouts, cooldown {}ms", modelName, threshold, cooldownMs);
        }
    }

    public long getActiveTasks(String modelGroup) {
        ModelExecutor me = executors.get(modelGroup);
        return me == null ? 0 : me.activeTasks.get();
    }

    public long getQueueSize(String modelGroup) {
        ModelExecutor me = executors.get(modelGroup);
        return me == null ? 0 : me.executor.getQueue().size();
    }

    public long getRejectedCount(String modelGroup) {
        ModelExecutor me = executors.get(modelGroup);
        return me == null ? 0 : me.rejectedCount.get();
    }

    public long getTimeoutCount(String modelName) {
        CircuitBreakerState cb = circuitBreakers.get(modelName);
        return cb == null ? 0 : cb.timeoutCount.get();
    }

    public boolean isCircuitOpenRaw(String modelName) {
        return isCircuitOpen(modelName);
    }

    public Instant getLastTimedOutAt(String modelName) {
        CircuitBreakerState cb = circuitBreakers.get(modelName);
        return cb == null ? null : cb.lastTimedOutAt;
    }

    public Map<String, Object> diagnostics() {
        Map<String, Object> diag = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, ModelExecutor> entry : executors.entrySet()) {
            Map<String, Object> ed = new java.util.LinkedHashMap<>();
            ed.put("activeThreads", entry.getValue().activeTasks.get());
            ed.put("queueSize", entry.getValue().executor.getQueue().size());
            ed.put("rejectedTasks", entry.getValue().rejectedCount.get());
            diag.put(entry.getKey(), ed);
        }
        return diag;
    }

    public Map<String, Object> circuitBreakerDiagnostics() {
        Map<String, Object> diag = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, CircuitBreakerState> entry : circuitBreakers.entrySet()) {
            Map<String, Object> cd = new java.util.LinkedHashMap<>();
            cd.put("timeoutCount", entry.getValue().timeoutCount.get());
            cd.put("circuitOpen", entry.getValue().isOpen());
            cd.put("cooldownUntil", entry.getValue().cooldownUntil != null ? entry.getValue().cooldownUntil.toString() : null);
            cd.put("lastTimedOutAt", entry.getValue().lastTimedOutAt != null ? entry.getValue().lastTimedOutAt.toString() : null);
            cd.put("permanentlyDisabled", permanentlyDisabledModels.contains(entry.getKey()));
            diag.put(entry.getKey(), cd);
        }
        return diag;
    }

    @PreDestroy
    public void shutdown() {
        for (ModelExecutor me : executors.values()) {
            me.executor.shutdownNow();
        }
    }

    private static class SequenceTaskMeta {
        final long taskId;
        final String modelName;
        final long submitNs;
        volatile long startedNs;
        volatile long finishedNs;
        volatile boolean timeoutFired;

        SequenceTaskMeta(long taskId, String modelName, long submitNs) {
            this.taskId = taskId;
            this.modelName = modelName;
            this.submitNs = submitNs;
        }
    }

    private static class ModelExecutor {
        final String name;
        volatile ThreadPoolExecutor executor;
        final AtomicInteger activeTasks = new AtomicInteger();
        final AtomicLong rejectedCount = new AtomicLong();
        final AtomicLong timeoutCount = new AtomicLong();
        final Deque<Long> recentQueueWaitMs = new ConcurrentLinkedDeque<>();
        final Deque<Long> recentExecMs = new ConcurrentLinkedDeque<>();
        private static final int MAX_TIMING_SAMPLES = 100;

        ModelExecutor(String name, ThreadPoolExecutor executor) {
            this.name = name;
            this.executor = executor;
        }

        void recordTaskTiming(long queueWaitMs, long execMs) {
            recentQueueWaitMs.addLast(queueWaitMs);
            recentExecMs.addLast(execMs);
            while (recentQueueWaitMs.size() > MAX_TIMING_SAMPLES) {
                recentQueueWaitMs.pollFirst();
                recentExecMs.pollFirst();
            }
        }
    }

    private static class CircuitBreakerState {
        final AtomicInteger timeoutCount = new AtomicInteger();
        volatile Instant lastTimedOutAt;
        volatile Instant cooldownUntil;

        boolean isOpen() {
            return cooldownUntil != null && Instant.now().isBefore(cooldownUntil);
        }
    }
}