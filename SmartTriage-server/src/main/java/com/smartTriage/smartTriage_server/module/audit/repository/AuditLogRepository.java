package com.smartTriage.smartTriage_server.module.audit.repository;

import com.smartTriage.smartTriage_server.module.audit.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID>, JpaSpecificationExecutor<AuditLog> {

    Page<AuditLog> findByHospitalIdOrderByCreatedAtDesc(UUID hospitalId, Pageable pageable);

    Page<AuditLog> findByHospitalIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            UUID hospitalId, Instant from, Instant to, Pageable pageable);

    /** Unpaged hospital+range list for CSV export (newest first). */
    List<AuditLog> findByHospitalIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            UUID hospitalId, Instant from, Instant to);

    /**
     * Full chronological trail for a visit (V107) — everything anyone did to this
     * patient's encounter, oldest first, exactly how an incident timeline reads.
     * Includes FAILED/denied attempts (they carry the same visit attribution).
     */
    List<AuditLog> findByVisitIdOrderByCreatedAtAsc(UUID visitId);
}
