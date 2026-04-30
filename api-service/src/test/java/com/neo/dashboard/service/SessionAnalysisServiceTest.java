package com.neo.dashboard.service;

import com.neo.dashboard.dto.NextActionScoreDto;
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
                .set(field(SessionAnalysis::getActionSequenceJson), "[\"LOGIN\",\"MFA\",\"HOME\"]")
                .set(field(SessionAnalysis::getRouteSequenceJson), "[\"/login\",\"/mfa\",\"/home\"]")
                .set(field(SessionAnalysis::getTop3NextActions), "[\"A\",\"B\",\"C\"]")
                .set(field(SessionAnalysis::getFeatureContributionsJson), """
                        [{"feature":"cumulativeKOs","importance":0.82,"actualValue":3,"description":"KO streak increased"}]
                        """)
                .set(field(SessionAnalysis::getWarningsJson), "[\"High error rate\"]")
                .set(field(SessionAnalysis::getTriggeredRulesJson), "[\"REPEATED_FAIL\"]")
                .set(field(SessionAnalysis::getContextTagsJson), "[\"IP Changed\",\"High Error Rate\"]")
                .set(field(SessionAnalysis::getRareTransitionsJson), """
                        [{"deviated":true,"fromAction":"MFA","toAction":"Download","transitionProbability":0.01}]
                        """)
                .create();

        SessionAnalysisDto dto = mapper.toDto(entity);

        assertThat(dto.getActionCounts()).containsEntry("LOGIN", 2L);
        assertThat(dto.getActionSequence()).containsExactly("LOGIN", "MFA", "HOME");
        assertThat(dto.getRouteSequence()).containsExactly("/login", "/mfa", "/home");
        assertThat(dto.getTop3NextActions()).extracting(NextActionScoreDto::getAction).containsExactly("A", "B", "C");
        assertThat(dto.getTopContributingFeatures()).hasSize(1);
        assertThat(dto.getTopContributingFeatures().getFirst().getFeature()).isEqualTo("cumulativeKOs");
        assertThat(dto.getWarnings()).containsExactly("High error rate");
        assertThat(dto.getTriggeredRules()).containsExactly("REPEATED_FAIL");
        assertThat(dto.getContextTags()).containsExactly("IP Changed", "High Error Rate");
        assertThat(dto.getRareTransitions()).hasSize(1);
        assertThat(dto.getRareTransitions().getFirst().getFromAction()).isEqualTo("MFA");
    }

    @Test
    void toDtoReturnsEmptyCollectionsWhenJsonIsInvalid() {
        SessionAnalysis entity = Instancio.of(SessionAnalysis.class)
                .set(field(SessionAnalysis::getActionCountsJson), "{invalid}")
                .set(field(SessionAnalysis::getActionSequenceJson), "{invalid}")
                .set(field(SessionAnalysis::getRouteSequenceJson), "{invalid}")
                .set(field(SessionAnalysis::getTop3NextActions), "not-json")
                .set(field(SessionAnalysis::getFeatureContributionsJson), "{invalid}")
                .set(field(SessionAnalysis::getWarningsJson), "{invalid}")
                .set(field(SessionAnalysis::getTriggeredRulesJson), "{invalid}")
                .set(field(SessionAnalysis::getContextTagsJson), "{invalid}")
                .set(field(SessionAnalysis::getRareTransitionsJson), "{invalid}")
                .create();

        SessionAnalysisDto dto = mapper.toDto(entity);

        assertThat(dto.getActionCounts()).isEmpty();
        assertThat(dto.getActionSequence()).isEmpty();
        assertThat(dto.getRouteSequence()).isEmpty();
        assertThat(dto.getTop3NextActions()).isEmpty();
        assertThat(dto.getTopContributingFeatures()).isEmpty();
        assertThat(dto.getWarnings()).isEmpty();
        assertThat(dto.getTriggeredRules()).isEmpty();
        assertThat(dto.getContextTags()).isEmpty();
        assertThat(dto.getRareTransitions()).isEmpty();
    }
}
