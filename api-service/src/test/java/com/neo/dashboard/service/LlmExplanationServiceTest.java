package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.dto.v36.V36LlmExplanationRequest;
import com.neo.dashboard.dto.v36.V36LlmExplanationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmExplanationServiceTest {

    private final LlmEvidenceReadService evidenceReadService = mock(LlmEvidenceReadService.class);
    private final LlmPromptBuilderV36 promptBuilder = mock(LlmPromptBuilderV36.class);
    private final LlmExplanationCacheService cacheService = mock(LlmExplanationCacheService.class);
    private final GeminiClient geminiClient = mock(GeminiClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private LlmExplanationService service;

    @BeforeEach
    void setUp() {
        service = new LlmExplanationService(
                evidenceReadService,
                promptBuilder,
                cacheService,
                geminiClient
        );
        ReflectionTestUtils.setField(service, "defaultStyle", "security_analyst");
        ReflectionTestUtils.setField(service, "defaultLanguage", "en");
        ReflectionTestUtils.setField(service, "explanationsEnabled", true);
    }

    @Test
    void postGenerationUsesEvidenceAndGeminiWhenConfigured() throws Exception {
        JsonNode evidence = objectMapper.readTree("""
                {"schemaVersion":"v3.6.1","eventId":"evt-1","risk":{"riskLevel":"CRITICAL","finalRiskScore":87.0}}
                """);
        when(evidenceReadService.readEvidence("evt-1")).thenReturn(Optional.of(evidence));
        when(evidenceReadService.isV36Evidence(evidence)).thenReturn(true);
        when(evidenceReadService.evidenceHash(evidence)).thenReturn("hash");
        when(cacheService.get("evt-1", "hash", "security_analyst", "en")).thenReturn(Optional.empty());
        when(promptBuilder.buildPrompt(evidence, "security_analyst", "en", true)).thenReturn("prompt");
        when(geminiClient.generate("evt-1", "prompt")).thenReturn(Optional.of("generated explanation"));
        when(geminiClient.getModel()).thenReturn("gemini-2.5-flash");

        V36LlmExplanationResponse result = service.generate("evt-1",
                new V36LlmExplanationRequest(false, "security_analyst", "en", true));

        assertThat(result.getProvider()).isEqualTo("gemini");
        assertThat(result.getSummary()).isEqualTo("generated explanation");
        assertThat(result.getEvidenceHash()).isEqualTo("hash");
        verify(cacheService).put(result);
    }

    @Test
    void postGenerationReturnsDeterministicFallbackWhenGeminiUnavailable() throws Exception {
        JsonNode evidence = objectMapper.readTree("""
                {"schemaVersion":"v3.6.1","eventId":"evt-2","risk":{"riskLevel":"HIGH","finalRiskScore":72.0}}
                """);
        when(evidenceReadService.readEvidence("evt-2")).thenReturn(Optional.of(evidence));
        when(evidenceReadService.isV36Evidence(evidence)).thenReturn(true);
        when(evidenceReadService.evidenceHash(evidence)).thenReturn("hash2");
        when(cacheService.get("evt-2", "hash2", "security_analyst", "en")).thenReturn(Optional.empty());
        when(promptBuilder.buildPrompt(evidence, "security_analyst", "en", true)).thenReturn("prompt");
        when(geminiClient.generate("evt-2", "prompt")).thenReturn(Optional.empty());

        V36LlmExplanationResponse result = service.generate("evt-2",
                new V36LlmExplanationRequest(false, "security_analyst", "en", true));

        assertThat(result.getProvider()).isEqualTo("heuristic");
        assertThat(result.getSummary()).contains("LLM provider unavailable");
    }

    @Test
    void postGenerationReturnsCachedExplanationWhenHashMatches() throws Exception {
        JsonNode evidence = objectMapper.readTree("{\"schemaVersion\":\"v3.6.1\",\"eventId\":\"evt-3\"}");
        V36LlmExplanationResponse cached = new V36LlmExplanationResponse();
        cached.setEventId("evt-3");
        cached.setCached(true);
        when(evidenceReadService.readEvidence("evt-3")).thenReturn(Optional.of(evidence));
        when(evidenceReadService.isV36Evidence(evidence)).thenReturn(true);
        when(evidenceReadService.evidenceHash(evidence)).thenReturn("hash3");
        when(cacheService.get("evt-3", "hash3", "security_analyst", "en")).thenReturn(Optional.of(cached));

        V36LlmExplanationResponse result = service.generate("evt-3",
                new V36LlmExplanationRequest(false, "security_analyst", "en", true));

        assertThat(result).isSameAs(cached);
        assertThat(result.getCached()).isTrue();
    }
}
