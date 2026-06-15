package com.smartTriage.smartTriage_server.module.user.service;

import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Self-service profile save — the path the Profile page was missing entirely
 * (the old "Save" was a no-op). Verifies the authenticated user's own
 * name/phone are persisted, and only those fields.
 */
class UserServiceProfileTest {

    private UserRepository userRepository;
    private UserService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        service = new UserService(
                userRepository,
                mock(HospitalService.class),
                mock(PasswordEncoder.class),
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
}
