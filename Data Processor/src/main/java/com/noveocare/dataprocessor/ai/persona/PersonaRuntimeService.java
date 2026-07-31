package com.noveocare.dataprocessor.ai.persona;

import com.noveocare.dataprocessor.config.AiPersonaProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Stub persona runtime service. Currently disabled; always returns a
 * "persona_disabled" assignment.
 */
@Service
@RequiredArgsConstructor
public class PersonaRuntimeService {

    /* ---- Dependencies ---- */
    private final AiPersonaProperties personaProperties;

    /* ========== Public API ========== */

    /* Persona assignment is currently disabled; returns the disabled state. */
    public PersonaAssignment assign(double[] embedding) {
        return disabled();
    }

    /* Returns an unknown/disabled persona assignment with a specific warning. */
    public PersonaAssignment unknown(String warning) {
        return disabled();
    }

    /* Returns the default disabled persona assignment. */
    public PersonaAssignment disabled() {
        return PersonaAssignment.builder()
                .clusterId(-1)
                .label("persona_disabled")
                .confidence(0.0)
                .source("disabled_v3_6_refactor")
                .warnings(List.of("persona_skipped_for_now", personaProperties.getSkippedReason()))
                .build();
    }
}
