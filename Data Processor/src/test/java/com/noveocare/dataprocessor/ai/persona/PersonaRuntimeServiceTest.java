package com.noveocare.dataprocessor.ai.persona;

import com.noveocare.dataprocessor.config.AiPersonaProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PersonaRuntimeServiceTest {

    @Test
    void missingEmbeddingReturnsDisabledPersona() {
        PersonaRuntimeService service = new PersonaRuntimeService(new AiPersonaProperties());

        PersonaAssignment assignment = service.assign(null);

        assertThat(assignment.getClusterId()).isEqualTo(-1);
        assertThat(assignment.getLabel()).isEqualTo("persona_disabled");
        assertThat(assignment.getSource()).isEqualTo("disabled_v3_6_refactor");
        assertThat(assignment.getWarnings()).contains("persona_skipped_for_now");
    }

    @Test
    void suppliedEmbeddingIsStillSkippedForV36Refactor() {
        PersonaAssignment assignment = new PersonaRuntimeService(new AiPersonaProperties()).assign(new double[]{1.0, 2.0});

        assertThat(assignment.getClusterId()).isEqualTo(-1);
        assertThat(assignment.getLabel()).isEqualTo("persona_disabled");
        assertThat(assignment.getSource()).isEqualTo("disabled_v3_6_refactor");
    }
}
