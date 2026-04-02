package com.noveocare.dataprocessor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for the offline data-processing worker.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class DataProcessorApplication {

    public static void main(String[] args) {
        // Boot the worker with Kafka listeners, schedulers, and persistence adapters enabled.
        SpringApplication.run(DataProcessorApplication.class, args);
    }

}
