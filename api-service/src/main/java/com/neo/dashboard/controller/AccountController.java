package com.neo.dashboard.controller;

import com.neo.dashboard.dto.AuthResponse;
import com.neo.dashboard.dto.ChangePasswordRequest;
import com.neo.dashboard.exception.AuthException;
import com.neo.dashboard.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * REST controller for authenticated user account management.
 * Provides endpoints for account-level operations such as password changes,
 * all scoped to the currently authenticated user's identity.
 */
@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountController {

    /** Service handling authentication and account-related business logic. */
    private final AuthService authService;

    /**
     * Changes the password for the currently authenticated user.
     * Validates that the request includes a valid current password and a new password
     * meeting policy requirements, then delegates to the auth service.
     *
     * @param principal the Spring Security principal representing the authenticated user
     * @param request   the change password payload containing current and new passwords
     * @return an AuthResponse indicating success or failure of the operation
     * @throws AuthException if no authenticated principal is present
     */
    @PostMapping("/password")
    public AuthResponse changePassword(
            Principal principal,
            @Valid @RequestBody ChangePasswordRequest request
    ) {
        /* Guard: reject unauthenticated requests before processing */
        if (principal == null) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", AuthResponse.builder()
                    .success(false)
                    .message("Authentication is required")
                    .build());
        }

        /* Delegate password update to the service layer using the authenticated user's name */
        return authService.changePassword(principal.getName(), request);
    }
}
