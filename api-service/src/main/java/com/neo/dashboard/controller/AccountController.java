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

@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountController {

    private final AuthService authService;

    @PostMapping("/password")
    public AuthResponse changePassword(
            Principal principal,
            @Valid @RequestBody ChangePasswordRequest request
    ) {
        if (principal == null) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", AuthResponse.builder()
                    .success(false)
                    .message("Authentication is required")
                    .build());
        }

        return authService.changePassword(principal.getName(), request);
    }
}
