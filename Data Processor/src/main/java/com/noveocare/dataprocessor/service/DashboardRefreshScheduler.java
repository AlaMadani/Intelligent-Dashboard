package com.noveocare.dataprocessor.service;

import com.noveocare.dataprocessor.config.PerformanceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Periodically refreshes dirty dashboard views (alerts, risky sessions, security overview).
 * Rate-limited per view and enforces a total timeout across all views per tick.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DashboardRefreshScheduler {

    /* Injected dependencies */
    private final DashboardSnapshotService dashboardSnapshotService;
    private final PerformanceProperties performanceProperties;

    /* Runtime state counters */
    private final AtomicLong runCount = new AtomicLong();
    private final AtomicReference<Instant> lastRunAt = new AtomicReference<>();

    /* --- Scheduled task --- */

    @Scheduled(fixedRateString = "${app.performance.dashboard-refresh.dashboard-refresh-interval-ms:15000}")
    public void refreshDirtyDashboards() {
        runCount.incrementAndGet();
        lastRunAt.set(Instant.now());
        boolean alertsDirty = dashboardSnapshotService.isAlertsDirty();
        boolean riskyDirty = dashboardSnapshotService.isRiskySessionsDirty();
        boolean overviewDirty = dashboardSnapshotService.isSecurityOverviewDirty();
        log.debug("Dashboard refresh scheduler tick: run={} alertsDirty={} riskyDirty={} overviewDirty={} lastRefreshAt={}",
                runCount.get(), alertsDirty, riskyDirty, overviewDirty,
                dashboardSnapshotService.getDashboardLastRefreshAt());
        if (!alertsDirty && !riskyDirty && !overviewDirty) {
            return;
        }
        if (!dashboardSnapshotService.tryStartRefresh()) {
            log.debug("Dashboard refresh already running, skipping tick");
            return;
        }
        long tickStartMs = System.currentTimeMillis();
        long viewTimeoutMs = performanceProperties.getDashboardRefresh().getDashboardRefreshViewTimeoutMs();
        try {
            log.info("Dashboard refresh scheduler: refreshing dirty views (alerts={} risky={} overview={})",
                    alertsDirty, riskyDirty, overviewDirty);
            if (overviewDirty) {
                log.debug("Refreshing security overview dashboard");
                dashboardSnapshotService.refreshSecurityOverview();
            }
            if (riskyDirty) {
                if (elapsedExceeds(tickStartMs, viewTimeoutMs)) {
                    log.error("Dashboard refresh timeout exceeded ({}ms), skipping risky-sessions", System.currentTimeMillis() - tickStartMs);
                    return;
                }
                log.debug("Refreshing risky sessions dashboard");
                dashboardSnapshotService.refreshRiskySessions();
            }
            if (alertsDirty) {
                if (elapsedExceeds(tickStartMs, viewTimeoutMs)) {
                    log.error("Dashboard refresh timeout exceeded ({}ms), skipping alerts", System.currentTimeMillis() - tickStartMs);
                    return;
                }
                log.debug("Refreshing alerts feed dashboard");
                dashboardSnapshotService.refreshAlertsFeed();
            }
            log.info("Dashboard refresh scheduler: completed successfully");
        } catch (Exception e) {
            log.warn("Dashboard refresh scheduler failed", e);
        } finally {
            dashboardSnapshotService.finishRefresh();
        }
    }

    /* Checks whether the elapsed time since startMs has reached the threshold */
    private boolean elapsedExceeds(long startMs, long thresholdMs) {
        return System.currentTimeMillis() - startMs >= thresholdMs;
    }

    /* --- Diagnostics --- */

    public Instant getLastRunAt() {
        return lastRunAt.get();
    }

    public long getRunCount() {
        return runCount.get();
    }
}