package com.smartTriage.smartTriage_server.module.audit.repository;

import com.smartTriage.smartTriage_server.module.audit.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    Page<AuditLog> findByHospitalIdOrderByCreatedAtDesc(UUID hospitalId, Pageable pageable);

    Page<AuditLog> findByHospitalIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            UUID hospitalId, Instant from, Instant to, Pageable pageable);

    /** Unpaged hospital+range list for CSV export (newest first). */
    List<AuditLog> findByHospitalIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            UUID hospitalId, Instant from, Instant to);
}
