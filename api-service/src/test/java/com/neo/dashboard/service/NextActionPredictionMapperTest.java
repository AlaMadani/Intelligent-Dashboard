package com.neo.dashboard.service;

import com.neo.dashboard.dto.NextActionPredictionDto;
import com.neo.dashboard.entity.NextActionPrediction;
import com.neo.dashboard.mapper.NextActionPredictionMapper;
import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;

/**
 * Unit tests for next-action prediction mapping logic.
 */
class NextActionPredictionMapperTest {

    private final NextActionPredictionMapper mapper = Mappers.getMapper(NextActionPredictionMapper.class);

    @Test
    void toDtoParsesTopActionsJson() {
        NextActionPrediction entity = Instancio.of(NextActionPrediction.class)
                .set(field(NextActionPrediction::getTop3ActionsJson), "[\"VERIFY\",\"UPLOAD\",\"SUBMIT\"]")
                .create();

        NextActionPredictionDto dto = mapper.toDto(entity);

        assertThat(dto.getTop3Actions()).containsExactly("VERIFY", "UPLOAD", "SUBMIT");
    }

    @Test
    void toDtoReturnsEmptyActionsWhenJsonIsInvalid() {
        NextActionPrediction entity = Instancio.of(NextActionPrediction.class)
                .set(field(NextActionPrediction::getTop3ActionsJson), "{broken}")
                .create();

        NextActionPredictionDto dto = mapper.toDto(entity);

        assertThat(dto.getTop3Actions()).isEmpty();
    }
}

