package com.smartTriage.smartTriage_server.module.user.service;

import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.user.dto.ChangePasswordRequest;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.service.HospitalService;
import com.smartTriage.smartTriage_server.module.user.dto.UpdateProfileRequest;
import com.smartTriage.smartTriage_server.module.user.dto.UserResponse;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import com.smartTriage.smartTriage_server.security.UserAdminAuthz;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Self-service profile save — the path the Profile page was missing entirely
 * (the old "Save" was a no-op). Verifies the authenticated user's own
 * name/phone are persisted, and only those fields.
 */
class UserServiceProfileTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private UserService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        service = new UserService(
                userRepository,
                mock(HospitalService.class),
                passwordEncoder,
                mock(UserAdminAuthz.class));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private User existingUser() {
        Hospital h = new Hospital();
        h.setId(UUID.randomUUID());
        h.setName("Test Hospital");
        User u = new User();
        u.setId(userId);
        u.setFirstName("Old");
        u.setLastName("Name");
        u.setEmail("user@hospital.rw");
        u.setPhoneNumber("0700000000");
        u.setRole(Role.NURSE);
        u.setHospital(h);
        return u;
    }

    @Test
    void updatesOwnNameAndPhone() {
        User user = existingUser();
        when(userRepository.findByIdAndIsActiveTrue(userId)).thenReturn(Optional.of(user));

        UpdateProfileRequest req = UpdateProfileRequest.builder()
                .firstName("  Jean  ").lastName("  Uwimana ").phoneNumber(" 0788123456 ").build();

        UserResponse resp = service.updateMyProfile(userId, req);

        // Persisted on the entity (trimmed), and the same role/email untouched.
        assertEquals("Jean", user.getFirstName());
        assertEquals("Uwimana", user.getLastName());
        assertEquals("0788123456", user.getPhoneNumber());
        assertEquals("user@hospital.rw", user.getEmail());
        assertEquals(Role.NURSE, user.getRole());
        assertEquals("Jean", resp.getFirstName());
        assertEquals("0788123456", resp.getPhoneNumber());
        verify(userRepository).save(user);
    }

    @Test
    void blankPhoneIsStoredAsNull() {
        User user = existingUser();
        when(userRepository.findByIdAndIsActiveTrue(userId)).thenReturn(Optional.of(user));

        service.updateMyProfile(userId, UpdateProfileRequest.builder()
                .firstName("A").lastName("B").phoneNumber("   ").build());

        assertNull(user.getPhoneNumber());
    }

    @Test
    void missingUserThrows() {
        when(userRepository.findByIdAndIsActiveTrue(userId)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.updateMyProfile(userId,
                UpdateProfileRequest.builder().firstName("A").lastName("B").build()));
    }

    // ── changeMyPassword ──

    @Test
    void changesPasswordWhenCurrentMatches() {
        User user = existingUser();
        when(userRepository.findByIdAndIsActiveTrue(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("current-pw", user.getPassword())).thenReturn(true);
        when(passwordEncoder.matches("new-pw-1234", user.getPassword())).thenReturn(false);
        when(passwordEncoder.encode("new-pw-1234")).thenReturn("ENCODED-NEW");

        service.changeMyPassword(userId, ChangePasswordRequest.builder()
                .currentPassword("current-pw").newPassword("new-pw-1234").build());

        assertEquals("ENCODED-NEW", user.getPassword());
        verify(userRepository).save(user);
    }

    @Test
    void rejectsWrongCurrentPassword() {
        User user = existingUser();
        when(userRepository.findByIdAndIsActiveTrue(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", user.getPassword())).thenReturn(false);

        assertThrows(ClinicalBusinessException.class, () -> service.changeMyPassword(userId,
                ChangePasswordRequest.builder().currentPassword("wrong").newPassword("new-pw-1234").build()));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void rejectsNewPasswordSameAsCurrent() {
        User user = existingUser();
        when(userRepository.findByIdAndIsActiveTrue(userId)).thenReturn(Optional.of(user));
        // Both the current-match check and the new-equals-current check see a match.
        when(passwordEncoder.matches("same-pw-1", user.getPassword())).thenReturn(true);

        assertThrows(ClinicalBusinessException.class, () -> service.changeMyPassword(userId,
                ChangePasswordRequest.builder().currentPassword("same-pw-1").newPassword("same-pw-1").build()));
        verify(userRepository, never()).save(any(User.class));
    }
}
