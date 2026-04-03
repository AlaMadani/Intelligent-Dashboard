package com.neo.dashboard.service;

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
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pushes live stats snapshots to connected dashboard clients over SSE on a
 * fixed schedule.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LiveStatsStreamService {

    /* Source of stats snapshots plus the current set of SSE subscribers. */
    private final StatsService statsService;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /* Register a new subscriber and send the current snapshot immediately. */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(60L * 60 * 1000); // 1 hour timeout
        this.emitters.add(emitter);

        // Remove dead emitters regardless of how the connection ends.
        emitter.onCompletion(() -> this.emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            this.emitters.remove(emitter);
        });
        emitter.onError(e -> {
            log.error("Live stats SSE error", e);
            this.emitters.remove(emitter);
        });

        // Send an initial snapshot immediately so the UI does not wait for the next scheduler tick.
        try {
            emitter.send(SseEmitter.event()
                    .name("stats")
                    .data(statsService.getLiveStats(LocalDate.now(ZoneOffset.UTC))));
        } catch (IOException e) {
            emitter.completeWithError(e);
            this.emitters.remove(emitter);
        }

        return emitter;
    }

    /* Periodically push the latest live stats snapshot to all connected clients. */
    @Scheduled(fixedRate = 10000)
    public void pushStats() {
        if (emitters.isEmpty()) {
            // Skip the Redis lookup when no clients are listening.
            return;
        }

        StatsResponseDto stats = statsService.getLiveStats(LocalDate.now(ZoneOffset.UTC));

        // Collect failed emitters and remove them after the broadcast loop.
        List<SseEmitter> deadEmitters = new java.util.ArrayList<>();

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("stats")
                        .data(stats));
            } catch (Exception e) {
                deadEmitters.add(emitter);
            }
        }

        emitters.removeAll(deadEmitters);
    }
}
