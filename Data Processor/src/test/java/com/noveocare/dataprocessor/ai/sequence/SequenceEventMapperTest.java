package com.noveocare.dataprocessor.ai.sequence;

import com.noveocare.dataprocessor.ai.V34TestArtifacts;
import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactService;
import com.noveocare.dataprocessor.config.AiSequenceProperties;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SequenceEventMapperTest {

    private RuntimeArtifactService artifactService;

    @BeforeEach
    void setUp() throws Exception {
        artifactService = V34TestArtifacts.loadedArtifactService();
    }

    @Test
    void mapsRawAuditDatasetFieldsInExactTrainedOrder() {
        SequenceEventMapper mapper = new SequenceEventMapper(artifactService, sequenceProperties(true, false));
        Instant timestamp = Instant.parse("2026-01-05T09:30:00Z");
        AuditTrailEvent event = completeEvent(timestamp);

        SequenceEventValues values = mapper.map(event, timestamp.minusMillis(5_000));

        assertThat(values.isSchemaValid()).isTrue();
        assertThat(values.getCategoricalColumns()).containsExactlyElementsOf(artifactService.getSequenceMetadata().getCatCols());
        assertThat(values.getContinuousColumns()).containsExactlyElementsOf(artifactService.getSequenceMetadata().getContCols());
        assertThat(values.getCategoricalValues()).containsEntry("page", "home_page");
        assertThat(values.getCategoricalValues()).containsEntry("frontend_action_name", "open_contract");
        assertThat(values.getCategoricalValues()).containsEntry("api_template", "/api/contracts/{id}");
        assertThat(values.getContinuousValuesRaw()).containsEntry("time_since_prev_action_ms", 5_000.0);
        assertThat(values.getContinuousValuesRaw()).containsEntry("request_data_size_bytes", 128.0);
        assertThat(values.getContinuousValuesRaw()).containsEntry("response_data_size_bytes", 512.0);
        assertThat(values.getContinuousValuesRaw()).containsEntry("is_business_hours", 1.0);
        assertThat(values.getContinuousValuesRaw()).containsEntry("is_weekend", 0.0);
    }

    @Test
    void strictSchemaRejectsMissingRawModelFieldWhenLegacyFallbackDisabled() {
        SequenceEventMapper mapper = new SequenceEventMapper(artifactService, sequenceProperties(true, false));
        AuditTrailEvent event = completeEvent(Instant.parse("2026-01-05T09:30:00Z"));
        event.setApiTemplate(null);
        event.setRoute("/legacy/route");

        SequenceEventValues values = mapper.map(event, null);

        assertThat(values.isSchemaValid()).isFalse();
        assertThat(values.getWarnings()).contains("missing_required_field_api_template");
        assertThat(values.getCategoricalValues().get("api_template")).isNull();
    }

    @Test
    void legacyFallbackIsOnlyUsedWhenExplicitlyEnabled() {
        SequenceEventMapper mapper = new SequenceEventMapper(artifactService, sequenceProperties(false, true));
        AuditTrailEvent event = completeEvent(Instant.parse("2026-01-05T09:30:00Z"));
        event.setApiTemplate(null);
        event.setRoute("/legacy/route");

        SequenceEventValues values = mapper.map(event, null);

        assertThat(values.isSchemaValid()).isTrue();
        assertThat(values.getCategoricalValues()).containsEntry("api_template", "/legacy/route");
        assertThat(values.getWarnings()).contains("legacy_fallback_api_template");
    }

    static AuditTrailEvent completeEvent(Instant timestamp) {
        AuditTrailEvent event = new AuditTrailEvent();
        event.setId("record-1");
        event.setInsuredId("insured-1");
        event.setSessionId("session-1");
        event.setCreatedAt(timestamp);
        event.setPage("home_page");
        event.setFrontendActionName("open_contract");
        event.setApiTemplate("/api/contracts/{id}");
        event.setActionValue("open_contract");
        event.setActionType("DOCUMENT");
        event.setActionSubtype("VIEW");
        event.setHttpMethod("GET");
        event.setStatus("OK");
        event.setDevice("desktop");
        event.setBrowser("chrome");
        event.setOs("windows");
        event.setIpCountry("FR");
        event.setController("ContractController");
        event.setApiFamily("contracts");
        event.setEnvironmentId("prod");
        event.setHour(9);
        event.setRawDayOfWeek(0);
        event.setIsBusinessHours(1);
        event.setIsWeekend(0);
        event.setTimeSincePrevActionMs(5_000L);
        event.setRequestDataSizeBytes(128L);
        event.setResponseDataSizeBytes(512L);
        return event;
    }

    private AiSequenceProperties sequenceProperties(boolean strict, boolean allowLegacy) {
        AiSequenceProperties properties = new AiSequenceProperties();
        properties.setStrictSchema(strict);
        properties.setAllowLegacyFallback(allowLegacy);
        return properties;
    }
}
