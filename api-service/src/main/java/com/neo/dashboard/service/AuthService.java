package com.neo.dashboard.service;

import com.neo.dashboard.dto.AuthResponse;
import com.neo.dashboard.dto.ChangePasswordRequest;
import com.neo.dashboard.dto.EmailVerificationRequest;
import com.neo.dashboard.dto.ForgotPasswordRequest;
import com.neo.dashboard.dto.PasswordResetConfirmRequest;
import com.neo.dashboard.dto.PasswordResetVerifyRequest;
import com.neo.dashboard.dto.ResendVerificationRequest;
import com.neo.dashboard.dto.SignInRequest;
import com.neo.dashboard.dto.SignUpRequest;
import com.neo.dashboard.entity.User;
import com.neo.dashboard.entity.UserRole;
import com.neo.dashboard.exception.AuthException;
import com.neo.dashboard.repository.UserRepository;
import com.neo.dashboard.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final EmailVerificationService emailVerificationService;
    private final PasswordResetService passwordResetService;

    @Value("${app.auth.allowed-domain:@noveocare.com}")
    private String allowedDomain;

    public AuthResponse signUp(SignUpRequest request) {
        String email = normalizeEmail(request.getEmail());
        String signUpDomain = normalizeAllowedDomain();

        if (!email.endsWith(signUpDomain)) {
            throw authException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_EMAIL_DOMAIN",
                    "Sign up is only allowed with " + signUpDomain + " email addresses"
            );
        }

        Optional<User> existingUser = userRepository.findByEmail(email);
        if (existingUser.isPresent()) {
            User user = existingUser.get();
            if (!user.isEmailVerified()) {
                return pendingVerificationResponseWithAvailableCode(
                        user,
                        "Email already registered but not verified. Please verify your email.",
                        true
                );
            }
            throw authException(HttpStatus.BAD_REQUEST, "EMAIL_ALREADY_REGISTERED", "Email already registered");
        }

        if (!request.getPassword().equals(request.getPasswordConfirm())) {
            throw authException(HttpStatus.BAD_REQUEST, "PASSWORD_MISMATCH", "Passwords do not match");
        }

        User user = User.builder()
                .email(email)
                .fullName(request.getFullName())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(UserRole.ANALYST)
                .enabled(true)
                .emailVerified(false)
                .build();

        userRepository.save(user);
        EmailVerificationService.DispatchResult dispatch = emailVerificationService.issueCode(user, false);
        log.info("New user registered and pending email verification: {}", email);

        return pendingVerificationResponse(
                user,
                "Account created. Verification code sent to your email.",
                dispatch.resendAvailableInSeconds(),
                dispatch.verificationExpiresInSeconds(),
                true
        );
    }

    public AuthResponse signIn(SignInRequest request) {
        String email = normalizeEmail(request.getEmail());
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || !user.isEnabled() || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            log.warn("Sign in failed for email: {}", email);
            throw authException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
        }

        if (!user.isEmailVerified()) {
            throw authException(
                    HttpStatus.FORBIDDEN,
                    "EMAIL_VERIFICATION_REQUIRED",
                    pendingVerificationResponseWithAvailableCode(
                            user,
                            "Please verify your email before signing in.",
                            false
                    )
            );
        }

        String accessToken = jwtTokenProvider.generateTokenFromEmail(user.getEmail());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getEmail());

        log.info("User signed in: {}", email);
        return buildAuthResponse(user, accessToken, refreshToken, "Sign in successful");
    }

    public AuthResponse verifyEmail(EmailVerificationRequest request) {
        String email = normalizeEmail(request.getEmail());
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            throw authException(HttpStatus.BAD_REQUEST, "INVALID_VERIFICATION_REQUEST", "Invalid verification request");
        }

        if (user.isEmailVerified()) {
            return issueTokens(user, "Email already verified");
        }

        EmailVerificationService.VerificationCheckResult check = emailVerificationService.verifyCode(email, request.getCode());
        if (!check.valid()) {
            EmailVerificationService.VerificationStatus status = emailVerificationService.status(email);
            throw authException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_VERIFICATION_CODE",
                    AuthResponse.builder()
                            .success(false)
                            .message(check.message())
                            .emailVerificationRequired(true)
                            .email(email)
                            .remainingAttempts(check.remainingAttempts())
                            .resendAvailableInSeconds(status.resendAvailableInSeconds())
                            .verificationExpiresInSeconds(status.verificationExpiresInSeconds())
                            .build()
            );
        }

        user.setEmailVerified(true);
        userRepository.save(user);
        emailVerificationService.clearVerification(email);
        log.info("Email verified for user: {}", email);

        return issueTokens(user, "Email verified successfully");
    }

    public AuthResponse resendVerificationCode(ResendVerificationRequest request) {
        String email = normalizeEmail(request.getEmail());
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            throw authException(HttpStatus.BAD_REQUEST, "USER_NOT_FOUND", "User not found");
        }

        if (user.isEmailVerified()) {
            return AuthResponse.builder()
                    .success(true)
                    .message("Email is already verified. You can sign in.")
                    .email(email)
                    .build();
        }

        EmailVerificationService.DispatchResult dispatch = emailVerificationService.issueCode(user, true);
        AuthResponse response = AuthResponse.builder()
                .success(dispatch.accepted())
                .message(dispatch.message())
                .emailVerificationRequired(true)
                .email(email)
                .resendAvailableInSeconds(dispatch.resendAvailableInSeconds())
                .verificationExpiresInSeconds(dispatch.verificationExpiresInSeconds())
                .build();
        if (!dispatch.accepted()) {
            throw authException(HttpStatus.TOO_MANY_REQUESTS, "VERIFICATION_CODE_COOLDOWN", response);
        }
        return response;
    }

    public AuthResponse forgotPassword(ForgotPasswordRequest request) {
        String email = normalizeEmail(request.getEmail());
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            throw authException(HttpStatus.BAD_REQUEST, "ACCOUNT_NOT_FOUND", "No account found with this email");
        }

        if (!user.isEnabled()) {
            throw authException(HttpStatus.BAD_REQUEST, "ACCOUNT_DISABLED", "This account is disabled");
        }

        PasswordResetService.DispatchResult dispatch = passwordResetService.issueCode(user, true);
        AuthResponse response = AuthResponse.builder()
                .success(dispatch.accepted())
                .message(dispatch.message())
                .passwordResetRequired(true)
                .email(email)
                .resendAvailableInSeconds(dispatch.resendAvailableInSeconds())
                .verificationExpiresInSeconds(dispatch.verificationExpiresInSeconds())
                .build();
        if (!dispatch.accepted()) {
            throw authException(HttpStatus.TOO_MANY_REQUESTS, "PASSWORD_RESET_CODE_COOLDOWN", response);
        }
        return response;
    }

    public AuthResponse verifyPasswordResetCode(PasswordResetVerifyRequest request) {
        String email = normalizeEmail(request.getEmail());
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            throw authException(HttpStatus.BAD_REQUEST, "ACCOUNT_NOT_FOUND", "No account found with this email");
        }

        PasswordResetService.ResetCodeResult check = passwordResetService.verifyCode(email, request.getCode());
        if (!check.valid()) {
            PasswordResetService.ResetStatus status = passwordResetService.status(email);
            throw authException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_PASSWORD_RESET_CODE",
                    AuthResponse.builder()
                            .success(false)
                            .message(check.message())
                            .passwordResetRequired(true)
                            .email(email)
                            .remainingAttempts(check.remainingAttempts())
                            .resendAvailableInSeconds(status.resendAvailableInSeconds())
                            .verificationExpiresInSeconds(status.verificationExpiresInSeconds())
                            .build()
            );
        }

        return AuthResponse.builder()
                .success(true)
                .message("Code verified")
                .passwordResetRequired(true)
                .email(email)
                .resetToken(check.resetToken())
                .build();
    }

    public AuthResponse resetPassword(PasswordResetConfirmRequest request) {
        String email = normalizeEmail(request.getEmail());
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            throw authException(HttpStatus.BAD_REQUEST, "ACCOUNT_NOT_FOUND", "No account found with this email");
        }

        if (!request.getPassword().equals(request.getPasswordConfirm())) {
            throw authException(HttpStatus.BAD_REQUEST, "PASSWORD_MISMATCH", "Passwords do not match");
        }

        if (!passwordResetService.consumeResetToken(email, request.getResetToken())) {
            throw authException(
                    HttpStatus.BAD_REQUEST,
                    "PASSWORD_RESET_SESSION_EXPIRED",
                    "Password reset session expired. Request a new code."
            );
        }

        user.setPassword(passwordEncoder.encode(request.getPassword()));
        userRepository.save(user);
        log.info("Password reset completed for user: {}", email);

        return AuthResponse.builder()
                .success(true)
                .message("Password reset successfully. You can sign in with your new password.")
                .email(email)
                .build();
    }

    public AuthResponse changePassword(String email, ChangePasswordRequest request) {
        String normalizedEmail = normalizeEmail(email);
        User user = userRepository.findByEmail(normalizedEmail).orElse(null);
        if (user == null) {
            throw authException(HttpStatus.BAD_REQUEST, "USER_NOT_FOUND", "User not found");
        }

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw authException(HttpStatus.BAD_REQUEST, "CURRENT_PASSWORD_INCORRECT", "Current password is incorrect");
        }

        if (!request.getNewPassword().equals(request.getPasswordConfirm())) {
            throw authException(HttpStatus.BAD_REQUEST, "PASSWORD_MISMATCH", "Passwords do not match");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        log.info("Password changed for user: {}", normalizedEmail);

        return AuthResponse.builder()
                .success(true)
                .message("Password changed successfully")
                .build();
    }

    public AuthResponse refreshToken(String refreshToken) {
        if (!jwtTokenProvider.validateToken(refreshToken) || !jwtTokenProvider.isRefreshToken(refreshToken)) {
            throw authException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Invalid or expired refresh token");
        }

        String email = jwtTokenProvider.getEmailFromToken(refreshToken);
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || !user.isEnabled() || !user.isEmailVerified()) {
            throw authException(HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND_OR_NOT_VERIFIED", "User not found or not verified");
        }

        return issueTokens(user, "Token refreshed successfully");
    }

    private AuthException authException(HttpStatus status, String error, String message) {
        return authException(status, error, AuthResponse.builder()
                .success(false)
                .message(message)
                .build());
    }

    private AuthException authException(HttpStatus status, String error, AuthResponse response) {
        return new AuthException(status, error, response);
    }

    private AuthResponse issueTokens(User user, String message) {
        String accessToken = jwtTokenProvider.generateTokenFromEmail(user.getEmail());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getEmail());
        return buildAuthResponse(user, accessToken, refreshToken, message);
    }

    private AuthResponse buildAuthResponse(User user, String accessToken, String refreshToken, String message) {
        return AuthResponse.builder()
                .success(true)
                .message(message)
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .user(AuthResponse.UserAuthDto.builder()
                        .id(user.getId())
                        .email(user.getEmail())
                        .fullName(user.getFullName())
                        .role(user.getRole().toString())
                        .emailVerified(user.isEmailVerified())
                        .build())
                .build();
    }

    private AuthResponse pendingVerificationResponseWithAvailableCode(User user, String message, boolean success) {
        EmailVerificationService.VerificationStatus status = emailVerificationService.status(user.getEmail());
        long resendAvailableInSeconds = status.resendAvailableInSeconds();
        long verificationExpiresInSeconds = status.verificationExpiresInSeconds();

        if (resendAvailableInSeconds == 0 && verificationExpiresInSeconds == 0) {
            EmailVerificationService.DispatchResult dispatch = emailVerificationService.issueCode(user, false);
            resendAvailableInSeconds = dispatch.resendAvailableInSeconds();
            verificationExpiresInSeconds = dispatch.verificationExpiresInSeconds();
        }

        return pendingVerificationResponse(user, message, resendAvailableInSeconds, verificationExpiresInSeconds, success);
    }

    private AuthResponse pendingVerificationResponse(
            User user,
            String message,
            long resendAvailableInSeconds,
            long verificationExpiresInSeconds,
            boolean success
    ) {
        return AuthResponse.builder()
                .success(success)
                .message(message)
                .emailVerificationRequired(true)
                .email(user.getEmail())
                .resendAvailableInSeconds(resendAvailableInSeconds)
                .verificationExpiresInSeconds(verificationExpiresInSeconds)
                .build();
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeAllowedDomain() {
        String domain = allowedDomain == null ? "@noveocare.com" : allowedDomain.trim().toLowerCase(Locale.ROOT);
        return domain.startsWith("@") ? domain : "@" + domain;
    }
}
