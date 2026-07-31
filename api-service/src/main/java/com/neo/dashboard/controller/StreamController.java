package com.neo.dashboard.controller;

import com.neo.dashboard.service.LiveStatsStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * REST controller for Server-Sent Events (SSE) streaming endpoints.
 * Provides real-time push of live statistics to connected clients
 * using the Spring SSE emitter mechanism.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class StreamController {

    /** Service managing SSE subscriptions and broadcasting live stats to subscribers. */
    private final LiveStatsStreamService liveStatsStreamService;

    /**
     * Subscribes to the live statistics event stream.
     * Clients receive a continuous flow of Server-Sent Events containing
     * real-time dashboard updates as they occur.
     *
     * @return an SseEmitter that pushes live stats events to the connected client
     */
    @GetMapping(path = "/stream/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLive() {
        return liveStatsStreamService.subscribe();
    }
}