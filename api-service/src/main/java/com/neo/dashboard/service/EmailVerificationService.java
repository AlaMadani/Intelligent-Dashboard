package com.neo.dashboard.service;

import com.neo.dashboard.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Service
public class EmailVerificationService {

    private static final String CODE_PREFIX = "auth:email-verification:code:";
    private static final String ATTEMPTS_PREFIX = "auth:email-verification:attempts:";
    private static final String COOLDOWN_PREFIX = "auth:email-verification:cooldown:";

    private final StringRedisTemplate redisTemplate;
    private final VerificationEmailService verificationEmailService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.auth.verification.code-ttl-seconds:300}")
    private long codeTtlSeconds;

    @Value("${app.auth.verification.resend-cooldown-seconds:120}")
    private long resendCooldownSeconds;

    @Value("${app.auth.verification.max-attempts:5}")
    private long maxAttempts;

    @Value("${jwt.secret}")
    private String jwtSecret;

    public EmailVerificationService(
            StringRedisTemplate redisTemplate,
            VerificationEmailService verificationEmailService
    ) {
        this.redisTemplate = redisTemplate;
        this.verificationEmailService = verificationEmailService;
    }

    public DispatchResult issueCode(User user, boolean enforceCooldown) {
        String email = normalizeEmail(user.getEmail());
        long cooldown = secondsToLive(cooldownKey(email));
        if (enforceCooldown && cooldown > 0) {
            return new DispatchResult(
                    false,
                    "Please wait before requesting another verification code",
                    cooldown,
                    secondsToLive(codeKey(email))
            );
        }

        String code = generateCode();
        redisTemplate.opsForValue().set(codeKey(email), hashCode(email, code), Duration.ofSeconds(codeTtlSeconds));
        redisTemplate.delete(attemptsKey(email));
        redisTemplate.opsForValue().set(cooldownKey(email), "1", Duration.ofSeconds(resendCooldownSeconds));

        verificationEmailService.sendVerificationCode(user.getEmail(), user.getFullName(), code);
        return new DispatchResult(true, "Verification code sent", resendCooldownSeconds, codeTtlSeconds);
    }

    public VerificationCheckResult verifyCode(String email, String code) {
        String normalizedEmail = normalizeEmail(email);
        String storedHash = redisTemplate.opsForValue().get(codeKey(normalizedEmail));
        if (storedHash == null) {
            return new VerificationCheckResult(false, "Verification code expired. Request a new code.", 0);
        }

        long attempts = currentAttempts(normalizedEmail);
        if (attempts >= maxAttempts) {
            deleteCodeAndAttempts(normalizedEmail);
            return new VerificationCheckResult(false, "Too many invalid attempts. Request a new code.", 0);
        }

        if (secureEquals(storedHash, hashCode(normalizedEmail, code))) {
            return new VerificationCheckResult(true, "Email verified", maxAttempts - attempts);
        }

        long updatedAttempts = incrementAttempts(normalizedEmail);
        long remainingAttempts = Math.max(0, maxAttempts - updatedAttempts);
        if (remainingAttempts == 0) {
            deleteCodeAndAttempts(normalizedEmail);
            return new VerificationCheckResult(false, "Too many invalid attempts. Request a new code.", 0);
        }

        return new VerificationCheckResult(false, "Invalid verification code", remainingAttempts);
    }

    public VerificationStatus status(String email) {
        String normalizedEmail = normalizeEmail(email);
        return new VerificationStatus(secondsToLive(cooldownKey(normalizedEmail)), secondsToLive(codeKey(normalizedEmail)));
    }

    public void clearVerification(String email) {
        String normalizedEmail = normalizeEmail(email);
        redisTemplate.delete(List.of(codeKey(normalizedEmail), attemptsKey(normalizedEmail), cooldownKey(normalizedEmail)));
    }

    private long incrementAttempts(String email) {
        Long attempts = redisTemplate.opsForValue().increment(attemptsKey(email));
        if (attempts != null && attempts == 1) {
            redisTemplate.expire(attemptsKey(email), Duration.ofSeconds(codeTtlSeconds));
        }
        return attempts == null ? 1 : attempts;
    }

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

    private void deleteCodeAndAttempts(String email) {
        redisTemplate.delete(List.of(codeKey(email), attemptsKey(email)));
    }

    private long secondsToLive(String key) {
        Long seconds = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (seconds == null || seconds < 0) {
            return 0;
        }
        return seconds;
    }

    private String generateCode() {
        return String.format(Locale.ROOT, "%06d", secureRandom.nextInt(1_000_000));
    }

    private String hashCode(String email, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((normalizeEmail(email) + ":" + code + ":" + jwtSecret).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private boolean secureEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private String codeKey(String email) {
        return CODE_PREFIX + normalizeEmail(email);
    }

    private String attemptsKey(String email) {
        return ATTEMPTS_PREFIX + normalizeEmail(email);
    }

    private String cooldownKey(String email) {
        return COOLDOWN_PREFIX + normalizeEmail(email);
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public record DispatchResult(
            boolean accepted,
            String message,
            long resendAvailableInSeconds,
            long verificationExpiresInSeconds
    ) {
    }

    public record VerificationCheckResult(boolean valid, String message, long remainingAttempts) {
    }

    public record VerificationStatus(long resendAvailableInSeconds, long verificationExpiresInSeconds) {
    }
}
