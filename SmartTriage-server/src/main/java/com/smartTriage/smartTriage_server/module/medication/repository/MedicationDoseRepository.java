package com.smartTriage.smartTriage_server.module.medication.repository;

import com.smartTriage.smartTriage_server.common.enums.DoseKind;
import com.smartTriage.smartTriage_server.common.enums.DoseStatus;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationDose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MedicationDoseRepository extends JpaRepository<MedicationDose, UUID> {

    Optional<MedicationDose> findByIdAndIsActiveTrue(UUID id);

    /** Full dose timeline for one order, in administration order. */
    List<MedicationDose> findByMedicationIdAndIsActiveTrueOrderBySequenceNumberAscCreatedAtAsc(
            UUID medicationId);

    /** Full dose history for a visit — the medication audit trail. */
    List<MedicationDose> findByVisitIdAndIsActiveTrueOrderByCreatedAtAsc(UUID visitId);

    /** Open (waiting) doses for one order. */
    List<MedicationDose> findByMedicationIdAndStatusAndIsActiveTrue(
            UUID medicationId, DoseStatus status);

    /** Number of doses actually given for an order (drives max-doses completion). */
    long countByMedicationIdAndStatusAndIsActiveTrue(UUID medicationId, DoseStatus status);

    /** All dose rows of an order — drives monotonic sequence numbering. */
    long countByMedicationIdAndIsActiveTrue(UUID medicationId);

    /** Most recent given dose of an order (drives the PRN min-interval guard). */
    Optional<MedicationDose> findFirstByMedicationIdAndStatusAndIsActiveTrueOrderByGivenAtDesc(
            UUID medicationId, DoseStatus status);

    /** Given doses in a trailing window (drives the PRN max-per-24h guard). */
    long countByMedicationIdAndStatusAndGivenAtAfterAndIsActiveTrue(
            UUID medicationId, DoseStatus status, Instant after);

    /** Latest infusion event for a CONTINUOUS order (running ⇔ latest != STOP). */
    Optional<MedicationDose> findFirstByMedicationIdAndKindInAndIsActiveTrueOrderByGivenAtDesc(
            UUID medicationId, Collection<DoseKind> kinds);

    /**
     * Every GIVEN dose of one DRUG across the whole visit in a trailing
     * window — across ALL orders of that drug, not just one (two
     * separate paracetamol orders still share one daily maximum).
     * Drives the cumulative daily-dose cap at administration time.
     */
    @Query("SELECT d FROM MedicationDose d JOIN d.medication m "
            + "WHERE d.visit.id = :visitId AND d.isActive = true "
            + "AND d.status = com.smartTriage.smartTriage_server.common.enums.DoseStatus.GIVEN "
            + "AND d.givenAt > :since "
            + "AND LOWER(m.drugName) = LOWER(:drugName)")
    List<MedicationDose> findGivenForVisitAndDrugSince(
            @Param("visitId") UUID visitId,
            @Param("drugName") String drugName,
            @Param("since") Instant since);

    /**
     * Zone medication board — every open DUE dose for a hospital with
     * its order + visit + patient eagerly fetched (one query, no N+1;
     * the controller maps it straight to the board DTO). The service
     * filters by zone in-memory from {@code visit.currentEdZone} so a
     * mid-prescription zone transfer is honoured live.
     */
    @Query("SELECT d FROM MedicationDose d "
            + "JOIN FETCH d.medication m JOIN FETCH d.visit v JOIN FETCH v.patient "
            + "WHERE v.hospital.id = :hospitalId AND d.isActive = true "
            + "AND d.status = com.smartTriage.smartTriage_server.common.enums.DoseStatus.DUE "
            + "ORDER BY d.dueAt ASC")
    List<MedicationDose> findOpenDueForHospital(@Param("hospitalId") UUID hospitalId);

    /**
     * Recently given doses for a hospital (board's "administered" lane).
     */
    @Query("SELECT d FROM MedicationDose d "
            + "JOIN FETCH d.medication m JOIN FETCH d.visit v JOIN FETCH v.patient "
            + "WHERE v.hospital.id = :hospitalId AND d.isActive = true "
            + "AND d.status = com.smartTriage.smartTriage_server.common.enums.DoseStatus.GIVEN "
            + "AND d.givenAt >= :since "
            + "ORDER BY d.givenAt DESC")
    List<MedicationDose> findRecentlyGivenForHospital(
            @Param("hospitalId") UUID hospitalId, @Param("since") Instant since);

    /**
     * Scheduler sweep — DUE doses past a cutoff, order + visit +
     * patient + hospital fetched so the monitor can build alerts
     * without LazyInit issues.
     */
    @Query("SELECT d FROM MedicationDose d "
            + "JOIN FETCH d.medication m JOIN FETCH d.visit v "
            + "JOIN FETCH v.patient JOIN FETCH v.hospital "
            + "WHERE d.isActive = true "
            + "AND d.status = com.smartTriage.smartTriage_server.common.enums.DoseStatus.DUE "
            + "AND d.dueAt < :cutoff")
    List<MedicationDose> findDueBefore(@Param("cutoff") Instant cutoff);
}
