package com.neo.dashboard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for the change-password endpoint. Requires the current
 * password for verification, a new password meeting length rules, and a
 * confirmation field to catch typos.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChangePasswordRequest {
    /* The user's existing password – verified before the update is allowed. */
    @NotBlank(message = "Current password is required")
    private String currentPassword;

    /* Desired password (8–255 characters). */
    @NotBlank(message = "New password is required")
    @Size(min = 8, max = 255, message = "New password must be at least 8 characters long")
    private String newPassword;

    /* Repeat of the new password – must match {@code newPassword}. */
    @NotBlank(message = "Password confirmation is required")
    private String passwordConfirm;
}
