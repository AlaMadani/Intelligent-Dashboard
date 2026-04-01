package com.neo.dashboard.service;

import com.neo.dashboard.dto.SessionAnalysisDto;
import com.neo.dashboard.entity.SessionAnalysis;
import com.neo.dashboard.repository.SessionAnalysisRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SessionAnalysisServiceTest {

    @Test
    void toDtoParsesJsonFields() {
        ObjectMapper mapper = new ObjectMapper();
        SessionAnalysisService service = new SessionAnalysisService(mock(SessionAnalysisRepository.class), mapper);

        SessionAnalysis entity = new SessionAnalysis();
        entity.setId(42L);
        entity.setActionCountsJson("{\"LOGIN\":2,\"LOGOUT\":1}");
        entity.setTop3NextActions("[\"A\",\"B\",\"C\"]");

        SessionAnalysisDto dto = service.toDto(entity);

        assertThat(dto.getActionCounts()).containsEntry("LOGIN", 2L);
        assertThat(dto.getTop3NextActions()).containsExactly("A", "B", "C");
    }
}
