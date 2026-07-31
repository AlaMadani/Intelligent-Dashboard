package com.neo.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO returned after authentication-related operations (sign-in, sign-up,
 * refresh, password reset). Contains tokens, user details, and any
 * intermediate flow flags (e.g. email verification required).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthResponse {
    /* Whether the overall authentication operation succeeded. */
    private boolean success;
    /* Human-readable description of the result or error. */
    private String message;
    /* JWT access token for authenticated API calls. */
    private String accessToken;
    /* JWT refresh token used to obtain a new access token. */
    private String refreshToken;
    /* Authenticated user details (id, email, name, role, verification status). */
    private UserAuthDto user;
    /* Signals the client that the user must verify their email before proceeding. */
    private boolean emailVerificationRequired;
    /* Signals the client that the user must reset their password before proceeding. */
    private boolean passwordResetRequired;
    /* Email address associated with the current operation (e.g. forgot-password). */
    private String email;
    /* Token carrying the password-reset grant for the confirmation step. */
    private String resetToken;
    /* Seconds the client must wait before requesting another resend. */
    private Long resendAvailableInSeconds;
    /* Seconds until the current verification code or token expires. */
    private Long verificationExpiresInSeconds;
    /* Remaining attempts before the operation is temporarily locked. */
    private Long remainingAttempts;

    /**
     * Minimal user profile embedded inside the authentication response.
     * Exposes enough info for the client to build a UI header without
     * a separate profile call.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UserAuthDto {
        /* Internal user primary key. */
        private Long id;
        /* User's email address (also used as the login identifier). */
        private String email;
        /* Display name shown in the UI. */
        private String fullName;
        /* Role assigned to the user (e.g. ADMIN, USER). */
        private String role;
        /* Whether the user has confirmed their email address. */
        private boolean emailVerified;
    }
}
