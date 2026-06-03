package com.neo.dashboard.exception;

import com.neo.dashboard.dto.AuthResponse;
import com.neo.dashboard.dto.v36.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

@RestControllerAdvice
@Slf4j
public class GlobalApiExceptionHandler {

    private boolean isSseRequest(HttpServletRequest request) {
        if (request == null) return false;
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/event-stream");
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleSseClientDisconnect(AsyncRequestNotUsableException ex, HttpServletRequest request) {
        log.debug("SSE client disconnected path={}", request != null ? request.getRequestURI() : "unknown");
    }

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

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<AuthResponse> handleAuthException(AuthException ex) {
        return ResponseEntity.status(ex.getStatus()).body(ex.getResponse());
    }

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
