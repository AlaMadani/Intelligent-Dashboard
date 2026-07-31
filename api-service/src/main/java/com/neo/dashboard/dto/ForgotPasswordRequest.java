package com.neo.dashboard.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body that starts the password-reset flow. The server sends a
 * reset code to the given email address if it belongs to a registered user.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ForgotPasswordRequest {
    /* Registered email address to send the reset instructions to. */
    @NotBlank(message = "Email is required")
    @Email(message = "Email should be valid")
    private String email;
}
