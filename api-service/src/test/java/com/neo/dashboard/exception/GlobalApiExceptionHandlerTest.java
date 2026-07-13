package com.neo.dashboard.exception;

import com.neo.dashboard.dto.AuthResponse;
import com.neo.dashboard.dto.v36.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalApiExceptionHandlerTest {

    @Test
    void apiExceptionUsesV36ErrorShape() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/alerts/missing");
        GlobalApiExceptionHandler handler = new GlobalApiExceptionHandler();

        ResponseEntity<ApiErrorResponse> response = handler.handleApiException(
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Alert not found"),
                request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getSchemaVersion()).isEqualTo("v3.6.1");
        assertThat(response.getBody().getPath()).isEqualTo("/api/v1/alerts/missing");
        assertThat(response.getBody().getError()).isEqualTo("NOT_FOUND");
    }

    @Test
    void authExceptionUsesAuthResponseShape() {
        GlobalApiExceptionHandler handler = new GlobalApiExceptionHandler();
        AuthResponse body = AuthResponse.builder()
                .success(false)
                .message("Invalid email or password")
                .build();

        ResponseEntity<AuthResponse> response = handler.handleAuthException(
                new AuthException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", body)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isSameAs(body);
        assertThat(response.getBody().isSuccess()).isFalse();
    }

    @Test
    void invalidJsonReturns400() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/dashboard-assistant/message");
        GlobalApiExceptionHandler handler = new GlobalApiExceptionHandler();

        ResponseEntity<ApiErrorResponse> response = handler.handleInvalidJson(
                new HttpMessageNotReadableException("JSON parse error: Unexpected character ('`')", mock(HttpInputMessage.class)),
                request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(400);
        assertThat(response.getBody().getError()).isEqualTo("INVALID_JSON");
        assertThat(response.getBody().getMessage()).contains("not valid JSON");
    }
}
