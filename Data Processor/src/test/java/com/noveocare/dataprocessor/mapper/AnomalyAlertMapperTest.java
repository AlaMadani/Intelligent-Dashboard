package com.noveocare.dataprocessor.mapper;

import com.noveocare.dataprocessor.dto.AnomalyAlert;
import com.noveocare.dataprocessor.entity.AnomalyEvent;
import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.instancio.Select.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for AnomalyAlertMapper: DTO/entity conversion, investigation payload
 * JSON serialization, and fallback for missing detected-at timestamps.
 */
class AnomalyAlertMapperTest {

    /* --- Fields --- */

    private final AnomalyAlertMapper mapper = Mappers.getMapper(AnomalyAlertMapper.class);

    /* --- Test methods: entity mapping --- */

    @Test
    void mapsAlertAndRawPayloadToEntity() {
        Instant detectedAt = Instant.parse("2026-04-01T10:15:30Z");
        AnomalyAlert alert = Instancio.of(AnomalyAlert.class)
                .set(field(AnomalyAlert::getInsuredId), "insured-123")
                .set(field(AnomalyAlert::getSessionId), "session-abc")
                .set(field(AnomalyAlert::getEventId), "event-xyz")
                .set(field(AnomalyAlert::getAnomalyTier), "TIER2")
                .set(field(AnomalyAlert::getAnomalyType), "geo_jump")
                .set(field(AnomalyAlert::getAnomalyScore), 0.93)
                .set(field(AnomalyAlert::getTypeConfidence), 0.82)
                .set(field(AnomalyAlert::getRuleType), "rapid_fire")
                .set(field(AnomalyAlert::getEventTime), Instant.parse("2026-04-01T10:15:00Z"))
                .set(field(AnomalyAlert::getDetectedAt), detectedAt)
                .create();

        String rawEventJson = "{\"k\":\"v\"}";
        AnomalyEvent entity = mapper.toEntity(alert, rawEventJson);

        assertNull(entity.getId());
        assertEquals(alert.getInsuredId(), entity.getInsuredId());
        assertEquals(alert.getSessionId(), entity.getSessionId());
        assertEquals(alert.getEventId(), entity.getEventId());
        assertEquals(alert.getEventTime(), entity.getEventTime());
        assertEquals(alert.getAnomalyTier(), entity.getAnomalyTier());
        assertEquals(alert.getAnomalyType(), entity.getAnomalyType());
        assertEquals(alert.getAnomalyScore(), entity.getAnomalyScore());
        assertEquals(alert.getTypeConfidence(), entity.getTypeConfidence());
        assertEquals(alert.getRuleType(), entity.getRuleType());
        assertEquals(rawEventJson, entity.getEventJson());
        assertEquals(detectedAt, entity.getDetectedAt());
    }

    /* --- Test methods: investigation payload --- */

    @Test
    void investigationPayloadJsonContainsEventIdMatchingRow() {
        Instant detectedAt = Instant.parse("2026-04-01T10:15:30Z");
        Map<String, Object> investigationPayload = new LinkedHashMap<>();
        investigationPayload.put("eventId", "event-xyz");
        investigationPayload.put("riskLevel", "CRITICAL");
        investigationPayload.put("finalRiskScore", 84.1);
        investigationPayload.put("anomalyType", "unknown_suspicious_behavior");
        investigationPayload.put("modelScores", Map.of("xgboostAnomalyScore", 0.95));
        investigationPayload.put("sessionEndReason", "TIMEOUT");
        investigationPayload.put("sessionEndedAt", "2026-04-01T10:20:00Z");

        AnomalyAlert alert = Instancio.of(AnomalyAlert.class)
                .set(field(AnomalyAlert::getEventId), "event-xyz")
                .set(field(AnomalyAlert::getInsuredId), "insured-123")
                .set(field(AnomalyAlert::getSessionId), "session-abc")
                .set(field(AnomalyAlert::getInvestigationPayload), investigationPayload)
                .set(field(AnomalyAlert::getDetectedAt), detectedAt)
                .create();

        AnomalyEvent entity = mapper.toEntity(alert, "{}");

        assertEquals("event-xyz", entity.getEventId());
        assertNotNull(entity.getInvestigationPayloadJson());
        assertTrue(entity.getInvestigationPayloadJson().contains("event-xyz"));
        assertTrue(entity.getInvestigationPayloadJson().contains("CRITICAL"));
        assertTrue(entity.getInvestigationPayloadJson().contains("TIMEOUT"));
    }

    @Test
    void investigationPayloadJsonIsNullWhenNotSet() {
        AnomalyAlert alert = Instancio.of(AnomalyAlert.class)
                .set(field(AnomalyAlert::getInvestigationPayload), null)
                .create();

        AnomalyEvent entity = mapper.toEntity(alert, "{}");
        assertNull(entity.getInvestigationPayloadJson());
    }

    /* --- Test methods: fallback --- */

    @Test
    void setsDetectedAtWhenMissingOnDto() {
        AnomalyAlert alert = Instancio.of(AnomalyAlert.class)
                .set(field(AnomalyAlert::getDetectedAt), null)
                .create();

        Instant before = Instant.now();
        AnomalyEvent entity = mapper.toEntity(alert, "{}");
        Instant after = Instant.now();

        assertNotNull(entity.getDetectedAt());
        assertTrue(!entity.getDetectedAt().isBefore(before));
        assertTrue(!entity.getDetectedAt().isAfter(after));
    }
}
