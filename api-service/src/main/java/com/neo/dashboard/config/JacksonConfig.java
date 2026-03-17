package com.neo.dashboard.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // Indispensable pour que Jackson comprenne ton type "Instant" dans AuditTrailEvent
        mapper.registerModule(new JavaTimeModule());

        // (Optionnel) Pour éviter les erreurs si Redis contient des champs que tu n'as pas mis dans ton DTO
        // mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        return mapper;
    }
}