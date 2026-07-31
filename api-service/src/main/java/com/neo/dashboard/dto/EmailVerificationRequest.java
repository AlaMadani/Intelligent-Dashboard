package com.neo.dashboard.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for verifying a user's email address with the 6-digit code
 * sent during sign-up or after a resend.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailVerificationRequest {
    /* Email address that must be verified. */
    @NotBlank(message = "Email is required")
    @Email(message = "Email should be valid")
    private String email;

    /* 6-digit numeric code delivered to the user's inbox. */
    @NotBlank(message = "Verification code is required")
    @Pattern(regexp = "\\d{6}", message = "Verification code must contain 6 digits")
    private String code;
}
