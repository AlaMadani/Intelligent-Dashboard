package com.neo.dashboard.service;

import com.neo.dashboard.dto.SessionAnalysisDto;
import com.neo.dashboard.entity.SessionAnalysis;
import com.neo.dashboard.mapper.SessionAnalysisMapper;
import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;

/**
 * Unit tests for session mapping logic.
 */
class SessionAnalysisServiceTest {

    private final SessionAnalysisMapper mapper = Mappers.getMapper(SessionAnalysisMapper.class);

    @Test
    void toDtoParsesJsonFields() {
        SessionAnalysis entity = Instancio.of(SessionAnalysis.class)
                .set(field(SessionAnalysis::getActionCountsJson), "{\"LOGIN\":2,\"LOGOUT\":1}")
                .set(field(SessionAnalysis::getTop3NextActions), "[\"A\",\"B\",\"C\"]")
                .create();

        SessionAnalysisDto dto = mapper.toDto(entity);

        assertThat(dto.getActionCounts()).containsEntry("LOGIN", 2L);
        assertThat(dto.getTop3NextActions()).containsExactly("A", "B", "C");
    }

    @Test
    void toDtoReturnsEmptyCollectionsWhenJsonIsInvalid() {
        SessionAnalysis entity = Instancio.of(SessionAnalysis.class)
                .set(field(SessionAnalysis::getActionCountsJson), "{invalid}")
                .set(field(SessionAnalysis::getTop3NextActions), "not-json")
                .create();

        SessionAnalysisDto dto = mapper.toDto(entity);

        assertThat(dto.getActionCounts()).isEmpty();
        assertThat(dto.getTop3NextActions()).isEmpty();
    }
}
