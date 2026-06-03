package com.noveocare.dataprocessor.ai.persona;

import com.noveocare.dataprocessor.config.AiPersonaProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PersonaRuntimeService {

    private final AiPersonaProperties personaProperties;

    public PersonaAssignment assign(double[] embedding) {
        return disabled();
    }

    public PersonaAssignment unknown(String warning) {
        return disabled();
    }

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
