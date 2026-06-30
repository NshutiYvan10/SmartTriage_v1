package com.smartTriage.smartTriage_server.module.shift.repository;

import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.ShiftFunction;
import com.smartTriage.smartTriage_server.common.enums.ShiftPeriod;
import com.smartTriage.smartTriage_server.module.shift.entity.ShiftAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ShiftAssignmentRepository extends JpaRepository<ShiftAssignment, UUID> {

    Optional<ShiftAssignment> findByIdAndIsActiveTrue(UUID id);

    /**
     * All active assignments for a hospital on a specific shift.
     */
    List<ShiftAssignment> findByHospitalIdAndShiftDateAndShiftPeriodAndIsActiveTrue(
            UUID hospitalId, LocalDate shiftDate, ShiftPeriod shiftPeriod);

    /**
     * Assignments for a specific zone on a shift — used to find the zone doctor.
     */
    List<ShiftAssignment> findByHospitalIdAndShiftDateAndShiftPeriodAndZoneAndIsActiveTrue(
            UUID hospitalId, LocalDate shiftDate, ShiftPeriod shiftPeriod, EdZone zone);

    /**
     * Find staff assigned to a specific zone with a specific function on a shift.
     */
    @Query("SELECT sa FROM ShiftAssignment sa WHERE sa.hospital.id = :hospitalId " +
            "AND sa.shiftDate = :shiftDate AND sa.shiftPeriod = :shiftPeriod " +
            "AND sa.zone = :zone AND sa.shiftFunction = :shiftFunction AND sa.isActive = true")
    List<ShiftAssignment> findByZoneAndFunction(
            @Param("hospitalId") UUID hospitalId,
            @Param("shiftDate") LocalDate shiftDate,
            @Param("shiftPeriod") ShiftPeriod shiftPeriod,
            @Param("zone") EdZone zone,
            @Param("shiftFunction") ShiftFunction shiftFunction);

    /**
     * All doctors (PRIMARY_DOCTOR + SUPERVISING_DOCTOR + RESIDENT) on duty — for
     * Tier 2 escalation.
     */
    @Query("SELECT sa FROM ShiftAssignment sa WHERE sa.hospital.id = :hospitalId " +
            "AND sa.shiftDate = :shiftDate AND sa.shiftPeriod = :shiftPeriod " +
            "AND sa.shiftFunction IN (com.smartTriage.smartTriage_server.common.enums.ShiftFunction.PRIMARY_DOCTOR, " +
            "com.smartTriage.smartTriage_server.common.enums.ShiftFunction.SUPERVISING_DOCTOR, " +
            "com.smartTriage.smartTriage_server.common.enums.ShiftFunction.RESIDENT) " +
            "AND sa.isActive = true")
    List<ShiftAssignment> findAllDoctorsOnDuty(
            @Param("hospitalId") UUID hospitalId,
            @Param("shiftDate") LocalDate shiftDate,
            @Param("shiftPeriod") ShiftPeriod shiftPeriod);

    /**
     * Find the CHARGE_NURSE for the current shift — receives all Tier 1 alerts.
     */
    @Query("SELECT sa FROM ShiftAssignment sa WHERE sa.hospital.id = :hospitalId " +
            "AND sa.shiftDate = :shiftDate AND sa.shiftPeriod = :shiftPeriod " +
            "AND sa.shiftFunction = com.smartTriage.smartTriage_server.common.enums.ShiftFunction.CHARGE_NURSE " +
            "AND sa.isActive = true")
    List<ShiftAssignment> findChargeNurse(
            @Param("hospitalId") UUID hospitalId,
            @Param("shiftDate") LocalDate shiftDate,
            @Param("shiftPeriod") ShiftPeriod shiftPeriod);

    /**
     * Find assignments for a specific date (all shifts) — for shift history view.
     */
    List<ShiftAssignment> findByHospitalIdAndShiftDateAndIsActiveTrue(
            UUID hospitalId, LocalDate shiftDate);

    /**
     * All active assignments at a hospital across an inclusive date range.
     * Used by the copy-week bulk operation to read every row in the source
     * week in a single query, regardless of period.
     */
    List<ShiftAssignment> findByHospitalIdAndShiftDateBetweenAndIsActiveTrue(
            UUID hospitalId, LocalDate from, LocalDate to);

    /**
     * Find a user's shift history.
     */
    List<ShiftAssignment> findByUserIdAndIsActiveTrueOrderByShiftDateDescShiftPeriodDesc(UUID userId);

    /**
     * All staff on duty for a hospital on a shift — for Tier 3 escalation.
     */
    List<ShiftAssignment> findByHospitalIdAndShiftDateAndShiftPeriodAndIsActiveTrueOrderByZone(
            UUID hospitalId, LocalDate shiftDate, ShiftPeriod shiftPeriod);

    /**
     * Find a user's current assignment.
     */
    Optional<ShiftAssignment> findByUserIdAndShiftDateAndShiftPeriodAndIsActiveTrue(
            UUID userId, LocalDate shiftDate, ShiftPeriod shiftPeriod);

    /**
     * Count staff per zone — for surge detection.
     */
    @Query("SELECT sa.zone, COUNT(sa) FROM ShiftAssignment sa WHERE sa.hospital.id = :hospitalId " +
            "AND sa.shiftDate = :shiftDate AND sa.shiftPeriod = :shiftPeriod " +
            "AND sa.isActive = true GROUP BY sa.zone")
    List<Object[]> countStaffByZone(
            @Param("hospitalId") UUID hospitalId,
            @Param("shiftDate") LocalDate shiftDate,
            @Param("shiftPeriod") ShiftPeriod shiftPeriod);

    /**
     * Check if a user already has an assignment for this shift.
     */
    boolean existsByUserIdAndShiftDateAndShiftPeriodAndIsActiveTrue(
            UUID userId, LocalDate shiftDate, ShiftPeriod shiftPeriod);

    /* ═════════════════════════ SHIFT-LEAD BADGE ═════════════════════════ */

    /**
     * Current shift-lead for the given (hospital, date, period). Returns empty
     * when no active assignment carries the badge. Partial unique index
     * {@code uk_shift_lead_per_shift} guarantees at most one result.
     */
    @Query("SELECT sa FROM ShiftAssignment sa WHERE sa.hospital.id = :hospitalId " +
            "AND sa.shiftDate = :shiftDate AND sa.shiftPeriod = :shiftPeriod " +
            "AND sa.isShiftLead = true AND sa.isActive = true")
    Optional<ShiftAssignment> findShiftLead(
            @Param("hospitalId") UUID hospitalId,
            @Param("shiftDate") LocalDate shiftDate,
            @Param("shiftPeriod") ShiftPeriod shiftPeriod);

    /**
     * ALL active shift-lead rows for a shift, newest first. The partial unique
     * index should keep this to one, but reading a List (instead of a single
     * Optional) is robust to a pre-existing duplicate-lead state — it lets the
     * service clear every stale badge and never throws NonUniqueResultException.
     */
    @Query("SELECT sa FROM ShiftAssignment sa WHERE sa.hospital.id = :hospitalId " +
            "AND sa.shiftDate = :shiftDate AND sa.shiftPeriod = :shiftPeriod " +
            "AND sa.isShiftLead = true AND sa.isActive = true ORDER BY sa.startedAt DESC")
    List<ShiftAssignment> findAllShiftLeads(
            @Param("hospitalId") UUID hospitalId,
            @Param("shiftDate") LocalDate shiftDate,
            @Param("shiftPeriod") ShiftPeriod shiftPeriod);

    /**
     * All shift-lead rows carried by a user (active or ended) — used for the
     * "previous lead has 30-min grace" fallback: we grab the latest, check
     * {@code endedAt}, and accept if recent.
     */
    @Query("SELECT sa FROM ShiftAssignment sa WHERE sa.user.id = :userId " +
            "AND sa.hospital.id = :hospitalId AND sa.isShiftLead = true " +
            "ORDER BY sa.shiftDate DESC, sa.endedAt DESC NULLS FIRST")
    List<ShiftAssignment> findRecentShiftLeadRowsForUser(
            @Param("userId") UUID userId,
            @Param("hospitalId") UUID hospitalId);

    /**
     * Most recent shift-lead row (active or ended) at a hospital — used to
     * find the previous lead within the grace window regardless of who they
     * were.
     */
    @Query("SELECT sa FROM ShiftAssignment sa WHERE sa.hospital.id = :hospitalId " +
            "AND sa.isShiftLead = true " +
            "ORDER BY sa.shiftDate DESC, sa.endedAt DESC NULLS FIRST, sa.startedAt DESC")
    List<ShiftAssignment> findRecentShiftLeadRowsAtHospital(
            @Param("hospitalId") UUID hospitalId);

    /**
     * V55 — distinct future (shiftDate, shiftPeriod) slots that were
     * materialised from a specific template. Used by
     * {@code ShiftTemplateService.update} to find which calendar slots
     * need to be re-applied after a template edit. Past dates are
     * intentionally excluded — historical rosters are immutable.
     */
    @Query("SELECT DISTINCT sa.shiftDate, sa.shiftPeriod FROM ShiftAssignment sa " +
            "WHERE sa.template.id = :templateId AND sa.isActive = true AND sa.shiftDate >= :fromDate " +
            "ORDER BY sa.shiftDate ASC, sa.shiftPeriod ASC")
    List<Object[]> findFutureSlotsForTemplate(
            @Param("templateId") UUID templateId,
            @Param("fromDate") java.time.LocalDate fromDate);
}
