package com.smartTriage.smartTriage_server.module.visit.service;

import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.module.bed.repository.BedRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ZoneRoutingService — single source of truth for the
 * "where should this patient be?" decision.
 *
 * <p>Wraps {@link EdZone#forPatientPlacement} with the two pieces of
 * runtime information it needs and the caller doesn't carry directly:
 * <ul>
 *   <li>the hospital's {@code has_pediatric_resus} flag (per-facility
 *       config)</li>
 *   <li>whether the hospital provisions any beds in the AMBULATORY
 *       zone (derived from bed inventory; AMBULATORY is opt-in and
 *       district hospitals don't have it)</li>
 * </ul>
 *
 * <p>One call site, one place to change the policy. Triage performance
 * and system-triggered re-triage both go through here so the placement
 * decision is identical regardless of how the category was set.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZoneRoutingService {

    private final BedRepository bedRepository;

    /**
     * Compute the canonical zone for a visit.
     *
     * @param visit must be non-null and have its hospital set
     * @param category the triage category that should drive routing —
     *        usually {@code visit.getCurrentTriageCategory()} but
     *        callers may pass a freshly-decided category that hasn't
     *        been written to the visit yet
     * @return the zone the patient should be placed in. Never null;
     *         returns {@link EdZone#TRIAGE} when category is null
     *         (patient hasn't been triaged yet).
     */
    public EdZone routeFor(Visit visit, TriageCategory category) {
        if (visit == null) return EdZone.TRIAGE;
        Hospital hospital = visit.getHospital();
        boolean hasPedsResus = hospital != null && hospital.isHasPediatricResus();
        boolean hasNeonatalUnit = hospital != null && hospital.isHasNeonatalUnit();
        boolean hasAmbulatoryZone = hospital != null
                && bedRepository.countByHospitalIdAndZoneAndIsActiveTrue(
                        hospital.getId(), EdZone.AMBULATORY) > 0;
        // Drive YELLOW (adult) routing from bed inventory the same way
        // GREEN drives AMBULATORY: if the hospital provisions any
        // OBSERVATION beds, YELLOW patients land there; otherwise they
        // fall back to GENERAL. Admin-added observation beds activate
        // the route automatically.
        boolean hasObservationZone = hospital != null
                && bedRepository.countByHospitalIdAndZoneAndIsActiveTrue(
                        hospital.getId(), EdZone.OBSERVATION) > 0;
        boolean isNeonatal = isNeonatal(visit);
        EdZone zone = EdZone.forPatientPlacement(
                category, visit.isPediatric(), isNeonatal,
                hasPedsResus, hasAmbulatoryZone, hasNeonatalUnit,
                hasObservationZone);
        log.debug("[zone-routing] visit={} category={} peds={} neonatal={} hasPedsResus={} hasAmbulatory={} hasObservation={} hasNeonatal={} → {}",
                visit.getVisitNumber(), category, visit.isPediatric(), isNeonatal,
                hasPedsResus, hasAmbulatoryZone, hasObservationZone, hasNeonatalUnit, zone);
        return zone;
    }

    /**
     * Neonatal classification — patient is ≤28 days old. Reads the
     * patient's date of birth on the visit; returns false when DOB
     * is null (unknown age → don't activate the neonatal branch
     * since miscoding a 5-year-old as a neonate is the worse failure
     * mode).
     */
    private static boolean isNeonatal(Visit visit) {
        if (visit == null || visit.getPatient() == null) return false;
        java.time.LocalDate dob = visit.getPatient().getDateOfBirth();
        if (dob == null) return false;
        long days = java.time.temporal.ChronoUnit.DAYS.between(dob, java.time.LocalDate.now());
        return days >= 0 && days <= 28;
    }

    /**
     * Convenience overload reading the category from the visit itself.
     */
    public EdZone routeFor(Visit visit) {
        return routeFor(visit, visit == null ? null : visit.getCurrentTriageCategory());
    }
}
