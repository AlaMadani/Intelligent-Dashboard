package com.neo.dashboard.exception;

import com.neo.dashboard.dto.AuthResponse;
import org.springframework.http.HttpStatus;

public class AuthException extends RuntimeException {

    private final HttpStatus status;
    private final String error;
    private final AuthResponse response;

    public AuthException(HttpStatus status, String error, AuthResponse response) {
        super(response.getMessage());
        this.status = status;
        this.error = error;
        this.response = response;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public AuthResponse getResponse() {
        return response;
    }
}
