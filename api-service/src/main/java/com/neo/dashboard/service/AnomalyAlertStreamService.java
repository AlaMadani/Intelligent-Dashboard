package com.neo.dashboard.service;

import com.neo.dashboard.dto.AnomalyAlertDto;
import com.neo.dashboard.dto.AnomalyEventDto;
import com.neo.dashboard.entity.AnomalyEvent;
import com.neo.dashboard.repository.AnomalyEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bridges Kafka anomaly alerts to Server-Sent Events and keeps a short replay
 * buffer for newly connected clients.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnomalyAlertStreamService {

    /* Replay only the most recent alerts so reconnecting clients get quick context. */
    private static final int REPLAY_LIMIT = 20;

    /* Message deserialization plus optional enrichment from the database. */
    private final ObjectMapper objectMapper;
    private final AnomalyEventRepository anomalyEventRepository;

    /* Active SSE subscribers and the most recent alerts kept for replay. */
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final Deque<AnomalyEventDto> replayBuffer = new ConcurrentLinkedDeque<>();

    /* Register a new SSE client, acknowledge the connection, and replay recent alerts. */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(error -> emitters.remove(emitter));

        try {
            // Send a handshake event first, then replay the recent anomaly history.
            emitter.send(SseEmitter.event().name("connected").data("stream-ready"));
            for (AnomalyEventDto replay : snapshot()) {
                emitter.send(SseEmitter.event().name("anomaly").data(replay));
            }
        } catch (IOException | IllegalStateException e) {
            emitters.remove(emitter);
            log.warn("Failed to initialize anomaly SSE subscription", e);
        }

        return emitter;
    }

    /* Consume Kafka alert payloads, enrich them when possible, and broadcast them to SSE clients. */
    @KafkaListener(topics = "${app.kafka.topics.anomaly-alerts}", groupId = "${spring.kafka.consumer.group-id}")
    public void onAlertMessage(String payload) {
        if (payload == null || payload.isBlank()) {
            return;
        }

        try {
            // Reuse the persisted event when available so streamed alerts include the full stored payload.
            AnomalyAlertDto alert = objectMapper.readValue(payload, AnomalyAlertDto.class);
            AnomalyEventDto event = resolveEvent(alert);
            addToReplay(event);
            broadcast(event);
        } catch (Exception e) {
            log.warn("Failed to process anomaly alert stream payload", e);
        }
    }

    /* Try to replace the lightweight alert with the richer persisted event from SQL. */
    private AnomalyEventDto resolveEvent(AnomalyAlertDto alert) {
        Optional<AnomalyEvent> persisted = anomalyEventRepository
                .findTopByInsuredIdAndSessionIdAndEventIdOrderByDetectedAtDesc(
                        alert.getInsuredId(),
                        alert.getSessionId(),
                        alert.getEventId()
                );

        if (persisted.isPresent()) {
            AnomalyEvent event = persisted.get();
            return new AnomalyEventDto(
                    event.getId(),
                    event.getInsuredId(),
                    event.getSessionId(),
                    event.getEventId(),
                    event.getEventTime(),
                    event.getAnomalyTier(),
                    event.getAnomalyType(),
                    event.getAnomalyScore(),
                    event.getTypeConfidence(),
                    event.getRuleType(),
                    event.getEventJson(),
                    event.getDetectedAt()
            );
        }

        // If the event has not been persisted yet, stream the Kafka payload as-is.
        return new AnomalyEventDto(
                null,
                alert.getInsuredId(),
                alert.getSessionId(),
                alert.getEventId(),
                alert.getEventTime(),
                alert.getAnomalyTier(),
                alert.getAnomalyType(),
                alert.getAnomalyScore(),
                alert.getTypeConfidence(),
                alert.getRuleType(),
                null,
                alert.getDetectedAt() == null ? Instant.now() : alert.getDetectedAt()
        );
    }

    /* Keep only the latest N events for replay on new subscriptions. */
    private void addToReplay(AnomalyEventDto event) {
        replayBuffer.addFirst(event);
        while (replayBuffer.size() > REPLAY_LIMIT) {
            replayBuffer.pollLast();
        }
    }

    /* Broadcast one alert to every active SSE subscriber and clean up stale emitters. */
    private void broadcast(AnomalyEventDto event) {
        List<SseEmitter> stale = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("anomaly").data(event));
            } catch (IOException | IllegalStateException e) {
                stale.add(emitter);
            }
        }
        if (!stale.isEmpty()) {
            emitters.removeAll(stale);
            stale.forEach(SseEmitter::complete);
        }
    }

    /* Snapshot the replay buffer to avoid iterating over a structure that may change during sends. */
    private List<AnomalyEventDto> snapshot() {
        return new ArrayList<>(replayBuffer);
    }
}
