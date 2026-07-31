package com.neo.dashboard.asr.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.asr.audio.AudioConverter;
import com.neo.dashboard.asr.config.AsrProperties;
import com.neo.dashboard.asr.dto.AsrStatus;
import com.neo.dashboard.asr.dto.AsrTranscriptionResult;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * ASR service implementation that talks to an OpenAI-compatible audio
 * transcription REST API (e.g. NVIDIA NIM). Activated when the configuration
 * property {@code app.asr.provider} is set to {@code "openai-compatible-audio"}.
 * Sends the audio file as multipart/form-data to the provider's
 * {@code /audio/transcriptions} endpoint and parses the JSON response.
 */
@Slf4j
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(value = "app.asr.provider", havingValue = "openai-compatible-audio", matchIfMissing = false)
public class NvidiaNimAsrService implements AsrService {

    /** Maximum allowed audio file size (10 MB). */
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    /** ASR configuration properties. */
    private final AsrProperties properties;
    /** Utility to convert audio files to WAV format via ffmpeg. */
    private final AudioConverter audioConverter;
    /** Jackson JSON mapper for parsing provider responses. */
    private final ObjectMapper objectMapper;
    /** Reactive HTTP client used to call the provider endpoint. */
    private WebClient webClient;

    public NvidiaNimAsrService(AsrProperties properties, AudioConverter audioConverter, ObjectMapper objectMapper) {
        this.properties = properties;
        this.audioConverter = audioConverter;
        this.objectMapper = objectMapper;
    }

