package com.neo.dashboard.exception;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    /** The HTTP status that should be returned to the client. */
    private final HttpStatus status;
    /** A machine-readable error code (e.g. {@code VALIDATION_ERROR}). */
    private final String error;

    /**
     * Constructs a general API exception.
     *
     * @param status  HTTP response status
     * @param error   short error code for the client
     * @param message human-readable description of the problem
     */
    public ApiException(HttpStatus status, String error, String message) {
        super(message);
        this.status = status;
        this.error = error;
    }

    /** Returns the intended HTTP response status. */
    public HttpStatus getStatus() {
        return status;
    }

    /** Returns the machine-readable error code. */
    public String getError() {
        return error;
    }
}
