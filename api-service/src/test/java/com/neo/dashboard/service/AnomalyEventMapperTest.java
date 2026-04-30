package com.neo.dashboard.service;

import com.neo.dashboard.dto.AnomalyEventDto;
import com.neo.dashboard.dto.NextActionScoreDto;
import com.neo.dashboard.entity.AnomalyEvent;
import com.neo.dashboard.mapper.AnomalyEventMapper;
import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;

/**
 * Unit tests for anomaly-event mapping logic.
 */
class AnomalyEventMapperTest {

    private final AnomalyEventMapper mapper = Mappers.getMapper(AnomalyEventMapper.class);

    @Test
    void toDtoParsesNextActionsAndCompactContextJson() {
        AnomalyEvent entity = Instancio.of(AnomalyEvent.class)
                .set(field(AnomalyEvent::getNextActionsJson), "[\"VERIFY\",\"UPLOAD\"]")
                .set(field(AnomalyEvent::getEventJson), """
                        {"lastAction":"UPLOAD","contextTags":["IP Changed"],"explainabilityText":"Suspicious jump"}
                        """)
                .create();

        AnomalyEventDto dto = mapper.toDto(entity);

        assertThat(dto.getNextActions()).extracting(NextActionScoreDto::getAction).containsExactly("VERIFY", "UPLOAD");
        assertThat(dto.getEventContext()).isNotNull();
        assertThat(dto.getEventContext().path("lastAction").asText()).isEqualTo("UPLOAD");
        assertThat(dto.getEventContext().path("contextTags")).hasSize(1);
    }
}
