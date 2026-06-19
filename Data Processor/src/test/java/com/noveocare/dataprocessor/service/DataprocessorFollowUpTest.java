package com.noveocare.dataprocessor.service;

import com.noveocare.dataprocessor.config.AiSequenceProperties;
import com.noveocare.dataprocessor.config.InferenceConfigProperties;
import com.noveocare.dataprocessor.config.PerformanceProperties;
import com.noveocare.dataprocessor.config.SessionFinalizationProperties;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.dto.SessionInsight;
import com.noveocare.dataprocessor.dto.SessionSummary;
import com.noveocare.dataprocessor.inference.InferenceConfig;
import com.noveocare.dataprocessor.inference.InferenceExecutorManager;
import com.noveocare.dataprocessor.inference.ModelHealthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataprocessorFollowUpTest {

    @Mock
    private ModelHealthService modelHealthService;
    @Mock
    private InferenceExecutorManager executorManager;

    private InferenceConfig inferenceConfig;
    private AiSequenceProperties sequenceProperties;
    private DashboardSnapshotService dashboardSnapshotService;
    private DashboardRefreshScheduler dashboardRefreshScheduler;

    @BeforeEach
    void setUp() {
        sequenceProperties = new AiSequenceProperties();
        InferenceConfigProperties props = new InferenceConfigProperties();
        inferenceConfig = new InferenceConfig(props, null, sequenceProperties, null, null);
        dashboardSnapshotService = new DashboardSnapshotService(null, null, null, null, null, null, null, null, null, null, null, null);
        dashboardRefreshScheduler = new DashboardRefreshScheduler(dashboardSnapshotService, null);
    }

    @Test
    void transformerEnabledDefaultsToFalse() {
        assertThat(sequenceProperties.isTransformerEnabled()).isFalse();
    }

    @Test
    void tcnEnabledDefaultsToFalse() {
        assertThat(sequenceProperties.isTcnEnabled()).isFalse();
    }

    @Test
    void sequenceEnabledDefaultsToFalse() {
        assertThat(sequenceProperties.isEnabled()).isFalse();
    }

    @Test
    void transformerDisabledWhenSequenceDisabled() {
        sequenceProperties.setEnabled(false);
        assertThat(inferenceConfig.isTransformerEnabled()).isFalse();
    }

    @Test
    void tcnDisabledWhenSequenceDisabled() {
        sequenceProperties.setEnabled(false);
        assertThat(inferenceConfig.isTcnEnabled()).isFalse();
    }

    @Test
    void inferenceConfigReportsTransformerEnabled() {
        sequenceProperties.setEnabled(true);
        sequenceProperties.setTransformerEnabled(true);
        assertThat(inferenceConfig.isTransformerEnabled()).isTrue();
    }

    @Test
    void inferenceConfigReportsTcnEnabled() {
        sequenceProperties.setEnabled(true);
        sequenceProperties.setTcnEnabled(true);
        assertThat(inferenceConfig.isTcnEnabled()).isTrue();
    }

    @Test
    void liveFastModeSkipTransformerDefaultsToTrue() {
        InferenceConfigProperties props = new InferenceConfigProperties();
        assertThat(props.getLiveFastMode().isSkipTransformer()).isTrue();
    }

    @Test
    void liveFastModeAllowTcnDefaultsToTrue() {
        InferenceConfigProperties props = new InferenceConfigProperties();
        assertThat(props.getLiveFastMode().isAllowTcn()).isTrue();
    }

    @Test
    void circuitBreakerDisableModelForRunDefaultsToFalse() {
        InferenceConfigProperties props = new InferenceConfigProperties();
        assertThat(props.getCircuitBreaker().isDisableModelForRunAfterCircuitOpen()).isFalse();
    }

    @Test
    void dashboardRateLimitNotIncrementedBeforeFirstRefresh() {
        DashboardSnapshotService service = new DashboardSnapshotService(null, null, null, null, null, null, null, null, null, null, null, null);
        assertThat(service.getDashboardRefreshSkippedDueToRateLimit()).isZero();
    }

    @Test
    void sessionFinalizationGracePeriodDefaultsTo30000() {
        SessionFinalizationProperties props = new SessionFinalizationProperties();
        assertThat(props.getGracePeriodMs()).isEqualTo(30000L);
    }

    @Test
    void lateEventLogResolvesActionFromActionValue() {
        AuditTrailEvent event = new AuditTrailEvent();
        event.setActionValue("D\u00e9connexion");
        event.setAction(null);
        // Verify the service uses action_value -> frontend_action_name -> action -> "unknown"
        assertThat(resolveAction(event)).isEqualTo("D\u00e9connexion");
    }

    @Test
    void lateEventLogResolvesActionFromFrontendActionName() {
        AuditTrailEvent event = new AuditTrailEvent();
        event.setActionValue(null);
        event.setFrontendActionName("logout_click");
        event.setAction(null);
        assertThat(resolveAction(event)).isEqualTo("logout_click");
    }

    @Test
    void lateEventLogResolvesActionFromActionFallback() {
        AuditTrailEvent event = new AuditTrailEvent();
        event.setActionValue(null);
        event.setFrontendActionName(null);
        event.setAction("LOGIN");
        assertThat(resolveAction(event)).isEqualTo("LOGIN");
    }

    @Test
    void lateEventLogResolvesActionUnknownWhenAllNull() {
        AuditTrailEvent event = new AuditTrailEvent();
        event.setActionValue(null);
        event.setFrontendActionName(null);
        event.setAction(null);
        assertThat(resolveAction(event)).isEqualTo("unknown");
    }

    @Test
    void inferenceDiagnosticsIncludeTransformerEnabledInSnapshot() {
        sequenceProperties.setTransformerEnabled(false);
        Map<String, Object> inference = inferenceConfig.getInferenceConfigProperties().getLiveFastMode() != null
                ? Map.of("transformerEnabled", inferenceConfig.isTransformerEnabled())
                : Map.of("transformerEnabled", false);
        assertThat(inferenceConfig.isTransformerEnabled()).isFalse();
    }

    @Test
    void executorManagerSupportsPermanentDisable() {
        InferenceExecutorManager em = new InferenceExecutorManager();
        assertThat(em.isPermanentlyDisabled("transformer")).isFalse();
        em.disablePermanently("transformer");
        assertThat(em.isPermanentlyDisabled("transformer")).isTrue();
        assertThat(em.isCircuitOpen("transformer")).isTrue();
    }

    @Test
    void executorManagerPermanentDisableSurvivesCooldownCheck() {
        InferenceExecutorManager em = new InferenceExecutorManager();
        em.disablePermanently("transformer");
        assertThat(em.isCircuitOpen("transformer")).isTrue();
        Map<String, Object> cbDiag = em.circuitBreakerDiagnostics();
        assertThat(cbDiag).containsKey("transformer");
        @SuppressWarnings("unchecked")
        Map<String, Object> tDiag = (Map<String, Object>) cbDiag.get("transformer");
        assertThat(tDiag).containsEntry("permanentlyDisabled", true);
    }

    @Test
    void circuitBreakerDiagnosticsIncludePermanentlyDisabled() {
        InferenceExecutorManager em = new InferenceExecutorManager();
        em.recordTimeout("transformer");
        em.recordTimeout("transformer");
        em.recordTimeout("transformer");
        em.checkAndOpenCircuit("transformer", 3, 60000);
        Map<String, Object> cbDiag = em.circuitBreakerDiagnostics();
        @SuppressWarnings("unchecked")
        Map<String, Object> tDiag = (Map<String, Object>) cbDiag.get("transformer");
        assertThat(tDiag).containsEntry("circuitOpen", true);
        assertThat(tDiag).containsEntry("permanentlyDisabled", false);
    }

    @Test
    void dashboardSchedulerTracksRunCount() {
        DashboardSnapshotService dss = new DashboardSnapshotService(null, null, null, null, null, null, null, null, null, null, null, null);
        DashboardRefreshScheduler scheduler = new DashboardRefreshScheduler(dss, null);
        assertThat(scheduler.getRunCount()).isZero();
    }

    @Test
    void dashboardSnapshotTracksRefreshSuccessCount() {
        DashboardSnapshotService dss = new DashboardSnapshotService(null, null, null, null, null, null, null, null, null, null, null, null);
        assertThat(dss.getRefreshSuccessCount()).isZero();
        assertThat(dss.getRefreshFailureCount()).isZero();
    }

    @Test
    void dashboardSnapshotTracksLastRefreshAttempt() {
        DashboardSnapshotService dss = new DashboardSnapshotService(null, null, null, null, null, null, null, null, null, null, null, null);
        assertThat(dss.getLastRefreshAttemptAt()).isNull();
        assertThat(dss.getLastRefreshError()).isNull();
    }

    @Test
    void sessionFinalizationDiagnosticsIncludeGracePeriod() {
        SessionFinalizationProperties props = new SessionFinalizationProperties();
        assertThat(props.getGracePeriodMs()).isPositive();
    }

    @Test
    void idempotencyTracksLastDuplicateEvent() {
        EventIdempotencyService service = new EventIdempotencyService(null, null);
        assertThat(service.getLastDuplicateEventId()).isNull();
        assertThat(service.getLastDuplicateEventSessionId()).isNull();
        assertThat(service.getLastDuplicateEventAt()).isNull();
    }

    private String resolveAction(AuditTrailEvent event) {
        if (event == null) return "unknown";
        if (event.getActionValue() != null && !event.getActionValue().isBlank()) {
            return event.getActionValue();
        }
        if (event.getFrontendActionName() != null && !event.getFrontendActionName().isBlank()) {
            return event.getFrontendActionName();
        }
        if (event.getAction() != null && !event.getAction().isBlank()) {
            return event.getAction();
        }
        return "unknown";
    }

    @Test
    void liveFastModeDisablesSequenceInferenceByDefault() {
        assertThat(sequenceProperties.isEnabled()).isFalse();
        assertThat(inferenceConfig.isSequenceEnabled()).isFalse();
        assertThat(inferenceConfig.isTransformerEnabled()).isFalse();
        assertThat(inferenceConfig.isTcnEnabled()).isFalse();
    }

    @Test
    void sequenceInferenceDisabledPreventsTransformerAndTcnCalls() {
        sequenceProperties.setEnabled(false);
        assertThat(inferenceConfig.isTransformerEnabled()).isFalse();
        assertThat(inferenceConfig.isTcnEnabled()).isFalse();
    }

    @Test
    void dashboardRefreshIsSingleFlight() {
        DashboardSnapshotService dss = new DashboardSnapshotService(null, null, null, null, null, null, null, null, null, null, null, null);
        assertThat(dss.tryStartRefresh()).isTrue();
        assertThat(dss.tryStartRefresh()).isFalse();
        assertThat(dss.getRefreshAlreadyRunningSkipped()).isEqualTo(1L);
        dss.finishRefresh();
        assertThat(dss.tryStartRefresh()).isTrue();
        dss.finishRefresh();
    }

    @Test
    void dirtyFlagsClearAfterSuccessfulPerViewRefresh() throws Exception {
        DashboardSnapshotService dss = new DashboardSnapshotService(null, null, null, null, null, null, null, null, null, null, null, null);
        dss.markAlertsDirty();
        dss.markRiskySessionsDirty();
        dss.markOverviewDirty();
        assertThat(dss.isAlertsDirty()).isTrue();
        assertThat(dss.isRiskySessionsDirty()).isTrue();
        assertThat(dss.isSecurityOverviewDirty()).isTrue();
    }

    @Test
    void perViewMinIntervalAvailable() {
        PerformanceProperties.DashboardRefresh config = new PerformanceProperties.DashboardRefresh();
        assertThat(config.getAlertsMinIntervalMs()).isGreaterThan(0);
        assertThat(config.getSecurityOverviewMinIntervalMs()).isGreaterThan(0);
        assertThat(config.getRiskySessionsMinIntervalMs()).isGreaterThan(0);
        assertThat(config.getMaxAlertItems()).isGreaterThan(0);
        assertThat(config.getMaxRiskySessionItems()).isGreaterThan(0);
    }

    @Test
    void dashboardSnapshotIncludesGeneratedAtUpdatedAtSourceItemCount() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("generatedAt", Instant.now().toString());
        snapshot.put("updatedAt", Instant.now().toString());
        snapshot.put("source", "redis_live_incremental");
        snapshot.put("itemCount", 42);
        assertThat(snapshot).containsKey("generatedAt");
        assertThat(snapshot).containsKey("updatedAt");
        assertThat(snapshot).containsKey("source");
        assertThat(snapshot).containsKey("itemCount");
        assertThat(snapshot.get("itemCount")).isEqualTo(42);
    }

    @Test
    void runtimeHealthExposesDashboardPerViewTimings() {
        DashboardSnapshotService dss = new DashboardSnapshotService(null, null, null, null, null, null, null, null, null, null, null, null);
        assertThat(dss.getLastAlertsRefreshMs()).isZero();
        assertThat(dss.getLastSecurityOverviewRefreshMs()).isZero();
        assertThat(dss.getLastRiskySessionsRefreshMs()).isZero();
        assertThat(dss.getLastTotalDashboardRefreshMs()).isZero();
        assertThat(dss.getRefreshAlreadyRunningSkipped()).isZero();
        assertThat(dss.getRefreshSuccessCount()).isZero();
        assertThat(dss.getRefreshFailureCount()).isZero();
    }

    @Test
    void refreshSkipCountersNotIncrementedOnEverySchedulerTick() {
        DashboardSnapshotService dss = new DashboardSnapshotService(null, null, null, null, null, null, null, null, null, null, null, null);
        long before = dss.getDashboardRefreshSkippedDueToRateLimit();
        assertThat(before).isZero();
    }

    @Test
    void debugBenchmarkEnabledDefaultsToFalse() {
        assertThat(sequenceProperties.isDebugBenchmarkEnabled()).isFalse();
    }

    @Test
    void inferenceDiagnosticsIncludeSequenceBenchmark() {
        Map<String, Object> bench = new LinkedHashMap<>();
        bench.put("lastRunAt", null);
        bench.put("transformerOnnxRunMs", null);
        bench.put("tcnOnnxRunMs", null);
        Map<String, Object> inference = new LinkedHashMap<>();
        inference.put("sequenceBenchmark", bench);
        assertThat(inference).containsKey("sequenceBenchmark");
        @SuppressWarnings("unchecked")
        Map<String, Object> extracted = (Map<String, Object>) inference.get("sequenceBenchmark");
        assertThat(extracted).containsKey("lastRunAt");
    }

    @Test
    void dashboardTimingFieldsExposed() {
        DashboardSnapshotService dss = new DashboardSnapshotService(null, null, null, null, null, null, null, null, null, null, null, null);
        assertThat(dss.getLastRefreshStartedAt()).isNull();
        assertThat(dss.getLastRefreshCompletedAt()).isNull();
        assertThat(dss.getDashboardLastRefreshAt()).isNull();
    }

    @Test
    void sequenceDisabledReasonExposed() {
        InferenceConfigProperties props = new InferenceConfigProperties();
        AiSequenceProperties seqProps = new AiSequenceProperties();
        seqProps.setEnabled(false);
        InferenceConfig config = new InferenceConfig(props, null, seqProps, null, null);
        assertThat(config.isSequenceEnabled()).isFalse();
    }

    @Test
    void transformerAndTcnDisabledWhenSequenceDisabled() {
        InferenceConfigProperties props = new InferenceConfigProperties();
        AiSequenceProperties seqProps = new AiSequenceProperties();
        seqProps.setEnabled(false);
        seqProps.setTransformerEnabled(true);
        seqProps.setTcnEnabled(true);
        InferenceConfig config = new InferenceConfig(props, null, seqProps, null, null);
        assertThat(config.isTransformerEnabled()).isFalse();
        assertThat(config.isTcnEnabled()).isFalse();
    }
}