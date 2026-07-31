package com.neo.dashboard.asr.service;

import com.google.protobuf.ByteString;
import com.neo.dashboard.asr.audio.AudioConverter;
import com.neo.dashboard.asr.config.AsrProperties;
import com.neo.dashboard.asr.dto.AsrStatus;
import com.neo.dashboard.asr.dto.AsrTranscriptionResult;
import com.neo.dashboard.asr.riva.proto.AudioEncoding;
import com.neo.dashboard.asr.riva.proto.RecognitionConfig;
import com.neo.dashboard.asr.riva.proto.RivaSpeechRecognitionGrpc;
import com.neo.dashboard.asr.riva.proto.StreamingRecognitionConfig;
import com.neo.dashboard.asr.riva.proto.StreamingRecognizeRequest;
import com.neo.dashboard.asr.riva.proto.StreamingRecognizeResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ASR service implementation that uses the NVIDIA Riva gRPC API for streaming
 * speech recognition. Activated when {@code app.asr.provider} is set to
 * {@code "nvidia-riva"} (the default). Strips the WAV header from incoming
 * audio to obtain raw PCM data, then sends it in chunks over a bidirectional
 * streaming gRPC call ({@code StreamingRecognize}).
 */
@Slf4j
@Service
public class NvidiaRivaAsrService implements AsrService {

    /** Maximum allowed audio file size (10 MB). */
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;
    /** Size of each PCM chunk sent over the gRPC stream (8 KB). */
    private static final int PCM_CHUNK_SIZE = 8192;
    /** Minimum byte count that a valid WAV header occupies (44 bytes for PCM). */
    private static final int WAV_HEADER_MIN = 44;

    /** ASR configuration properties. */
    private final AsrProperties properties;
    /** Utility to convert audio files to WAV format via ffmpeg. */
    private final AudioConverter audioConverter;
    /** gRPC managed channel to the Riva server. */
    private ManagedChannel channel;
    /** Async gRPC stub for bidirectional streaming recognition. */
    private RivaSpeechRecognitionGrpc.RivaSpeechRecognitionStub asyncStub;

    public NvidiaRivaAsrService(AsrProperties properties, AudioConverter audioConverter) {
        this.properties = properties;
        this.audioConverter = audioConverter;
    }

