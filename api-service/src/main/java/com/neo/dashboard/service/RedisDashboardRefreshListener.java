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

    /** JSON parser for extracting the refresh target from incoming message payloads. */
    private final ObjectMapper objectMapper;
    /** SSE streaming service that pushes refresh events to connected frontend clients. */
    private final LiveStatsStreamService liveStatsStreamService;
    /** Service that reads live statistics from Redis to broadcast when a stats refresh is requested. */
    private final StatsService statsService;

    /**
     * Handles an incoming Redis Pub/Sub message: decodes the payload, resolves
     * the refresh target, and broadcasts the appropriate SSE event(s) to all
     * connected frontend clients.
     *
     * @param message the raw Redis message containing the payload bytes
     * @param pattern the channel pattern the message was received on
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        /* Resolve the raw refresh target from the JSON payload, then normalize it. */
        String originalRefresh = resolveRefreshTarget(payload);
        String refresh = normalizeRefreshTarget(originalRefresh);
        if (originalRefresh == null && refresh == null) {
            return;
        }

        try {
            /* If the refresh is for stats, fetch live stats and broadcast them. */
            if ("stats".equalsIgnoreCase(originalRefresh) || "stats".equalsIgnoreCase(refresh)) {
                liveStatsStreamService.broadcastStats(statsService.getLiveStats(LocalDate.now(ZoneOffset.UTC)));
            }
            /* Always broadcast the original (unnormalized) refresh target. */
            if (originalRefresh != null) {
                liveStatsStreamService.broadcastRefresh(originalRefresh);
            }
            /* If the normalized target is different, broadcast it as well so both names work. */
            if (refresh == null || refresh.equals(originalRefresh)) {
                return;
            }
            liveStatsStreamService.broadcastRefresh(refresh);
        } catch (Exception ex) {
            log.warn("Dashboard refresh broadcast failed", ex);
        }
    }

    /**
     * Parses the incoming JSON payload and extracts the {@code refresh} field
     * value that identifies which dashboard section should be refreshed.
     *
     * @param payload the raw JSON string received from Redis
     * @return the refresh target string, or {@code null} if absent or unparseable
     */
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

    /**
     * Normalizes variant refresh-target names to a canonical dashboard section
     * name so that the frontend can handle a consistent set of values.
     *
     * @param refresh the raw refresh target string
     * @return the canonical section name, or the original value if no mapping exists
     */
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
