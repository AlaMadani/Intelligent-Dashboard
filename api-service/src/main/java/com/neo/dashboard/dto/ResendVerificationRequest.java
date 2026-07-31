package com.neo.dashboard.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body to re-issue the email-verification code. Typically invoked
 * when the original code expired or was lost, subject to a rate-limit
 * controlled by the server.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResendVerificationRequest {
    /* Email address that needs a fresh verification code. */
    @NotBlank(message = "Email is required")
    @Email(message = "Email should be valid")
    private String email;
}
