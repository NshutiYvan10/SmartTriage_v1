package com.smartTriage.smartTriage_server.module.auth.service;

import com.smartTriage.smartTriage_server.common.enums.AccountStatus;
import com.smartTriage.smartTriage_server.module.auth.dto.AuthResponse;
import com.smartTriage.smartTriage_server.module.auth.dto.LoginRequest;
import com.smartTriage.smartTriage_server.module.auth.dto.RefreshTokenRequest;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import com.smartTriage.smartTriage_server.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authentication service — handles login, token refresh, and session
 * management.
 *
 * Security design decisions:
 * - Access tokens: 15 min lifetime (short for healthcare security)
 * - Refresh tokens: 24 hours (requires re-login once per shift)
 * - Failed login tracking (lockout after 5 attempts)
 * - Hospital context embedded in JWT claims
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserRepository userRepository;

    private static final int MAX_FAILED_ATTEMPTS = 5;

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmailAndIsActiveTrue(request.getEmail())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (user.getAccountStatus() == AccountStatus.PENDING_ACTIVATION) {
            throw new BadCredentialsException(
                    "Your account has not been activated yet. Please check your email for the invitation link.");
        }

        if (user.isAccountLocked()) {
            throw new BadCredentialsException(
                    "Account is locked due to too many failed login attempts. Contact administrator.");
        }

        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));

            // Reset failed attempts on successful login
            if (user.getFailedLoginAttempts() > 0) {
                user.setFailedLoginAttempts(0);
                userRepository.save(user);
            }

            String accessToken = jwtService.generateAccessToken(
                    user,
                    user.getHospital().getId().toString(),
                    user.getRole().name());
            String refreshToken = jwtService.generateRefreshToken(user);

            log.info("User logged in: {} (Hospital: {})", user.getEmail(), user.getHospital().getHospitalCode());

            return AuthResponse.builder()
                    .accessToken(accessToken)
                    .refreshToken(refreshToken)
                    .tokenType("Bearer")
                    .userId(user.getId())
                    .email(user.getEmail())
                    .firstName(user.getFirstName())
                    .lastName(user.getLastName())
                    .role(user.getRole().name())
                    .designation(user.getDesignation() != null ? user.getDesignation().name() : null)
                    .designationLabel(user.getDesignation() != null ? user.getDesignation().getLabel() : null)
                    .hospitalId(user.getHospital().getId())
                    .hospitalName(user.getHospital().getName())
                    .build();

        } catch (BadCredentialsException e) {
            // Increment failed attempts
            user.setFailedLoginAttempts(user.getFailedLoginAttempts() + 1);
            if (user.getFailedLoginAttempts() >= MAX_FAILED_ATTEMPTS) {
                user.setAccountLocked(true);
                log.warn("Account locked due to {} failed login attempts: {}", MAX_FAILED_ATTEMPTS, user.getEmail());
            }
            userRepository.save(user);
            throw new BadCredentialsException("Invalid email or password");
        }
    }

    @Transactional(readOnly = true)
    public AuthResponse refreshToken(RefreshTokenRequest request) {
        String refreshToken = request.getRefreshToken();

        if (!jwtService.isRefreshToken(refreshToken)) {
            throw new BadCredentialsException("Invalid refresh token");
        }

        String email = jwtService.extractUsername(refreshToken);
        User user = userRepository.findByEmailAndIsActiveTrue(email)
                .orElseThrow(() -> new BadCredentialsException("User not found"));

        if (!jwtService.isTokenValid(refreshToken, user)) {
            throw new BadCredentialsException("Refresh token is expired or invalid");
        }

        String newAccessToken = jwtService.generateAccessToken(
                user,
                user.getHospital().getId().toString(),
                user.getRole().name());

        log.debug("Token refreshed for user: {}", user.getEmail());

        return AuthResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(refreshToken) // reuse same refresh token
                .tokenType("Bearer")
                .userId(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .role(user.getRole().name())
                .designation(user.getDesignation() != null ? user.getDesignation().name() : null)
                .designationLabel(user.getDesignation() != null ? user.getDesignation().getLabel() : null)
                .hospitalId(user.getHospital().getId())
                .hospitalName(user.getHospital().getName())
                .build();
    }
}
