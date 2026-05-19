package com.neo.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthResponse {
    private boolean success;
    private String message;
    private String accessToken;
    private String refreshToken;
    private UserAuthDto user;
    private boolean emailVerificationRequired;
    private boolean passwordResetRequired;
    private String email;
    private String resetToken;
    private Long resendAvailableInSeconds;
    private Long verificationExpiresInSeconds;
    private Long remainingAttempts;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UserAuthDto {
        private Long id;
        private String email;
        private String fullName;
        private String role;
        private boolean emailVerified;
    }
}
