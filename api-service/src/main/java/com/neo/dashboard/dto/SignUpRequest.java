package com.neo.dashboard.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Registration payload for new user accounts. The full name, a valid email,
 * and a password (with confirmation) are all required server-side.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SignUpRequest {
    /* User's display name (2–100 characters). */
    @NotBlank(message = "Full name is required")
    @Size(min = 2, max = 100, message = "Full name must be between 2 and 100 characters")
    private String fullName;

    /* Email address used as the account login. Must not already be taken. */
    @NotBlank(message = "Email is required")
    @Email(message = "Email should be valid")
    private String email;

    /* Desired password (8–255 characters). */
    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 255, message = "Password must be at least 8 characters long")
    private String password;

    /* Repeat of the chosen password – must match {@code password}. */
    @NotBlank(message = "Password confirmation is required")
    private String passwordConfirm;
}
