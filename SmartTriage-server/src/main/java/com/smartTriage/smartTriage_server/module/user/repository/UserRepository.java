package com.smartTriage.smartTriage_server.module.user.repository;

import com.smartTriage.smartTriage_server.common.enums.Designation;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmailAndIsActiveTrue(String email);

    /**
     * Auth-path lookup: eagerly fetches hospital so that isEnabled()
     * can read hospital.isActive() outside a transaction (the JWT
     * filter does not run inside @Transactional, and accessing the
     * lazy hospital association there would throw
     * LazyInitializationException).
     */
    @Query("SELECT u FROM User u LEFT JOIN FETCH u.hospital WHERE u.email = :email AND u.isActive = true")
    Optional<User> findByEmailWithHospital(@Param("email") String email);

    Optional<User> findByIdAndIsActiveTrue(UUID id);

    boolean existsByEmail(String email);

    boolean existsByEmployeeNumber(String employeeNumber);

    Page<User> findByHospitalIdAndIsActiveTrue(UUID hospitalId, Pageable pageable);

    /** Includes deactivated (isActive=false) users — admin User Management list only. */
    Page<User> findByHospitalId(UUID hospitalId, Pageable pageable);

    Page<User> findByHospitalIdAndRoleAndIsActiveTrue(UUID hospitalId, Role role, Pageable pageable);

    /**
     * Fetch just the user's hospital id as a primitive projection — safe to
     * call from authorization code paths that run <em>outside</em> a JPA
     * session (e.g. {@code @PreAuthorize} SpEL evaluators), where dereferencing
     * the lazy {@code hospital} association on a detached {@link User} would
     * otherwise throw a {@code LazyInitializationException} that bubbles up
     * as an HTTP 500 instead of a proper 403.
     */
    @Query("SELECT u.hospital.id FROM User u WHERE u.id = :userId AND u.isActive = true")
    Optional<UUID> findHospitalIdByUserId(@Param("userId") UUID userId);

    /**
     * Count active users at this hospital with the given designation.
     * Used to enforce the "at least one Charge Nurse per hospital"
     * invariant — admins cannot demote the last CN without explicitly
     * appointing a replacement first. See {@code UserService.updateUser}.
     */
    long countByHospitalIdAndDesignationAndIsActiveTrue(UUID hospitalId, Designation designation);
}
