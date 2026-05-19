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
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Service
public class PasswordResetService {

    private static final String CODE_PREFIX = "auth:password-reset:code:";
    private static final String ATTEMPTS_PREFIX = "auth:password-reset:attempts:";
    private static final String COOLDOWN_PREFIX = "auth:password-reset:cooldown:";
    private static final String TOKEN_PREFIX = "auth:password-reset:token:";

    private final StringRedisTemplate redisTemplate;
    private final VerificationEmailService verificationEmailService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.auth.verification.code-ttl-seconds:300}")
    private long codeTtlSeconds;

    @Value("${app.auth.verification.resend-cooldown-seconds:120}")
    private long resendCooldownSeconds;

    @Value("${app.auth.verification.max-attempts:5}")
    private long maxAttempts;

    @Value("${app.auth.password-reset.token-ttl-seconds:600}")
    private long resetTokenTtlSeconds;

    @Value("${jwt.secret}")
    private String jwtSecret;

    public PasswordResetService(
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
            return new DispatchResult(false, "Please wait before requesting another reset code", cooldown, secondsToLive(codeKey(email)));
        }

        String code = generateCode();
        redisTemplate.opsForValue().set(codeKey(email), hashSecret(email, code, "code"), Duration.ofSeconds(codeTtlSeconds));
        redisTemplate.delete(List.of(attemptsKey(email), tokenKey(email)));
        redisTemplate.opsForValue().set(cooldownKey(email), "1", Duration.ofSeconds(resendCooldownSeconds));

        verificationEmailService.sendPasswordResetCode(user.getEmail(), user.getFullName(), code);
        return new DispatchResult(true, "Password reset code sent", resendCooldownSeconds, codeTtlSeconds);
    }

    public ResetCodeResult verifyCode(String email, String code) {
        String normalizedEmail = normalizeEmail(email);
        String storedHash = redisTemplate.opsForValue().get(codeKey(normalizedEmail));
        if (storedHash == null) {
            return new ResetCodeResult(false, "Password reset code expired. Request a new code.", 0, null);
        }

        long attempts = currentAttempts(normalizedEmail);
        if (attempts >= maxAttempts) {
            deleteCodeAndAttempts(normalizedEmail);
            return new ResetCodeResult(false, "Too many invalid attempts. Request a new code.", 0, null);
        }

        if (!secureEquals(storedHash, hashSecret(normalizedEmail, code, "code"))) {
            long updatedAttempts = incrementAttempts(normalizedEmail);
            long remainingAttempts = Math.max(0, maxAttempts - updatedAttempts);
            if (remainingAttempts == 0) {
                deleteCodeAndAttempts(normalizedEmail);
                return new ResetCodeResult(false, "Too many invalid attempts. Request a new code.", 0, null);
            }
            return new ResetCodeResult(false, "Invalid password reset code", remainingAttempts, null);
        }

        String resetToken = generateResetToken();
        redisTemplate.opsForValue().set(tokenKey(normalizedEmail), hashSecret(normalizedEmail, resetToken, "token"), Duration.ofSeconds(resetTokenTtlSeconds));
        deleteCodeAndAttempts(normalizedEmail);
        return new ResetCodeResult(true, "Code verified", maxAttempts - attempts, resetToken);
    }

    public boolean consumeResetToken(String email, String resetToken) {
        String normalizedEmail = normalizeEmail(email);
        String storedHash = redisTemplate.opsForValue().get(tokenKey(normalizedEmail));
        if (storedHash == null || !secureEquals(storedHash, hashSecret(normalizedEmail, resetToken, "token"))) {
            return false;
        }

        redisTemplate.delete(List.of(tokenKey(normalizedEmail), cooldownKey(normalizedEmail)));
        return true;
    }

    public ResetStatus status(String email) {
        String normalizedEmail = normalizeEmail(email);
        return new ResetStatus(secondsToLive(cooldownKey(normalizedEmail)), secondsToLive(codeKey(normalizedEmail)));
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

    private String generateResetToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashSecret(String email, String secret, String purpose) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((normalizeEmail(email) + ":" + purpose + ":" + secret + ":" + jwtSecret).getBytes(StandardCharsets.UTF_8));
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

    private String tokenKey(String email) {
        return TOKEN_PREFIX + normalizeEmail(email);
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public record DispatchResult(boolean accepted, String message, long resendAvailableInSeconds, long verificationExpiresInSeconds) {
    }

    public record ResetCodeResult(boolean valid, String message, long remainingAttempts, String resetToken) {
    }

    public record ResetStatus(long resendAvailableInSeconds, long verificationExpiresInSeconds) {
    }
}
