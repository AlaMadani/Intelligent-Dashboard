package com.neo.dashboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Boots the analytics API and lets Spring discover controllers, services,
 * repositories, and configuration from the root package.
 */
@SpringBootApplication
@EnableAsync
public class ApiServiceApplication {

    /* Delegate startup to Spring Boot's auto-configuration pipeline. */
    public static void main(String[] args) {
        SpringApplication.run(ApiServiceApplication.class, args);
    }
}