    /**
     * Initialises the gRPC channel and stub. Attaches the function-id and
     * authorization metadata to every call. Skips initialisation if the ASR
     * feature is disabled. Logs the provider configuration and API key status.
     */
    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            log.info("ASR_PROVIDER_DISABLED provider=nvidia-riva");
            return;
        }

        String apiKey = properties.getApiKey();
        boolean hasKey = apiKey != null && !apiKey.isBlank();
        boolean looksValid = hasKey && apiKey.length() > 40;

        // Attach the NVIDIA function-id header required by the Riva gRPC gateway
        Metadata metadata = new Metadata();
        Metadata.Key<String> functionIdKey = Metadata.Key.of("function-id", Metadata.ASCII_STRING_MARSHALLER);
        metadata.put(functionIdKey, properties.getFunctionId());

        // Attach the bearer token if an API key is configured
        if (hasKey) {
            Metadata.Key<String> authKey = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
            metadata.put(authKey, "Bearer " + apiKey);
        }

        // Build the gRPC channel with TLS or plaintext depending on configuration
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forTarget(properties.getServer());
        if (properties.isUseSsl()) {
            builder.useTransportSecurity();
        } else {
            builder.usePlaintext();
        }

        channel = builder
                .intercept(MetadataUtils.newAttachHeadersInterceptor(metadata))
                .build();

        // Create the async stub for StreamingRecognize
        asyncStub = RivaSpeechRecognitionGrpc.newStub(channel);

        log.info("ASR_PROVIDER_SELECTED provider={} protocol=grpc server={} model={} method={} ssl={} languageCode={} enabled={}",
                properties.getProvider(), properties.getServer(), properties.getModel(),
                properties.getMethod(), properties.isUseSsl(), properties.getLanguageCode(),
                properties.isEnabled());
        if (!hasKey) {
            log.warn("ASR_API_KEY_MISSING present=false");
        } else if (looksValid) {
            log.info("ASR_API_KEY_PRESENT present=true length={} looksValid=true", apiKey.length());
        } else {
            log.warn("ASR_API_KEY_PRESENT length={}", apiKey.length());
        }
    }

    /** Shuts down the gRPC channel gracefully on bean destruction. */
    @PreDestroy
    public void shutdown() {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdownNow();
        }
    }

    /** Returns true if the configured API key is present, non-blank, and longer than 40 characters. */
    private boolean isApiKeyLooksValid() {
        String key = properties.getApiKey();
        return key != null && !key.isBlank() && key.length() > 40;
    }

    /** Returns true if the API key starts with the mandatory "nvapi-" prefix. */
    private boolean isNvapiKey() {
        String key = properties.getApiKey();
        return key != null && key.startsWith("nvapi-");
    }

    /**
     * Converts a short language code to a full BCP-47 tag. Defaults to "en-US"
     * when the input is null or blank.
     */
    private String normalizeLanguageCode(String lang) {
        if (lang == null || lang.isBlank()) return "en-US";
        if ("en".equals(lang)) return "en-US";
        if ("fr".equals(lang)) return "fr-FR";
        if ("ar".equals(lang)) return "ar-SA";
        return lang;
    }

    @Override
    public AsrStatus getStatus() {
        String apiKey = properties.getApiKey();
        boolean hasKey = apiKey != null && !apiKey.isBlank();
        return new AsrStatus(
                properties.isEnabled(),
                properties.getProvider(),
                properties.getProtocol(),
                properties.getServer(),
                properties.getModel(),
                properties.getMethod(),
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
        // Normalize the language code (e.g. "en" -> "en-US")
        String langCode = normalizeLanguageCode(
                (languageCode != null && !languageCode.isBlank()) ? languageCode : properties.getLanguageCode());

        String originalFilename = file.getOriginalFilename();
        String contentType = file.getContentType();
        long fileSize = file.getSize();

        log.info("ASR_REQUEST_STARTED requestId={} contentType={} originalFilename={} sizeBytes={} languageCode={}",
                requestId, contentType, originalFilename, fileSize, langCode);

        // Reject files larger than the configured maximum
        if (fileSize > MAX_FILE_SIZE) {
            log.warn("ASR_AUDIO_TOO_LARGE requestId={} sizeBytes={} maxBytes={}",
                    requestId, fileSize, MAX_FILE_SIZE);
            throw new AsrException("ASR_AUDIO_TOO_LARGE", "Audio file too large. Maximum size is " + (MAX_FILE_SIZE / 1024 / 1024) + "MB.");
        }

        // Read the file bytes from the multipart request
        byte[] audioBytes;
        try {
            audioBytes = file.getBytes();
        } catch (IOException e) {
            throw new AsrException("ASR_INVALID_AUDIO", "Could not read audio file.");
        }

        // Convert unsupported audio formats to WAV via ffmpeg if enabled
        boolean needsConversion = originalFilename != null && !audioConverter.isSupportedFormat(originalFilename);
        if (needsConversion && properties.getAudio().isConvertToWav()) {
            byte[] converted = audioConverter.convertToWav(audioBytes, originalFilename, requestId);
            if (converted == null) {
                throw new AsrException("ASR_CONVERSION_FAILED", "Could not convert audio to supported format. Ensure ffmpeg is installed.");
            }
            audioBytes = converted;
        }

        // Guard against uninitialised service (ASR disabled at startup)
        if (asyncStub == null) {
            log.warn("ASR_DISABLED requestId={}", requestId);
            throw new AsrException("ASR_DISABLED", "ASR is not configured.");
        }

        // Validate the API key: must be present, look valid, and be an nvapi- key
        if (!isApiKeyLooksValid() || !isNvapiKey()) {
            String key = properties.getApiKey();
            boolean hasKey = key != null && !key.isBlank();
            if (!isNvapiKey() && hasKey) {
                log.warn("ASR_API_KEY_INVALID requestId={} keyLength={} looksLikeNvapi=false", requestId, key.length());
                throw new AsrException("ASR_API_KEY_INVALID",
                        "ASR requires an NVIDIA nvapi-* API key.");
            }
            log.warn("ASR_API_KEY_MISSING_OR_INVALID requestId={} hasKey={} keyLength={}",
                    requestId, hasKey, hasKey ? key.length() : 0);
            throw new AsrException("ASR_API_KEY_MISSING_OR_INVALID",
                    "ASR API key is missing or appears invalid (length="
                    + (hasKey ? key.length() : 0)
                    + ", expected > 40 chars). Configure app.asr.api-key.");
        }

        // Remove the WAV container header to obtain raw LINEAR16 PCM samples
        byte[] pcmData = stripWavHeader(audioBytes, requestId);

        log.info("ASR_AUDIO_PCM_READY requestId={} pcmBytes={} encoding=LINEAR16 sampleRate={} channels={}",
                requestId, pcmData.length, properties.getAudio().getTargetSampleRate(),
                properties.getAudio().getTargetChannels());

        String grpcMethodFullName = RivaSpeechRecognitionGrpc.getStreamingRecognizeMethod().getFullMethodName();
        log.info("ASR_GRPC_METHOD requestId={} fullMethodName={}", requestId, grpcMethodFullName);

        log.info("ASR_PROVIDER_REQUEST requestId={} provider={} method=StreamingRecognize server={} functionId={} languageCode={} timeoutMs={}",
                requestId, properties.getProvider(), properties.getServer(),
                properties.getFunctionId(), langCode, properties.getTimeoutMs());

        // Configure the recognition request with the desired audio encoding and language
        RecognitionConfig recognitionConfig = RecognitionConfig.newBuilder()
                .setEncoding(AudioEncoding.LINEAR_PCM)
                .setSampleRateHertz(properties.getAudio().getTargetSampleRate())
                .setLanguageCode(langCode)
                .setMaxAlternatives(1)
                .setEnableAutomaticPunctuation(true)
                .build();

        StreamingRecognitionConfig streamingConfig = StreamingRecognitionConfig.newBuilder()
                .setConfig(recognitionConfig)
                .setInterimResults(false)
                .build();

        // Prepare the asynchronous streaming gRPC call
        long timeoutMs = properties.getTimeoutMs();
        AtomicReference<String> transcriptRef = new AtomicReference<>("");
        AtomicReference<String> errorRef = new AtomicReference<>(null);
        CountDownLatch latch = new CountDownLatch(1);

        // Observer that collects the final transcript or any error from the server
        StreamObserver<StreamingRecognizeResponse> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(StreamingRecognizeResponse response) {
                if (response.getResultsCount() > 0
                        && response.getResults(0).getAlternativesCount() > 0) {
                    String t = response.getResults(0).getAlternatives(0).getTranscript();
                    if (t != null && !t.isEmpty()) {
                        transcriptRef.set(t);
                    }
                }
            }

            @Override
            public void onError(Throwable t) {
                String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                log.warn("ASR_PROVIDER_ERROR requestId={} error={}", requestId, msg);
                errorRef.set(msg);
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        };

        // Initiate the streaming RPC with a deadline
        StreamObserver<StreamingRecognizeRequest> requestObserver;
        try {
            requestObserver = asyncStub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                    .streamingRecognize(responseObserver);
        } catch (Exception e) {
            throw new AsrException("ASR_FAILED", "Could not transcribe audio: " + e.getMessage());
        }

        // Send the streaming configuration as the first message
        requestObserver.onNext(StreamingRecognizeRequest.newBuilder()
                .setStreamingConfig(streamingConfig)
                .build());

        // Stream the PCM audio data in fixed-size chunks
        for (int offset = 0; offset < pcmData.length; offset += PCM_CHUNK_SIZE) {
            int end = Math.min(offset + PCM_CHUNK_SIZE, pcmData.length);
            byte[] chunk = new byte[end - offset];
            System.arraycopy(pcmData, offset, chunk, 0, chunk.length);
            requestObserver.onNext(StreamingRecognizeRequest.newBuilder()
                    .setAudioContent(ByteString.copyFrom(chunk))
                    .build());
        }

        // Signal the end of the audio stream
        requestObserver.onCompleted();

        // Block until the server completes or the extended deadline expires
        try {
            boolean finished = latch.await(timeoutMs + 5000, TimeUnit.MILLISECONDS);
            if (!finished) {
                throw new AsrException("ASR_PROVIDER_TIMEOUT", "ASR provider timed out.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AsrException("ASR_FAILED", "Interrupted while waiting for ASR response.");
        }

        // If the server reported an error, map it to the appropriate AsrException
        String error = errorRef.get();
        if (error != null) {
            String lower = error.toLowerCase();
            if (lower.contains("unimplemented")) {
                throw new AsrException("ASR_METHOD_UNIMPLEMENTED",
                        "ASR method is not implemented by provider.");
            }
            if (lower.contains("unauthenticated")) {
                throw new AsrException("ASR_AUTH_FAILED",
                        "ASR authentication failed. Check API key.");
            }
            if (lower.contains("permission denied")) {
                throw new AsrException("ASR_PERMISSION_DENIED",
                        "ASR permission denied.");
            }
            if (lower.contains("not found") || lower.contains("404")) {
                throw new AsrException("ASR_PROVIDER_ENDPOINT_NOT_FOUND",
                        "ASR provider endpoint was not found. Check provider/protocol configuration.");
            }
            if (lower.contains("timeout") || lower.contains("deadline")) {
                throw new AsrException("ASR_PROVIDER_TIMEOUT", "ASR provider timed out.");
            }
            if (lower.contains("unavailable")) {
                throw new AsrException("ASR_PROVIDER_UNAVAILABLE", "ASR provider is unavailable.");
            }
            throw new AsrException("ASR_FAILED", "Could not transcribe audio: " + error);
        }

        // Calculate end-to-end latency
        long latencyMs = Duration.between(start, Instant.now()).toMillis();
        String transcript = transcriptRef.get();

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

    /**
     * Removes the RIFF/WAV header from a WAV byte array, returning only the
     * raw PCM sample data. Walks the RIFF chunk structure to locate the "data"
     * chunk. If the input is not a valid WAV, it is returned unchanged.
     */
    byte[] stripWavHeader(byte[] wavBytes, String requestId) {
        if (wavBytes == null || wavBytes.length < WAV_HEADER_MIN) {
            log.warn("ASR_WAV_HEADER_INVALID requestId={} sizeBytes={}", requestId, wavBytes != null ? wavBytes.length : 0);
            return wavBytes;
        }

        // Validate the RIFF magic bytes
        if (wavBytes[0] != 'R' || wavBytes[1] != 'I' || wavBytes[2] != 'F' || wavBytes[3] != 'F') {
            log.warn("ASR_WAV_NOT_RIFF requestId={} - not a WAV file, returning as-is", requestId);
            return wavBytes;
        }

        // Walk through RIFF chunks looking for the "data" chunk
        int offset = 12;
        while (offset + 8 <= wavBytes.length) {
            String chunkId = new String(wavBytes, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int chunkSize = ByteBuffer.wrap(wavBytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if ("data".equals(chunkId)) {
                int dataStart = offset + 8;
                int dataLen = Math.min(chunkSize, wavBytes.length - dataStart);
                if (dataLen <= 0) {
                    log.warn("ASR_WAV_EMPTY_DATA requestId={}", requestId);
                    return new byte[0];
                }
                // Extract the PCM samples into a new array
                byte[] pcm = new byte[dataLen];
                System.arraycopy(wavBytes, dataStart, pcm, 0, dataLen);
                log.info("ASR_WAV_HEADER_STRIPPED requestId={} headerBytes={} pcmBytes={}", requestId, dataStart, pcm.length);
                return pcm;
            }
            offset += 8 + chunkSize;
            if (chunkSize % 2 != 0) {
                offset++;
            }
        }

        log.warn("ASR_WAV_NO_DATA_CHUNK requestId={} - no data chunk found, returning as-is", requestId);
        return wavBytes;
    }

    /** Truncates a string to {@code max} characters, appending "..." if it was longer. */
    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
