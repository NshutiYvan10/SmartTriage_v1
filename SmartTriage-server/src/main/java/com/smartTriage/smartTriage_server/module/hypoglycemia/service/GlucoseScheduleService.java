package com.smartTriage.smartTriage_server.module.hypoglycemia.service;

import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.PrescriptionType;
import com.smartTriage.smartTriage_server.module.hypoglycemia.engine.GlucoseScheduleEngine;
import com.smartTriage.smartTriage_server.module.hypoglycemia.engine.GlucoseScheduleEngine.Tier;
import com.smartTriage.smartTriage_server.module.hypoglycemia.engine.HypoglycemiaEnforcementEngine;
import com.smartTriage.smartTriage_server.module.hypoglycemia.repository.HypoglycemiaEventRepository;
import com.smartTriage.smartTriage_server.module.medication.repository.MedicationAdministrationRepository;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.triage.repository.TriageRecordRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * GlucoseScheduleService — computes, ON THE FLY, which in-department patients
 * are on a glucose-measurement schedule and when their next reading is due.
 * Deliberately stateless (no "next_due_at" column): tier membership, the latest
 * reading and the anchors are all re-derived from live data every evaluation,
 * so the clock can never desynchronize from reality — recording a glucose
 * anywhere silently re-arms it, an insulin stop silently disarms it.
 *
 * Shared by three consumers:
 * <ul>
 *   <li>{@link GlucoseScheduleMonitorService} — the scheduled scan that pages
 *       DUE / OVERDUE;</li>
 *   <li>the due-list endpoint (nurses' worklist on the glucose dashboard);</li>
 *   <li>{@link HypoglycemiaService#checkAndEnforce} — the staleness guard
 *       ("this reading is too old to reassure for this patient's tier").</li>
 * </ul>
 *
 * Due-time semantics: {@code dueAt = max(latest reading, tier anchor) + interval}.
 * The anchor is when the tier's condition STARTED (insulin order start, event
 * resolution, latest triage, arrival) — without it, starting an infusion on a
 * patient whose last glucose was hours ago would page OVERDUE instantly instead
 * of starting a fresh q1h cycle.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GlucoseScheduleService {

    /** How far back an insulin order still counts as live exposure (see the repository query). */
    static final Duration INSULIN_RECENCY = Duration.ofHours(24);

    /** One patient's computed schedule state. {@code dueAt} may be in the future (worklist countdown). */
    public record DueEntry(Visit visit, Tier tier, Instant lastReadingAt, Instant dueAt, Instant escalateAt) {
        public boolean due(Instant now)     { return !now.isBefore(dueAt); }
        public boolean overdue(Instant now) { return !now.isBefore(escalateAt); }
    }

    private final VisitRepository visitRepository;
    private final TriageRecordRepository triageRecordRepository;
    private final MedicationAdministrationRepository medicationAdministrationRepository;
    private final HypoglycemiaEventRepository hypoglycemiaEventRepository;
    private final GlucoseReadingLookup readingLookup;
    private final GlucoseScheduleEngine scheduleEngine;

    /** Schedule state for every in-department patient, all hospitals — the monitor scan. */
    public List<DueEntry> computeAll(Instant now) {
        return compute(visitRepository.findInDepartmentWithPatient(
                HypoglycemiaService.TERMINAL_VISIT_STATUSES), now);
    }

    /** Schedule state for one hospital's in-department patients, optionally zone-filtered — the worklist. */
    public List<DueEntry> computeForHospital(UUID hospitalId, EdZone zone, Instant now) {
        List<Visit> visits = visitRepository.findInDepartmentWithPatientByHospital(
                hospitalId, HypoglycemiaService.TERMINAL_VISIT_STATUSES);
        if (zone != null) {
            visits = visits.stream().filter(v -> v.getCurrentEdZone() == zone).toList();
        }
        return compute(visits, now);
    }

    /**
     * Schedule state for ONE visit (empty when no tier applies, or when an
     * unresolved hypoglycemia event owns the patient) — the staleness guard.
     * Triage records are passed in because the caller already loaded them.
     */
    public Optional<DueEntry> computeForVisit(Visit visit, List<TriageRecord> triageRecords, Instant now) {
        return computeInternal(List.of(visit), triageRecords, now).stream().findFirst();
    }

    private List<DueEntry> compute(List<Visit> visits, Instant now) {
        if (visits.isEmpty()) return List.of();
        List<UUID> ids = visits.stream().map(Visit::getId).toList();
        return computeInternal(visits, triageRecordRepository.findAllByVisitIds(ids), now);
    }

    private List<DueEntry> computeInternal(List<Visit> visits, List<TriageRecord> triageRecords, Instant now) {
        if (visits.isEmpty()) return List.of();
        List<UUID> ids = visits.stream().map(Visit::getId).toList();

        // Patients with an UNRESOLVED event are owned by the 15-minute recheck clock —
        // the due-clock must never double-page them.
        Set<UUID> ownedByRecheck = new HashSet<>(hypoglycemiaEventRepository.findVisitIdsWithUnresolvedEvents());

        // Latest triage per visit (mandatory-trigger state) — records per visit are few.
        Map<UUID, TriageRecord> latestTriage = new HashMap<>();
        if (triageRecords != null) {
            for (TriageRecord t : triageRecords) {
                latestTriage.merge(t.getVisit().getId(), t, (a, b) -> {
                    if (a.getTriageTime() == null) return b;
                    if (b.getTriageTime() == null) return a;
                    return b.getTriageTime().isAfter(a.getTriageTime()) ? b : a;
                });
            }
        }

        // Live insulin exposure: infusion? and when the latest order started.
        Map<UUID, Boolean> insulinInfusion = new HashMap<>();
        Map<UUID, Instant> insulinStart = new HashMap<>();
        for (Object[] row : medicationAdministrationRepository.findInsulinExposureByVisit(
                ids, now, now.minus(INSULIN_RECENCY))) {
            UUID vid = (UUID) row[0];
            if (row[1] == PrescriptionType.CONTINUOUS) insulinInfusion.put(vid, true);
            else insulinInfusion.putIfAbsent(vid, false);
            if (row[2] != null) {
                insulinStart.merge(vid, (Instant) row[2], (a, b) -> a.isAfter(b) ? a : b);
            }
        }

        // Recently-resolved hypoglycemia (q1h observation window).
        Map<UUID, Instant> resolvedAt = new HashMap<>();
        for (Object[] row : hypoglycemiaEventRepository.latestResolvedAfterByVisit(
                ids, now.minus(scheduleEngine.postHypoWindow()))) {
            resolvedAt.put((UUID) row[0], (Instant) row[1]);
        }

        Map<UUID, Instant> latestReadingAt = readingLookup.latestAtByVisit(ids, triageRecords);

        List<DueEntry> entries = new ArrayList<>();
        for (Visit visit : visits) {
            UUID vid = visit.getId();
            if (ownedByRecheck.contains(vid)) continue;

            TriageRecord triage = latestTriage.get(vid);
            Tier tier = scheduleEngine.determineTier(
                    resolvedAt.containsKey(vid),
                    Boolean.TRUE.equals(insulinInfusion.get(vid)),
                    insulinInfusion.containsKey(vid),
                    scheduleEngine.isCriticallyIll(visit, triage),
                    HypoglycemiaEnforcementEngine.isKnownDiabetic(visit.getPatient()));
            if (tier == null) continue;

            Instant anchor = switch (tier.key()) {
                case "POST_HYPO" -> resolvedAt.get(vid);
                case "INSULIN_INFUSION", "INSULIN" -> insulinStart.get(vid);
                case "CRITICAL" -> triage != null && triage.getTriageTime() != null
                        ? triage.getTriageTime() : visit.getArrivalTime();
                default -> visit.getArrivalTime();
            };
            Instant base = latest(latestReadingAt.get(vid), anchor, visit.getArrivalTime());
            if (base == null) continue; // no reading, no anchor, no arrival time — nothing to clock from

            Instant dueAt = base.plus(tier.interval());
            entries.add(new DueEntry(visit, tier, latestReadingAt.get(vid), dueAt, dueAt.plus(tier.grace())));
        }
        entries.sort(Comparator.comparing(DueEntry::dueAt));
        return entries;
    }

    private static Instant latest(Instant... candidates) {
        Instant best = null;
        for (Instant c : candidates) {
            if (c != null && (best == null || c.isAfter(best))) best = c;
        }
        return best;
    }
}
