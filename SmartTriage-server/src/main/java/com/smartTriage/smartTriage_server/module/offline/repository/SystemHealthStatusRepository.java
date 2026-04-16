package com.smartTriage.smartTriage_server.module.offline.repository;

import com.smartTriage.smartTriage_server.module.offline.entity.SystemHealthStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SystemHealthStatusRepository extends JpaRepository<SystemHealthStatus, UUID> {

    Optional<SystemHealthStatus> findFirstByHospitalIdAndIsActiveTrueOrderByCheckTimeDesc(UUID hospitalId);

    Page<SystemHealthStatus> findByHospitalIdAndIsActiveTrueOrderByCheckTimeDesc(UUID hospitalId, Pageable pageable);
}
