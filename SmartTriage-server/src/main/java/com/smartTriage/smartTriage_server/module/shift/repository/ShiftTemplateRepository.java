package com.smartTriage.smartTriage_server.module.shift.repository;

import com.smartTriage.smartTriage_server.common.enums.ShiftPeriod;
import com.smartTriage.smartTriage_server.module.shift.entity.ShiftTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ShiftTemplateRepository extends JpaRepository<ShiftTemplate, UUID> {

    /**
     * Fetch by id, ignoring soft-deleted templates.
     */
    Optional<ShiftTemplate> findByIdAndIsActiveTrue(UUID id);

    /**
     * All active templates for a hospital (normally two: DAY + NIGHT).
     */
    List<ShiftTemplate> findByHospitalIdAndIsActiveTrueOrderByShiftPeriodAsc(UUID hospitalId);

    /**
     * The single active template for a (hospital, shiftPeriod) — this is what
     * the scheduler materializes at shift boundary. Partial unique index
     * {@code uk_shift_template_active_per_period} guarantees at most one row.
     */
    Optional<ShiftTemplate> findByHospitalIdAndShiftPeriodAndIsActiveTrue(
            UUID hospitalId, ShiftPeriod shiftPeriod);

    /**
     * All active templates for a period across hospitals — used by the
     * materializer scheduler when it sweeps every hospital at shift boundary.
     */
    List<ShiftTemplate> findByShiftPeriodAndIsActiveTrue(ShiftPeriod shiftPeriod);
}
