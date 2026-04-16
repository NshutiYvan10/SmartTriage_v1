package com.smartTriage.smartTriage_server.module.user.repository;

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

    Optional<User> findByIdAndIsActiveTrue(UUID id);

    boolean existsByEmail(String email);

    boolean existsByEmployeeNumber(String employeeNumber);

    Page<User> findByHospitalIdAndIsActiveTrue(UUID hospitalId, Pageable pageable);

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
}
