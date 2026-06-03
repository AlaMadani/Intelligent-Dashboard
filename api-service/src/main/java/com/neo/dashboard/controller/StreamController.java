package com.neo.dashboard.controller;

import com.neo.dashboard.service.LiveStatsStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class StreamController {

    private final LiveStatsStreamService liveStatsStreamService;

    @GetMapping(path = "/stream/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLive() {
        return liveStatsStreamService.subscribe();
    }
}