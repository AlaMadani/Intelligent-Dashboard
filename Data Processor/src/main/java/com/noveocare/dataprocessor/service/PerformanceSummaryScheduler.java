package com.noveocare.dataprocessor.service;

import com.noveocare.dataprocessor.config.PerformanceProperties;
import com.noveocare.dataprocessor.kafka.AuditTrailConsumer;
import com.noveocare.dataprocessor.redis.RedisSessionBufferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@Component
@Slf4j
@RequiredArgsConstructor
public class PerformanceSummaryScheduler {

    private final AuditTrailConsumer auditTrailConsumer;
    private final DashboardSnapshotService dashboardSnapshotService;
    private final DashboardSnapshotPersistenceService dashboardSnapshotPersistenceService;
    private final PerformanceProperties performanceProperties;
    private final EventIdempotencyService eventIdempotencyService;
    private final RedisSessionBufferService redisSessionBufferService;

    private final AtomicReference<Instant> lastSummaryAt = new AtomicReference<>();

    @Scheduled(fixedDelayString = "${app.performance.summary-interval-ms:30000}")
    public void logPerformanceSummary() {
        Instant now = Instant.now();
        Instant last = lastSummaryAt.get();
        if (last != null && now.toEpochMilli() - last.toEpochMilli() < 1000) {
            return;
        }
        lastSummaryAt.set(now);
        auditTrailConsumer.markSummaryRun();
        try {
            log.info("=== Performance Summary ===");
            log.info("recordsProcessedTotal={}, processingFailuresTotal={}",
                    auditTrailConsumer.diagnosticsSnapshot().get("recordsProcessedTotal"),
                    auditTrailConsumer.diagnosticsSnapshot().get("processingFailuresTotal"));
            log.info("duplicateEventsSkipped={}, duplicateSequenceAppendsSkipped={}",
                    eventIdempotencyService.getDuplicateEventsSkipped(),
                    redisSessionBufferService.getDuplicateSequenceAppendsSkipped());
            log.info("dashboardLastRefreshAt={}, dashboardRefreshSkippedDueToRateLimit={}",
                    dashboardSnapshotService.getDashboardLastRefreshAt(),
                    dashboardSnapshotService.getDashboardRefreshSkippedDueToRateLimit());
            log.info("dashboardSnapshotSqlWriteSuccess={}, dashboardSnapshotSqlWriteFailure={}, dashboardSnapshotSqlLastWriteAt={}, dashboardSnapshotSqlLastFailureAt={}",
                    dashboardSnapshotPersistenceService.getSqlWriteSuccessTotal(),
                    dashboardSnapshotPersistenceService.getSqlWriteFailureTotal(),
                    dashboardSnapshotPersistenceService.getSqlLastWriteAt(),
                    dashboardSnapshotPersistenceService.getSqlLastFailureAt());
            log.info("commitFailedCount={}, rebalanceCount={}, hotPathBlocked={}",
                    auditTrailConsumer.getCommitFailedCount(),
                    auditTrailConsumer.getRebalanceCount(),
                    auditTrailConsumer.isHotPathBlocked());
            log.info("===========================");
        } catch (Exception e) {
            log.debug("Could not log performance summary", e);
        }
    }
}