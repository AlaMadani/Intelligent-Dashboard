package com.neo.dashboard.asr.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Spring {@link ConfigurationProperties} that bind to the {@code app.asr.*} prefix.
 * Holds all ASR-provider-agnostic settings such as which provider to use, the
 * network protocol, the server endpoint, authentication, language, and audio
 * processing configuration.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.asr")
public class AsrProperties {
    /** Master toggle: set to false to disable all ASR endpoints. */
    private boolean enabled = true;
    /** Identifier of the active ASR provider (e.g. "nvidia-riva", "openai-compatible-audio"). */
    private String provider = "nvidia-riva";
    /** Transport protocol used to communicate with the provider ("grpc" or "rest"). */
    private String protocol = "grpc";
    /** Provider server address (host:port). */
    private String server = "grpc.nvcf.nvidia.com:443";
    /** Whether to use TLS when connecting to the server. */
    private boolean useSsl = true;
    /** NVIDIA Cloud Function function ID for Riva gRPC calls. */
    private String functionId = "71203149-d3b7-4460-8231-1be2543a1fca";
    /** API key / bearer token for the ASR provider. */
    private String apiKey = "";
    /** Default BCP-47 language code for transcription. */
    private String languageCode = "en-US";
    /** Maximum time (ms) to wait for a provider response before timing out. */
    private long timeoutMs = 15000;
    /** ASR model identifier sent to the provider. */
    private String model = "parakeet-1.1b-rnnt-multilingual-asr";
    /** Method used for recognition: "streaming" or "recognize". */
    private String method = "streaming";
    /** Audio processing settings (ffmpeg path, sample rate, etc.). */
    private Audio audio = new Audio();

    /**
     * Nested configuration for audio pre-processing before sending to the ASR
     * provider. Controls ffmpeg path, whether to convert, target sample rate,
     * target channels, and maximum allowed file size.
     */
    @Data
    public static class Audio {
        /** Path or command name of the ffmpeg binary. */
        private String ffmpegPath = "ffmpeg";
        /** Whether unsupported audio formats should be converted to WAV via ffmpeg. */
        private boolean convertToWav = true;
        /** Target sample rate (Hz) for audio conversion (e.g. 16000). */
        private int targetSampleRate = 16000;
        /** Target number of audio channels (1 = mono). */
        private int targetChannels = 1;
        /** Maximum allowed audio file size in bytes before rejecting (default 10 MB). */
        private long maxSizeBytes = 10485760;
    }
}
