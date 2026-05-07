package com.smartTriage.smartTriage_server.module.user.service;

import com.smartTriage.smartTriage_server.common.enums.Designation;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.DuplicateResourceException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.service.HospitalService;
import com.smartTriage.smartTriage_server.module.user.dto.CreateUserRequest;
import com.smartTriage.smartTriage_server.module.user.dto.UpdateUserRequest;
import com.smartTriage.smartTriage_server.module.user.dto.UserResponse;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.mapper.UserMapper;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * User service — manages system users (clinicians, nurses, admins).
 * Also implements Spring Security's UserDetailsService for authentication.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;
    private final HospitalService hospitalService;
    private final PasswordEncoder passwordEncoder;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmailAndIsActiveTrue(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));
    }

    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("User", "email", request.getEmail());
        }
        validateRoleDesignationPair(request.getRole(), request.getDesignation());

        Hospital hospital = hospitalService.findHospitalOrThrow(request.getHospitalId());

        User user = User.builder()
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .phoneNumber(request.getPhoneNumber())
                .role(request.getRole())
                .designation(request.getDesignation())
                .employeeNumber(request.getEmployeeNumber())
                .professionalLicense(request.getProfessionalLicense())
                .department(request.getDepartment())
                .hospital(hospital)
                .build();

        user = userRepository.save(user);
        log.info("User created: {} {} ({})", user.getFirstName(), user.getLastName(), user.getRole());
        return UserMapper.toResponse(user);
    }

    public UserResponse getUserById(UUID id) {
        User user = findUserOrThrow(id);
        return UserMapper.toResponse(user);
    }

    public Page<UserResponse> getUsersByHospital(UUID hospitalId, Pageable pageable) {
        return userRepository.findByHospitalIdAndIsActiveTrue(hospitalId, pageable)
                .map(UserMapper::toResponse);
    }

    @Transactional
    public void deactivateUser(UUID id) {
        User user = findUserOrThrow(id);
        // V44+ at-least-one-CN invariant: deactivating the last Charge
        // Nurse at a hospital would leave the floor without designated
        // unit-management authority. Block until an admin appoints a
        // replacement first.
        if (user.getDesignation() == Designation.CHARGE_NURSE
                && user.getHospital() != null) {
            assertNotLastChargeNurse(user, "deactivate");
        }
        user.softDelete();
        userRepository.save(user);
        log.info("User deactivated: {}", user.getEmail());
    }

    /**
     * Update a user's professional designation.
     * Restricted to SUPER_ADMIN and HOSPITAL_ADMIN via controller @PreAuthorize.
     */
    @Transactional
    public UserResponse updateDesignation(UUID userId, Designation designation) {
        User user = findUserOrThrow(userId);
        validateRoleDesignationPair(user.getRole(), designation);
        // V44+ at-least-one-CN invariant: prevent demotion of the last
        // Charge Nurse via designation change.
        if (user.getDesignation() == Designation.CHARGE_NURSE
                && designation != Designation.CHARGE_NURSE
                && user.getHospital() != null) {
            assertNotLastChargeNurse(user, "demote");
        }
        user.setDesignation(designation);
        user = userRepository.save(user);
        log.info("User {} designation updated to {}", user.getEmail(), designation);
        return UserMapper.toResponse(user);
    }

    /**
     * Update a user's details (for admin edit).
     */
    @Transactional
    public UserResponse updateUser(UUID userId, UpdateUserRequest request) {
        User user = findUserOrThrow(userId);
        validateRoleDesignationPair(request.getRole(), request.getDesignation());
        // V44+ at-least-one-CN invariant: catch the case where the admin
        // edit either changes the designation away from CHARGE_NURSE or
        // changes the role away from NURSE (which makes the CN designation
        // illegal). Both effectively remove the last CN if no replacement
        // has been appointed.
        boolean wasChargeNurse = user.getDesignation() == Designation.CHARGE_NURSE;
        boolean willBeChargeNurse = request.getRole() == Role.NURSE
                && request.getDesignation() == Designation.CHARGE_NURSE;
        if (wasChargeNurse && !willBeChargeNurse && user.getHospital() != null) {
            assertNotLastChargeNurse(user, "remove CHARGE_NURSE designation from");
        }
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setPhoneNumber(request.getPhoneNumber());
        user.setRole(request.getRole());
        user.setDesignation(request.getDesignation());
        user.setEmployeeNumber(request.getEmployeeNumber());
        user.setProfessionalLicense(request.getProfessionalLicense());
        user.setDepartment(request.getDepartment());
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        }
        user = userRepository.save(user);
        log.info("User updated: {} {}", user.getFirstName(), user.getLastName());
        return UserMapper.toResponse(user);
    }

    /**
     * Throws if removing this user's CHARGE_NURSE designation (via
     * deactivate / role change / designation change) would leave their
     * hospital with zero active charge nurses. Admins must appoint a
     * replacement first — every hospital floor needs at least one
     * permanent unit-management authority.
     */
    private void assertNotLastChargeNurse(User user, String actionVerb) {
        UUID hospitalId = user.getHospital().getId();
        long currentCount = userRepository
                .countByHospitalIdAndDesignationAndIsActiveTrue(hospitalId, Designation.CHARGE_NURSE);
        // currentCount includes the user being changed. After the change
        // the count would drop by 1 — block when that would hit 0.
        if (currentCount <= 1) {
            throw new ClinicalBusinessException(
                    "Cannot " + actionVerb + " user " + user.getEmail()
                            + " — they are the only Charge Nurse at this hospital. "
                            + "Appoint another Charge Nurse first, then retry.");
        }
    }

    public User findUserOrThrow(UUID id) {
        return userRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
    }

    /**
     * Backend-enforced (role, designation) pair check. Used on every
     * user create / update / designation-only update path so an admin
     * (or a malformed API client) cannot persist an invalid pair like
     * DOCTOR + CHARGE_NURSE — which would inadvertently grant nurse
     * unit-management authority to a doctor account via
     * {@code ShiftAssignmentAuthz.canAssign}'s designation check.
     *
     * <p>UI dropdown filtering is not a security boundary; this is.
     * {@code null} or {@link Designation#UNSPECIFIED} are always
     * valid (the column is nullable for users whose seniority hasn't
     * been recorded yet).
     */
    private void validateRoleDesignationPair(Role role, Designation designation) {
        if (!Designation.isValidForRole(designation, role)) {
            throw new IllegalArgumentException(
                    "Designation " + designation + " is not valid for role " + role
                            + ". Allowed: " + java.util.Arrays.toString(Designation.forRole(role)));
        }
    }
}
