package com.neo.dashboard.service;

import com.neo.dashboard.dto.NextActionPredictionDto;
import com.neo.dashboard.entity.NextActionPrediction;
import com.neo.dashboard.repository.NextActionPredictionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class NextActionPredictionService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final NextActionPredictionRepository repository;

    @Transactional(readOnly = true)
    public Optional<NextActionPredictionDto> getPrediction(String insuredId) {
        String key = "next_actions:" + insuredId;
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null && !cached.isBlank()) {
            try {
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

        return repository.findByInsuredId(insuredId).map(this::toDto);
    }

    NextActionPredictionDto toDto(NextActionPrediction entity) {
        return new NextActionPredictionDto(
                entity.getId(),
                entity.getInsuredId(),
                entity.getSessionId(),
                entity.getPredictedAt(),
                parseActions(entity.getTop3ActionsJson())
        );
    }

    private List<String> parseActions(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }

        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse top3_actions_json", e);
            return Collections.emptyList();
        }
    }
}
