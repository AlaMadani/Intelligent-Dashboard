package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Bridges Redis dashboard refresh notifications into the SSE live stream used
 * by the frontend.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisDashboardRefreshListener implements MessageListener {

    private final ObjectMapper objectMapper;
    private final LiveStatsStreamService liveStatsStreamService;
    private final StatsService statsService;

@Override
    public void onMessage(Message message, byte[] pattern) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        String originalRefresh = resolveRefreshTarget(payload);
        String refresh = normalizeRefreshTarget(originalRefresh);
        if (originalRefresh == null && refresh == null) {
            return;
        }

        try {
            if ("stats".equalsIgnoreCase(originalRefresh) || "stats".equalsIgnoreCase(refresh)) {
                liveStatsStreamService.broadcastStats(statsService.getLiveStats(LocalDate.now(ZoneOffset.UTC)));
            }
            if (originalRefresh != null) {
                liveStatsStreamService.broadcastRefresh(originalRefresh);
            }
            if (refresh == null || refresh.equals(originalRefresh)) {
                return;
            }
            liveStatsStreamService.broadcastRefresh(refresh);
        } catch (Exception ex) {
            log.warn("Dashboard refresh broadcast failed", ex);
        }
    }

    private String resolveRefreshTarget(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode refresh = node.path("refresh");
            if (refresh.isTextual() && !refresh.asText().isBlank()) {
                return refresh.asText();
            }
        } catch (Exception ex) {
            log.warn("Failed to parse dashboard refresh payload {}", payload, ex);
        }
        return null;
    }

    private String normalizeRefreshTarget(String refresh) {
        if (refresh == null || refresh.isBlank()) {
            return null;
        }
        return switch (refresh.toLowerCase()) {
            case "forecasts", "forecast-series" -> "forecast";
            case "security", "security_overview", "security-overview" -> "security-overview";
            case "runtime", "runtime_health", "runtime-health", "ai-runtime-health" -> "runtime-health";
            case "critical-alerts", "live-alerts" -> "alerts";
            default -> refresh;
        };
    }
}
