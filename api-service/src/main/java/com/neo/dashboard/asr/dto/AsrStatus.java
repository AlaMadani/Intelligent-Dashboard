package com.neo.dashboard.asr.dto;

/**
 * Immutable snapshot of the current ASR subsystem status. Returned by the
 * status endpoints to let callers inspect provider configuration, API key
 * validity, and ffmpeg availability in a single structured object.
 */
public record AsrStatus(
    /** Whether the ASR feature is enabled in configuration. */
    boolean enabled,
    /** Active provider identifier (e.g. "nvidia-riva", "openai-compatible-audio"). */
    String provider,
    /** Transport protocol ("grpc" or "rest"). */
    String protocol,
    /** Provider server address. */
    String server,
    /** ASR model name. */
    String model,
    /** Recognition method ("streaming" or "recognize"). */
    String method,
    /** Whether an API key value is present (non-blank). */
    boolean apiKeyPresent,
    /** Whether the API key passes basic validation (length > 40). */
    boolean apiKeyLooksValid,
    /** Whether ffmpeg was detected during startup. */
    boolean ffmpegAvailable,
    /** Configured ffmpeg path. */
    String ffmpegPath,
    /** Whether audio-to-WAV conversion is enabled. */
    boolean convertToWav
) {}
