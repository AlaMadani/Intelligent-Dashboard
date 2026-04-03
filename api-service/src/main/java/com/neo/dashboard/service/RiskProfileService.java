package com.neo.dashboard.service;

import com.neo.dashboard.dto.UserRiskProfileDto;
import com.neo.dashboard.entity.UserRiskProfile;
import com.neo.dashboard.repository.UserRiskProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * Provides the insured user's current risk profile from Redis when available,
 * otherwise from the SQL snapshot table.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RiskProfileService {

    /* Redis offers the freshest profile; SQL is the durable fallback. */
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final UserRiskProfileRepository repository;

    /* Return the latest risk profile for one insured id. */
    @Transactional(readOnly = true)
    public Optional<UserRiskProfileDto> getRiskProfile(String insuredId) {
        String key = "risk:" + insuredId;
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null && !cached.isBlank()) {
            try {
                // Cached profiles are stored directly in the DTO shape.
                return Optional.of(objectMapper.readValue(cached, UserRiskProfileDto.class));
            } catch (Exception e) {
                log.warn("Failed to parse cached risk profile for insuredId {}", insuredId, e);
            }
        }

        // Fall back to the latest relational snapshot when Redis has no usable entry.
        return repository.findByInsuredId(insuredId).map(this::toDto);
    }

    /* Flatten the persistence entity into the DTO exposed by the API. */
    UserRiskProfileDto toDto(UserRiskProfile entity) {
        return new UserRiskProfileDto(
                entity.getId(),
                entity.getInsuredId(),
                entity.getLastUpdated(),
                entity.getAnomalyCount7d(),
                entity.getAnomalyCount30d(),
                entity.getLastAnomalyType(),
                entity.getRiskTier(),
                entity.getAnomalyRate30d(),
                entity.getSessions7d(),
                entity.getSessions30d(),
                entity.getMostFrequentAction30d(),
                entity.getAvgSessionDuration30d(),
                entity.getConsecutiveCleanSessions()
        );
    }
}
