package com.neo.dashboard.service;

import com.neo.dashboard.dto.UserRiskProfileDto;
import com.neo.dashboard.mapper.UserRiskProfileMapper;
import com.neo.dashboard.redis.CacheKeys;
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
    private final UserRiskProfileMapper userRiskProfileMapper;

    /*
     * Return the latest risk profile for one insured id.
     * Redis is checked first without opening a JDBC transaction.  The SQL
     * fallback is isolated so a database connection is only acquired when the
     * cache is cold.
     */
    public Optional<UserRiskProfileDto> getRiskProfile(String insuredId) {
        String key = CacheKeys.riskKey(insuredId);
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
        return findRiskProfileFromDb(insuredId);
    }

    @Transactional(readOnly = true)
    Optional<UserRiskProfileDto> findRiskProfileFromDb(String insuredId) {
        return repository.findByInsuredId(insuredId).map(userRiskProfileMapper::toDto);
    }
}
