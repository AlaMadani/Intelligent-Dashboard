package com.neo.dashboard.controller;

import com.neo.dashboard.dto.AuthResponse;
import com.neo.dashboard.dto.EmailVerificationRequest;
import com.neo.dashboard.dto.ForgotPasswordRequest;
import com.neo.dashboard.dto.PasswordResetConfirmRequest;
import com.neo.dashboard.dto.ResendVerificationRequest;
import com.neo.dashboard.dto.PasswordResetVerifyRequest;
import com.neo.dashboard.dto.SignInRequest;
import com.neo.dashboard.dto.SignUpRequest;
import com.neo.dashboard.exception.AuthException;
import com.neo.dashboard.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse signUp(@Valid @RequestBody SignUpRequest request) {
        return authService.signUp(request);
    }

    @PostMapping("/signin")
    public AuthResponse signIn(@Valid @RequestBody SignInRequest request) {
        return authService.signIn(request);
    }

    @PostMapping("/verify-email")
    public AuthResponse verifyEmail(@Valid @RequestBody EmailVerificationRequest request) {
        return authService.verifyEmail(request);
    }

    @PostMapping("/resend-verification")
    public AuthResponse resendVerificationCode(@Valid @RequestBody ResendVerificationRequest request) {
        return authService.resendVerificationCode(request);
    }

    @PostMapping("/forgot-password")
    public AuthResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        return authService.forgotPassword(request);
    }

    @PostMapping("/forgot-password/verify")
    public AuthResponse verifyPasswordResetCode(@Valid @RequestBody PasswordResetVerifyRequest request) {
        return authService.verifyPasswordResetCode(request);
    }

    @PostMapping("/forgot-password/reset")
    public AuthResponse resetPassword(@Valid @RequestBody PasswordResetConfirmRequest request) {
        return authService.resetPassword(request);
    }

    @PostMapping("/refresh")
    public AuthResponse refreshToken(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader
    ) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REQUIRED", AuthResponse.builder()
                    .success(false)
                    .message("Refresh token is required")
                    .build());
        }

        String token = authorizationHeader.replace("Bearer ", "");
        return authService.refreshToken(token);
    }

    @GetMapping("/health")
    public String health() {
        return "Auth service is running";
    }
}
