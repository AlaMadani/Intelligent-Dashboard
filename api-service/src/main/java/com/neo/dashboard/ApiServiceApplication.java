package com.neo.dashboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Boots the analytics API and lets Spring discover controllers, services,
 * repositories, and configuration from the root package.
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class ApiServiceApplication {

    /**
     * Entry point that delegates to {@link SpringApplication#run(Class, String[])}.
     * Spring Boot's auto-configuration scans the {@code com.neo.dashboard} package
     * tree for controllers, services, repositories, and configuration classes.
     */
    public static void main(String[] args) {
        /* Bootstrap the Spring application context. */
        SpringApplication.run(ApiServiceApplication.class, args);
    }
}
