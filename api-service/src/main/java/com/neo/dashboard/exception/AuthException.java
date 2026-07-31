package com.neo.dashboard.exception;

import com.neo.dashboard.dto.AuthResponse;
import org.springframework.http.HttpStatus;

public class AuthException extends RuntimeException {

    /** The HTTP status that should be returned to the client. */
    private final HttpStatus status;
    /** A machine-readable error code. */
    private final String error;
    /** The structured {@link AuthResponse} body to return, containing the message and optional data. */
    private final AuthResponse response;

    /**
     * Constructs an authentication-specific exception that carries a full
     * {@link AuthResponse} payload.
     *
     * @param status   HTTP response status
     * @param error    short error code
     * @param response structured response body to return to the client
     */
    public AuthException(HttpStatus status, String error, AuthResponse response) {
        super(response.getMessage());
        this.status = status;
        this.error = error;
        this.response = response;
    }

    /** Returns the intended HTTP response status. */
    public HttpStatus getStatus() {
        return status;
    }

    /** Returns the machine-readable error code. */
    public String getError() {
        return error;
    }

    /** Returns the structured response payload to send to the client. */
    public AuthResponse getResponse() {
        return response;
    }
}
