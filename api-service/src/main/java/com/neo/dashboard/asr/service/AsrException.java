package com.neo.dashboard.asr.service;

/**
 * Specialized runtime exception for ASR-related failures. Carries a symbolic
 * {@code errorCode} (e.g. {@code "ASR_TIMEOUT"}) and an HTTP status code so
 * that a global exception handler can map it to the appropriate API response
 * status without inspecting the message string.
 */
public class AsrException extends RuntimeException {
    /** Machine-readable error code for client-side discrimination. */
    private final String errorCode;
    /** HTTP status that should be returned to the API caller. */
    private final int httpStatus;

    /**
     * Constructs an {@code AsrException} whose HTTP status is automatically
     * derived from the error code via a switch expression.
     *
     * @param errorCode symbolic error identifier
     * @param message   human-readable description
     */
    public AsrException(String errorCode, String message) {
        this(errorCode, message, switch (errorCode) {
            case "ASR_DISABLED" -> 503;
            case "ASR_INVALID_AUDIO" -> 400;
            case "ASR_AUDIO_TOO_LARGE" -> 413;
            case "ASR_API_KEY_MISSING_OR_INVALID" -> 502;
            case "ASR_API_KEY_INVALID" -> 502;
            case "ASR_METHOD_UNIMPLEMENTED" -> 501;
            case "ASR_AUTH_FAILED" -> 401;
            case "ASR_PERMISSION_DENIED" -> 403;
            case "ASR_PROVIDER_ENDPOINT_NOT_FOUND" -> 502;
            case "ASR_CONVERSION_FAILED" -> 500;
            case "ASR_PROVIDER_TIMEOUT" -> 504;
            case "ASR_PROVIDER_UNAVAILABLE" -> 502;
            default -> 502;
        });
    }

    /**
     * Constructs an {@code AsrException} with an explicit HTTP status,
     * bypassing the automatic code-to-status mapping.
     */
    public AsrException(String errorCode, String message, int httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    /** Returns the symbolic error code. */
    public String getErrorCode() { return errorCode; }

    /** Returns the HTTP status code that should be returned to the client. */
    public int getHttpStatus() { return httpStatus; }
}
