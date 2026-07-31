package com.neo.dashboard.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for the final step of the password-reset flow. The client
 * supplies the email, the reset token obtained after code verification, and
 * the new password (with confirmation).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PasswordResetConfirmRequest {
    /* Email address that owns the reset request. */
    @NotBlank(message = "Email is required")
    @Email(message = "Email should be valid")
    private String email;

    /* Opaque reset token returned by the verification step. */
    @NotBlank(message = "Reset token is required")
    private String resetToken;

    /* New password (8–255 characters). */
    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 255, message = "Password must be at least 8 characters long")
    private String password;

    /* Repeat of the new password – must match {@code password}. */
    @NotBlank(message = "Password confirmation is required")
    private String passwordConfirm;
}
