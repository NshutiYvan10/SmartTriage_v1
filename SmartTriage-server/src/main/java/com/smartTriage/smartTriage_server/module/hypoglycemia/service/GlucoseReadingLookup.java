package com.smartTriage.smartTriage_server.module.hypoglycemia.service;

import com.smartTriage.smartTriage_server.common.enums.GlucoseUnit;
import com.smartTriage.smartTriage_server.module.hypoglycemia.engine.HypoglycemiaEnforcementEngine;
import com.smartTriage.smartTriage_server.module.hypoglycemia.entity.HypoglycemiaEvent;
import com.smartTriage.smartTriage_server.module.hypoglycemia.repository.HypoglycemiaEventRepository;
import com.smartTriage.smartTriage_server.module.lab.entity.LabOrder;
import com.smartTriage.smartTriage_server.module.lab.repository.LabOrderRepository;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.vital.entity.VitalSigns;
import com.smartTriage.smartTriage_server.module.vital.repository.VitalSignsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * GlucoseReadingLookup — "when (and what) was this patient's LAST blood glucose,
 * from ANY source?" Glucose reaches the system through four doors (triage
 * capture, manual/POC vitals, resulted labs, and hypoglycemia-event repeat
 * readings); each was previously consulted only by its own feature, so nothing
 * could reason about measurement RECENCY across them. This component is that
 * single answer, shared by the glucose due-clock scan and the check-path
 * staleness guard so they can never disagree on what counts as a reading.
 */
@Component
@RequiredArgsConstructor
public class GlucoseReadingLookup {

    /** One glucose observation: value normalized to mmol/L, when it was taken, which door it came through. */
    public record Reading(Double mmol, Instant at, String source) {}

    private final VitalSignsRepository vitalSignsRepository;
    private final HypoglycemiaEventRepository hypoglycemiaEventRepository;
    private final LabOrderRepository labOrderRepository;

    /**
     * Latest reading for ONE visit with its value — the check-path staleness
     * guard (needs the value to display / re-interpret). Triage records are
     * passed in pre-loaded because every caller already has them.
     */
    public Optional<Reading> latestForVisit(UUID visitId, Collection<TriageRecord> triageRecords) {
        Reading best = null;

        VitalSigns vitals = vitalSignsRepository
                .findFirstByVisitIdAndIsActiveTrueAndBloodGlucoseIsNotNullOrderByRecordedAtDesc(visitId)
                .orElse(null);
        if (vitals != null) best = newer(best, new Reading(vitals.getBloodGlucose(), vitals.getRecordedAt(), "VITALS"));

        if (triageRecords != null) {
            for (TriageRecord t : triageRecords) {
                Double g = HypoglycemiaEnforcementEngine.resolveGlucoseValue(t);
                if (g != null && t.getTriageTime() != null) {
                    best = newer(best, new Reading(g, t.getTriageTime(), "TRIAGE"));
                }
            }
        }

        for (HypoglycemiaEvent e : hypoglycemiaEventRepository.findByVisitIdAndIsActiveTrueOrderByDetectedAtDesc(visitId)) {
            if (e.getGlucoseLevel() != null && e.getDetectedAt() != null) {
                best = newer(best, new Reading(e.getGlucoseLevel(), e.getDetectedAt(), "EVENT"));
            }
            if (e.getRepeatGlucoseLevel() != null && e.getRepeatGlucoseAt() != null) {
                best = newer(best, new Reading(e.getRepeatGlucoseLevel(), e.getRepeatGlucoseAt(), "RECHECK"));
            }
        }

        List<LabOrder> labs = labOrderRepository.findGlucoseResultsForVisit(visitId, PageRequest.of(0, 1));
        if (!labs.isEmpty()) {
            LabOrder lab = labs.get(0);
            Double mmol = toMmol(lab.getResultNumeric(), lab.getResultUnit());
            if (mmol != null) best = newer(best, new Reading(mmol, lab.getResultedAt(), "LAB"));
        }

        return Optional.ofNullable(best);
    }

    /**
     * Latest reading TIMESTAMP per visit across all sources — the due-clock
     * scan (due-ness needs recency only, so this stays four grouped queries
     * instead of loading every reading row). Visits with no reading anywhere
     * are absent from the map.
     */
    public Map<UUID, Instant> latestAtByVisit(Collection<UUID> visitIds, Collection<TriageRecord> triageRecords) {
        Map<UUID, Instant> latest = new HashMap<>();
        if (visitIds == null || visitIds.isEmpty()) return latest;

        merge(latest, vitalSignsRepository.latestGlucoseAtByVisit(visitIds));
        merge(latest, hypoglycemiaEventRepository.latestEventGlucoseAtByVisit(visitIds));
        merge(latest, labOrderRepository.latestGlucoseResultAtByVisit(visitIds));

        if (triageRecords != null) {
            for (TriageRecord t : triageRecords) {
                if (t.getTriageTime() == null || HypoglycemiaEnforcementEngine.resolveGlucoseValue(t) == null) continue;
                UUID vid = t.getVisit().getId();
                latest.merge(vid, t.getTriageTime(), (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        return latest;
    }

    private static void merge(Map<UUID, Instant> into, List<Object[]> rows) {
        for (Object[] row : rows) {
            into.merge((UUID) row[0], (Instant) row[1], (a, b) -> a.isAfter(b) ? a : b);
        }
    }

    private static Reading newer(Reading a, Reading b) {
        if (a == null) return b;
        if (b == null || b.at() == null) return a;
        if (a.at() == null) return b;
        return b.at().isAfter(a.at()) ? b : a;
    }

    /** Normalize a lab glucose to mmol/L from its unit string (mirrors LabOrderService.glucoseToMmolL). */
    private static Double toMmol(Double value, String unit) {
        if (value == null) return null;
        String u = unit == null ? "" : unit.toLowerCase();
        return (u.contains("mg") ? GlucoseUnit.MG_DL : GlucoseUnit.MMOL_L).toMmolL(value);
    }
}
