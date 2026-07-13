package com.neo.dashboard.asr.service;

public class AsrException extends RuntimeException {
    private final String errorCode;
    private final int httpStatus;

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

    public AsrException(String errorCode, String message, int httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public String getErrorCode() { return errorCode; }
    public int getHttpStatus() { return httpStatus; }
}