    /**
     * Initializes the {@link WebClient} with a custom {@link HttpClient} that
     * has read/write timeouts derived from the configured ASR timeout. Also
     * logs the selected provider configuration and API key status.
     */
    @PostConstruct
    public void init() {
        long effectiveTimeout = Math.max(properties.getTimeoutMs() + 2000, 10000);
        // Build a Netty HttpClient with explicit read/write timeout handlers
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMillis(effectiveTimeout))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new io.netty.handler.timeout.ReadTimeoutHandler(effectiveTimeout, TimeUnit.MILLISECONDS))
                        .addHandlerLast(new io.netty.handler.timeout.WriteTimeoutHandler(effectiveTimeout, TimeUnit.MILLISECONDS)));
        this.webClient = WebClient.builder()
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .build();

        // Validate and log API key status
        String apiKey = properties.getApiKey();
        boolean hasKey = apiKey != null && !apiKey.isBlank();
        boolean looksValid = hasKey && apiKey.length() > 40;

        log.info("ASR_PROVIDER_SELECTED provider={} endpoint={} model={} languageCode={} enabled={}",
                properties.getProvider(), properties.getServer(), properties.getModel(),
                properties.getLanguageCode(), properties.isEnabled());
        if (!hasKey) {
            log.warn("ASR_API_KEY_MISSING present=false");
        } else if (looksValid) {
            log.info("ASR_API_KEY_PRESENT present=true length={} looksValid=true", apiKey.length());
        } else {
            log.warn("ASR_API_KEY_PRESENT length={}", apiKey.length());
        }
    }

    /** Returns true if the configured API key is present, non-blank, and longer than 40 characters. */
    private boolean isApiKeyLooksValid() {
        String key = properties.getApiKey();
        return key != null && !key.isBlank() && key.length() > 40;
    }

    @Override
    public AsrStatus getStatus() {
        String apiKey = properties.getApiKey();
        boolean hasKey = apiKey != null && !apiKey.isBlank();
        return new AsrStatus(
                properties.isEnabled(),
                properties.getProvider(),
                "rest",
                properties.getServer(),
                properties.getModel(),
                "recognize",
                hasKey,
                hasKey && apiKey.length() > 40,
                audioConverter.isFfmpegAvailable(),
                properties.getAudio().getFfmpegPath(),
                properties.getAudio().isConvertToWav()
        );
    }

    @Override
    public AsrTranscriptionResult transcribe(MultipartFile file, String languageCode) {
        // Generate a short correlation ID for request tracing
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        Instant start = Instant.now();
        // Use the provided language code or fall back to the configured default
        String langCode = (languageCode != null && !languageCode.isBlank())
                ? languageCode : properties.getLanguageCode();

        String originalFilename = file.getOriginalFilename();
        String contentType = file.getContentType();
        long fileSize = file.getSize();

        log.info("ASR_REQUEST_STARTED requestId={} contentType={} originalFilename={} sizeBytes={} languageCode={}",
                requestId, contentType, originalFilename, fileSize, langCode);

        // Reject files that exceed the maximum allowed size
        if (fileSize > MAX_FILE_SIZE) {
            log.warn("ASR_AUDIO_TOO_LARGE requestId={} sizeBytes={} maxBytes={}",
                    requestId, fileSize, MAX_FILE_SIZE);
            throw new AsrException("ASR_AUDIO_TOO_LARGE", "Audio file too large. Maximum size is " + (MAX_FILE_SIZE / 1024 / 1024) + "MB.");
        }

        // Read the file bytes from the multipart stream
        byte[] audioBytes;
        try {
            audioBytes = file.getBytes();
        } catch (IOException e) {
            throw new AsrException("ASR_INVALID_AUDIO", "Could not read audio file.");
        }

        // Convert unsupported audio formats (e.g. webm, mp4) to WAV via ffmpeg
        boolean needsConversion = originalFilename != null && !audioConverter.isSupportedFormat(originalFilename);
        if (needsConversion && properties.getAudio().isConvertToWav()) {
            byte[] converted = audioConverter.convertToWav(audioBytes, originalFilename, requestId);
            if (converted == null) {
                throw new AsrException("ASR_CONVERSION_FAILED", "Could not convert audio to supported format. Ensure ffmpeg is installed.");
            }
            audioBytes = converted;
        }

        // Ensure the API key passes the basic length heuristic
        if (!isApiKeyLooksValid()) {
            String key = properties.getApiKey();
            boolean hasKey = key != null && !key.isBlank();
            log.warn("ASR_API_KEY_MISSING_OR_INVALID requestId={} hasKey={} keyLength={}",
                    requestId, hasKey, hasKey ? key.length() : 0);
            throw new AsrException("ASR_API_KEY_MISSING_OR_INVALID",
                    "ASR API key is missing or appears invalid (length="
                    + (hasKey ? key.length() : 0)
                    + ", expected > 40 chars). Configure app.asr.api-key.");
        }

        // Build the full provider endpoint URL
        String endpoint = properties.getServer() + "/audio/transcriptions";
        log.info("ASR_PROVIDER_REQUEST requestId={} provider={} endpoint={} languageCode={} timeoutMs={}",
                requestId, properties.getProvider(), endpoint, langCode, properties.getTimeoutMs());

        // Execute the REST call with multipart upload
        String responseBody;
        try {
            MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
            // Wrap audio bytes as a ByteArrayResource with a fixed filename
            bodyBuilder.part("file", new ByteArrayResource(audioBytes) {
                @Override
                public String getFilename() {
                    return "audio.wav";
                }
            }, MediaType.valueOf("audio/wav"));

            responseBody = webClient.post()
                    .uri(URI.create(endpoint))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(bodyBuilder.build()))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .block();
        } catch (WebClientResponseException e) {
            // Provider returned a non-2xx status; log and map to appropriate error
            String errorBody = e.getResponseBodyAsString();
            log.warn("ASR_PROVIDER_ERROR requestId={} status={} body={}", requestId, e.getStatusCode(), errorBody);
            if (e.getStatusCode().is5xxServerError()) {
                throw new AsrException("ASR_PROVIDER_UNAVAILABLE", "ASR provider returned " + e.getStatusCode());
            }
            throw new AsrException("ASR_FAILED", "ASR provider returned " + e.getStatusCode() + ": " + truncate(errorBody, 200));
        } catch (Exception e) {
            // Detect timeout exceptions from the WebClient/Netty layer
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("timeout") || msg.contains("timed out")) {
                throw new AsrException("ASR_PROVIDER_TIMEOUT", "ASR provider timed out.");
            }
            throw new AsrException("ASR_FAILED", "Could not transcribe audio: " + e.getMessage());
        }

        // Calculate end-to-end latency
        long latencyMs = Duration.between(start, Instant.now()).toMillis();

        // Extract the transcript from the JSON response (field "text")
        String transcript = "";
        try {
            JsonNode json = objectMapper.readTree(responseBody);
            if (json.has("text")) {
                transcript = json.get("text").asText("");
            }
        } catch (Exception e) {
            log.warn("ASR_PARSE_ERROR requestId={} rawResponse={}", requestId, truncate(responseBody, 500));
        }

        log.info("ASR_PROVIDER_RESPONSE requestId={} latencyMs={} transcriptChars={} transcript=\"{}\"",
                requestId, latencyMs, transcript.length(), truncate(transcript, 80));

        // Build and return the result DTO
        AsrTranscriptionResult result = new AsrTranscriptionResult(
                transcript,
                properties.getProvider(),
                properties.getModel(),
                langCode,
                latencyMs,
                null,
                transcript.length(),
                requestId
        );

        log.info("ASR_RESPONSE requestId={} latencyMs={} transcript=\"{}\"",
                requestId, latencyMs, truncate(transcript, 80));

        return result;
    }

    /** Truncates a string to {@code max} characters, appending "..." if it was longer. */
    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
