package com.smartTriage.smartTriage_server.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * JWT Token service for SmartTriage authentication.
 * Handles token generation, validation, and claims extraction.
 *
 * Security requirements for a healthcare system:
 *  - Short-lived access tokens (15 min)
 *  - Refresh token support
 *  - Hospital context embedded in token claims
 *  - Role embedded in token claims
 */
@Slf4j
@Service
public class JwtService {

    @Value("${smarttriage.security.jwt.secret}")
    private String jwtSecret;

    @Value("${smarttriage.security.jwt.access-token-expiration-ms}")
    private long accessTokenExpirationMs;

    @Value("${smarttriage.security.jwt.refresh-token-expiration-ms}")
    private long refreshTokenExpirationMs;

    /**
     * Generate access token with user details and hospital context.
     */
    public String generateAccessToken(UserDetails userDetails, String hospitalId, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("hospitalId", hospitalId);
        claims.put("role", role);
        claims.put("type", "ACCESS");
        return buildToken(claims, userDetails.getUsername(), accessTokenExpirationMs);
    }

    /**
     * Generate refresh token.
     */
    public String generateRefreshToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "REFRESH");
        return buildToken(claims, userDetails.getUsername(), refreshTokenExpirationMs);
    }

    /**
     * Extract username (email) from token.
     */
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    /**
     * Extract hospital ID from token.
     */
    public String extractHospitalId(String token) {
        return extractClaim(token, claims -> claims.get("hospitalId", String.class));
    }

    /**
     * Extract role from token.
     */
    public String extractRole(String token) {
        return extractClaim(token, claims -> claims.get("role", String.class));
    }

    /**
     * Validate token against user details.
     */
    public boolean isTokenValid(String token, UserDetails userDetails) {
        try {
            final String username = extractUsername(token);
            return (username.equals(userDetails.getUsername())) && !isTokenExpired(token);
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check if token is a refresh token.
     */
    public boolean isRefreshToken(String token) {
        try {
            return "REFRESH".equals(extractClaim(token, claims -> claims.get("type", String.class)));
        } catch (Exception e) {
            return false;
        }
    }

    private String buildToken(Map<String, Object> extraClaims, String subject, long expirationMs) {
        return Jwts.builder()
                .claims(extraClaims)
                .subject(subject)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(getSigningKey())
                .compact();
    }

    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    private <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = Base64.getDecoder().decode(jwtSecret);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
