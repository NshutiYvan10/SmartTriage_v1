package com.smartTriage.smartTriage_server.module.hospital.repository;

import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HospitalRepository extends JpaRepository<Hospital, UUID> {

    Optional<Hospital> findByHospitalCodeAndIsActiveTrue(String hospitalCode);

    Optional<Hospital> findByIdAndIsActiveTrue(UUID id);

    boolean existsByHospitalCode(String hospitalCode);

    boolean existsByEmail(String email);

    /**
     * All active tenants — used by shift-boundary schedulers that sweep
     * every hospital.
     */
    List<Hospital> findByIsActiveTrue();
}
