package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.dto.StatsApiResponseDto;
import com.neo.dashboard.dto.StatsResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pushes live stats snapshots to connected dashboard clients over SSE on a
 * fixed schedule. Also emits per-page refresh events so the frontend
 * auto-updates without a Redis/Kafka trigger.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LiveStatsStreamService {

    /** Provides the latest stats snapshot to be broadcast. */
    private final StatsService statsService;
    /** Serialises stats payloads to safe JSON trees. */
    private final ObjectMapper objectMapper;
    /** Thread-safe list of currently connected SSE emitters. */
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    /** Dashboard views that receive an explicit refresh event on each tick. */
    private static final Set<String> V36_REFRESH_EVENTS = Set.of(
            "alerts",
            "security-overview",
            "runtime-health",
            "churn",
            "forecast"
    );

    /**
     * Registers a new SSE subscriber, sends the current stats snapshot
     * immediately, and sets up automatic cleanup on completion/timeout/error.
     *
     * @return the SseEmitter for the newly connected client
     */
    public SseEmitter subscribe() {
        // Create an emitter with a 1-hour timeout
        SseEmitter emitter = new SseEmitter(60L * 60 * 1000);
        this.emitters.add(emitter);

        // Register lifecycle callbacks to clean up after disconnection
        emitter.onCompletion(() -> this.emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            this.emitters.remove(emitter);
        });
        emitter.onError(e -> {
            if (isClientDisconnect(e)) {
                log.debug("Live stats SSE client disconnected");
            } else {
                log.warn("Live stats SSE error", e);
            }
            this.emitters.remove(emitter);
        });

        // Send an initial snapshot so the UI does not wait for the next scheduled tick
        try {
            emitter.send(SseEmitter.event()
                    .name("stats")
                    .data(toSafeStats(statsService.getLiveStats(LocalDate.now(ZoneOffset.UTC)))));
        } catch (IOException e) {
            // If the initial send fails, clean up the emitter
            if (isClientDisconnect(e)) {
                emitter.complete();
            } else {
                emitter.completeWithError(e);
            }
            this.emitters.remove(emitter);
        }

        return emitter;
    }

    /**
     * Periodically (every 10 s) fetches live stats and pushes them to all
     * connected SSE clients, plus emits a refresh event per dashboard page.
     */
    @Scheduled(fixedRate = 10000)
    public void pushStats() {
        // Skip entirely when there are no listeners
        if (emitters.isEmpty()) {
            return;
        }

        // Broadcast the latest stats snapshot
        broadcastStats(statsService.getLiveStats(LocalDate.now(ZoneOffset.UTC)));

        // Emit per-page refresh events so the frontend auto-updates
        log.debug("pushStats: broadcasting {} refresh events", V36_REFRESH_EVENTS.size());
        for (String event : V36_REFRESH_EVENTS) {
            log.debug("pushStats: broadcasting refresh event '{}'", event);
            broadcastRefresh(event);
        }
    }

    /**
     * Sends a stats snapshot to all connected clients under the {@code stats}
     * event name.
     */
    public void broadcastStats(StatsResponseDto stats) {
        if (stats == null) {
            return;
        }
        broadcast("stats", toSafeStats(stats));
    }

    /**
     * Sends a named refresh event to all connected clients. If the event name
     * is one of the known V3.6 views, also emits a dedicated event so the
     * frontend can listen specifically.
     */
    public void broadcastRefresh(String refresh) {
        if (refresh == null || refresh.isBlank()) {
            return;
        }
        broadcast("refresh", java.util.Map.of("refresh", refresh));
        if (V36_REFRESH_EVENTS.contains(refresh)) {
            broadcast(refresh, java.util.Map.of("refresh", refresh));
        }
    }

    /**
     * Sends an SSE event with the given name and payload to every connected
     * emitter. Dead emitters are collected and removed after the iteration.
     */
    private void broadcast(String eventName, Object payload) {
        if (emitters.isEmpty()) {
            return;
        }

        List<SseEmitter> deadEmitters = new java.util.ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(payload));
            } catch (Exception e) {
                // Any send failure marks the emitter as stale
                deadEmitters.add(emitter);
            }
        }
        emitters.removeAll(deadEmitters);
    }

    /**
     * Walks the exception cause chain to determine whether the error is a
     * client-side disconnection rather than a server-side failure.
     */
    private boolean isClientDisconnect(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String className = current.getClass().getName();
            String message = current.getMessage();
            if (className.contains("AsyncRequestNotUsableException")
                    || className.contains("ClientAbortException")
                    || current instanceof IOException
                    || (message != null && (message.contains("aborted")
                    || message.contains("disconnect")
                    || message.contains("Broken pipe")
                    || message.contains("Une connexion")
                    || message.contains("client")))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Converts a stats response's payload (a {@link
     * com.fasterxml.jackson.databind.JsonNode}) into a plain {@link Object},
     * falling back to the raw node on failure.
     */
    private StatsApiResponseDto toSafeStats(StatsResponseDto stats) {
        Object payload = null;
        if (stats.getPayload() != null) {
            try {
                payload = objectMapper.treeToValue(stats.getPayload(), Object.class);
            } catch (Exception e) {
                log.warn("Failed to convert stats payload to safe object", e);
                payload = stats.getPayload();
            }
        }
        return new StatsApiResponseDto(stats.getDate(), stats.getSource(), payload);
    }
}
