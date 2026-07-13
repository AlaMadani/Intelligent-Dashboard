package com.neo.dashboard.asr.dto;

public record AsrTranscriptionResult(
    String transcript,
    String provider,
    String model,
    String languageCode,
    long latencyMs,
    Long audioDurationMs,
    Integer transcriptLength,
    String requestId
) {}
