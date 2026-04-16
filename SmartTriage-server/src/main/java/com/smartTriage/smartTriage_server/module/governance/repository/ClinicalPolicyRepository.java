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

@Repository
public interface ClinicalPolicyRepository extends JpaRepository<ClinicalPolicy, UUID> {

    Optional<ClinicalPolicy> findByIdAndIsActiveTrue(UUID id);

    Page<ClinicalPolicy> findByHospitalIdAndIsActiveTrueOrderByCreatedAtDesc(
            UUID hospitalId, Pageable pageable);

    @Query("SELECT p FROM ClinicalPolicy p WHERE p.hospital.id = :hospitalId " +
            "AND p.policyType = :policyType " +
            "AND p.status = 'ACTIVE' " +
            "AND p.isActive = true " +
            "ORDER BY p.effectiveFrom DESC")
    List<ClinicalPolicy> findActivePoliciesByHospitalAndType(
            @Param("hospitalId") UUID hospitalId,
            @Param("policyType") PolicyType policyType);

    @Query("SELECT p FROM ClinicalPolicy p WHERE p.hospital.id = :hospitalId " +
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

    @Query("SELECT p FROM ClinicalPolicy p WHERE p.hospital IS NULL " +
            "AND p.policyType = :policyType " +
            "AND p.status = 'ACTIVE' " +
            "AND p.isActive = true " +
            "ORDER BY p.effectiveFrom DESC")
    List<ClinicalPolicy> findSystemWideActivePolicies(
            @Param("policyType") PolicyType policyType);

    boolean existsByHospitalIdAndPolicyCodeAndIsActiveTrue(UUID hospitalId, String policyCode);
}
