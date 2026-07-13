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

@Slf4j
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(value = "app.asr.provider", havingValue = "openai-compatible-audio", matchIfMissing = false)
public class NvidiaNimAsrService implements AsrService {

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    private final AsrProperties properties;
    private final AudioConverter audioConverter;
    private final ObjectMapper objectMapper;
    private WebClient webClient;

    public NvidiaNimAsrService(AsrProperties properties, AudioConverter audioConverter, ObjectMapper objectMapper) {
        this.properties = properties;
        this.audioConverter = audioConverter;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        long effectiveTimeout = Math.max(properties.getTimeoutMs() + 2000, 10000);
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMillis(effectiveTimeout))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new io.netty.handler.timeout.ReadTimeoutHandler(effectiveTimeout, TimeUnit.MILLISECONDS))
                        .addHandlerLast(new io.netty.handler.timeout.WriteTimeoutHandler(effectiveTimeout, TimeUnit.MILLISECONDS)));
        this.webClient = WebClient.builder()
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .build();

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
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        Instant start = Instant.now();
        String langCode = (languageCode != null && !languageCode.isBlank())
                ? languageCode : properties.getLanguageCode();

        String originalFilename = file.getOriginalFilename();
        String contentType = file.getContentType();
        long fileSize = file.getSize();

        log.info("ASR_REQUEST_STARTED requestId={} contentType={} originalFilename={} sizeBytes={} languageCode={}",
                requestId, contentType, originalFilename, fileSize, langCode);

        if (fileSize > MAX_FILE_SIZE) {
            log.warn("ASR_AUDIO_TOO_LARGE requestId={} sizeBytes={} maxBytes={}",
                    requestId, fileSize, MAX_FILE_SIZE);
            throw new AsrException("ASR_AUDIO_TOO_LARGE", "Audio file too large. Maximum size is " + (MAX_FILE_SIZE / 1024 / 1024) + "MB.");
        }

        byte[] audioBytes;
        try {
            audioBytes = file.getBytes();
        } catch (IOException e) {
            throw new AsrException("ASR_INVALID_AUDIO", "Could not read audio file.");
        }

        boolean needsConversion = originalFilename != null && !audioConverter.isSupportedFormat(originalFilename);
        if (needsConversion && properties.getAudio().isConvertToWav()) {
            byte[] converted = audioConverter.convertToWav(audioBytes, originalFilename, requestId);
            if (converted == null) {
                throw new AsrException("ASR_CONVERSION_FAILED", "Could not convert audio to supported format. Ensure ffmpeg is installed.");
            }
            audioBytes = converted;
        }

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

        String endpoint = properties.getServer() + "/audio/transcriptions";
        log.info("ASR_PROVIDER_REQUEST requestId={} provider={} endpoint={} languageCode={} timeoutMs={}",
                requestId, properties.getProvider(), endpoint, langCode, properties.getTimeoutMs());

        String responseBody;
        try {
            MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
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
            String errorBody = e.getResponseBodyAsString();
            log.warn("ASR_PROVIDER_ERROR requestId={} status={} body={}", requestId, e.getStatusCode(), errorBody);
            if (e.getStatusCode().is5xxServerError()) {
                throw new AsrException("ASR_PROVIDER_UNAVAILABLE", "ASR provider returned " + e.getStatusCode());
            }
            throw new AsrException("ASR_FAILED", "ASR provider returned " + e.getStatusCode() + ": " + truncate(errorBody, 200));
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("timeout") || msg.contains("timed out")) {
                throw new AsrException("ASR_PROVIDER_TIMEOUT", "ASR provider timed out.");
            }
            throw new AsrException("ASR_FAILED", "Could not transcribe audio: " + e.getMessage());
        }

        long latencyMs = Duration.between(start, Instant.now()).toMillis();

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

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
