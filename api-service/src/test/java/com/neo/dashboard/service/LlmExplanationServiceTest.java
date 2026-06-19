package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.dto.v36.V36LlmExplanationRequest;
import com.neo.dashboard.dto.v36.V36LlmExplanationResponse;
import com.neo.dashboard.exception.ApiException;
import com.neo.dashboard.service.llm.LlmProvider;
import com.neo.dashboard.service.llm.LlmProviderRequest;
import com.neo.dashboard.service.llm.LlmProviderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmExplanationServiceTest {

    private final LlmEvidenceReadService evidenceReadService = mock(LlmEvidenceReadService.class);
    private final LlmPromptBuilderV36 promptBuilder = mock(LlmPromptBuilderV36.class);
    private final LlmExplanationCacheService cacheService = mock(LlmExplanationCacheService.class);
    private final LlmProvider llmProvider = mock(LlmProvider.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private LlmExplanationService service;

    @BeforeEach
    void setUp() {
        service = new LlmExplanationService(
                evidenceReadService,
                promptBuilder,
                cacheService,
                llmProvider,
                objectMapper
        );
        ReflectionTestUtils.setField(service, "defaultStyle", "security_analyst");
        ReflectionTestUtils.setField(service, "defaultLanguage", "en");
        ReflectionTestUtils.setField(service, "lockTtlSeconds", 30L);
    }

    @Test
    void postGenerationUsesEvidenceAndLlmWhenConfigured() throws Exception {
        JsonNode evidence = objectMapper.readTree("""
                {"schemaVersion":"v3.6.1","eventId":"evt-1","risk":{"riskLevel":"CRITICAL","finalRiskScore":87.0}}
                """);
        LlmProviderResponse llmResponse = LlmProviderResponse.builder()
                .provider("nvidia-nim")
                .model("nvidia/nemotron-3-super-120b-a12b")
                .text("generated explanation")
                .success(true)
                .build();

        when(evidenceReadService.readEvidence("evt-1")).thenReturn(Optional.of(evidence));
        when(evidenceReadService.isV36Evidence(evidence)).thenReturn(true);
        when(evidenceReadService.evidenceHash(evidence)).thenReturn("hash");
        when(cacheService.getLatest("evt-1")).thenReturn(Optional.empty());
        when(cacheService.get("evt-1", "hash", "security_analyst", "en")).thenReturn(Optional.empty());
        when(cacheService.tryAcquireLock(eq("evt-1"), eq("en"), eq("security_analyst"), any()))
                .thenReturn(true);
        when(promptBuilder.buildSystemPrompt("security_analyst", "en", true)).thenReturn("system prompt");
        when(promptBuilder.buildPrompt(evidence, "security_analyst", "en", true)).thenReturn("user prompt");
        when(llmProvider.generate(any(LlmProviderRequest.class))).thenReturn(llmResponse);

        V36LlmExplanationResponse result = service.generate("evt-1",
                new V36LlmExplanationRequest(false, "security_analyst", "en", true));

        assertThat(result.getProvider()).isEqualTo("nvidia-nim");
        assertThat(result.getModel()).isEqualTo("nvidia/nemotron-3-super-120b-a12b");
        assertThat(result.getEvidenceHash()).isEqualTo("hash");
        assertThat(result.getSource()).isEqualTo("provider_parse_fallback");
        assertThat(result.getCached()).isFalse();
        assertThat(result.getForceRefresh()).isFalse();
        assertThat(result.getFallback()).isTrue();
        assertThat(result.getSummary()).contains("CRITICAL");
        assertThat(result.getSummary()).contains("87.0");
        assertThat(result.getSummary()).contains("invalid structured response");
        assertThat(result.getSummary()).doesNotStartWith("{");
        assertThat(result.getRawProviderResponse()).isEqualTo("generated explanation");
        verify(cacheService, never()).put(any());
    }

    @Test
    void postGenerationReturnsDeterministicFallbackWhenLlmUnavailable() throws Exception {
        JsonNode evidence = objectMapper.readTree("""
                {"schemaVersion":"v3.6.1","eventId":"evt-2","risk":{"riskLevel":"HIGH","finalRiskScore":72.0}}
                """);
        LlmProviderResponse llmResponse = LlmProviderResponse.builder()
                .provider("nvidia-nim")
                .model("nvidia/nemotron-3-super-120b-a12b")
                .success(false)
                .errorCode("NOT_CONFIGURED")
                .build();

        when(evidenceReadService.readEvidence("evt-2")).thenReturn(Optional.of(evidence));
        when(evidenceReadService.isV36Evidence(evidence)).thenReturn(true);
        when(evidenceReadService.evidenceHash(evidence)).thenReturn("hash2");
        when(cacheService.getLatest("evt-2")).thenReturn(Optional.empty());
        when(cacheService.get("evt-2", "hash2", "security_analyst", "en")).thenReturn(Optional.empty());
        when(cacheService.tryAcquireLock(eq("evt-2"), eq("en"), eq("security_analyst"), any()))
                .thenReturn(true);
        when(promptBuilder.buildSystemPrompt("security_analyst", "en", true)).thenReturn("system prompt");
        when(promptBuilder.buildPrompt(evidence, "security_analyst", "en", true)).thenReturn("user prompt");
        when(llmProvider.generate(any(LlmProviderRequest.class))).thenReturn(llmResponse);

        V36LlmExplanationResponse result = service.generate("evt-2",
                new V36LlmExplanationRequest(false, "security_analyst", "en", true));

        assertThat(result.getProvider()).isEqualTo("heuristic");
        assertThat(result.getSummary()).contains("LLM provider unavailable");
        assertThat(result.getSource()).isEqualTo("provider_error_fallback");
        assertThat(result.getCached()).isFalse();
        assertThat(result.getFallback()).isTrue();
        verify(cacheService, never()).put(any());
    }

    @Test
    void postGenerationReturnsCachedExplanationFromRedisWhenLatestHit() {
        V36LlmExplanationResponse cached = new V36LlmExplanationResponse();
        cached.setEventId("evt-3");
        cached.setCached(true);
        cached.setSource("redis");
        when(cacheService.getLatest("evt-3")).thenReturn(Optional.of(cached));

        V36LlmExplanationResponse result = service.generate("evt-3",
                new V36LlmExplanationRequest(false, "security_analyst", "en", true));

        assertThat(result).isSameAs(cached);
        assertThat(result.getCached()).isTrue();
        assertThat(result.getSource()).isEqualTo("redis");
        verify(evidenceReadService, never()).readEvidence(any());
        verify(llmProvider, never()).generate(any());
    }

    @Test
    void postGenerationReturnsCachedExplanationFromSqlWhenLatestMissAndHashHit() throws Exception {
        JsonNode evidence = objectMapper.readTree("{\"schemaVersion\":\"v3.6.1\",\"eventId\":\"evt-4\"}");
        V36LlmExplanationResponse cached = new V36LlmExplanationResponse();
        cached.setEventId("evt-4");
        cached.setCached(true);
        cached.setSource("sql_fallback");
        when(cacheService.getLatest("evt-4")).thenReturn(Optional.empty());
        when(cacheService.tryAcquireLock(eq("evt-4"), eq("en"), eq("security_analyst"), any()))
                .thenReturn(true);
        when(evidenceReadService.readEvidence("evt-4")).thenReturn(Optional.of(evidence));
        when(evidenceReadService.isV36Evidence(evidence)).thenReturn(true);
        when(evidenceReadService.evidenceHash(evidence)).thenReturn("hash4");
        when(cacheService.get("evt-4", "hash4", "security_analyst", "en")).thenReturn(Optional.of(cached));

        V36LlmExplanationResponse result = service.generate("evt-4",
                new V36LlmExplanationRequest(false, "security_analyst", "en", true));

        assertThat(result.getCached()).isTrue();
        assertThat(result.getSource()).isEqualTo("sql_fallback");
        verify(llmProvider, never()).generate(any());
    }

    @Test
    void postForceRefreshBypassesCacheAndGeneratesNewExplanation() throws Exception {
        JsonNode evidence = objectMapper.readTree("""
                {"schemaVersion":"v3.6.1","eventId":"evt-5","risk":{"riskLevel":"CRITICAL"}}
                """);
        LlmProviderResponse llmResponse = LlmProviderResponse.builder()
                .provider("nvidia-nim")
                .model("nvidia/nemotron-3-super-120b-a12b")
                .text("force generated explanation")
                .success(true)
                .build();

        when(cacheService.getLatest("evt-5")).thenReturn(Optional.empty());
        when(evidenceReadService.readEvidence("evt-5")).thenReturn(Optional.of(evidence));
        when(evidenceReadService.isV36Evidence(evidence)).thenReturn(true);
        when(evidenceReadService.evidenceHash(evidence)).thenReturn("hash5");
        when(cacheService.tryAcquireLock(eq("evt-5"), eq("en"), eq("security_analyst"), any()))
                .thenReturn(true);
        when(promptBuilder.buildSystemPrompt("security_analyst", "en", true)).thenReturn("system prompt");
        when(promptBuilder.buildPrompt(evidence, "security_analyst", "en", true)).thenReturn("user prompt");
        when(llmProvider.generate(any(LlmProviderRequest.class))).thenReturn(llmResponse);

        V36LlmExplanationResponse result = service.generate("evt-5",
                new V36LlmExplanationRequest(true, "security_analyst", "en", true));

        assertThat(result.getProvider()).isEqualTo("nvidia-nim");
        assertThat(result.getSource()).isEqualTo("provider_parse_fallback");
        assertThat(result.getCached()).isFalse();
        assertThat(result.getForceRefresh()).isTrue();
        assertThat(result.getFallback()).isTrue();
        assertThat(result.getSummary()).contains("CRITICAL");
        assertThat(result.getSummary()).contains("invalid structured response");
        assertThat(result.getSummary()).doesNotStartWith("{");
        assertThat(result.getRawProviderResponse()).isEqualTo("force generated explanation");
        verify(cacheService, never()).put(any());
    }

    @Test
    void postForceRefreshBypassesExistingRedisCache() throws Exception {
        V36LlmExplanationResponse existingCache = new V36LlmExplanationResponse();
        existingCache.setEventId("evt-6");
        existingCache.setCached(true);
        existingCache.setSource("redis");

        JsonNode evidence = objectMapper.readTree("{\"schemaVersion\":\"v3.6.1\",\"eventId\":\"evt-6\"}");
        LlmProviderResponse llmResponse = LlmProviderResponse.builder()
                .provider("nvidia-nim")
                .model("nvidia/nemotron-3-super-120b-a12b")
                .text("force generated")
                .success(true)
                .build();

        when(cacheService.getLatest("evt-6")).thenReturn(Optional.of(existingCache));
        when(evidenceReadService.readEvidence("evt-6")).thenReturn(Optional.of(evidence));
        when(evidenceReadService.isV36Evidence(evidence)).thenReturn(true);
        when(evidenceReadService.evidenceHash(evidence)).thenReturn("hash6");
        when(cacheService.tryAcquireLock(eq("evt-6"), eq("en"), eq("security_analyst"), any()))
                .thenReturn(true);
        when(promptBuilder.buildSystemPrompt("security_analyst", "en", true)).thenReturn("system prompt");
        when(promptBuilder.buildPrompt(evidence, "security_analyst", "en", true)).thenReturn("user prompt");
        when(llmProvider.generate(any(LlmProviderRequest.class))).thenReturn(llmResponse);

        V36LlmExplanationResponse result = service.generate("evt-6",
                new V36LlmExplanationRequest(true, "security_analyst", "en", true));

        assertThat(result.getSource()).isEqualTo("provider_parse_fallback");
        assertThat(result.getCached()).isFalse();
        assertThat(result.getForceRefresh()).isTrue();
        assertThat(result.getFallback()).isTrue();
        assertThat(result.getSummary()).doesNotStartWith("{");
        assertThat(result.getRawProviderResponse()).isEqualTo("force generated");
        verify(llmProvider).generate(any());
    }

    @Test
    void postGenerationThrowsWhenNoEvidence() {
        when(cacheService.getLatest("evt-missing")).thenReturn(Optional.empty());
        when(evidenceReadService.readEvidence("evt-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generate("evt-missing",
                new V36LlmExplanationRequest(false, "security_analyst", "en", true)))
                .isInstanceOf(ApiException.class)
                .matches(e -> ((ApiException) e).getStatus() == HttpStatus.NOT_FOUND);
        verify(llmProvider, never()).generate(any());
    }

    @Test
    void getCachedReturnsEmptyWhenNoCache() {
        when(cacheService.getLatest("evt-nonexistent")).thenReturn(Optional.empty());

        Optional<V36LlmExplanationResponse> result = service.getCached("evt-nonexistent");

        assertThat(result).isEmpty();
    }

    @Test
    void getCachedReturnsRedisExplanation() {
        V36LlmExplanationResponse cached = new V36LlmExplanationResponse();
        cached.setEventId("evt-7");
        cached.setCached(true);
        cached.setSource("redis");
        when(cacheService.getLatest("evt-7")).thenReturn(Optional.of(cached));

        Optional<V36LlmExplanationResponse> result = service.getCached("evt-7");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().getCached()).isTrue();
        assertThat(result.orElseThrow().getSource()).isEqualTo("redis");
    }

    @Test
    void getCachedReturnsSqlExplanation() {
        V36LlmExplanationResponse sqlResponse = new V36LlmExplanationResponse();
        sqlResponse.setEventId("evt-8");
        sqlResponse.setCached(true);
        sqlResponse.setSource("sql_fallback");
        when(cacheService.getLatest("evt-8")).thenReturn(Optional.of(sqlResponse));

        Optional<V36LlmExplanationResponse> result = service.getCached("evt-8");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().getCached()).isTrue();
        assertThat(result.orElseThrow().getSource()).isEqualTo("sql_fallback");
    }

    @Test
    void postGenerationParsesJsonResponseWhenLlmReturnsStructuredJson() throws Exception {
        JsonNode evidence = objectMapper.readTree("{\"schemaVersion\":\"v3.6.1\",\"eventId\":\"evt-9\"}");
        String jsonResponse = """
                {
                  "summary": "AI-generated summary",
                  "evidenceBullets": ["Risk level: CRITICAL"],
                  "possibleInterpretation": "Possible data exfiltration",
                  "recommendedActions": ["Review session"],
                  "modelScoreExplanation": {"xgboost": 91.0}
                }
                """;
        LlmProviderResponse llmResponse = LlmProviderResponse.builder()
                .provider("nvidia-nim")
                .model("nvidia/nemotron-3-super-120b-a12b")
                .text(jsonResponse)
                .success(true)
                .build();

        when(evidenceReadService.readEvidence("evt-9")).thenReturn(Optional.of(evidence));
        when(evidenceReadService.isV36Evidence(evidence)).thenReturn(true);
        when(evidenceReadService.evidenceHash(evidence)).thenReturn("hash9");
        when(cacheService.getLatest("evt-9")).thenReturn(Optional.empty());
        when(cacheService.get("evt-9", "hash9", "security_analyst", "en")).thenReturn(Optional.empty());
        when(cacheService.tryAcquireLock(eq("evt-9"), eq("en"), eq("security_analyst"), any()))
                .thenReturn(true);
        when(promptBuilder.buildSystemPrompt("security_analyst", "en", true)).thenReturn("system prompt");
        when(promptBuilder.buildPrompt(evidence, "security_analyst", "en", true)).thenReturn("user prompt");
        when(llmProvider.generate(any(LlmProviderRequest.class))).thenReturn(llmResponse);

        V36LlmExplanationResponse result = service.generate("evt-9",
                new V36LlmExplanationRequest(false, "security_analyst", "en", true));

        assertThat(result.getSummary()).isEqualTo("AI-generated summary");
        assertThat(result.getEvidenceBullets()).contains("Risk level: CRITICAL");
        assertThat(result.getPossibleInterpretation()).isEqualTo("Possible data exfiltration");
        assertThat(result.getRecommendedActions()).contains("Review session");
        assertThat(result.getModelScoreExplanation()).containsEntry("xgboost", 91.0);
    }

    @Test
    void postGenerationParsesKeyEvidenceBulletsFromResponse() throws Exception {
        JsonNode evidence = objectMapper.readTree("{\"schemaVersion\":\"v3.6.1\",\"eventId\":\"evt-kb\"}");
        String jsonResponse = """
                {
                  "summary": "Critical risk summary",
                  "keyEvidenceBullets": ["Risk level: CRITICAL", "35 events in session"],
                  "possibleInterpretation": "Possible data exfiltration",
                  "recommendedActions": ["Review session"],
                  "modelScoreExplanation": {"xgboost": 91.0}
                }
                """;
        LlmProviderResponse llmResponse = LlmProviderResponse.builder()
                .provider("nvidia-nim")
                .model("nvidia/nemotron-3-super-120b-a12b")
                .text(jsonResponse)
                .success(true)
                .build();

        when(evidenceReadService.readEvidence("evt-kb")).thenReturn(Optional.of(evidence));
        when(evidenceReadService.isV36Evidence(evidence)).thenReturn(true);
        when(evidenceReadService.evidenceHash(evidence)).thenReturn("hash-kb");
        when(cacheService.getLatest("evt-kb")).thenReturn(Optional.empty());
        when(cacheService.get("evt-kb", "hash-kb", "security_analyst", "en")).thenReturn(Optional.empty());
        when(cacheService.tryAcquireLock(eq("evt-kb"), eq("en"), eq("security_analyst"), any()))
                .thenReturn(true);
        when(promptBuilder.buildSystemPrompt("security_analyst", "en", true)).thenReturn("system prompt");
        when(promptBuilder.buildPrompt(evidence, "security_analyst", "en", true)).thenReturn("user prompt");
        when(llmProvider.generate(any(LlmProviderRequest.class))).thenReturn(llmResponse);

        V36LlmExplanationResponse result = service.generate("evt-kb",
                new V36LlmExplanationRequest(false, "security_analyst", "en", true));

        assertThat(result.getSummary()).isEqualTo("Critical risk summary");
        assertThat(result.getEvidenceBullets()).contains("Risk level: CRITICAL", "35 events in session");
        assertThat(result.getPossibleInterpretation()).isEqualTo("Possible data exfiltration");
    }

    @Test
    void postGenerationSetsFallbackAndWarningWhenJsonParsingFails() throws Exception {
        JsonNode evidence = objectMapper.readTree("{\"schemaVersion\":\"v3.6.1\",\"eventId\":\"evt-fb\"}");
        LlmProviderResponse llmResponse = LlmProviderResponse.builder()
                .provider("nvidia-nim")
                .model("nvidia/nemotron-3-super-120b-a12b")
                .text("Plain text explanation without JSON structure")
                .success(true)
                .build();

        when(evidenceReadService.readEvidence("evt-fb")).thenReturn(Optional.of(evidence));
        when(evidenceReadService.isV36Evidence(evidence)).thenReturn(true);
        when(evidenceReadService.evidenceHash(evidence)).thenReturn("hash-fb");
        when(cacheService.getLatest("evt-fb")).thenReturn(Optional.empty());
        when(cacheService.get("evt-fb", "hash-fb", "security_analyst", "en")).thenReturn(Optional.empty());
        when(cacheService.tryAcquireLock(eq("evt-fb"), eq("en"), eq("security_analyst"), any()))
                .thenReturn(true);
        when(promptBuilder.buildSystemPrompt("security_analyst", "en", true)).thenReturn("system prompt");
        when(promptBuilder.buildPrompt(evidence, "security_analyst", "en", true)).thenReturn("user prompt");
        when(llmProvider.generate(any(LlmProviderRequest.class))).thenReturn(llmResponse);

        V36LlmExplanationResponse result = service.generate("evt-fb",
                new V36LlmExplanationRequest(false, "security_analyst", "en", true));

        assertThat(result.getFallback()).isTrue();
        assertThat(result.getSummary()).doesNotStartWith("{");
        assertThat(result.getSummary()).contains("invalid structured response");
        assertThat(result.getWarnings()).isNotNull();
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("not valid structured JSON"));
        assertThat(result.getRawProviderResponse()).isEqualTo("Plain text explanation without JSON structure");
        assertThat(result.getSource()).isEqualTo("provider_parse_fallback");
    }

    @Test
    void postGenerationFallsBackToRawTextWhenJsonParsingFails() throws Exception {
        JsonNode evidence = objectMapper.readTree("{\"schemaVersion\":\"v3.6.1\",\"eventId\":\"evt-10\"}");
        LlmProviderResponse llmResponse = LlmProviderResponse.builder()
                .provider("nvidia-nim")
                .model("nvidia/nemotron-3-super-120b-a12b")
                .text("Plain text explanation without JSON structure")
                .success(true)
                .build();

        when(evidenceReadService.readEvidence("evt-10")).thenReturn(Optional.of(evidence));
        when(evidenceReadService.isV36Evidence(evidence)).thenReturn(true);
        when(evidenceReadService.evidenceHash(evidence)).thenReturn("hash10");
        when(cacheService.getLatest("evt-10")).thenReturn(Optional.empty());
        when(cacheService.get("evt-10", "hash10", "security_analyst", "en")).thenReturn(Optional.empty());
        when(cacheService.tryAcquireLock(eq("evt-10"), eq("en"), eq("security_analyst"), any()))
                .thenReturn(true);
        when(promptBuilder.buildSystemPrompt("security_analyst", "en", true)).thenReturn("system prompt");
        when(promptBuilder.buildPrompt(evidence, "security_analyst", "en", true)).thenReturn("user prompt");
        when(llmProvider.generate(any(LlmProviderRequest.class))).thenReturn(llmResponse);

        V36LlmExplanationResponse result = service.generate("evt-10",
                new V36LlmExplanationRequest(false, "security_analyst", "en", true));

        assertThat(result.getSummary()).doesNotStartWith("{");
        assertThat(result.getSummary()).contains("invalid structured response");
        assertThat(result.getEvidenceBullets()).isNotNull();
        assertThat(result.getRawProviderResponse()).isEqualTo("Plain text explanation without JSON structure");
    }

    @Test
    void postGenerationHandlesTruncatedJson() throws Exception {
        JsonNode evidence = objectMapper.readTree("{\"schemaVersion\":\"v3.6.1\",\"eventId\":\"evt-trunc\",\"risk\":{\"riskLevel\":\"HIGH\",\"finalRiskScore\":66.1}}");
        LlmProviderResponse llmResponse = LlmProviderResponse.builder()
                .provider("nvidia-nim")
                .model("nvidia/nemotron-3-super-120b-a12b")
                .text("{\"summary\":\"A\",\"rulesNarrative\":\"API")
                .success(true)
                .build();

        when(evidenceReadService.readEvidence("evt-trunc")).thenReturn(Optional.of(evidence));
        when(evidenceReadService.isV36Evidence(evidence)).thenReturn(true);
        when(evidenceReadService.evidenceHash(evidence)).thenReturn("hash-trunc");
        when(cacheService.getLatest("evt-trunc")).thenReturn(Optional.empty());
        when(cacheService.get("evt-trunc", "hash-trunc", "security_analyst", "en")).thenReturn(Optional.empty());
        when(cacheService.tryAcquireLock(eq("evt-trunc"), eq("en"), eq("security_analyst"), any()))
                .thenReturn(true);
        when(promptBuilder.buildSystemPrompt("security_analyst", "en", true)).thenReturn("system prompt");
        when(promptBuilder.buildPrompt(evidence, "security_analyst", "en", true)).thenReturn("user prompt");
        when(llmProvider.generate(any(LlmProviderRequest.class))).thenReturn(llmResponse);

        V36LlmExplanationResponse result = service.generate("evt-trunc",
                new V36LlmExplanationRequest(false, "security_analyst", "en", true));

        assertThat(result.getFallback()).isTrue();
        assertThat(result.getSummary()).doesNotStartWith("{");
        assertThat(result.getSummary()).contains("HIGH");
        assertThat(result.getSummary()).contains("66.1");
        assertThat(result.getSummary()).contains("invalid structured response");
        assertThat(result.getRawProviderResponse()).isEqualTo("{\"summary\":\"A\",\"rulesNarrative\":\"API");
        assertThat(result.getSource()).isEqualTo("provider_parse_fallback");
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("not valid structured JSON"));
    }

    @Test
    void postGenerationReturnsTruncatedFallbackWhenFinishReasonIsLength() throws Exception {
        JsonNode evidence = objectMapper.readTree("{\"schemaVersion\":\"v3.6.1\",\"eventId\":\"evt-len\",\"risk\":{\"riskLevel\":\"HIGH\"}}");
        LlmProviderResponse llmResponse = LlmProviderResponse.builder()
                .provider("nvidia-nim")
                .model("nvidia/nemotron-3-super-120b-a12b")
                .text("{\"summary\":\"Partial...")
                .success(true)
                .finishReason("length")
                .build();

        when(evidenceReadService.readEvidence("evt-len")).thenReturn(Optional.of(evidence));
        when(evidenceReadService.isV36Evidence(evidence)).thenReturn(true);
        when(evidenceReadService.evidenceHash(evidence)).thenReturn("hash-len");
        when(cacheService.getLatest("evt-len")).thenReturn(Optional.empty());
        when(cacheService.get("evt-len", "hash-len", "security_analyst", "en")).thenReturn(Optional.empty());
        when(cacheService.tryAcquireLock(eq("evt-len"), eq("en"), eq("security_analyst"), any()))
                .thenReturn(true);
        when(promptBuilder.buildSystemPrompt("security_analyst", "en", true)).thenReturn("system prompt");
        when(promptBuilder.buildPrompt(evidence, "security_analyst", "en", true)).thenReturn("user prompt");
        when(promptBuilder.buildRetryPrompt(evidence, "security_analyst", "en", true)).thenReturn("retry prompt");
        when(llmProvider.generate(any(LlmProviderRequest.class))).thenReturn(llmResponse);

        V36LlmExplanationResponse result = service.generate("evt-len",
                new V36LlmExplanationRequest(false, "security_analyst", "en", true));

        assertThat(result.getFallback()).isTrue();
        assertThat(result.getSummary()).doesNotStartWith("{");
        assertThat(result.getSummary()).contains("HIGH");
        assertThat(result.getSummary()).contains("truncated before a valid structured explanation");
        assertThat(result.getRawProviderResponse()).isNull();
        assertThat(result.getFinishReason()).isEqualTo("length");
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("truncated before valid JSON was completed even after retry"));
    }

    @Test
    void postGenerationPropagatesFinishReasonToResponse() throws Exception {
        JsonNode evidence = objectMapper.readTree("{\"schemaVersion\":\"v3.6.1\",\"eventId\":\"evt-fr\"}");
        LlmProviderResponse llmResponse = LlmProviderResponse.builder()
                .provider("nvidia-nim")
                .model("nvidia/nemotron-3-super-120b-a12b")
                .text("Plain text")
                .success(true)
                .finishReason("stop")
                .build();

        when(evidenceReadService.readEvidence("evt-fr")).thenReturn(Optional.of(evidence));
        when(evidenceReadService.isV36Evidence(evidence)).thenReturn(true);
        when(evidenceReadService.evidenceHash(evidence)).thenReturn("hash-fr");
        when(cacheService.getLatest("evt-fr")).thenReturn(Optional.empty());
        when(cacheService.get("evt-fr", "hash-fr", "security_analyst", "en")).thenReturn(Optional.empty());
        when(cacheService.tryAcquireLock(eq("evt-fr"), eq("en"), eq("security_analyst"), any()))
                .thenReturn(true);
        when(promptBuilder.buildSystemPrompt("security_analyst", "en", true)).thenReturn("system prompt");
        when(promptBuilder.buildPrompt(evidence, "security_analyst", "en", true)).thenReturn("user prompt");
        when(llmProvider.generate(any(LlmProviderRequest.class))).thenReturn(llmResponse);

        V36LlmExplanationResponse result = service.generate("evt-fr",
                new V36LlmExplanationRequest(false, "security_analyst", "en", true));

        assertThat(result.getFinishReason()).isEqualTo("stop");
    }

    @Test
    void postGenerationParsesJsonInsideMarkdownFences() throws Exception {
        JsonNode evidence = objectMapper.readTree("{\"schemaVersion\":\"v3.6.1\",\"eventId\":\"evt-md\"}");
        String markdownResponse = """
                Here is my analysis:
                
                ```json
                {
                  "summary": "Analysis extracted from markdown fences",
                  "keyEvidenceBullets": ["Risk level: HIGH"],
                  "possibleInterpretation": "Session anomaly detected"
                }
                ```
                """;
        LlmProviderResponse llmResponse = LlmProviderResponse.builder()
                .provider("nvidia-nim")
                .model("nvidia/nemotron-3-super-120b-a12b")
                .text(markdownResponse)
                .success(true)
                .build();

        when(evidenceReadService.readEvidence("evt-md")).thenReturn(Optional.of(evidence));
        when(evidenceReadService.isV36Evidence(evidence)).thenReturn(true);
        when(evidenceReadService.evidenceHash(evidence)).thenReturn("hash-md");
        when(cacheService.getLatest("evt-md")).thenReturn(Optional.empty());
        when(cacheService.get("evt-md", "hash-md", "security_analyst", "en")).thenReturn(Optional.empty());
        when(cacheService.tryAcquireLock(eq("evt-md"), eq("en"), eq("security_analyst"), any()))
                .thenReturn(true);
        when(promptBuilder.buildSystemPrompt("security_analyst", "en", true)).thenReturn("system prompt");
        when(promptBuilder.buildPrompt(evidence, "security_analyst", "en", true)).thenReturn("user prompt");
        when(llmProvider.generate(any(LlmProviderRequest.class))).thenReturn(llmResponse);

        V36LlmExplanationResponse result = service.generate("evt-md",
                new V36LlmExplanationRequest(false, "security_analyst", "en", true));

        assertThat(result.getSummary()).isEqualTo("Analysis extracted from markdown fences");
        assertThat(result.getEvidenceBullets()).contains("Risk level: HIGH");
        assertThat(result.getPossibleInterpretation()).isEqualTo("Session anomaly detected");
        assertThat(result.getFallback()).isFalse(); // successfully parsed, no fallback
    }
}
