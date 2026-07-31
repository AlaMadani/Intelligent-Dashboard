package com.neo.dashboard.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides an ObjectMapper bean for services that parse Redis/HTTP payloads.
 * Uses Jackson 2.x (com.fasterxml.jackson) matching Spring Boot 3.x default.
 */
@Configuration
public class JacksonConfig {

    /**
     * Provides a shared {@link ObjectMapper} configured for the application's
     * JSON serialisation needs — Java 8 date/time support and ISO-8601 date
     * formatting instead of numeric timestamps.
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        /* Register module that handles LocalDate, LocalDateTime, etc. */
        mapper.registerModule(new JavaTimeModule());
        /* Write dates as ISO-8601 strings rather than milliseconds-since-epoch */
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
