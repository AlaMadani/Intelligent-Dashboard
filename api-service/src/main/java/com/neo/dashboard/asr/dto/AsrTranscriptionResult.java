package com.neo.dashboard.asr.dto;

/**
 * Immutable result of a single ASR transcription request. Contains the
 * recognized text alongside metadata such as provider info, processing
 * latency, and the correlation request ID for tracing.
 */
public record AsrTranscriptionResult(
    /** The transcribed text returned by the ASR provider. */
    String transcript,
    /** Provider that produced the transcription. */
    String provider,
    /** Model used for recognition. */
    String model,
    /** BCP-47 language code applied to the request. */
    String languageCode,
    /** End-to-end latency of the transcription in milliseconds. */
    long latencyMs,
    /** Duration of the source audio in milliseconds (may be null if unknown). */
    Long audioDurationMs,
    /** Character count of the transcript (null-safe convenience field). */
    Integer transcriptLength,
    /** Unique request identifier for correlation in logs. */
    String requestId
) {}
