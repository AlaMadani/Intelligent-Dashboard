package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.dto.AnomalyAlertDto;
import com.neo.dashboard.dto.AnomalyEventDto;
import com.neo.dashboard.mapper.AnomalyEventMapper;
import com.neo.dashboard.repository.AnomalyEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * Listens to Redis Pub/Sub channels for critical alerts and broadcasts them via WebSocket.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisAlertListener implements MessageListener {

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    private final AnomalyEventRepository anomalyEventRepository;
    private final AnomalyEventMapper anomalyEventMapper;

    private static final String[] ALERT_CHANNELS = {
            "CRITICAL_ALERT",
            "SYSTEM_TRAFFIC_ANOMALY"
    };

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);

        log.debug("Received Redis Pub/Sub message on channel {}: {}", channel, payload);

        try {
            AnomalyAlertDto alert = objectMapper.readValue(payload, AnomalyAlertDto.class);
            AnomalyEventDto event = resolveBroadcastEvent(alert);
            messagingTemplate.convertAndSend("/topic/alerts", event);
        } catch (Exception e) {
            log.warn("Failed to parse or broadcast Redis message: {}", payload, e);
        }
    }

    public String[] getAlertChannels() {
        return ALERT_CHANNELS;
    }

    private AnomalyEventDto resolveBroadcastEvent(AnomalyAlertDto alert) {
        if (alert == null) {
            return null;
        }
        if (hasCorrelationIds(alert)) {
            return anomalyEventRepository.findTopByInsuredIdAndSessionIdAndEventIdOrderByDetectedAtDesc(
                            alert.getInsuredId(),
                            alert.getSessionId(),
                            alert.getEventId())
                    .map(anomalyEventMapper::toDto)
                    .orElseGet(() -> anomalyEventMapper.fromAlert(alert));
        }
        return anomalyEventMapper.fromAlert(alert);
    }

    private boolean hasCorrelationIds(AnomalyAlertDto alert) {
        return hasText(alert.getInsuredId()) && hasText(alert.getSessionId()) && hasText(alert.getEventId());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
