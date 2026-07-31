package com.neo.dashboard.service;

import com.neo.dashboard.entity.User;
import com.neo.dashboard.security.HashUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Manages the password-reset flow using Redis-backed storage. The flow is:
 * issue a reset code → verify the code (with attempt tracking) → exchange
 * the verified code for a one-time reset token → consume the token to
 * authorise the new password write.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    /** Redis key prefix for the hashed reset code. */
    private static final String CODE_PREFIX = "auth:password-reset:code:";
    /** Redis key prefix for the failed-attempts counter. */
    private static final String ATTEMPTS_PREFIX = "auth:password-reset:attempts:";
    /** Redis key prefix for the resend-cooldown flag. */
    private static final String COOLDOWN_PREFIX = "auth:password-reset:cooldown:";
    /** Redis key prefix for the one-time reset token hash. */
    private static final String TOKEN_PREFIX = "auth:password-reset:token:";

    /** Redis client for storing/tracking reset state. */
    private final StringRedisTemplate redisTemplate;
    /** Sends the reset code email to the user. */
    private final VerificationEmailService verificationEmailService;
    /** Provides salted hashing and constant-time comparison. */
    private final HashUtil hashUtil;
    /** Cryptographically strong RNG for code/token generation. */
    private final SecureRandom secureRandom = new SecureRandom();

    /** How long (seconds) a reset code remains valid. */
    @Value("${app.auth.verification.code-ttl-seconds:300}")
    private long codeTtlSeconds;

    /** Minimum wait (seconds) before a new code can be requested. */
    @Value("${app.auth.verification.resend-cooldown-seconds:120}")
    private long resendCooldownSeconds;

    /** Maximum consecutive failed code verifications before invalidation. */
    @Value("${app.auth.verification.max-attempts:5}")
    private long maxAttempts;

    /** TTL (seconds) of the one-time reset token issued after successful code verification. */
    @Value("${app.auth.password-reset.token-ttl-seconds:600}")
    private long resetTokenTtlSeconds;

    /**
     * Generates and stores a hashed reset code, then dispatches it via email.
     * Optionally enforces a resend cooldown.
     *
     * @param user           the target user
     * @param enforceCooldown if true, reject if the cooldown is still active
     * @return a DispatchResult with acceptance status and timing info
     */
    public DispatchResult issueCode(User user, boolean enforceCooldown) {
        String email = normalizeEmail(user.getEmail());
        // Check whether the resend cooldown is still active
        long cooldown = secondsToLive(cooldownKey(email));
        if (enforceCooldown && cooldown > 0) {
            return new DispatchResult(false, "Please wait before requesting another reset code",
                    cooldown, secondsToLive(codeKey(email)));
        }

        // Generate a 6-digit code, hash it, and store with a TTL
        String code = generateCode();
        redisTemplate.opsForValue().set(codeKey(email), hashSecret(email, code, "code"),
                Duration.ofSeconds(codeTtlSeconds));
        // Reset the attempt counter and any existing token, then set the cooldown
        redisTemplate.delete(List.of(attemptsKey(email), tokenKey(email)));
        redisTemplate.opsForValue().set(cooldownKey(email), "1", Duration.ofSeconds(resendCooldownSeconds));

        // Dispatch the plain-text code via email
        verificationEmailService.sendPasswordResetCode(user.getEmail(), user.getFullName(), code);
        return new DispatchResult(true, "Password reset code sent", resendCooldownSeconds, codeTtlSeconds);
    }

    /**
     * Validates a user-supplied reset code against the stored hash. On
     * success, deletes the code and returns a short-lived one-time reset
     * token that can later be exchanged for a password change.
     *
     * @param email the user's email
     * @param code  the plain-text code to verify
     * @return a ResetCodeResult with validity, message, remaining attempts,
     *         and the reset token (if valid)
     */
    public ResetCodeResult verifyCode(String email, String code) {
        String normalizedEmail = normalizeEmail(email);
        // Look up the stored hash; null means expired or never issued
        String storedHash = redisTemplate.opsForValue().get(codeKey(normalizedEmail));
        if (storedHash == null) {
            return new ResetCodeResult(false, "Password reset code expired. Request a new code.", 0, null);
        }

        // Check the attempt limit before doing the hash comparison
        long attempts = currentAttempts(normalizedEmail);
        if (attempts >= maxAttempts) {
            deleteCodeAndAttempts(normalizedEmail);
            return new ResetCodeResult(false, "Too many invalid attempts. Request a new code.", 0, null);
        }

        // Constant-time comparison to prevent timing attacks
        if (!secureEquals(storedHash, hashSecret(normalizedEmail, code, "code"))) {
            // Wrong code – increment the attempt counter
            long updatedAttempts = incrementAttempts(normalizedEmail);
            long remainingAttempts = Math.max(0, maxAttempts - updatedAttempts);
            if (remainingAttempts == 0) {
                deleteCodeAndAttempts(normalizedEmail);
                return new ResetCodeResult(false, "Too many invalid attempts. Request a new code.", 0, null);
            }
            return new ResetCodeResult(false, "Invalid password reset code", remainingAttempts, null);
        }

        // Code is correct – generate a one-time reset token and replace the
        // code with the token hash
        String resetToken = generateResetToken();
        redisTemplate.opsForValue().set(tokenKey(normalizedEmail),
                hashSecret(normalizedEmail, resetToken, "token"),
                Duration.ofSeconds(resetTokenTtlSeconds));
        deleteCodeAndAttempts(normalizedEmail);
        return new ResetCodeResult(true, "Code verified", maxAttempts - attempts, resetToken);
    }

    /**
     * Consumes a one-time reset token, thereby authorising the password
     * change. The token and cooldown are deleted on success.
     *
     * @param email      the user's email
     * @param resetToken the one-time token obtained from a successful
     *                   {@link #verifyCode} call
     * @return true if the token was valid and consumed
     */
    public boolean consumeResetToken(String email, String resetToken) {
        String normalizedEmail = normalizeEmail(email);
        String storedHash = redisTemplate.opsForValue().get(tokenKey(normalizedEmail));
        if (storedHash == null || !secureEquals(storedHash, hashSecret(normalizedEmail, resetToken, "token"))) {
            return false;
        }

        // Delete the token and cooldown so the same token cannot be reused
        redisTemplate.delete(List.of(tokenKey(normalizedEmail), cooldownKey(normalizedEmail)));
        return true;
    }

    /**
     * Returns the current cooldown and code-expiry timings without modifying
     * any state.
     */
    public ResetStatus status(String email) {
        String normalizedEmail = normalizeEmail(email);
        return new ResetStatus(secondsToLive(cooldownKey(normalizedEmail)),
                secondsToLive(codeKey(normalizedEmail)));
    }

    /**
     * Atomically increments the failed-attempts counter and sets its TTL on
     * first creation.
     */
    private long incrementAttempts(String email) {
        Long attempts = redisTemplate.opsForValue().increment(attemptsKey(email));
        // On the very first increment, set the expiry to match the code TTL
        if (attempts != null && attempts == 1) {
            redisTemplate.expire(attemptsKey(email), Duration.ofSeconds(codeTtlSeconds));
        }
        return attempts == null ? 1 : attempts;
    }

    /** Reads the current failed-attempts count (0 if no entry exists). */
    private long currentAttempts(String email) {
        String rawAttempts = redisTemplate.opsForValue().get(attemptsKey(email));
        if (rawAttempts == null) {
            return 0;
        }
        try {
            return Long.parseLong(rawAttempts);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** Deletes both the stored code and its attempt counter. */
    private void deleteCodeAndAttempts(String email) {
        redisTemplate.delete(List.of(codeKey(email), attemptsKey(email)));
    }

    /** Returns the remaining TTL of a key in seconds (0 if absent or expired). */
    private long secondsToLive(String key) {
        Long seconds = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (seconds == null || seconds < 0) {
            return 0;
        }
        return seconds;
    }

    /** Generates a cryptographically random 6-digit code as a zero-padded string. */
    private String generateCode() {
        return String.format(Locale.ROOT, "%06d", secureRandom.nextInt(1_000_000));
    }

    /** Generates a 32-byte random reset token, base64url-encoded (no padding). */
    private String generateResetToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Produces a salted hash of the secret bound to the email for the given purpose. */
    private String hashSecret(String email, String secret, String purpose) {
        return hashUtil.hashWithSalt(email, secret, purpose);
    }

    /** Constant-time equality check to prevent timing side-channels. */
    private boolean secureEquals(String expected, String actual) {
        return hashUtil.secureEquals(expected, actual);
    }

    /** Builds the full Redis key for the reset code hash. */
    private String codeKey(String email) {
        return CODE_PREFIX + normalizeEmail(email);
    }

    /** Builds the full Redis key for the attempt counter. */
    private String attemptsKey(String email) {
        return ATTEMPTS_PREFIX + normalizeEmail(email);
    }

    /** Builds the full Redis key for the resend-cooldown flag. */
    private String cooldownKey(String email) {
        return COOLDOWN_PREFIX + normalizeEmail(email);
    }

    /** Builds the full Redis key for the one-time reset token hash. */
    private String tokenKey(String email) {
        return TOKEN_PREFIX + normalizeEmail(email);
    }

    /** Trims and lower-cases the email for consistent key generation. */
    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** Outcome of a code-issuance attempt, including timings. */
    public record DispatchResult(boolean accepted, String message,
                                  long resendAvailableInSeconds, long verificationExpiresInSeconds) {
    }

    /** Outcome of a code-verification attempt. */
    public record ResetCodeResult(boolean valid, String message,
                                   long remainingAttempts, String resetToken) {
    }

    /** Current cooldown and code-expiry state for a given email. */
    public record ResetStatus(long resendAvailableInSeconds, long verificationExpiresInSeconds) {
    }
}
