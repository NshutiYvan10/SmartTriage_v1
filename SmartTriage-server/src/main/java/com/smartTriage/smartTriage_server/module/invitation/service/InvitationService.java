package com.smartTriage.smartTriage_server.module.invitation.service;

import com.smartTriage.smartTriage_server.common.enums.AccountStatus;
import com.smartTriage.smartTriage_server.common.exception.DuplicateResourceException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.service.HospitalService;
import com.smartTriage.smartTriage_server.module.invitation.dto.ActivateAccountRequest;
import com.smartTriage.smartTriage_server.module.invitation.dto.InviteUserRequest;
import com.smartTriage.smartTriage_server.module.invitation.entity.InvitationToken;
import com.smartTriage.smartTriage_server.module.invitation.repository.InvitationTokenRepository;
import com.smartTriage.smartTriage_server.module.user.dto.UserResponse;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.mapper.UserMapper;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Invitation service — handles the full invite → activate lifecycle.
 *
 * Flow:
 *  1. Admin calls invite() → creates a PENDING_ACTIVATION user + token → sends email
 *  2. User clicks link → calls activate() → sets name/password → account becomes ACTIVE
 *  3. Admin can resend() → invalidates old token, creates new one, re-sends email
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvitationService {

    private final UserRepository userRepository;
    private final InvitationTokenRepository tokenRepository;
    private final HospitalService hospitalService;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;

    private static final Duration TOKEN_VALIDITY = Duration.ofHours(48);

    /**
     * Step 1: Admin invites a user by email.
     * Creates a PENDING_ACTIVATION user (no password) and sends invitation email.
     */
    @Transactional
    public UserResponse inviteUser(InviteUserRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("User", "email", request.getEmail());
        }

        Hospital hospital = hospitalService.findHospitalOrThrow(request.getHospitalId());

        // Create user with PENDING_ACTIVATION status and a placeholder password
        User user = User.builder()
                .firstName("Pending")
                .lastName("Activation")
                .email(request.getEmail())
                .passwordHash("PENDING_ACTIVATION_NO_LOGIN")  // Not a valid BCrypt hash — cannot login
                .role(request.getRole())
                .designation(request.getDesignation())
                .department(request.getDepartment())
                .hospital(hospital)
                .accountStatus(AccountStatus.PENDING_ACTIVATION)
                .build();

        user = userRepository.save(user);

        // Generate invitation token
        String token = generateToken();
        InvitationToken invitation = InvitationToken.builder()
                .user(user)
                .token(token)
                .expiresAt(Instant.now().plus(TOKEN_VALIDITY))
                .build();
        tokenRepository.save(invitation);

        // Send invitation email — best-effort. SMTP failure must not roll
        // back the user creation (which would block ALL invitations when
        // SMTP isn't configured, surfaced to the admin as the unhelpful
        // "An unexpected error occurred" generic message). The user row
        // and invitation token are persisted regardless; the admin can
        // share the activation link manually or use Resend Invitation
        // once SMTP is configured.
        String roleName = request.getRole().name().replace("_", " ");
        boolean emailSent;
        try {
            emailService.sendInvitationEmail(request.getEmail(), token, roleName, hospital.getName());
            emailSent = true;
        } catch (Exception emailErr) {
            emailSent = false;
            log.warn("Invitation email FAILED for {} (role {}, hospital {}): {}. "
                    + "User created with PENDING_ACTIVATION — admin can resend or share the "
                    + "activation link manually. Check SMTP configuration (SMTP_HOST, SMTP_USERNAME, "
                    + "SMTP_PASSWORD, SMTP_FROM env vars).",
                    request.getEmail(), request.getRole(), hospital.getName(),
                    emailErr.getMessage());
        }

        if (emailSent) {
            log.info("Invitation sent to {} for role {} at hospital {}",
                    request.getEmail(), request.getRole(), hospital.getName());
        } else {
            log.info("Invitation created (email delivery deferred) for {} role {} at {}",
                    request.getEmail(), request.getRole(), hospital.getName());
        }

        return UserMapper.toResponse(user);
    }

    /**
     * Step 2: User activates their account using the invitation token.
     * Sets their name, password, and changes status to ACTIVE.
     */
    @Transactional
    public UserResponse activateAccount(ActivateAccountRequest request) {
        InvitationToken invitation = tokenRepository.findByTokenAndIsActiveTrue(request.getToken())
                .orElseThrow(() -> new ResourceNotFoundException("InvitationToken", "token", request.getToken()));

        if (invitation.isUsed()) {
            throw new IllegalStateException("This invitation has already been used.");
        }

        if (invitation.isExpired()) {
            throw new IllegalStateException("This invitation has expired. Please ask your administrator to resend the invitation.");
        }

        User user = invitation.getUser();

        if (user.getAccountStatus() != AccountStatus.PENDING_ACTIVATION) {
            throw new IllegalStateException("This account has already been activated.");
        }

        // Update user profile
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setPhoneNumber(request.getPhoneNumber());
        user.setEmployeeNumber(request.getEmployeeNumber());
        user.setProfessionalLicense(request.getProfessionalLicense());
        user.setAccountStatus(AccountStatus.ACTIVE);
        userRepository.save(user);

        // Mark token as used
        invitation.setUsedAt(Instant.now());
        tokenRepository.save(invitation);

        log.info("Account activated for user: {} {} ({})", user.getFirstName(), user.getLastName(), user.getEmail());

        return UserMapper.toResponse(user);
    }

    /**
     * Resend invitation — invalidates previous token, generates new one, re-sends email.
     */
    @Transactional
    public void resendInvitation(UUID userId) {
        User user = userRepository.findByIdAndIsActiveTrue(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        if (user.getAccountStatus() != AccountStatus.PENDING_ACTIVATION) {
            throw new IllegalStateException("Can only resend invitations for pending accounts.");
        }

        // Invalidate any existing active tokens for this user
        tokenRepository.findFirstByUserIdAndUsedAtIsNullAndIsActiveTrueOrderByCreatedAtDesc(userId)
                .ifPresent(existing -> {
                    existing.softDelete();
                    tokenRepository.save(existing);
                });

        // Generate new token
        String token = generateToken();
        InvitationToken invitation = InvitationToken.builder()
                .user(user)
                .token(token)
                .expiresAt(Instant.now().plus(TOKEN_VALIDITY))
                .build();
        tokenRepository.save(invitation);

        // Resend email — best-effort, same policy as inviteUser. A SMTP
        // outage must not roll back the new token (which would leave the
        // user with the old, just-invalidated link and no way forward).
        // The new token is persisted; the admin can re-trigger resend
        // once SMTP is healthy, or share the activation URL out of band.
        String roleName = user.getRole().name().replace("_", " ");
        try {
            emailService.sendInvitationEmail(user.getEmail(), token, roleName, user.getHospital().getName());
            log.info("Invitation resent to {}", user.getEmail());
        } catch (Exception emailErr) {
            log.warn("Resend-invitation email FAILED for {} (role {}, hospital {}): {}. "
                            + "New token issued — admin can re-resend once SMTP is configured, or "
                            + "share the activation link manually.",
                    user.getEmail(), user.getRole(), user.getHospital().getName(),
                    emailErr.getMessage());
        }
    }

    /**
     * Cancel a pending invitation.
     *
     * <p>Reversible by sending a fresh invite to the same email later.
     * Implementation: soft-delete the user (sets {@code is_active=false})
     * AND invalidate any outstanding tokens so the existing email link
     * stops working immediately. The user row stays in the DB so audit
     * trails referencing it (alerts, signatures, …) remain valid.
     *
     * <p>Only valid for users in {@code PENDING_ACTIVATION} status. An
     * already-activated user must be deactivated through the regular
     * deactivate flow, which is a different action with different
     * cleanup (open shift assignments, in-flight transfers).
     */
    @Transactional
    public void cancelInvitation(UUID userId) {
        User user = userRepository.findByIdAndIsActiveTrue(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        if (user.getAccountStatus() != AccountStatus.PENDING_ACTIVATION) {
            throw new IllegalStateException(
                    "Can only cancel invitations for pending accounts. "
                            + "For an already-activated user, use deactivate instead.");
        }

        // Invalidate any outstanding token so the email link is dead
        // the instant cancellation hits, even before the soft-delete
        // fully propagates.
        tokenRepository.findFirstByUserIdAndUsedAtIsNullAndIsActiveTrueOrderByCreatedAtDesc(userId)
                .ifPresent(tok -> {
                    tok.softDelete();
                    tokenRepository.save(tok);
                });

        // Soft-delete the user. The account remains in the DB but
        // findByIdAndIsActiveTrue (the standard lookup) will skip it,
        // and the unique-by-email index allows the same address to be
        // re-invited later.
        user.softDelete();
        userRepository.save(user);

        log.info("Cancelled pending invitation for {} (user id {})",
                user.getEmail(), userId);
    }

    /**
     * Validate a token without consuming it — used by the frontend to show
     * the activation form or an error message.
     */
    @Transactional(readOnly = true)
    public InvitationTokenInfo validateToken(String token) {
        InvitationToken invitation = tokenRepository.findByTokenAndIsActiveTrue(token)
                .orElseThrow(() -> new ResourceNotFoundException("InvitationToken", "token", token));

        User user = invitation.getUser();

        return new InvitationTokenInfo(
                user.getEmail(),
                user.getRole().name(),
                user.getHospital().getName(),
                invitation.isExpired(),
                invitation.isUsed()
        );
    }

    public record InvitationTokenInfo(
            String email,
            String role,
            String hospitalName,
            boolean expired,
            boolean used
    ) {}

    private String generateToken() {
        return UUID.randomUUID().toString() + "-" + UUID.randomUUID().toString();
    }
}
