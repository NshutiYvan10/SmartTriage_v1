package com.smartTriage.smartTriage_server.module.governance.repository;

import com.smartTriage.smartTriage_server.module.governance.entity.PolicyAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PolicyAuditLogRepository extends JpaRepository<PolicyAuditLog, UUID> {

    Page<PolicyAuditLog> findByPolicyIdAndIsActiveTrueOrderByActionAtDesc(
            UUID policyId, Pageable pageable);
}
