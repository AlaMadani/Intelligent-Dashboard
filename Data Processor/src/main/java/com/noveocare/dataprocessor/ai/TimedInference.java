package com.noveocare.dataprocessor.ai;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class TimedInference {

    public static <T> T runWithTimeout(ExecutorService executor, Callable<T> task, long timeoutMs, T fallback) {
        if (timeoutMs <= 0) {
            try {
                return task.call();
            } catch (Exception e) {
                return fallback;
            }
        }
        Future<T> future = executor.submit(task);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return fallback;
        } catch (ExecutionException e) {
            return fallback;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        }
    }
}