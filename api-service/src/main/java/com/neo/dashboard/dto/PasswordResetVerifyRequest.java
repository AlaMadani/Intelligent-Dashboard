package com.neo.dashboard.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for the code-verification step of the password-reset flow.
 * The user enters the 6-digit code they received, and on success the server
 * returns a signed reset token used in the confirmation step.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PasswordResetVerifyRequest {
    /* Email address that initiated the reset flow. */
    @NotBlank(message = "Email is required")
    @Email(message = "Email should be valid")
    private String email;

    /* 6-digit code delivered to the user's email. */
    @NotBlank(message = "Verification code is required")
    @Pattern(regexp = "\\d{6}", message = "Verification code must contain 6 digits")
    private String code;
}
