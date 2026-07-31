package com.neo.dashboard.service;

import com.neo.dashboard.entity.User;
import com.neo.dashboard.security.HashUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Manages email verification codes using Redis for storage. Handles code
 * generation, hashed storage, resend cooldowns, attempt tracking, and
 * max-attempt lockout.
 */
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    /** Redis key prefix for the hashed verification code. */
    private static final String CODE_PREFIX = "auth:email-verification:code:";
    /** Redis key prefix for the failed-attempts counter. */
    private static final String ATTEMPTS_PREFIX = "auth:email-verification:attempts:";
    /** Redis key prefix for the resend-cooldown flag. */
    private static final String COOLDOWN_PREFIX = "auth:email-verification:cooldown:";

    /** Redis client for storing/tracking verification state. */
    private final StringRedisTemplate redisTemplate;
    /** Sends the verification code email to the user. */
    private final VerificationEmailService verificationEmailService;
    /** Provides salted hashing and constant-time comparison. */
    private final HashUtil hashUtil;
    /** Cryptographically strong RNG for code generation. */
    private final SecureRandom secureRandom = new SecureRandom();

    /** How long (seconds) a verification code remains valid. */
    @Value("${app.auth.verification.code-ttl-seconds:300}")
    private long codeTtlSeconds;

    /** Minimum wait (seconds) before the user can request a new code. */
    @Value("${app.auth.verification.resend-cooldown-seconds:120}")
    private long resendCooldownSeconds;

    /** Maximum consecutive failed attempts before the code is invalidated. */
    @Value("${app.auth.verification.max-attempts:5}")
    private long maxAttempts;


    /**
     * Generates and stores a hashed verification code, then dispatches it
     * via email. Optionally enforces a resend cooldown.
     *
     * @param user           the target user
     * @param enforceCooldown if true, reject if the cooldown period is still active
     * @return a DispatchResult with acceptance status and timing info
     */
    public DispatchResult issueCode(User user, boolean enforceCooldown) {
        String email = normalizeEmail(user.getEmail());
        // Check whether the resend cooldown is still active
        long cooldown = secondsToLive(cooldownKey(email));
        if (enforceCooldown && cooldown > 0) {
            return new DispatchResult(
                    false,
                    "Please wait before requesting another verification code",
                    cooldown,
                    secondsToLive(codeKey(email))
            );
        }

        // Generate a 6-digit code, hash it, and persist with a TTL
        String code = generateCode();
        redisTemplate.opsForValue().set(codeKey(email), hashCode(email, code), Duration.ofSeconds(codeTtlSeconds));
        // Reset the attempt counter and set the cooldown marker
        redisTemplate.delete(attemptsKey(email));
        redisTemplate.opsForValue().set(cooldownKey(email), "1", Duration.ofSeconds(resendCooldownSeconds));

        // Send the plain-text code to the user's email
        verificationEmailService.sendVerificationCode(user.getEmail(), user.getFullName(), code);
        return new DispatchResult(true, "Verification code sent", resendCooldownSeconds, codeTtlSeconds);
    }

    /**
     * Validates a user-supplied code against the stored hash. Tracks
     * consecutive failures and invalidates the code after the configured
     * max attempts.
     *
     * @param email the user's email
     * @param code  the plain-text code to verify
     * @return a VerificationCheckResult with validity, message, and remaining
     *         attempts
     */
    public VerificationCheckResult verifyCode(String email, String code) {
        String normalizedEmail = normalizeEmail(email);
        // Look up the stored hash; null means expired or never issued
        String storedHash = redisTemplate.opsForValue().get(codeKey(normalizedEmail));
        if (storedHash == null) {
            return new VerificationCheckResult(false, "Verification code expired. Request a new code.", 0);
        }

        // Check attempt limit before costly hash comparison
        long attempts = currentAttempts(normalizedEmail);
        if (attempts >= maxAttempts) {
            deleteCodeAndAttempts(normalizedEmail);
            return new VerificationCheckResult(false, "Too many invalid attempts. Request a new code.", 0);
        }

        // Constant-time comparison to prevent timing attacks
        if (secureEquals(storedHash, hashCode(normalizedEmail, code))) {
            return new VerificationCheckResult(true, "Email verified", maxAttempts - attempts);
        }

        // Wrong code – increment the attempt counter
        long updatedAttempts = incrementAttempts(normalizedEmail);
        long remainingAttempts = Math.max(0, maxAttempts - updatedAttempts);
        if (remainingAttempts == 0) {
            // Exhausted all attempts – purge the code
            deleteCodeAndAttempts(normalizedEmail);
            return new VerificationCheckResult(false, "Too many invalid attempts. Request a new code.", 0);
        }

        return new VerificationCheckResult(false, "Invalid verification code", remainingAttempts);
    }

    /**
     * Returns the current cooldown and code-expiry timings without modifying
     * any state.
     */
    public VerificationStatus status(String email) {
        String normalizedEmail = normalizeEmail(email);
        return new VerificationStatus(secondsToLive(cooldownKey(normalizedEmail)), secondsToLive(codeKey(normalizedEmail)));
    }

    /**
     * Removes all verification-related Redis keys for the given email.
     */
    public void clearVerification(String email) {
        String normalizedEmail = normalizeEmail(email);
        redisTemplate.delete(List.of(codeKey(normalizedEmail), attemptsKey(normalizedEmail), cooldownKey(normalizedEmail)));
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

    /** Produces a salted hash of the code bound to the email. */
    private String hashCode(String email, String code) {
        return hashUtil.hashWithSalt(email, code, "code");
    }

    /** Constant-time equality check to prevent timing side-channels. */
    private boolean secureEquals(String expected, String actual) {
        return hashUtil.secureEquals(expected, actual);
    }

    /** Builds the full Redis key for the verification code hash. */
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

    /** Trims and lower-cases the email for consistent key generation. */
    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** Outcome of a code-issuance attempt, including timings. */
    public record DispatchResult(
            boolean accepted,
            String message,
            long resendAvailableInSeconds,
            long verificationExpiresInSeconds
    ) {
    }

    /** Outcome of a code-verification attempt. */
    public record VerificationCheckResult(boolean valid, String message, long remainingAttempts) {
    }

    /** Current cooldown and code-expiry state for a given email. */
    public record VerificationStatus(long resendAvailableInSeconds, long verificationExpiresInSeconds) {
    }
}
