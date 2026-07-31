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
import com.neo.dashboard.mapper.AuthApiMapper;
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

/**
 * REST controller for authentication and account management.
 * Handles user registration, login, email verification, password recovery,
 * and token refresh operations for the dashboard application.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    /** Core service for authentication, registration, and token management. */
    private final AuthService authService;

    /** Mapper for converting internal user domain objects to API DTOs. */
    private final AuthApiMapper authApiMapper;

    /**
     * Registers a new user account with the provided credentials and profile information.
     * Returns a 201 Created status upon successful registration.
     *
     * @param request the sign-up payload containing email, password, and profile details
     * @return an AuthResponse with the result of the registration attempt
     */
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse signUp(@Valid @RequestBody SignUpRequest request) {
        return authService.signUp(request);
    }

    /**
     * Authenticates a user with email and password credentials.
     * On success, returns an access token, refresh token, and user profile data.
     *
     * @param request the sign-in payload containing email and password
     * @return an AuthResponse with tokens and user data on successful authentication
     */
    @PostMapping("/signin")
    public AuthResponse signIn(@Valid @RequestBody SignInRequest request) {
        /* Authenticate credentials and extract user + token output from the service */
        AuthService.SignInOutput output = authService.authenticate(request);
        /* Map the internal user domain object to the API-facing DTO */
        AuthResponse.UserAuthDto userDto = authApiMapper.toUserAuthDto(output.user());
        /* Build the success response with tokens and user information */
        return AuthResponse.builder()
                .success(true)
                .message("Sign in successful")
                .accessToken(output.accessToken())
                .refreshToken(output.refreshToken())
                .user(userDto)
                .build();
    }

    /**
     * Verifies a user's email address using a verification code sent during registration.
     *
     * @param request the email verification payload containing the code
     * @return an AuthResponse indicating whether verification was successful
     */
    @PostMapping("/verify-email")
    public AuthResponse verifyEmail(@Valid @RequestBody EmailVerificationRequest request) {
        return authService.verifyEmail(request);
    }

    /**
     * Resends the email verification code to the user's registered email address.
     * Useful when the original verification email was lost or expired.
     *
     * @param request the resend verification payload containing the user's email
     * @return an AuthResponse confirming that a new code was sent
     */
    @PostMapping("/resend-verification")
    public AuthResponse resendVerificationCode(@Valid @RequestBody ResendVerificationRequest request) {
        return authService.resendVerificationCode(request);
    }

    /**
     * Initiates the password reset flow by sending a reset code to the user's email.
     *
     * @param request the forgot-password payload containing the user's email
     * @return an AuthResponse indicating that the reset code was sent
     */
    @PostMapping("/forgot-password")
    public AuthResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        return authService.forgotPassword(request);
    }

    /**
     * Verifies the password reset code provided by the user.
     * This is the second step of the password reset flow, confirming the code is valid.
     *
     * @param request the password reset verification payload containing the code
     * @return an AuthResponse indicating whether the code is valid
     */
    @PostMapping("/forgot-password/verify")
    public AuthResponse verifyPasswordResetCode(@Valid @RequestBody PasswordResetVerifyRequest request) {
        return authService.verifyPasswordResetCode(request);
    }

    /**
     * Completes the password reset flow by setting a new password.
     * This is the final step, executed after successful code verification.
     *
     * @param request the password reset confirmation payload with new password
     * @return an AuthResponse indicating whether the password was successfully updated
     */
    @PostMapping("/forgot-password/reset")
    public AuthResponse resetPassword(@Valid @RequestBody PasswordResetConfirmRequest request) {
        return authService.resetPassword(request);
    }

    /**
     * Refreshes the access token using a valid refresh token.
     * The refresh token must be provided in the Authorization header as a Bearer token.
     *
     * @param authorizationHeader the HTTP Authorization header containing the Bearer refresh token
     * @return an AuthResponse with a new access token (and optionally a new refresh token)
     * @throws AuthException if the Authorization header is missing or malformed
     */
    @PostMapping("/refresh")
    public AuthResponse refreshToken(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader
    ) {
        /* Validate the presence and format of the Bearer token header */
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REQUIRED", AuthResponse.builder()
                    .success(false)
                    .message("Refresh token is required")
                    .build());
        }

        /* Strip the "Bearer " prefix to extract the raw token value */
        String token = authorizationHeader.replace("Bearer ", "");
        return authService.refreshToken(token);
    }

    /**
     * Simple health-check endpoint to verify the auth controller is reachable.
     *
     * @return a plain-text confirmation string
     */
    @GetMapping("/health")
    public String health() {
        return "Auth service is running";
    }
}
