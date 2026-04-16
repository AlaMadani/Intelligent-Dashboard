package com.neo.dashboard.service;

import com.neo.dashboard.dto.AnomalyAlertDto;
import com.neo.dashboard.mapper.AnomalyAlertMapper;
import com.neo.dashboard.repository.AnomalyEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * Resolves the current active anomaly for an insured user, preferring Redis
 * for low-latency reads and falling back to the latest persisted event.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActiveAnomalyService {

    /* Cache access, JSON parsing, and persistence fallback. */
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AnomalyEventRepository anomalyEventRepository;
    private final AnomalyAlertMapper anomalyAlertMapper;

    /* Return the current anomaly marker, ignoring empty or "UNKNOWN" placeholders. */
    @Transactional(readOnly = true)
    public Optional<AnomalyAlertDto> getActiveAnomaly(String insuredId) {
        String key = "anomaly:active:" + insuredId;
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null && !cached.isBlank()) {
            try {
                // The Redis payload already matches the API DTO shape.
                AnomalyAlertDto alert = objectMapper.readValue(cached, AnomalyAlertDto.class);
                if (isUnknown(alert)) {
                    return Optional.empty();
                }
                return Optional.of(alert);
            } catch (Exception e) {
                log.warn("Failed to parse cached active anomaly for insuredId {}", insuredId, e);
            }
        }

        // Fall back to the freshest persisted anomaly event when the cache is cold or invalid.
        return anomalyEventRepository.findTopByInsuredIdOrderByDetectedAtDesc(insuredId)
                .map(anomalyAlertMapper::toDto)
                .filter(alert -> !isUnknown(alert));
    }

    /* Treat missing or placeholder anomaly types as "no active alert". */
    private boolean isUnknown(AnomalyAlertDto alert) {
        return alert == null
                || alert.getAnomalyType() == null
                || "UNKNOWN".equalsIgnoreCase(alert.getAnomalyType());
    }
}
