package com.noveocare.dataprocessor.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.noveocare.dataprocessor.config.AiResourceProperties;
import com.noveocare.dataprocessor.config.FeatureEngineeringProperties;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Focused tests that validate the feature-engineering contract used by the sequence models.
 */
@SpringJUnitConfig(classes = FeatureEngineeringServiceTest.TestConfig.class)
class FeatureEngineeringServiceTest {

    @Configuration
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            // Match the production ObjectMapper configuration for Instant handling.
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            return mapper;
        }

        @Bean
        ResourceLoader resourceLoader() {
            return new DefaultResourceLoader();
        }

        @Bean
        AiResourceProperties aiResourceProperties() {
            // Point tests at the classpath copies of the AI metadata files.
            AiResourceProperties props = new AiResourceProperties();
            props.setBasePath("classpath:/AI/");
            AiResourceProperties.FilePaths files = new AiResourceProperties.FilePaths();
            files.setFeatureConfig("feature_config.json");
            files.setScalerDelta("scaler_delta.json");
            files.setActionVocab("action_vocab.json");
            files.setDeviceVocab("device_vocab.json");
            files.setCountryVocab("country_vocab.json");
            files.setTypeVocab("type_vocab.json");
            files.setSubtypeVocab("subtype_vocab.json");
            props.setFiles(files);
            return props;
        }

        @Bean
        FeatureConfigLoader featureConfigLoader(AiResourceProperties props, ResourceLoader loader, ObjectMapper mapper) {
            return new FeatureConfigLoader(props, loader, mapper);
        }

        @Bean
        DeltaScalerLoader deltaScalerLoader(AiResourceProperties props, ResourceLoader loader, ObjectMapper mapper) {
            return new DeltaScalerLoader(props, loader, mapper);
        }

        @Bean
        VocabService vocabService(AiResourceProperties props, ResourceLoader loader, ObjectMapper mapper) {
            return new VocabService(props, loader, mapper);
        }

        @Bean
        FeatureEngineeringService featureEngineeringService(FeatureConfigLoader featureConfigLoader,
                                                           DeltaScalerLoader deltaScalerLoader,
                                                           VocabService vocabService,
                                                           FeatureEngineeringProperties featureEngineeringProperties) {
            // Wire the service under test with the same collaborators it uses in production.
            return new FeatureEngineeringService(featureConfigLoader, deltaScalerLoader, vocabService, featureEngineeringProperties);
        }

        @Bean
        FeatureEngineeringProperties featureEngineeringProperties() {
            FeatureEngineeringProperties props = new FeatureEngineeringProperties();
            props.setDeltaClipSeconds(3600);
            return props;
        }
    }

    @jakarta.annotation.Resource
    private FeatureEngineeringService featureEngineeringService;

    @jakarta.annotation.Resource
    private FeatureConfigLoader featureConfigLoader;

    @jakarta.annotation.Resource
    private DeltaScalerLoader deltaScalerLoader;

    @jakarta.annotation.Resource
    private VocabService vocabService;

    private int idx(String name) {
        return featureConfigLoader.getFeatureConfig().getFeatureCols().indexOf(name);
    }

    @BeforeEach
    void initLoaders() throws Exception {
        // Explicitly load JSON resources because the test slices do not run full Spring startup hooks.
        featureConfigLoader.load();
        deltaScalerLoader.load();
        vocabService.load();
    }

    @Test
    void buildsMatrixWithMidSessionFeatures() {
        // Validate predecessor, position, and scaled-delta features for a middle event.
        Instant base = Instant.parse("2025-06-15T10:00:00Z");

        AuditTrailEvent e1 = baseEvent("Connexion", "OK", base, 1, 3, "1.1.1.1", "logging_login");
        AuditTrailEvent e2 = baseEvent("Validation MFA", "KO", base.plusSeconds(60), 2, 3, "1.1.1.1", "logging_mfa_validation");
        AuditTrailEvent e3 = baseEvent("Exporter remboursement", "OK", base.plusSeconds(120), 3, 3, "2.2.2.2", "export_refund");

        float[][] matrix = featureEngineeringService.buildFeatureMatrix(List.of(e1, e2, e3));
        int rowIndex = matrix.length - 2;

        int prevActionId = (int) matrix[rowIndex][idx("prev_action_id")];
        int expectedPrev = vocabService.actionId("Connexion") + 1;
        assertEquals(expectedPrev, prevActionId);

        float seqPos = matrix[rowIndex][idx("seq_pos_norm")];
        assertEquals(2.0f / 3.0f, seqPos, 0.0001f);

        double mean = deltaScalerLoader.getDeltaScaler().meanValue();
        double scale = deltaScalerLoader.getDeltaScaler().scaleValue();
        double expectedDeltaScaled = (60.0 - mean) / scale;
        assertEquals((float) expectedDeltaScaled, matrix[rowIndex][idx("delta_scaled")], 0.0001f);
    }

    @Test
    void buildsMatrixForSessionStart() {
        // The first event should expose start-of-session flags and zero previous-state features.
        Instant base = Instant.parse("2025-06-15T10:00:00Z");
        AuditTrailEvent e1 = baseEvent("Connexion", "OK", base, 1, 3, "1.1.1.1", "logging_login");

        float[][] matrix = featureEngineeringService.buildFeatureMatrix(List.of(e1));
        int rowIndex = matrix.length - 1;

        assertEquals(1.0f, matrix[rowIndex][idx("is_session_start")], 0.0001f);
        assertEquals(0.0f, matrix[rowIndex][idx("prev_action_id")], 0.0001f);
        assertEquals(0.0f, matrix[rowIndex][idx("ip_changed")], 0.0001f);
        assertEquals(0.0f, matrix[rowIndex][idx("ko_count_so_far")], 0.0001f);
    }

    @Test
    void handlesNullSubtype() {
        // Null subtype values should safely collapse to the fallback vocabulary id.
        Instant base = Instant.parse("2025-06-15T10:00:00Z");
        AuditTrailEvent e1 = baseEvent("Connexion", "OK", base, 1, 3, "1.1.1.1", null);
        e1.setSubType(null);

        float[][] matrix = featureEngineeringService.buildFeatureMatrix(List.of(e1));
        int rowIndex = matrix.length - 1;
        assertEquals(0.0f, matrix[rowIndex][idx("subtype_id")], 0.0001f);
    }

    private AuditTrailEvent baseEvent(String action, String status, Instant time,
                                      int sequence, int sessionLength, String ip, String subType) {
        // Build a compact, production-like event fixture for the tests above.
        AuditTrailEvent event = new AuditTrailEvent();
        event.setAction(action);
        event.setStatus(status);
        event.setCreatedAt(time);
        event.setSequenceInSession(sequence);
        event.setSessionLength(sessionLength);
        event.setDevice("WEB");
        event.setType("LOGGING_ACTIONS");
        event.setCountryCode("FR");
        event.setIp(ip);
        event.setSubType(subType);
        return event;
    }
}
