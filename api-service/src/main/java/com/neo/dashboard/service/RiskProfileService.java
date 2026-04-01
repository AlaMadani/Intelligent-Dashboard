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

@Service
@RequiredArgsConstructor
@Slf4j
public class RiskProfileService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final UserRiskProfileRepository repository;

    @Transactional(readOnly = true)
    public Optional<UserRiskProfileDto> getRiskProfile(String insuredId) {
        String key = "risk:" + insuredId;
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null && !cached.isBlank()) {
            try {
                return Optional.of(objectMapper.readValue(cached, UserRiskProfileDto.class));
            } catch (Exception e) {
                log.warn("Failed to parse cached risk profile for insuredId {}", insuredId, e);
            }
        }

        return repository.findByInsuredId(insuredId).map(this::toDto);
    }

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
