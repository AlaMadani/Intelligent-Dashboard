package com.neo.dashboard.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class HashUtil {

    /** The JWT signing secret, used as an additional salt for hashing. */
    private final String jwtSecret;

    /** Injects the JWT secret from configuration to use as a server-side salt. */
    public HashUtil(@Value("${jwt.secret}") String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    /**
     * Produces a SHA-256 hex hash from the concatenation of
     * {@code email:purpose:secret:jwtSecret}.
     * <p>
     * This is used to generate tamper-resistant verification tokens (e.g. email
     * confirmation, password reset) that bind a user, a purpose, and a
     * one-time secret together with the server key.
     */
    public String hashWithSalt(String email, String secret, String purpose) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            /* Hash: normalized(email) + ":" + purpose + ":" + secret + ":" + jwtSecret */
            byte[] hash = digest.digest((normalize(email) + ":" + purpose + ":" + secret + ":" + jwtSecret)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * Constant-time comparison of two strings to prevent timing attacks.
     * Returns {@code true} if both strings are identical.
     */
    public boolean secureEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Lower-cases and trims the email so that the hash is case-insensitive
     * and tolerant of leading/trailing whitespace.
     */
    private String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
