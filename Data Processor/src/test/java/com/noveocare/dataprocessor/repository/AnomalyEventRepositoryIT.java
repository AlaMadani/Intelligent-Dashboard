package com.noveocare.dataprocessor.repository;

import com.noveocare.dataprocessor.entity.AnomalyEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration test for AnomalyEventRepository queries against a real SQL Server container
 * using Testcontainers, validating correct ordering and filtering of anomaly events.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.liquibase.enabled=true",
        "spring.main.web-application-type=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class AnomalyEventRepositoryIT {

    /* --- Testcontainers setup --- */

    @Container
    static final MSSQLServerContainer<?> SQL_SERVER = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
            .acceptLicense();

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SQL_SERVER::getJdbcUrl);
        registry.add("spring.datasource.username", SQL_SERVER::getUsername);
        registry.add("spring.datasource.password", SQL_SERVER::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.microsoft.sqlserver.jdbc.SQLServerDriver");
    }

    /* --- Fields --- */

    @Autowired
    private AnomalyEventRepository anomalyEventRepository;

    /* --- Test methods --- */

    @Test
    void returnsLatestAnomaliesForInsured() {
        AnomalyEvent older = anomaly("insured-a", Instant.parse("2026-04-01T10:00:00Z"));
        AnomalyEvent newer = anomaly("insured-a", Instant.parse("2026-04-01T11:00:00Z"));
        AnomalyEvent other = anomaly("insured-b", Instant.parse("2026-04-01T12:00:00Z"));
        anomalyEventRepository.saveAll(List.of(older, newer, other));

        List<AnomalyEvent> results = anomalyEventRepository.findTop100ByInsuredIdOrderByDetectedAtDesc("insured-a");

        assertEquals(2, results.size());
        assertEquals(newer.getDetectedAt(), results.get(0).getDetectedAt());
        assertEquals(older.getDetectedAt(), results.get(1).getDetectedAt());
    }

    /* --- Helper methods --- */

    private AnomalyEvent anomaly(String insuredId, Instant detectedAt) {
        AnomalyEvent event = new AnomalyEvent();
        event.setInsuredId(insuredId);
        event.setSessionId("session-" + detectedAt.toEpochMilli());
        event.setEventId("event-" + detectedAt.toEpochMilli());
        event.setEventTime(detectedAt.minusSeconds(10));
        event.setAnomalyTier("TIER1");
        event.setAnomalyType("rapid_fire");
        event.setAnomalyScore(0.8);
        event.setTypeConfidence(0.9);
        event.setRuleType("rapid_fire");
        event.setEventJson("{}");
        event.setDetectedAt(detectedAt);
        return event;
    }
}
