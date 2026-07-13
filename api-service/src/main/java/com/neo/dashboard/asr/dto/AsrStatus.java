package com.neo.dashboard.asr.dto;

public record AsrStatus(
    boolean enabled,
    String provider,
    String protocol,
    String server,
    String model,
    String method,
    boolean apiKeyPresent,
    boolean apiKeyLooksValid,
    boolean ffmpegAvailable,
    String ffmpegPath,
    boolean convertToWav
) {}
