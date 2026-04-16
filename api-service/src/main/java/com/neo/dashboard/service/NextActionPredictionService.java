package com.neo.dashboard.service;

import com.neo.dashboard.dto.NextActionPredictionDto;
import com.neo.dashboard.mapper.NextActionPredictionMapper;
import com.neo.dashboard.repository.NextActionPredictionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Serves "next best action" predictions, preferring the low-latency Redis
 * representation and falling back to the SQL snapshot.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NextActionPredictionService {

    /* Cache access, JSON parsing, and persistence fallback. */
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final NextActionPredictionRepository repository;
    private final NextActionPredictionMapper nextActionPredictionMapper;

    /* Return the current next-action prediction for one insured id. */
    @Transactional(readOnly = true)
    public Optional<NextActionPredictionDto> getPrediction(String insuredId) {
        String key = "next_actions:" + insuredId;
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null && !cached.isBlank()) {
            try {
                // Redis only stores the ordered action labels, so missing metadata stays null here.
                List<String> actions = objectMapper.readValue(cached, new TypeReference<List<String>>() {});
                return Optional.of(new NextActionPredictionDto(
                        null,
                        insuredId,
                        null,
                        null,
                        actions == null ? Collections.emptyList() : actions
                ));
            } catch (Exception e) {
                log.warn("Failed to parse cached next actions for insuredId {}", insuredId, e);
            }
        }

        // The SQL snapshot includes metadata such as prediction time and source session.
        return repository.findByInsuredId(insuredId).map(nextActionPredictionMapper::toDto);
    }
}
