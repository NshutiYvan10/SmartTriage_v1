package com.smartTriage.smartTriage_server.module.governance.repository;

import com.smartTriage.smartTriage_server.common.enums.PolicyStatus;
import com.smartTriage.smartTriage_server.common.enums.PolicyType;
import com.smartTriage.smartTriage_server.module.governance.entity.ClinicalPolicy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every finder that feeds a mapped response uses {@code LEFT JOIN FETCH p.hospital}:
 * the mapping happens in the controller after the service transaction closes, and
 * open-in-view is disabled, so a lazy hospital proxy would throw
 * LazyInitializationException. Fetching the (single-valued) hospital up front is
 * pagination-safe and keeps the whole module out of 500s.
 */
@Repository
public interface ClinicalPolicyRepository extends JpaRepository<ClinicalPolicy, UUID> {

    @Query("SELECT p FROM ClinicalPolicy p LEFT JOIN FETCH p.hospital " +
            "WHERE p.id = :id AND p.isActive = true")
    Optional<ClinicalPolicy> findByIdAndIsActiveTrue(@Param("id") UUID id);

    /**
     * Paged list for a hospital, INCLUDING system-wide (hospital NULL) defaults, with
     * an optional status filter applied in SQL (so pagination + counts stay correct).
     */
    @Query(value = "SELECT p FROM ClinicalPolicy p LEFT JOIN FETCH p.hospital h " +
            "WHERE (h.id = :hospitalId OR p.hospital IS NULL) " +
            "AND (:status IS NULL OR p.status = :status) " +
            "AND p.isActive = true ORDER BY p.createdAt DESC",
            countQuery = "SELECT COUNT(p) FROM ClinicalPolicy p " +
            "WHERE (p.hospital.id = :hospitalId OR p.hospital IS NULL) " +
            "AND (:status IS NULL OR p.status = :status) AND p.isActive = true")
    Page<ClinicalPolicy> findForHospital(
            @Param("hospitalId") UUID hospitalId,
            @Param("status") PolicyStatus status,
            Pageable pageable);

    /** All ACTIVE policies for a hospital (+ system-wide) across all types — one query (no N+1 loop). */
    @Query("SELECT p FROM ClinicalPolicy p LEFT JOIN FETCH p.hospital h " +
            "WHERE (h.id = :hospitalId OR p.hospital IS NULL) " +
            "AND p.status = 'ACTIVE' AND p.isActive = true " +
            "ORDER BY p.policyType ASC, p.effectiveFrom DESC")
    List<ClinicalPolicy> findAllActiveForHospital(@Param("hospitalId") UUID hospitalId);

    @Query("SELECT p FROM ClinicalPolicy p LEFT JOIN FETCH p.hospital WHERE p.hospital.id = :hospitalId " +
            "AND p.policyType = :policyType " +
            "AND p.status = 'ACTIVE' " +
            "AND p.isActive = true " +
            "ORDER BY p.effectiveFrom DESC")
    List<ClinicalPolicy> findActivePoliciesByHospitalAndType(
            @Param("hospitalId") UUID hospitalId,
            @Param("policyType") PolicyType policyType);

    @Query("SELECT p FROM ClinicalPolicy p LEFT JOIN FETCH p.hospital WHERE p.hospital.id = :hospitalId " +
            "AND p.policyCode = :policyCode " +
            "AND p.isActive = true " +
            "ORDER BY p.createdAt DESC")
    List<ClinicalPolicy> findByHospitalAndPolicyCode(
            @Param("hospitalId") UUID hospitalId,
            @Param("policyCode") String policyCode);

    @Query("SELECT p FROM ClinicalPolicy p WHERE p.hospital.id = :hospitalId " +
            "AND p.policyCode = :policyCode " +
            "AND p.policyType = :policyType " +
            "AND p.status = 'ACTIVE' " +
            "AND p.isActive = true")
    Optional<ClinicalPolicy> findActiveByHospitalAndCodeAndType(
            @Param("hospitalId") UUID hospitalId,
            @Param("policyCode") String policyCode,
            @Param("policyType") PolicyType policyType);

    @Query("SELECT p FROM ClinicalPolicy p LEFT JOIN FETCH p.hospital WHERE p.hospital IS NULL " +
            "AND p.policyType = :policyType " +
            "AND p.status = 'ACTIVE' " +
            "AND p.isActive = true " +
            "ORDER BY p.effectiveFrom DESC")
    List<ClinicalPolicy> findSystemWideActivePolicies(
            @Param("policyType") PolicyType policyType);

    /** System-wide supersession lookup — the NULL-hospital counterpart of findActiveByHospitalAndCodeAndType. */
    @Query("SELECT p FROM ClinicalPolicy p WHERE p.hospital IS NULL " +
            "AND p.policyCode = :policyCode " +
            "AND p.policyType = :policyType " +
            "AND p.status = 'ACTIVE' " +
            "AND p.isActive = true")
    Optional<ClinicalPolicy> findSystemWideActiveByCodeAndType(
            @Param("policyCode") String policyCode,
            @Param("policyType") PolicyType policyType);

    /**
     * Object-level authz projection: the policy's hospital_id (nullable).
     * Empty list → no such active policy; [null] → system-wide; [uuid] → hospital-scoped.
     * Returned as a list so a present-but-NULL hospital is distinguishable from not-found.
     */
    @Query("SELECT p.hospital.id FROM ClinicalPolicy p WHERE p.id = :id AND p.isActive = true")
    List<UUID> findHospitalIdByPolicyId(@Param("id") UUID id);

    boolean existsByHospitalIdAndPolicyCodeAndIsActiveTrue(UUID hospitalId, String policyCode);
}
