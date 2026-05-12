package com.smartTriage.smartTriage_server.module.visit.repository;

import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.common.enums.VisitStatus;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
public interface VisitRepository extends JpaRepository<Visit, UUID> {

        Optional<Visit> findByIdAndIsActiveTrue(UUID id);

        Optional<Visit> findByVisitNumberAndIsActiveTrue(String visitNumber);

        /**
         * Lightweight projection used by ClinicalAuthz to verify a visit
         * belongs to a given hospital without hydrating the entity. Returns
         * empty when no visit with that id exists.
         */
        @Query("SELECT v.hospital.id FROM Visit v WHERE v.id = :visitId")
        Optional<UUID> findHospitalIdByVisitId(@Param("visitId") UUID visitId);

        /**
         * Lightweight projection used by ClinicalAuthz.callerCanWriteToVisit
         * to enforce zone-scoped clinical writes. Returns empty Optional
         * for a pre-triage visit (currentEdZone IS NULL) OR for an unknown
         * visit id; the caller distinguishes via the existence check above.
         */
        @Query("SELECT v.currentEdZone FROM Visit v WHERE v.id = :visitId")
        Optional<com.smartTriage.smartTriage_server.common.enums.EdZone>
                findCurrentEdZoneByVisitId(@Param("visitId") UUID visitId);

        Page<Visit> findByHospitalIdAndIsActiveTrue(UUID hospitalId, Pageable pageable);

        Page<Visit> findByPatientIdAndIsActiveTrue(UUID patientId, Pageable pageable);

        @Query("SELECT v FROM Visit v WHERE v.hospital.id = :hospitalId AND v.isActive = true " +
                        "AND v.status NOT IN ('DISCHARGED', 'ADMITTED', 'TRANSFERRED', 'ICU_ADMITTED', 'DECEASED', 'LEFT_WITHOUT_BEING_SEEN')")
        Page<Visit> findActiveVisits(@Param("hospitalId") UUID hospitalId, Pageable pageable);

        @Query("SELECT v FROM Visit v WHERE v.hospital.id = :hospitalId AND v.isActive = true " +
                        "AND v.currentTriageCategory = :category " +
                        "AND v.status NOT IN ('DISCHARGED', 'ADMITTED', 'TRANSFERRED', 'ICU_ADMITTED', 'DECEASED', 'LEFT_WITHOUT_BEING_SEEN')")
        List<Visit> findActiveVisitsByTriageCategory(
                        @Param("hospitalId") UUID hospitalId,
                        @Param("category") TriageCategory category);

        @Query("SELECT v FROM Visit v WHERE v.hospital.id = :hospitalId AND v.isActive = true " +
                        "AND v.status = :status")
        Page<Visit> findByHospitalIdAndStatus(
                        @Param("hospitalId") UUID hospitalId,
                        @Param("status") VisitStatus status,
                        Pageable pageable);

        /**
         * Active visits whose triage category maps to a given set of categories
         * (zone-based).
         * Used for "My Patients" — doctor sees only patients in their zone.
         */
        @Query("SELECT v FROM Visit v WHERE v.hospital.id = :hospitalId AND v.isActive = true " +
                        "AND v.currentTriageCategory IN :categories " +
                        "AND v.status NOT IN ('DISCHARGED', 'ADMITTED', 'TRANSFERRED', 'ICU_ADMITTED', 'DECEASED', 'LEFT_WITHOUT_BEING_SEEN')")
        List<Visit> findActiveVisitsByTriageCategories(
                        @Param("hospitalId") UUID hospitalId,
                        @Param("categories") List<TriageCategory> categories);

        /**
         * Active visits filtered by specific statuses — used by retriage and monitoring engines.
         */
        @Query("SELECT v FROM Visit v WHERE v.hospital.id = :hospitalId AND v.isActive = true AND v.status IN :statuses")
        List<Visit> findActiveVisitsByStatuses(
                        @Param("hospitalId") UUID hospitalId,
                        @Param("statuses") List<VisitStatus> statuses);

