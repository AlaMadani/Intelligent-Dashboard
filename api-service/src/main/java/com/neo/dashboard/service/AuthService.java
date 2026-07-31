package com.neo.dashboard.service;

import com.neo.dashboard.dto.AuthResponse;
import com.neo.dashboard.dto.ChangePasswordRequest;
import com.neo.dashboard.mapper.AuthApiMapper;
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

/**
 * Orchestrates all authentication-related flows: sign-up, sign-in, email
 * verification, password reset, password change, and token refresh.
 */
@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class AuthService {

    /** Persistence layer for user accounts. */
    private final UserRepository userRepository;
    /** Spring Security password encoder for hashing credentials. */
    private final PasswordEncoder passwordEncoder;
    /** Issues signed JWT access and refresh tokens. */
    private final JwtTokenProvider jwtTokenProvider;
    /** Handles email verification code dispatch and validation. */
    private final EmailVerificationService emailVerificationService;
    /** Handles password reset code dispatch and token exchange. */
    private final PasswordResetService passwordResetService;
    /** Maps domain entities to API DTOs. */
    private final AuthApiMapper authApiMapper;

    /** Only email addresses under this domain are allowed to sign up. */
    @Value("${app.auth.allowed-domain:@noveocare.com}")
    private String allowedDomain;

    /**
     * Registers a new user account after validating the email domain,
     * checking for duplicates, and dispatching a verification code.
     *
     * @param request the sign-up payload containing email, name, and passwords
     * @return an AuthResponse directing the client to verify their email
     */
    public AuthResponse signUp(SignUpRequest request) {
        // Normalize email to lowercase trimmed form
        String email = normalizeEmail(request.getEmail());
        // Normalize the configured allowed domain
        String signUpDomain = normalizeAllowedDomain();

        // Reject sign-up if the email domain does not match the allowed domain
        if (!email.endsWith(signUpDomain)) {
            throw authException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_EMAIL_DOMAIN",
                    "Sign up is only allowed with " + signUpDomain + " email addresses"
            );
        }

        // Look up existing user by email
        Optional<User> existingUser = userRepository.findByEmail(email);
        if (existingUser.isPresent()) {
            User user = existingUser.get();
            // If the user exists but hasn't verified, allow them to re-verify
            if (!user.isEmailVerified()) {
                return pendingVerificationResponseWithAvailableCode(
                        user,
                        "Email already registered but not verified. Please verify your email.",
                        true
                );
            }
            // Fully registered duplicate
            throw authException(HttpStatus.BAD_REQUEST, "EMAIL_ALREADY_REGISTERED", "Email already registered");
        }

        // Validate that password and confirmation match
        if (!request.getPassword().equals(request.getPasswordConfirm())) {
            throw authException(HttpStatus.BAD_REQUEST, "PASSWORD_MISMATCH", "Passwords do not match");
        }

        // Build a new User entity with hashed password and default ANALYST role
        User user = User.builder()
                .email(email)
                .fullName(request.getFullName())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(UserRole.ANALYST)
                .enabled(true)
                .emailVerified(false)
                .build();

        // Persist the new user
        userRepository.save(user);
        // Issue an email verification code (no cooldown enforcement on fresh sign-up)
        EmailVerificationService.DispatchResult dispatch = emailVerificationService.issueCode(user, false);
        log.info("New user registered and pending email verification: {}", email);

        // Return a response instructing the client to verify their email
        return pendingVerificationResponse(
                user,
                "Account created. Verification code sent to your email.",
                dispatch.resendAvailableInSeconds(),
                dispatch.verificationExpiresInSeconds(),
                true
        );
    }

    /** Carries the result of a successful credential validation. */
    public record SignInOutput(User user, String accessToken, String refreshToken) {}

    /**
     * Validates credentials and returns tokens without building the full response DTO.
     *
     * @param request the sign-in payload
     * @return a SignInOutput with the authenticated user and tokens
     */
    public SignInOutput authenticate(SignInRequest request) {
        String email = normalizeEmail(request.getEmail());
        // Fetch user; if missing, disabled, or password wrong, fail
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || !user.isEnabled() || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            log.warn("Sign in failed for email: {}", email);
            throw authException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
        }

        // Enforce email verification before allowing sign-in
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

        // Generate both access and refresh JWT tokens
        String accessToken = jwtTokenProvider.generateTokenFromEmail(user.getEmail());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getEmail());

        log.info("User signed in: {}", email);
        return new SignInOutput(user, accessToken, refreshToken);
    }

    /**
     * Validates credentials and returns a full AuthResponse suitable for the API.
     *
     * @param request the sign-in payload
     * @return AuthResponse containing tokens and user DTO
     */
    public AuthResponse signIn(SignInRequest request) {
        SignInOutput output = authenticate(request);
        return buildAuthResponse(output.user(), output.accessToken(), output.refreshToken(), "Sign in successful");
    }

    /**
     * Verifies the email with a confirmation code. On success, marks the user
     * as verified and issues JWT tokens.
     *
     * @param request the verification request containing email and code
     * @return AuthResponse with tokens if verification succeeded
     */
    public AuthResponse verifyEmail(EmailVerificationRequest request) {
        String email = normalizeEmail(request.getEmail());
        // Validate that the user exists
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            throw authException(HttpStatus.BAD_REQUEST, "INVALID_VERIFICATION_REQUEST", "Invalid verification request");
        }

        // Already verified – silently return tokens
        if (user.isEmailVerified()) {
            return issueTokens(user, "Email already verified");
        }

        // Delegate code verification to the dedicated service
        EmailVerificationService.VerificationCheckResult check = emailVerificationService.verifyCode(email, request.getCode());
        if (!check.valid()) {
            // Code invalid or expired – retrieve remaining timings and report details
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

        // Mark the user as verified and clean up Redis state
        user.setEmailVerified(true);
        userRepository.save(user);
        emailVerificationService.clearVerification(email);
        log.info("Email verified for user: {}", email);

        return issueTokens(user, "Email verified successfully");
    }

    /**
     * Re-issues a verification code, respecting the resend cooldown.
     *
     * @param request contains the email address
     * @return AuthResponse indicating whether a new code was sent
     */
    public AuthResponse resendVerificationCode(ResendVerificationRequest request) {
        String email = normalizeEmail(request.getEmail());
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            throw authException(HttpStatus.BAD_REQUEST, "USER_NOT_FOUND", "User not found");
        }

        // User is already verified – no need to resend
        if (user.isEmailVerified()) {
            return AuthResponse.builder()
                    .success(true)
                    .message("Email is already verified. You can sign in.")
                    .email(email)
                    .build();
        }

        // Issue a new code with cooldown enforcement
        EmailVerificationService.DispatchResult dispatch = emailVerificationService.issueCode(user, true);
        AuthResponse response = AuthResponse.builder()
                .success(dispatch.accepted())
                .message(dispatch.message())
                .emailVerificationRequired(true)
                .email(email)
                .resendAvailableInSeconds(dispatch.resendAvailableInSeconds())
                .verificationExpiresInSeconds(dispatch.verificationExpiresInSeconds())
                .build();
        // If the cooldown is still active, reject with 429
        if (!dispatch.accepted()) {
            throw authException(HttpStatus.TOO_MANY_REQUESTS, "VERIFICATION_CODE_COOLDOWN", response);
        }
        return response;
    }

    /**
     * Initiates a password-reset flow by issuing a reset code to the user's
     * email. Respects the resend cooldown.
     *
     * @param request contains the email address
     * @return AuthResponse indicating whether the code was sent
     */
    public AuthResponse forgotPassword(ForgotPasswordRequest request) {
        String email = normalizeEmail(request.getEmail());
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            throw authException(HttpStatus.BAD_REQUEST, "ACCOUNT_NOT_FOUND", "No account found with this email");
        }

        if (!user.isEnabled()) {
            throw authException(HttpStatus.BAD_REQUEST, "ACCOUNT_DISABLED", "This account is disabled");
        }

        // Issue a password-reset code with cooldown enforcement
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

    /**
     * Validates the password-reset code and, if correct, returns a short-lived
     * reset token that can be exchanged for a new password.
     *
     * @param request contains email, code, and optional reset token
     * @return AuthResponse with a resetToken on success
     */
    public AuthResponse verifyPasswordResetCode(PasswordResetVerifyRequest request) {
        String email = normalizeEmail(request.getEmail());
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            throw authException(HttpStatus.BAD_REQUEST, "ACCOUNT_NOT_FOUND", "No account found with this email");
        }

        // Delegate code verification to the password-reset service
        PasswordResetService.ResetCodeResult check = passwordResetService.verifyCode(email, request.getCode());
        if (!check.valid()) {
            // Code invalid/expired – return detailed timings in the error body
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

        // Return the short-lived reset token needed for the actual password change
        return AuthResponse.builder()
                .success(true)
                .message("Code verified")
                .passwordResetRequired(true)
                .email(email)
                .resetToken(check.resetToken())
                .build();
    }

    /**
     * Completes the password reset by consuming the reset token and persisting
     * the new password hash.
     *
     * @param request contains email, reset token, and the new password
     * @return AuthResponse confirming the password has been changed
     */
    public AuthResponse resetPassword(PasswordResetConfirmRequest request) {
        String email = normalizeEmail(request.getEmail());
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            throw authException(HttpStatus.BAD_REQUEST, "ACCOUNT_NOT_FOUND", "No account found with this email");
        }

        // New password and confirmation must match
        if (!request.getPassword().equals(request.getPasswordConfirm())) {
            throw authException(HttpStatus.BAD_REQUEST, "PASSWORD_MISMATCH", "Passwords do not match");
        }

        // Consume the one-time reset token; fail if expired or invalid
        if (!passwordResetService.consumeResetToken(email, request.getResetToken())) {
            throw authException(
                    HttpStatus.BAD_REQUEST,
                    "PASSWORD_RESET_SESSION_EXPIRED",
                    "Password reset session expired. Request a new code."
            );
        }

        // Persist the new hashed password
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        userRepository.save(user);
        log.info("Password reset completed for user: {}", email);

        return AuthResponse.builder()
                .success(true)
                .message("Password reset successfully. You can sign in with your new password.")
                .email(email)
                .build();
    }

    /**
     * Changes the password for an already authenticated user. Requires the
     * current password for verification.
     *
     * @param email   the authenticated user's email
     * @param request contains current + new password pair
     * @return AuthResponse confirming the change
     */
    public AuthResponse changePassword(String email, ChangePasswordRequest request) {
        String normalizedEmail = normalizeEmail(email);
        User user = userRepository.findByEmail(normalizedEmail).orElse(null);
        if (user == null) {
            throw authException(HttpStatus.BAD_REQUEST, "USER_NOT_FOUND", "User not found");
        }

        // Current password must match the stored hash
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw authException(HttpStatus.BAD_REQUEST, "CURRENT_PASSWORD_INCORRECT", "Current password is incorrect");
        }

        // New password and confirmation must match
        if (!request.getNewPassword().equals(request.getPasswordConfirm())) {
            throw authException(HttpStatus.BAD_REQUEST, "PASSWORD_MISMATCH", "Passwords do not match");
        }

        // Hash and persist the new password
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        log.info("Password changed for user: {}", normalizedEmail);

        return AuthResponse.builder()
                .success(true)
                .message("Password changed successfully")
                .build();
    }

    /**
     * Issues a new access + refresh token pair using a valid refresh token.
     *
     * @param refreshToken the JWT refresh token
     * @return AuthResponse containing fresh tokens
     */
    public AuthResponse refreshToken(String refreshToken) {
        // Validate that the token is a well-formed, non-expired refresh token
        if (!jwtTokenProvider.validateToken(refreshToken) || !jwtTokenProvider.isRefreshToken(refreshToken)) {
            throw authException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Invalid or expired refresh token");
        }

        // Extract the email and verify the user still exists and is active
        String email = jwtTokenProvider.getEmailFromToken(refreshToken);
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || !user.isEnabled() || !user.isEmailVerified()) {
            throw authException(HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND_OR_NOT_VERIFIED", "User not found or not verified");
        }

        return issueTokens(user, "Token refreshed successfully");
    }

    /**
     * Builds an AuthException from a simple string message.
     */
    private AuthException authException(HttpStatus status, String error, String message) {
        return authException(status, error, AuthResponse.builder()
                .success(false)
                .message(message)
                .build());
    }

    /**
     * Builds an AuthException with a pre-constructed AuthResponse body.
     */
    private AuthException authException(HttpStatus status, String error, AuthResponse response) {
        return new AuthException(status, error, response);
    }

    /**
     * Generates both access and refresh tokens for the given user and wraps
     * them in a success response.
     */
    private AuthResponse issueTokens(User user, String message) {
        String accessToken = jwtTokenProvider.generateTokenFromEmail(user.getEmail());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getEmail());
        return buildAuthResponse(user, accessToken, refreshToken, message);
    }

    /**
     * Assembles the full AuthResponse DTO with tokens and the mapped user DTO.
     */
    private AuthResponse buildAuthResponse(User user, String accessToken, String refreshToken, String message) {
        return AuthResponse.builder()
                .success(true)
                .message(message)
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .user(authApiMapper.toUserAuthDto(user))
                .build();
    }

    /**
     * Retrieves or creates a verification code when a pending-verification
     * response is needed but timings are unavailable.
     */
    private AuthResponse pendingVerificationResponseWithAvailableCode(User user, String message, boolean success) {
        EmailVerificationService.VerificationStatus status = emailVerificationService.status(user.getEmail());
        long resendAvailableInSeconds = status.resendAvailableInSeconds();
        long verificationExpiresInSeconds = status.verificationExpiresInSeconds();

        // If no code exists (both TTLs are zero), issue a fresh one
        if (resendAvailableInSeconds == 0 && verificationExpiresInSeconds == 0) {
            EmailVerificationService.DispatchResult dispatch = emailVerificationService.issueCode(user, false);
            resendAvailableInSeconds = dispatch.resendAvailableInSeconds();
            verificationExpiresInSeconds = dispatch.verificationExpiresInSeconds();
        }

        return pendingVerificationResponse(user, message, resendAvailableInSeconds, verificationExpiresInSeconds, success);
    }

    /**
     * Builds a generic "pending email verification" AuthResponse with
     * resend-cooldown and code-expiry timings.
     */
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

    /** Trims and lower-cases the email for consistent lookups. */
    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** Ensures the configured allowed domain has a leading "@" and is lower-cased. */
    private String normalizeAllowedDomain() {
        String domain = allowedDomain == null ? "@noveocare.com" : allowedDomain.trim().toLowerCase(Locale.ROOT);
        return domain.startsWith("@") ? domain : "@" + domain;
    }
}
