package com.neo.dashboard.asr.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.asr")
public class AsrProperties {
    private boolean enabled = true;
    private String provider = "nvidia-riva";
    private String protocol = "grpc";
    private String server = "grpc.nvcf.nvidia.com:443";
    private boolean useSsl = true;
    private String functionId = "71203149-d3b7-4460-8231-1be2543a1fca";
    private String apiKey = "";
    private String languageCode = "en-US";
    private long timeoutMs = 15000;
    private String model = "parakeet-1.1b-rnnt-multilingual-asr";
    private String method = "streaming";
    private Audio audio = new Audio();

    @Data
    public static class Audio {
        private String ffmpegPath = "ffmpeg";
        private boolean convertToWav = true;
        private int targetSampleRate = 16000;
        private int targetChannels = 1;
        private long maxSizeBytes = 10485760;
    }
}
