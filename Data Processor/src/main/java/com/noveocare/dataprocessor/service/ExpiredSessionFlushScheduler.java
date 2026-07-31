package com.noveocare.dataprocessor.service;

import com.noveocare.dataprocessor.ai.FeatureEngineeringService;
import com.noveocare.dataprocessor.config.SessionFinalizationProperties;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.dto.SessionInsight;
import com.noveocare.dataprocessor.dto.SessionState;
import com.noveocare.dataprocessor.dto.SessionSummary;
import com.noveocare.dataprocessor.inference.ModelInferenceService;
import com.noveocare.dataprocessor.redis.RedisSessionBufferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Periodically flushes sessions that have exceeded inactivity timeout or max open duration.
 * For each expired session it enriches events, builds a summary, evaluates rules,
 * runs model inference, and orchestrates finalization.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ExpiredSessionFlushScheduler {

    /* Injected dependencies */
    private final SessionFinalizationService finalizationService;
    private final SessionFinalizationOrchestrator finalizationOrchestrator;
    private final FeatureEngineeringService featureEngineeringService;
    private final SessionRuleEvaluator ruleEvaluator;
    private final ModelInferenceService modelInferenceService;
    private final RedisSessionBufferService sessionBufferService;
    private final SessionFinalizationProperties finalizationProperties;

    /* --- Scheduled task --- */

    @Scheduled(fixedDelayString = "${app.session.finalization.expired-flush-interval-ms:30000}")
    public void flushExpiredSessions() {
        List<SessionState> openSessions = finalizationService.getOpenSessionStates();
        if (openSessions.isEmpty()) {
            finalizationService.markFlushRun(0);
            return;
        }

        Instant now = Instant.now();
        long inactivityTimeout = finalizationProperties.getInactivityTimeoutSeconds();
        long maxDuration = finalizationProperties.getMaxOpenDurationSeconds();
        long gracePeriodMs = finalizationProperties.getGracePeriodMs();
        int finalizedCount = 0;

        for (SessionState state : openSessions) {
            try {
                String endReason = null;
                boolean endedExplicitly = false;

                if (state.getLastEventIngestedAt() != null) {
                    long idleSeconds = Duration.between(state.getLastEventIngestedAt(), now).getSeconds();
                    if (idleSeconds >= inactivityTimeout) {
                        endReason = finalizationProperties.getTimeoutEndReason();
                    }
                }
                if (endReason == null && state.getFirstEventTimestamp() != null) {
                    long openSeconds = Duration.between(state.getFirstEventTimestamp(), now).getSeconds();
                    if (openSeconds >= maxDuration) {
                        long ingestionAgeMs = state.getLastEventIngestedAt() != null
                                ? Duration.between(state.getLastEventIngestedAt(), now).toMillis()
                                : Long.MAX_VALUE;
                        if (ingestionAgeMs >= gracePeriodMs) {
                            endReason = finalizationProperties.getMaxDurationEndReason();
                        }
                    }
                }
                if (endReason == null) {
                    continue;
                }

                List<AuditTrailEvent> events = sessionBufferService.getSessionEvents(
                        state.getInsuredId(), state.getSessionId());
                if (events.isEmpty()) {
                    finalizationService.finalizeSession(
                            state.getInsuredId(), state.getSessionId(), endReason, endedExplicitly);
                    finalizedCount++;
                    continue;
                }

                List<AuditTrailEvent> enrichedEvents = featureEngineeringService.enrichSessionEvents(events);
                SessionSummary summary = featureEngineeringService.buildSessionSummary(enrichedEvents);
                List<String> triggeredRules = ruleEvaluator.evaluateSessionRules(enrichedEvents);
                SessionInsight insight = modelInferenceService.infer(summary, enrichedEvents, triggeredRules);

                finalizationOrchestrator.completeFinalization(
                        summary, insight, enrichedEvents, triggeredRules, endReason, endedExplicitly);
                finalizedCount++;
                log.debug("Flush finalized session {}/{} reason={}", state.getInsuredId(), state.getSessionId(), endReason);
            } catch (Exception e) {
                log.error("Failed to finalize expired session {}/{}",
                        state.getInsuredId(), state.getSessionId(), e);
            }
        }

        finalizationService.markFlushRun(finalizedCount);
        if (finalizedCount > 0) {
            log.info("Expired session flush completed: {} sessions finalized out of {} open",
                    finalizedCount, openSessions.size());
        }
    }
}