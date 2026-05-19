package com.neo.dashboard.controller;

import com.neo.dashboard.dto.AuthResponse;
import com.neo.dashboard.dto.EmailVerificationRequest;
import com.neo.dashboard.dto.ForgotPasswordRequest;
import com.neo.dashboard.dto.PasswordResetConfirmRequest;
import com.neo.dashboard.dto.PasswordResetVerifyRequest;
import com.neo.dashboard.dto.ResendVerificationRequest;
import com.neo.dashboard.dto.SignInRequest;
import com.neo.dashboard.dto.SignUpRequest;
import com.neo.dashboard.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signUp(@Valid @RequestBody SignUpRequest request) {
        AuthResponse response = authService.signUp(request);
        if (response.isSuccess()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @PostMapping("/signin")
    public ResponseEntity<AuthResponse> signIn(@Valid @RequestBody SignInRequest request) {
        AuthResponse response = authService.signIn(request);
        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        }
        if (response.isEmailVerificationRequired()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    @PostMapping("/verify-email")
    public ResponseEntity<AuthResponse> verifyEmail(@Valid @RequestBody EmailVerificationRequest request) {
        AuthResponse response = authService.verifyEmail(request);
        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<AuthResponse> resendVerificationCode(@Valid @RequestBody ResendVerificationRequest request) {
        AuthResponse response = authService.resendVerificationCode(request);
        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        }
        if (response.getResendAvailableInSeconds() != null && response.getResendAvailableInSeconds() > 0) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(response);
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<AuthResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        AuthResponse response = authService.forgotPassword(request);
        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        }
        if (response.getResendAvailableInSeconds() != null && response.getResendAvailableInSeconds() > 0) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(response);
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @PostMapping("/forgot-password/verify")
    public ResponseEntity<AuthResponse> verifyPasswordResetCode(@Valid @RequestBody PasswordResetVerifyRequest request) {
        AuthResponse response = authService.verifyPasswordResetCode(request);
        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @PostMapping("/forgot-password/reset")
    public ResponseEntity<AuthResponse> resetPassword(@Valid @RequestBody PasswordResetConfirmRequest request) {
        AuthResponse response = authService.resetPassword(request);
        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refreshToken(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader
    ) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(AuthResponse.builder()
                    .success(false)
                    .message("Refresh token is required")
                    .build());
        }

        String token = authorizationHeader.replace("Bearer ", "");
        AuthResponse response = authService.refreshToken(token);
        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Auth service is running");
    }
}
