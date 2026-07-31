package com.neo.dashboard.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
@Slf4j
public class JwtTokenProvider {

    /** HMAC-SHA signing key loaded from {@code jwt.secret}. */
    @Value("${jwt.secret}")
    private String jwtSecret;

    /** Access token lifetime in milliseconds (default 24 h). */
    @Value("${jwt.expiration:86400000}")
    private long jwtExpirationMs;

    /** Refresh token lifetime in milliseconds (default 7 d). */
    @Value("${jwt.refresh-expiration:604800000}")
    private long refreshTokenExpirationMs;

    /**
     * Creates a short-lived access JWT containing only the user's email as the subject.
     */
    public String generateTokenFromEmail(String email) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpirationMs);

        return Jwts.builder()
                .subject(email)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey(), Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Creates a long-lived refresh JWT with a {@code type=refresh} claim used
     * to distinguish it from access tokens.
     */
    public String generateRefreshToken(String email) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + refreshTokenExpirationMs);

        return Jwts.builder()
                .subject(email)
                .claim("type", "refresh")
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey(), Jwts.SIG.HS256)
                .compact();
    }

    /** Extracts the subject (email) from a token; returns {@code null} if the token is invalid. */
    public String getEmailFromToken(String token) {
        try {
            return parseClaims(token).getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            log.error("Invalid JWT token", e);
            return null;
        }
    }

    /** Checks whether the given token carries the {@code type=refresh} claim. */
    public boolean isRefreshToken(String token) {
        try {
            return "refresh".equals(parseClaims(token).get("type", String.class));
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /** Returns {@code true} if the token can be parsed without throwing an exception. */
    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
        }
        return false;
    }

    /** Parses and verifies the JWT, returning its claims payload. */
    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** Derives an HMAC-SHA key from the configured JWT secret bytes. */
    private SecretKey getSigningKey() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
