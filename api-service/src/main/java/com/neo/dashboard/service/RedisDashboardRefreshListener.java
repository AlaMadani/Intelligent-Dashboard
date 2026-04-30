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
        String refresh = resolveRefreshTarget(payload);
        if (refresh == null) {
            return;
        }

        if ("stats".equalsIgnoreCase(refresh)) {
            liveStatsStreamService.broadcastStats(statsService.getLiveStats(LocalDate.now(ZoneOffset.UTC)));
        }
        liveStatsStreamService.broadcastRefresh(refresh);
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
}