        /**
         * All active visits across all hospitals filtered by statuses — for scheduled monitoring tasks.
         */
        @Query("SELECT v FROM Visit v WHERE v.isActive = true AND v.status IN :statuses")
        List<Visit> findAllActiveVisitsByStatuses(@Param("statuses") List<VisitStatus> statuses);

        /**
         * Most recent arrival time across all active visits for a patient.
         * Used by the patient-lookup service to surface "last seen at" when a
         * triage nurse is choosing among ranked candidates. Returns empty if
         * the patient has no active visits.
         */
        @Query("SELECT MAX(v.arrivalTime) FROM Visit v WHERE v.patient.id = :patientId AND v.isActive = true")
        Optional<Instant> findLastArrivalByPatientId(@Param("patientId") UUID patientId);

        /**
         * Phase 1 zone-scoped visit list. The doctor-side question
         * "show me only the patients in my zone" reads
         * {@code current_ed_zone} directly. Discharged / admitted /
         * transferred visits are excluded — same shape as
         * {@link #findActiveVisits} so the zone-scoped list is a
         * straight subset of the full active list.
         *
         * <p>Accepts a collection of zones rather than a single zone
         * because (a) Phase 2 will surface pending-transfer patients
         * in both their current and target zones, (b) the AMBULATORY
         * zone often shares staff coverage with GENERAL on smaller
         * shifts, so a doctor may legitimately need both at once.
         */
        /**
         * RBAC fix — active pre-triage visits for the TRIAGE_NURSE's queue.
         * A visit is "pre-triage" when {@code currentEdZone IS NULL} (no
         * triage decision yet) OR explicitly placed in the TRIAGE holding
         * zone. The Triage Nurse owns this list end-to-end; once triaged,
         * the patient leaves their queue and lands in a destination-zone
         * nurse's queue via {@link #findActiveVisitsInZones}.
         */
        @Query("SELECT v FROM Visit v WHERE v.hospital.id = :hospitalId AND v.isActive = true " +
                        "AND (v.currentEdZone IS NULL OR v.currentEdZone = 'TRIAGE') " +
                        "AND v.status NOT IN ('DISCHARGED', 'ADMITTED', 'TRANSFERRED', 'ICU_ADMITTED', 'DECEASED', 'LEFT_WITHOUT_BEING_SEEN')")
        Page<Visit> findPreTriageActiveVisits(
                        @Param("hospitalId") UUID hospitalId,
                        Pageable pageable);

        @Query("SELECT v FROM Visit v WHERE v.hospital.id = :hospitalId AND v.isActive = true " +
                        "AND v.currentEdZone IN :zones " +
                        "AND v.status NOT IN ('DISCHARGED', 'ADMITTED', 'TRANSFERRED', 'ICU_ADMITTED', 'DECEASED', 'LEFT_WITHOUT_BEING_SEEN')")
        Page<Visit> findActiveVisitsInZones(
                        @Param("hospitalId") UUID hospitalId,
                        @Param("zones") Collection<EdZone> zones,
                        Pageable pageable);

        /**
         * Phase 1 EMS — visits whose ED re-triage window has elapsed
         * and that are still in active care. The retriage scheduler
         * compares each row against its TriageRecord existence and
         * fires an alert when a paramedic-brought patient hasn't been
         * formally re-triaged within 15 min.
         */
        @Query("SELECT v FROM Visit v WHERE v.isActive = true " +
                        "AND v.edRetriageDueAt IS NOT NULL " +
                        "AND v.edRetriageDueAt < :now " +
                        "AND v.status NOT IN ('DISCHARGED', 'ADMITTED', 'TRANSFERRED', 'ICU_ADMITTED', 'DECEASED', 'LEFT_WITHOUT_BEING_SEEN')")
        java.util.List<Visit> findRetriageDueBefore(@Param("now") java.time.Instant now);
}
