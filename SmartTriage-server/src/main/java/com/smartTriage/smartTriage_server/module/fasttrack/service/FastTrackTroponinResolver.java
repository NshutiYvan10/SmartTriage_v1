package com.smartTriage.smartTriage_server.module.fasttrack.service;

import com.smartTriage.smartTriage_server.common.enums.InvestigationStatus;
import com.smartTriage.smartTriage_server.module.clinical.entity.Investigation;
import com.smartTriage.smartTriage_server.module.clinical.repository.InvestigationRepository;
import com.smartTriage.smartTriage_server.module.lab.entity.LabOrder;
import com.smartTriage.smartTriage_server.module.lab.entity.LabResultComponent;
import com.smartTriage.smartTriage_server.module.lab.repository.LabOrderRepository;
import com.smartTriage.smartTriage_server.module.lab.repository.LabResultComponentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves the most recent resulted TROPONIN for a visit — from BOTH resulted
 * single {@link Investigation}s and lab-panel analytes ({@link LabResultComponent}) —
 * so an MI/ACS fast-track can surface the one biomarker that defines it.
 *
 * <p>Read-side only: it never mutates and never persists. The MI fast-track card and
 * the handover report call this to DISPLAY the latest troponin (value + unit +
 * resulted time + an "elevated" flag lifted from the lab's own abnormal/critical
 * determination). Analyte matching mirrors {@code CriticalValueEngine}, which flags
 * troponin via {@code testName.contains("troponin")}, so troponin is identified the
 * same way everywhere.
 *
 * <p>This fills a real hole: {@code FastTrackActivation} carries troponin columns and
 * the handover already tries to print them, but nothing ever wrote them — every
 * STEMI/NSTEMI pathway showed a blank troponin even after Labs had resulted (and
 * flagged critical) one. Mirrors the sepsis {@code SepsisLabResolver} labs bridge.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FastTrackTroponinResolver {

    private final InvestigationRepository investigationRepository;
    private final LabOrderRepository labOrderRepository;
    private final LabResultComponentRepository labResultComponentRepository;

    /** Generous cap — a single ED visit rarely has more than a handful of lab orders. */
    private static final int MAX_LAB_ORDERS = 200;

    /** The latest resulted troponin, ready to display. */
    public record Troponin(double value, String unit, Instant resultedAt, boolean elevated, String source) {}

    /** A troponin value gathered from either source, with what we need to rank + flag it. */
    private record Candidate(double value, String unit, Instant at, boolean elevated, String source) {}

    /** @return the most recent resulted troponin for the visit, or {@code null} if none. */
    public Troponin latestForVisit(UUID visitId) {
        return gatherCandidates(visitId).stream()
                .max(Comparator.comparing(c -> c.at() != null ? c.at() : Instant.EPOCH))
                .map(c -> new Troponin(c.value(), c.unit(), c.at(), c.elevated(), c.source()))
                .orElse(null);
    }

    private List<Candidate> gatherCandidates(UUID visitId) {
        List<Candidate> candidates = new ArrayList<>();

        // 1. Resulted single investigations carrying a numeric troponin (e.g. a
        //    point-of-care assay, or a manually-resulted send-out).
        for (Investigation inv : investigationRepository
                .findByVisitIdAndStatusAndIsActiveTrueOrderByOrderedAtAsc(visitId, InvestigationStatus.RESULTED)) {
            if (inv.getResultNumeric() == null || !isTroponin(inv.getTestName(), null)) continue;
            Instant at = inv.getResultedAt() != null ? inv.getResultedAt() : inv.getCreatedAt();
            boolean elevated = Boolean.TRUE.equals(inv.getIsCritical()) || Boolean.TRUE.equals(inv.getIsAbnormal());
            candidates.add(new Candidate(inv.getResultNumeric(), inv.getResultUnit(), at, elevated,
                    "Investigation: " + inv.getTestName()));
        }

        // 2. Lab-panel analytes (a cardiac panel's troponin) via the visit's lab
        //    orders, batch-fetched to avoid N+1.
        Map<UUID, LabOrder> orderById = new HashMap<>();
        labOrderRepository
                .findByVisitIdAndIsActiveTrueOrderByOrderedAtDesc(visitId, PageRequest.of(0, MAX_LAB_ORDERS))
                .forEach(o -> orderById.put(o.getId(), o));
        if (!orderById.isEmpty()) {
            for (LabResultComponent comp : labResultComponentRepository
                    .findByLabOrder_IdInAndIsActiveTrueOrderByDisplayOrderAsc(orderById.keySet())) {
                if (comp.getResultNumeric() == null || !isTroponin(comp.getAnalyteName(), comp.getAnalyteCode())) continue;
                LabOrder order = orderById.get(comp.getLabOrder().getId()); // getId() on proxy is safe
                String panel = order != null && order.getTestName() != null ? order.getTestName() : "panel";
                boolean elevated = comp.isCritical() || comp.isAbnormal()
                        || (comp.getReferenceHigh() != null && comp.getResultNumeric() > comp.getReferenceHigh());
                candidates.add(new Candidate(comp.getResultNumeric(), comp.getResultUnit(),
                        comp.getCreatedAt(), elevated, "Lab: " + panel + " — " + comp.getAnalyteName()));
            }
        }
        return candidates;
    }

    /** Mirrors CriticalValueEngine's troponin recognition (name contains "troponin"), plus common assay codes. */
    private boolean isTroponin(String name, String code) {
        String n = lower(name);
        String c = lower(code);
        return n.contains("troponin") || c.contains("troponin")
                || c.equals("trop") || c.equals("ctni") || c.equals("ctnt")
                || c.equals("hstnt") || c.equals("hstni") || c.equals("tnt") || c.equals("tni");
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }
}
