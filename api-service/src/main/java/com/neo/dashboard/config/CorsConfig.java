package com.neo.dashboard.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * Configures global CORS for the servlet stack used by the dashboard API.
 */
@Configuration
public class CorsConfig {

    /** Origins parsed from configuration, falling back to localhost:9008 if not set. */
    private final List<String> allowedOriginPatterns;

    /**
     * Parses the comma-separated {@code app.cors.allowed-origins} property into a
     * trimmed, non-empty list of origin patterns.
     */
    public CorsConfig(@Value("${app.cors.allowed-origins:http://localhost:9008}") String allowedOrigins) {
        /* Split on comma, trim whitespace, and discard empty entries */
        this.allowedOriginPatterns = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
    }

    /** Registers a global CORS filter that applies to every incoming request. */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        /* Use configured origins; fall back to default if list is empty */
        config.setAllowedOriginPatterns(allowedOriginPatterns.isEmpty()
                ? List.of("http://localhost:9008")
                : allowedOriginPatterns);
        /* Allow standard REST + preflight methods */
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD"));
        /* Allow any request header */
        config.setAllowedHeaders(List.of("*"));
        /* Expose auth headers so clients can read them */
        config.setExposedHeaders(List.of("Authorization", "Content-Type"));
        /* Permit credentials (cookies, authorization headers) */
        config.setAllowCredentials(true);
        /* Cache preflight response for 1 hour */
        config.setMaxAge(3600L);

        /* Apply the CORS configuration to all paths */
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
