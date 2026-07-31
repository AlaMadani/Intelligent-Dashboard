package com.neo.dashboard.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Credentials submitted during the standard email/password sign-in flow.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SignInRequest {
    /* Registered email address acting as the login identifier. */
    @NotBlank(message = "Email is required")
    @Email(message = "Email should be valid")
    private String email;

    /* Corresponding plain-text password (validated server-side against the hash). */
    @NotBlank(message = "Password is required")
    private String password;
}
