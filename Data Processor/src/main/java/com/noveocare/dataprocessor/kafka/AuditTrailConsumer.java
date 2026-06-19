package com.noveocare.dataprocessor.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.ai.FeatureEngineeringService;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.AiLiveSessionProperties;
import com.noveocare.dataprocessor.config.FeatureEngineeringProperties;
import com.noveocare.dataprocessor.config.KafkaConsumerProperties;
import com.noveocare.dataprocessor.config.KafkaTopicProperties;
import com.noveocare.dataprocessor.config.PerformanceProperties;
import com.noveocare.dataprocessor.config.RedisCacheProperties;

import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.dto.RunningSummaryUpdateResult;
import com.noveocare.dataprocessor.dto.SessionInsight;
import com.noveocare.dataprocessor.dto.SessionRunningSummary;
import com.noveocare.dataprocessor.dto.SessionSummary;
import com.noveocare.dataprocessor.inference.ModelInferenceService;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import com.noveocare.dataprocessor.redis.RedisSessionBufferService;
import com.noveocare.dataprocessor.service.DashboardSnapshotService;
import com.noveocare.dataprocessor.service.EventIdempotencyService;
import com.noveocare.dataprocessor.service.SessionFinalizationOrchestrator;
import com.noveocare.dataprocessor.service.SessionFinalizationService;
import com.noveocare.dataprocessor.service.SessionRuleEvaluator;
import com.noveocare.dataprocessor.service.SessionRunningSummaryService;
import com.noveocare.dataprocessor.service.StatisticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuditTrailConsumer {

    private final ObjectMapper objectMapper;
    private final RedisSessionBufferService sessionBufferService;
    private final FeatureEngineeringService featureEngineeringService;
    private final ModelInferenceService modelInferenceService;
    private final RedisCacheService redisCacheService;
    private final RedisCacheProperties redisCacheProperties;
    private final FeatureEngineeringProperties featureEngineeringProperties;
    private final StatisticsService statisticsService;
    private final DashboardSnapshotService dashboardSnapshotService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaTopicProperties kafkaTopicProperties;
    private final KafkaConsumerProperties kafkaConsumerProperties;
    private final SessionFinalizationService sessionFinalizationService;
    private final SessionFinalizationOrchestrator sessionFinalizationOrchestrator;
    private final SessionRuleEvaluator sessionRuleEvaluator;
    private final EventIdempotencyService eventIdempotencyService;
    private final PerformanceProperties performanceProperties;
    private final SessionRunningSummaryService sessionRunningSummaryService;
    private final AiLiveSessionProperties liveSessionProperties;

    private final AtomicLong recordsProcessedTotal = new AtomicLong();
    private final AtomicLong processingFailuresTotal = new AtomicLong();
    private final AtomicReference<Instant> lastConsumedAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastAckAt = new AtomicReference<>();
    private final AtomicReference<String> lastProcessingError = new AtomicReference<>();

    private final AtomicLong totalEventProcessingMsAcc = new AtomicLong();
    private final AtomicLong totalEventProcessingCount = new AtomicLong();
    private final AtomicReference<String> lastSlowEventId = new AtomicReference<>();
    private final AtomicReference<String> lastSlowEventAction = new AtomicReference<>();
    private final AtomicReference<Map<String, Long>> lastSlowEventStageBreakdown = new AtomicReference<>();
    private final AtomicLong summaryRunCount = new AtomicLong();
    private final AtomicReference<Instant> performanceSummaryLastRunAt = new AtomicReference<>();

    private final AtomicInteger rebalanceCount = new AtomicInteger();
    private final AtomicLong commitFailedCount = new AtomicLong();
    private final AtomicReference<Instant> lastCommitFailedAt = new AtomicReference<>();
    private final AtomicReference<String> lastCommitFailedReason = new AtomicReference<>();
    private final AtomicReference<Instant> lastPartitionAssignmentAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastPartitionRevocationAt = new AtomicReference<>();
    private final Map<String, Set<String>> assignedPartitionsByConsumer = new ConcurrentHashMap<>();
    private final AtomicReference<Collection<TopicPartition>> revokedPartitionsLastSeen = new AtomicReference<>();
    private final Set<String> revokedPartitionSet = ConcurrentHashMap.newKeySet();
    private volatile boolean hotPathBlocked = false;

    private volatile int topicPartitionCount = 0;
    private volatile String autoOffsetReset = "latest";
    private volatile int maxPollRecords = 50;
    private volatile long maxPollIntervalMs = 900000;
    private volatile String testConsumerIdOverride;

    private final AtomicLong lastRateCalcCount = new AtomicLong();
    private final AtomicReference<Instant> lastRateCalcAt = new AtomicReference<>(Instant.now());
    private volatile double recordsProcessedPerSecond = 0.0;
    private volatile long kafkaLagCached = 0;

    private static final int PERF_AGGREGATE_MAX_SAMPLES = 1000;
    private final java.util.List<Long> perfTotalMsSamples = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final java.util.List<Long> perfSequenceMsSamples = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final java.util.List<Long> perfTabularMsSamples = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final java.util.List<Long> perfRiskComputeMsSamples = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final java.util.List<Long> perfFinalizationMsSamples = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final java.util.List<Long> perfAlertPublishMsSamples = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final java.util.List<Long> perfAgeReceiveMsSamples = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final java.util.List<Long> perfAgeOutputMsSamples = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final java.util.List<Long> perfHistoryFetchMsSamples = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final java.util.List<Long> perfRunningSummaryMsSamples = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final java.util.List<Long> perfRulesMsSamples = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private void trackInferenceSummary() {
        summaryRunCount.incrementAndGet();
        performanceSummaryLastRunAt.set(Instant.now());
    }

    @KafkaListener(
            topics = "${app.kafka.topics.audit-trail}",
            groupId = "${spring.kafka.consumer.group-id}",
            concurrency = "${app.kafka.consumer.concurrency:3}")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack, Consumer<?, ?> consumer) {
        lastConsumedAt.set(Instant.now());
        long tStart = System.nanoTime();
        long kafkaReceiveTs = System.currentTimeMillis();
        String payload = record.value();
        if (payload == null || payload.isBlank()) {
            ack.acknowledge();
            return;
        }

        long t1 = System.nanoTime();
        AuditTrailEvent event;
        try {
            event = objectMapper.readValue(payload, AuditTrailEvent.class);
        } catch (Exception ex) {
            log.error("Failed to parse audit event JSON", ex);
            routeToDlq(record, "invalid_json", ex);
            ack.acknowledge();
            return;
        }
        long parseMs = (System.nanoTime() - t1) / 1_000_000L;

        if (event.getInsuredId() == null || event.getSessionId() == null) {
            log.warn("Skipping event without insuredId/sessionId: {}", payload);
            ack.acknowledge();
            return;
        }

        long eventTimestamp = record.timestamp();
        long kafkaEventAgeAtReceiveMs = kafkaReceiveTs - eventTimestamp;

        long t2 = System.nanoTime();
        if (!eventIdempotencyService.tryMarkProcessing(event)) {
            ack.acknowledge();
            return;
        }
        long idempotencyMs = (System.nanoTime() - t2) / 1_000_000L;

        boolean processingMarkerRemoved = false;
        try {
            long t3 = System.nanoTime();
            sessionFinalizationService.handleIncomingEvent(event);
            long sessionStateMs = (System.nanoTime() - t3) / 1_000_000L;

            long t4 = System.nanoTime();
            statisticsService.recordEvent(event);
            long statsMs = (System.nanoTime() - t4) / 1_000_000L;

            long t5 = System.nanoTime();
            sessionBufferService.appendEvent(event);
            long redisAppendMs = (System.nanoTime() - t5) / 1_000_000L;

            int historyLimit = liveSessionProperties.getRecentEventsLimit();
            long t6 = System.nanoTime();
            List<AuditTrailEvent> sessionEvents = new java.util.ArrayList<>(
                    sessionBufferService.getRecentSessionEvents(
                            event.getInsuredId(),
                            event.getSessionId(),
                            historyLimit));
            sessionEvents.sort(java.util.Comparator
                    .<AuditTrailEvent, Integer>comparing(AuditTrailEvent::getSequenceInSession, java.util.Comparator.nullsLast(Integer::compareTo))
                    .thenComparing(AuditTrailEvent::getCreatedAt, java.util.Comparator.nullsLast(java.time.Instant::compareTo))
                    .thenComparing(AuditTrailEvent::getId, java.util.Comparator.nullsLast(String::compareTo)));
            long historyFetchMs = (System.nanoTime() - t6) / 1_000_000L;
            int historySize = sessionEvents.size();

            if (historySize >= historyLimit) {
                log.warn("LIVE_HISTORY_SIZE_LIMITED sessionId={} size={} limit={}",
                        event.getSessionId(), historySize, historyLimit);
            }

            long t7 = System.nanoTime();
            List<AuditTrailEvent> enrichedEvents = featureEngineeringService.enrichSessionEvents(sessionEvents);
            long enrichmentMs = (System.nanoTime() - t7) / 1_000_000L;

            long t8 = System.nanoTime();
            SessionRunningSummary runningSummary = sessionRunningSummaryService.loadOrCreate(event.getSessionId(), event.getInsuredId());
            AuditTrailEvent currentEnriched = event;
            if (!enrichedEvents.isEmpty()) {
                currentEnriched = enrichedEvents.get(enrichedEvents.size() - 1);
            }
            runningSummary = sessionRunningSummaryService.updateWithEvent(runningSummary, currentEnriched);
            sessionRunningSummaryService.save(event.getSessionId(), runningSummary);
            SessionSummary summary = sessionRunningSummaryService.toLiveSessionSummary(runningSummary, enrichedEvents);
            long summaryBuildMs = (System.nanoTime() - t8) / 1_000_000L;

            long t9 = System.nanoTime();
            List<String> triggeredRules = sessionRuleEvaluator.evaluateSessionRules(enrichedEvents);
            long rulesMs = (System.nanoTime() - t9) / 1_000_000L;

            long lag = estimatePartitionLag(record, consumer);
            kafkaLagCached = lag;

            long t10 = System.nanoTime();
            SessionInsight insight = modelInferenceService.infer(summary, enrichedEvents, triggeredRules, lag);
            long inferenceMs = (System.nanoTime() - t10) / 1_000_000L;

            boolean isExplicitEnd = sessionFinalizationService.isExplicitSessionEnd(event);

            long t11 = System.nanoTime();
            long finalizationMs = 0;
            long alertPublishMs = 0;
            if (isExplicitEnd) {
                String endReason = sessionFinalizationService.resolveEndReason(event);
                List<AuditTrailEvent> fullHistory = sessionBufferService.getSessionEvents(
                        event.getInsuredId(), event.getSessionId());
                SessionSummary fullSummary = featureEngineeringService.buildSessionSummary(
                        featureEngineeringService.enrichSessionEvents(fullHistory));
                sessionFinalizationOrchestrator.completeFinalization(
                        fullSummary, insight, fullHistory, triggeredRules, endReason, true);
                finalizationMs = (System.nanoTime() - t11) / 1_000_000L;
            } else {
                if (sessionFinalizationOrchestrator.shouldAlert(insight)
                        && !sessionFinalizationOrchestrator.hasDetectedAnomaly(summary.getInsuredId(), summary.getSessionId())) {
                    sessionFinalizationOrchestrator.publishAlert(summary, insight, enrichedEvents);
                    alertPublishMs = (System.nanoTime() - t11) / 1_000_000L;
                }
            }
            long outputMs = (System.nanoTime() - t11) / 1_000_000L;
            Map<String, Long> inferenceTiming = modelInferenceService.getLastTimingBreakdown();

            long t12 = System.nanoTime();
            dashboardSnapshotService.cacheSessionInsight(summary, insight);
            long dashboardMs = (System.nanoTime() - t12) / 1_000_000L;

            long t13 = System.nanoTime();
            eventIdempotencyService.markProcessed(event);
            processingMarkerRemoved = true;
            ack.acknowledge();
            lastAckAt.set(Instant.now());
            long ackMs = (System.nanoTime() - t13) / 1_000_000L;

            long totalProcessingMs = (System.nanoTime() - tStart) / 1_000_000L;
            long kafkaEventAgeAtOutputMs = System.currentTimeMillis() - eventTimestamp;

            recordsProcessedTotal.incrementAndGet();

            String resolvedAction = resolveAction(event);

            addPerfSample(perfTotalMsSamples, totalProcessingMs);
            addPerfSample(perfAgeReceiveMsSamples, kafkaEventAgeAtReceiveMs);
            addPerfSample(perfAgeOutputMsSamples, kafkaEventAgeAtOutputMs);
            addPerfSample(perfHistoryFetchMsSamples, historyFetchMs);
            addPerfSample(perfRunningSummaryMsSamples, summaryBuildMs);
            addPerfSample(perfRulesMsSamples, rulesMs);
            if (inferenceTiming != null) {
                Long seqMs = inferenceTiming.get("sequenceInferenceMs");
                if (seqMs != null) addPerfSample(perfSequenceMsSamples, seqMs);
                Long tabMs = inferenceTiming.get("tabularInferenceMs");
                if (tabMs != null) addPerfSample(perfTabularMsSamples, tabMs);
                Long riskMs = inferenceTiming.get("inferenceMs");
                if (riskMs != null) addPerfSample(perfRiskComputeMsSamples, riskMs);
            }
            if (finalizationMs > 0) addPerfSample(perfFinalizationMsSamples, finalizationMs);
            if (alertPublishMs > 0) addPerfSample(perfAlertPublishMsSamples, alertPublishMs);
            long count = recordsProcessedTotal.get();
            if (count % 100 == 0 && count > 0) {
                logPerformanceAggregate();
            }

            if (performanceProperties.isTraceEventProcessing()) {
                log.info("PERF_EVENT eventId={} insuredId={} sessionId={} partition={} offset={} "
                                + "ageReceiveMs={} parseMs={} idempotencyMs={} sessionStateMs={} statsMs={} "
                                + "redisAppendMs={} historyFetchMs={} historySize={} enrichmentMs={} "
                                + "summaryBuildMs={} rulesMs={} inferenceMs={} tabularMs={} sequenceMs={} "
                                + "churnMs={} forecastMs={} riskComputeMs={} finalizationMs={} alertPublishMs={} "
                                + "dashboardMs={} ackMs={} totalMs={} ageOutputMs={}",
                        event.getId(), event.getInsuredId(), event.getSessionId(),
                        record.partition(), record.offset(),
                        kafkaEventAgeAtReceiveMs, parseMs, idempotencyMs, sessionStateMs, statsMs,
                        redisAppendMs, historyFetchMs, historySize, enrichmentMs,
                        summaryBuildMs, rulesMs, inferenceMs,
                        inferenceTiming == null ? null : inferenceTiming.getOrDefault("tabularInferenceMs", null),
                        inferenceTiming == null ? null : inferenceTiming.getOrDefault("sequenceInferenceMs", null),
                        inferenceTiming == null ? null : inferenceTiming.getOrDefault("churnMs", null),
                        inferenceTiming == null ? null : inferenceTiming.getOrDefault("forecastMs", null),
                        inferenceTiming == null ? null : inferenceTiming.getOrDefault("inferenceMs", null),
                        finalizationMs, alertPublishMs,
                        dashboardMs, ackMs, totalProcessingMs, kafkaEventAgeAtOutputMs);
            }

            Map<String, Long> baseBreakdown = new LinkedHashMap<>();
            baseBreakdown.put("parseMs", parseMs);
            baseBreakdown.put("idempotencyMs", idempotencyMs);
            baseBreakdown.put("sessionStateMs", sessionStateMs);
            baseBreakdown.put("statsMs", statsMs);
            baseBreakdown.put("redisSequenceAppendMs", redisAppendMs);
            baseBreakdown.put("historyFetchMs", historyFetchMs);
            baseBreakdown.put("historySize", (long) historySize);
            baseBreakdown.put("enrichmentMs", enrichmentMs);
            baseBreakdown.put("summaryBuildMs", summaryBuildMs);
            baseBreakdown.put("rulesEvaluationMs", rulesMs);
            baseBreakdown.put("inferenceMs", inferenceMs);
            if (inferenceTiming != null) {
                baseBreakdown.putAll(inferenceTiming);
            }
            baseBreakdown.put("outputMs", outputMs);
            baseBreakdown.put("finalizationMs", finalizationMs);
            baseBreakdown.put("alertPublishMs", alertPublishMs);
            baseBreakdown.put("dashboardMs", dashboardMs);
            baseBreakdown.put("ackMs", ackMs);

            if (totalProcessingMs > 30000) {
                hotPathBlocked = true;
                lastSlowEventId.set(event.getInsuredId() + "/" + event.getSessionId());
                lastSlowEventAction.set(resolvedAction);
                lastSlowEventStageBreakdown.set(new LinkedHashMap<>(baseBreakdown));
                log.warn("KAFKA_LISTENER_HOT_PATH_BLOCKED: {}ms for event {}/{}, action={}, breakdown={}",
                        totalProcessingMs, event.getInsuredId(), event.getSessionId(), resolvedAction, baseBreakdown);
            } else if (totalProcessingMs > 5000) {
                lastSlowEventId.set(event.getInsuredId() + "/" + event.getSessionId());
                lastSlowEventAction.set(resolvedAction);
                lastSlowEventStageBreakdown.set(new LinkedHashMap<>(baseBreakdown));
                log.warn("Slow event processing: {}ms for event {}/{}, action={}, breakdown={}",
                        totalProcessingMs, event.getInsuredId(), event.getSessionId(), resolvedAction, baseBreakdown);
            } else if (totalProcessingMs > 2000) {
                log.warn("Slow event processing: {}ms for event {}/{}, action={}",
                        totalProcessingMs, event.getInsuredId(), event.getSessionId(), resolvedAction);
            }

            totalEventProcessingMsAcc.addAndGet(totalProcessingMs);
            totalEventProcessingCount.incrementAndGet();

        } catch (CommitFailedException ex) {
            commitFailedCount.incrementAndGet();
            lastCommitFailedAt.set(Instant.now());
            lastCommitFailedReason.set(ex.getClass().getSimpleName() + ": " + ex.getMessage());
            hotPathBlocked = true;
            log.error("CommitFailedException for topic={} partition={} offset={} event={}/{} - processing likely exceeded poll interval or partitions revoked",
                    record.topic(), record.partition(), record.offset(), event.getInsuredId(), event.getSessionId(), ex);
            if (!processingMarkerRemoved) {
                eventIdempotencyService.removeProcessingMarker(event);
            }
        } catch (Exception ex) {
            processingFailuresTotal.incrementAndGet();
            lastProcessingError.set(ex.getClass().getSimpleName() + ": " + ex.getMessage());
            log.error("Processing failed for topic={} partition={} offset={} event={}/{}",
                    record.topic(), record.partition(), record.offset(),
                    event != null ? event.getInsuredId() : "?",
                    event != null ? event.getSessionId() : "?", ex);
            if (!processingMarkerRemoved) {
                eventIdempotencyService.removeProcessingMarker(event);
            }
            routeToDlq(record, "processing_failure", ex);
            try {
                ack.acknowledge();
            } catch (Exception ackEx) {
                log.warn("Ack after failure also failed for topic={} partition={} offset={}",
                        record.topic(), record.partition(), record.offset(), ackEx);
            }
        }
    }

    private String resolveAction(AuditTrailEvent event) {
        if (event == null) return null;
        if (event.getActionValue() != null && !event.getActionValue().isBlank()) {
            return event.getActionValue();
        }
        if (event.getFrontendActionName() != null && !event.getFrontendActionName().isBlank()) {
            return event.getFrontendActionName();
        }
        return event.getAction();
    }

    private void addPerfSample(java.util.List<Long> samples, long value) {
        samples.add(value);
        while (samples.size() > PERF_AGGREGATE_MAX_SAMPLES) {
            samples.remove(0);
        }
    }

    private void logPerformanceAggregate() {
        long count = recordsProcessedTotal.get();
        if (count == 0) return;
        log.info("PERF_AGGREGATE windowSec={} count={}"
                        + " eventTotal.p50Ms={} eventTotal.p95Ms={} eventTotal.maxMs={}"
                        + " sequence.p50Ms={} sequence.p95Ms={}"
                        + " tabular.p50Ms={} tabular.p95Ms={}"
                        + " riskCompute.p50Ms={} riskCompute.p95Ms={}"
                        + " finalization.p50Ms={} finalization.p95Ms={}"
                        + " alertPublish.p50Ms={} alertPublish.p95Ms={}"
                        + " ageReceive.p50Ms={} ageReceive.p95Ms={}"
                        + " ageOutput.p50Ms={} ageOutput.p95Ms={}",
                performanceProperties.getSummaryLogIntervalMs() / 1000, count,
                percentile(perfTotalMsSamples, 50), percentile(perfTotalMsSamples, 95), maxOrZero(perfTotalMsSamples),
                percentile(perfSequenceMsSamples, 50), percentile(perfSequenceMsSamples, 95),
                percentile(perfTabularMsSamples, 50), percentile(perfTabularMsSamples, 95),
                percentile(perfRiskComputeMsSamples, 50), percentile(perfRiskComputeMsSamples, 95),
                percentile(perfFinalizationMsSamples, 50), percentile(perfFinalizationMsSamples, 95),
                percentile(perfAlertPublishMsSamples, 50), percentile(perfAlertPublishMsSamples, 95),
                percentile(perfAgeReceiveMsSamples, 50), percentile(perfAgeReceiveMsSamples, 95),
                percentile(perfAgeOutputMsSamples, 50), percentile(perfAgeOutputMsSamples, 95));
    }

    private static long percentile(java.util.List<Long> samples, int pct) {
        if (samples == null || samples.isEmpty()) return 0L;
        long[] arr;
        synchronized (samples) {
            arr = new long[samples.size()];
            for (int i = 0; i < samples.size(); i++) {
                arr[i] = samples.get(i);
            }
        }
        java.util.Arrays.sort(arr);
        int index = (int) Math.ceil(pct / 100.0 * arr.length) - 1;
        if (index < 0) index = 0;
        if (index >= arr.length) index = arr.length - 1;
        return arr[index];
    }

    private static long maxOrZero(java.util.List<Long> samples) {
        if (samples == null || samples.isEmpty()) return 0L;
        long max = 0L;
        synchronized (samples) {
            for (long v : samples) {
                if (v > max) max = v;
            }
        }
        return max;
    }

    private long estimatePartitionLag(ConsumerRecord<String, String> record, Consumer<?, ?> consumer) {
        try {
            TopicPartition topicPartition = new TopicPartition(record.topic(), record.partition());
            Long endOffset = consumer.endOffsets(Set.of(topicPartition)).get(topicPartition);
            if (endOffset == null) {
                return 0L;
            }
            return Math.max(0L, endOffset - record.offset() - 1);
        } catch (Exception ex) {
            log.debug("Could not estimate lag for topic={} partition={}", record.topic(), record.partition(), ex);
            return 0L;
        }
    }

    private void routeToDlq(ConsumerRecord<String, String> record, String reason, Exception ex) {
        if (kafkaTopicProperties.getDlq() == null || kafkaTopicProperties.getDlq().isBlank()) {
            return;
        }
        Map<String, Object> dlqPayload = new LinkedHashMap<>();
        dlqPayload.put("reason", reason);
        dlqPayload.put("topic", record.topic());
        dlqPayload.put("partition", record.partition());
        dlqPayload.put("offset", record.offset());
        dlqPayload.put("key", record.key());
        dlqPayload.put("value", record.value());
        dlqPayload.put("error", ex == null ? null : ex.getMessage());
        dlqPayload.put("processedAt", Instant.now().toString());
        try {
            kafkaTemplate.send(kafkaTopicProperties.getDlq(), record.key(), objectMapper.writeValueAsString(dlqPayload));
        } catch (JsonProcessingException jsonProcessingException) {
            log.error("Failed to serialize DLQ payload for topic={} partition={} offset={}",
                    record.topic(), record.partition(), record.offset(), jsonProcessingException);
        }
    }

    private String resolveConsumerId() {
        if (testConsumerIdOverride != null) return testConsumerIdOverride;
        return Thread.currentThread().getName();
    }

    public ConsumerRebalanceListener createRebalanceListener() {
        return new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                String consumerId = resolveConsumerId();
                rebalanceCount.incrementAndGet();
                lastPartitionAssignmentAt.set(Instant.now());
                Set<String> parts = partitions.stream()
                        .map(tp -> tp.topic() + "-" + tp.partition())
                        .collect(java.util.stream.Collectors.toSet());
                assignedPartitionsByConsumer.put(consumerId, parts);
                revokedPartitionSet.clear();
                log.info("KAFKA_PARTITIONS_ASSIGNED consumerId={} partitions={}", consumerId, parts);
            }

            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                String consumerId = resolveConsumerId();
                lastPartitionRevocationAt.set(Instant.now());
                revokedPartitionsLastSeen.set(partitions);
                assignedPartitionsByConsumer.remove(consumerId);
                for (TopicPartition tp : partitions) {
                    revokedPartitionSet.add(tp.topic() + "-" + tp.partition());
                }
                log.warn("KAFKA_PARTITIONS_REVOKED consumerId={} partitions={}", consumerId,
                        partitions.stream().map(tp -> tp.topic() + "-" + tp.partition()).toList());
            }
        };
    }

    Set<String> getAllAssignedPartitions() {
        java.util.Set<String> all = new java.util.TreeSet<>();
        for (Set<String> parts : assignedPartitionsByConsumer.values()) {
            all.addAll(parts);
        }
        return all;
    }

    public void setTopicPartitionCount(int count) {
        this.topicPartitionCount = count;
    }

    public void setAutoOffsetReset(String value) {
        this.autoOffsetReset = value;
    }

    public void setMaxPollRecords(int value) {
        this.maxPollRecords = value;
    }

    public void setMaxPollIntervalMs(long value) {
        this.maxPollIntervalMs = value;
    }

    public void setTestConsumerIdOverride(String id) {
        this.testConsumerIdOverride = id;
    }

    private boolean isPartitionRevoked(String topic, int partition) {
        return revokedPartitionSet.contains(topic + "-" + partition);
    }

    public Map<String, Object> diagnosticsSnapshot() {
        Map<String, Object> diag = new LinkedHashMap<>();
        diag.put("consumerGroupId", kafkaConsumerProperties != null ? "data-processor-group" : null);
        diag.put("topic", kafkaTopicProperties != null ? kafkaTopicProperties.getAuditTrail() : null);
        diag.put("configuredConcurrency", kafkaConsumerProperties != null ? kafkaConsumerProperties.getConcurrency() : null);
        diag.put("topicPartitionCount", topicPartitionCount);
        diag.put("autoOffsetReset", autoOffsetReset);
        diag.put("maxPollRecords", maxPollRecords);
        diag.put("maxPollIntervalMs", maxPollIntervalMs);
        diag.put("lastConsumedAt", lastConsumedAt.get() != null ? lastConsumedAt.get().toString() : null);
        diag.put("lastAckAt", lastAckAt.get() != null ? lastAckAt.get().toString() : null);
        diag.put("lastProcessingError", lastProcessingError.get());
        diag.put("recordsProcessedTotal", recordsProcessedTotal.get());
        diag.put("processingFailuresTotal", processingFailuresTotal.get());
        diag.put("rebalanceCount", rebalanceCount.get());
        diag.put("commitFailedCount", commitFailedCount.get());
        diag.put("lastCommitFailedAt", lastCommitFailedAt.get() != null ? lastCommitFailedAt.get().toString() : null);
        diag.put("lastCommitFailedReason", lastCommitFailedReason.get());

        Set<String> allAssigned = getAllAssignedPartitions();
        diag.put("assignedPartitions", allAssigned.isEmpty() ? null : List.copyOf(allAssigned));
        int assignedCount = allAssigned.size();
        diag.put("assignedPartitionCount", assignedCount);
        int concurrency = kafkaConsumerProperties != null && kafkaConsumerProperties.getConcurrency() != null
                ? kafkaConsumerProperties.getConcurrency() : 3;
        int effectiveParallelism = Math.min(concurrency, assignedCount > 0 ? assignedCount : concurrency);
        diag.put("effectiveConsumerParallelism", effectiveParallelism);

        String status;
        if (topicPartitionCount <= 0) {
            status = "UNKNOWN";
        } else if (assignedCount == 0 && rebalanceCount.get() == 0) {
            status = "UNKNOWN";
        } else if (assignedCount >= topicPartitionCount) {
            status = "OK";
        } else {
            status = "PARTIAL";
        }
        diag.put("partitionAssignmentStatus", status);
        diag.put("partitionAssignmentMessage", status.equals("OK")
                ? "All " + topicPartitionCount + " topic partitions are assigned. Effective parallelism is " + effectiveParallelism + "."
                : status.equals("PARTIAL")
                ? assignedCount + " of " + topicPartitionCount + " partitions assigned. Expected full set after rebalance."
                : "Partition assignment status not yet available.");

        diag.put("revokedPartitionsLastSeen", revokedPartitionsLastSeen.get() != null ? revokedPartitionsLastSeen.get().toString() : null);
        diag.put("lastPartitionAssignmentAt", lastPartitionAssignmentAt.get() != null ? lastPartitionAssignmentAt.get().toString() : null);
        diag.put("lastPartitionRevocationAt", lastPartitionRevocationAt.get() != null ? lastPartitionRevocationAt.get().toString() : null);
        diag.put("hotPathBlocked", hotPathBlocked);
        diag.put("performance", buildPerformanceDiagnostics());

        log.info("KAFKA_ASSIGNMENT_SUMMARY assignedPartitionCount={} topicPartitionCount={} configuredConcurrency={} effectiveParallelism={} status={}",
                assignedCount, topicPartitionCount, concurrency, effectiveParallelism, status);
        return diag;
    }

    private void updateProcessingRate() {
        long currentCount = recordsProcessedTotal.get();
        Instant now = Instant.now();
        Instant last = lastRateCalcAt.getAndSet(now);
        long lastCount = lastRateCalcCount.getAndSet(currentCount);
        if (last == null) return;
        long deltaMs = java.time.Duration.between(last, now).toMillis();
        long deltaCount = currentCount - lastCount;
        if (deltaMs > 0) {
            recordsProcessedPerSecond = (double) deltaCount / deltaMs * 1000.0;
        }
    }

    private Map<String, Object> buildPerformanceDiagnostics() {
        updateProcessingRate();
        Map<String, Object> perf = new LinkedHashMap<>();
        long count = totalEventProcessingCount.get();
        long totalMs = totalEventProcessingMsAcc.get();
        perf.put("eventProcessingMsAvg", count == 0 ? null : (double) totalMs / count);
        perf.put("eventProcessingMsP95", count == 0 ? null : (double) percentile(perfTotalMsSamples, 95));
        perf.put("modelInferenceMsAvg", count == 0 ? null : (double) avgOf(perfRiskComputeMsSamples));
        perf.put("modelInferenceMsP95", count == 0 ? null : (double) percentile(perfRiskComputeMsSamples, 95));
        perf.put("sequenceMsAvg", count == 0 ? null : (double) avgOf(perfSequenceMsSamples));
        perf.put("sequenceMsP95", count == 0 ? null : (double) percentile(perfSequenceMsSamples, 95));
        perf.put("tabularMsAvg", count == 0 ? null : (double) avgOf(perfTabularMsSamples));
        perf.put("tabularMsP95", count == 0 ? null : (double) percentile(perfTabularMsSamples, 95));
        perf.put("historyFetchMsAvg", count == 0 ? null : (double) avgOf(perfHistoryFetchMsSamples));
        perf.put("historyFetchMsP95", count == 0 ? null : (double) percentile(perfHistoryFetchMsSamples, 95));
        perf.put("rulesMsAvg", count == 0 ? null : (double) avgOf(perfRulesMsSamples));
        perf.put("rulesMsP95", count == 0 ? null : (double) percentile(perfRulesMsSamples, 95));
        perf.put("finalizationMsAvg", count == 0 ? null : (double) avgOf(perfFinalizationMsSamples));
        perf.put("finalizationMsP95", count == 0 ? null : (double) percentile(perfFinalizationMsSamples, 95));
        perf.put("alertPublishMsAvg", count == 0 ? null : (double) avgOf(perfAlertPublishMsSamples));
        perf.put("alertPublishMsP95", count == 0 ? null : (double) percentile(perfAlertPublishMsSamples, 95));
        perf.put("kafkaEventAgeReceiveMsAvg", count == 0 ? null : (double) avgOf(perfAgeReceiveMsSamples));
        perf.put("kafkaEventAgeReceiveMsP95", count == 0 ? null : (double) percentile(perfAgeReceiveMsSamples, 95));
        perf.put("recordsProcessedPerSecond", count == 0 ? null : recordsProcessedPerSecond);
        perf.put("kafkaLagCached", kafkaLagCached);
        perf.put("lastSlowEventId", lastSlowEventId.get());
        perf.put("lastSlowEventAction", lastSlowEventAction.get());
        perf.put("lastSlowEventStageBreakdown", lastSlowEventStageBreakdown.get());
        perf.put("performanceSummaryLastRunAt", performanceSummaryLastRunAt.get() != null ? performanceSummaryLastRunAt.get().toString() : null);
        perf.put("performanceSummaryRunCount", summaryRunCount.get());
        perf.put("kafka_listener_hot_path_blocked", hotPathBlocked);
        return perf;
    }

    static long avgOf(java.util.List<Long> samples) {
        if (samples == null || samples.isEmpty()) return 0L;
        long sum = 0L;
        synchronized (samples) {
            for (long v : samples) {
                sum += v;
            }
            return sum / samples.size();
        }
    }

    public void markSummaryRun() {
        trackInferenceSummary();
    }

    public long getRecordsProcessedTotal() {
        return recordsProcessedTotal.get();
    }

    public long getProcessingFailuresTotal() {
        return processingFailuresTotal.get();
    }

    public long getCommitFailedCount() {
        return commitFailedCount.get();
    }

    public long getRebalanceCount() {
        return rebalanceCount.get();
    }

    public boolean isHotPathBlocked() {
        return hotPathBlocked;
    }
}