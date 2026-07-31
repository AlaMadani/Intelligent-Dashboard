package com.neo.dashboard.exception;

import com.neo.dashboard.asr.service.AsrException;
import com.neo.dashboard.dto.AuthResponse;
import com.neo.dashboard.dto.v36.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

@RestControllerAdvice
@Slf4j
public class GlobalApiExceptionHandler {

    /**
     * Checks whether the incoming request is an SSE (Server-Sent Events)
     * stream by inspecting the {@code Accept} header.
     */
    private boolean isSseRequest(HttpServletRequest request) {
        if (request == null) return false;
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/event-stream");
    }

    /** Silently swallows SSE client disconnects so they don't pollute the error log. */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleSseClientDisconnect(AsyncRequestNotUsableException ex, HttpServletRequest request) {
        log.debug("SSE client disconnected path={}", request != null ? request.getRequestURI() : "unknown");
    }

    /**
     * Translates a {@link ApiException} into an {@link ApiErrorResponse} JSON body.
     * For SSE requests only the HTTP status is returned (no body).
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(ApiException ex, HttpServletRequest request) {
        if (isSseRequest(request)) {
            log.debug("API SSE error path={} error={}", request.getRequestURI(), ex.getError());
            return ResponseEntity.status(ex.getStatus()).build();
        }
        return ResponseEntity.status(ex.getStatus()).body(ApiErrorResponse.of(
                request.getRequestURI(),
                ex.getStatus().value(),
                ex.getError(),
                ex.getMessage()
        ));
    }

    /** Forwards the structured {@link AuthResponse} carried by an {@link AuthException}. */
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<AuthResponse> handleAuthException(AuthException ex) {
        return ResponseEntity.status(ex.getStatus()).body(ex.getResponse());
    }

    /**
     * Translates an {@link AsrException} (ASR service error) into an error
     * response. For SSE requests only the HTTP status is returned.
     */
    @ExceptionHandler(AsrException.class)
    public ResponseEntity<ApiErrorResponse> handleAsrException(AsrException ex, HttpServletRequest request) {
        if (isSseRequest(request)) {
            log.debug("ASR SSE error path={} error={}", request.getRequestURI(), ex.getErrorCode());
            return ResponseEntity.status(ex.getHttpStatus()).build();
        }
        log.warn("ASR_ERROR errorType={} message={}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.status(ex.getHttpStatus()).body(ApiErrorResponse.of(
                request.getRequestURI(),
                ex.getHttpStatus(),
                ex.getErrorCode(),
                ex.getMessage()
        ));
    }

    /** Handles {@code @Valid} / {@code @Validated} constraint violations as 400. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        if (isSseRequest(request)) {
            log.debug("SSE validation error path={}", request.getRequestURI());
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.badRequest().body(ApiErrorResponse.of(
                request.getRequestURI(),
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_ERROR",
                "Request validation failed"
        ));
    }

    /** Handles malformed JSON in the request body as 400. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidJson(HttpMessageNotReadableException ex, HttpServletRequest request) {
        if (isSseRequest(request)) {
            log.debug("SSE invalid JSON path={}", request.getRequestURI());
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.badRequest().body(ApiErrorResponse.of(
                request.getRequestURI(),
                HttpStatus.BAD_REQUEST.value(),
                "INVALID_JSON",
                "Request body is not valid JSON."
        ));
    }

    /** Catch-all for any unhandled exception — returns 500. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        if (isSseRequest(request)) {
            log.debug("SSE error path={}", request.getRequestURI());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        log.warn("Unhandled API exception path={}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiErrorResponse.of(
                request.getRequestURI(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "INTERNAL_ERROR",
                "Unexpected server error"
        ));
    }
}
