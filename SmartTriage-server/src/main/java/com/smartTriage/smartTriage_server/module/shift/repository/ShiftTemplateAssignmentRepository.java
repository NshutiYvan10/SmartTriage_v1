package com.smartTriage.smartTriage_server.module.shift.repository;

import com.smartTriage.smartTriage_server.module.shift.entity.ShiftTemplateAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ShiftTemplateAssignmentRepository extends JpaRepository<ShiftTemplateAssignment, UUID> {

    List<ShiftTemplateAssignment> findByTemplateIdAndIsActiveTrue(UUID templateId);

    List<ShiftTemplateAssignment> findByUserIdAndIsActiveTrue(UUID userId);

    void deleteByTemplateId(UUID templateId);
}
