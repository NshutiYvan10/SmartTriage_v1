package com.smartTriage.smartTriage_server.module.sepsis.service;

import com.smartTriage.smartTriage_server.common.enums.InvestigationStatus;
import com.smartTriage.smartTriage_server.module.clinical.entity.Investigation;
import com.smartTriage.smartTriage_server.module.clinical.repository.InvestigationRepository;
import com.smartTriage.smartTriage_server.module.lab.entity.LabOrder;
import com.smartTriage.smartTriage_server.module.lab.entity.LabResultComponent;
import com.smartTriage.smartTriage_server.module.lab.repository.LabOrderRepository;
import com.smartTriage.smartTriage_server.module.lab.repository.LabResultComponentRepository;
import com.smartTriage.smartTriage_server.module.sepsis.dto.SepsisLabSuggestionsResponse;
import com.smartTriage.smartTriage_server.module.sepsis.dto.SepsisLabSuggestionsResponse.LabSuggestion;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the most recent lactate / WBC recorded for a visit — from BOTH resulted
 * single {@link Investigation}s and lab-panel analytes ({@link LabResultComponent}) —
 * and normalises them to the units the sepsis engine expects, so the screening
 * "Add labs" form can be pre-filled instead of re-keyed.
 *
 * <p>This is a read-side convenience ONLY. It never runs a screening and never
 * mutates anything; the clinician reviews the pre-filled values (each tagged with
 * provenance and any unit-confirmation flag) and confirms before screening. Analyte
 * matching mirrors {@code CriticalValueEngine} so the app identifies lactate/WBC
 * the same way everywhere.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SepsisLabResolver {

    private final InvestigationRepository investigationRepository;
    private final LabOrderRepository labOrderRepository;
    private final LabResultComponentRepository labResultComponentRepository;

    /** Generous cap — a single ED visit rarely has more than a handful of lab orders. */
    private static final int MAX_LAB_ORDERS = 200;

    /** A numeric lab value gathered from either source, with what we need to match + rank it. */
    private record Candidate(String name, String code, double value, String unit, Instant at, String source) {}

    public SepsisLabSuggestionsResponse resolve(UUID visitId) {
        List<Candidate> candidates = gatherCandidates(visitId);
        return SepsisLabSuggestionsResponse.builder()
                .lactate(mostRecent(candidates, this::isLactate).map(this::toLactate).orElse(null))
                .wbc(mostRecent(candidates, this::isWbc).map(this::toWbc).orElse(null))
                .build();
    }

    // ── Gather ──────────────────────────────────────────────────────────

    private List<Candidate> gatherCandidates(UUID visitId) {
        List<Candidate> candidates = new ArrayList<>();

        // 1. Resulted single investigations that carry a numeric result (e.g. a
        //    point-of-care lactate, or a manually-resulted test).
        for (Investigation inv : investigationRepository
                .findByVisitIdAndStatusAndIsActiveTrueOrderByOrderedAtAsc(visitId, InvestigationStatus.RESULTED)) {
            if (inv.getResultNumeric() == null || inv.getTestName() == null) continue;
            Instant at = inv.getResultedAt() != null ? inv.getResultedAt() : inv.getCreatedAt();
            candidates.add(new Candidate(inv.getTestName(), null, inv.getResultNumeric(),
                    inv.getResultUnit(), at, "Investigation: " + inv.getTestName()));
        }

        // 2. Lab-panel analytes (a CBC's WBC, a chem/gas lactate) — via the visit's
        //    lab orders, batch-fetched to avoid N+1.
        Map<UUID, LabOrder> orderById = new HashMap<>();
        labOrderRepository
                .findByVisitIdAndIsActiveTrueOrderByOrderedAtDesc(visitId, PageRequest.of(0, MAX_LAB_ORDERS))
                .forEach(o -> orderById.put(o.getId(), o));
        if (!orderById.isEmpty()) {
            for (LabResultComponent comp : labResultComponentRepository
                    .findByLabOrder_IdInAndIsActiveTrueOrderByDisplayOrderAsc(orderById.keySet())) {
                if (comp.getResultNumeric() == null) continue;
                LabOrder order = orderById.get(comp.getLabOrder().getId()); // getId() on proxy is safe
                String panel = order != null && order.getTestName() != null ? order.getTestName() : "panel";
                candidates.add(new Candidate(comp.getAnalyteName(), comp.getAnalyteCode(),
                        comp.getResultNumeric(), comp.getResultUnit(),
                        comp.getCreatedAt(), "Lab: " + panel + " — " + comp.getAnalyteName()));
            }
        }
        return candidates;
    }

    private Optional<Candidate> mostRecent(List<Candidate> candidates, java.util.function.Predicate<Candidate> match) {
        return candidates.stream()
                .filter(match)
                .max(Comparator.comparing(c -> c.at() != null ? c.at() : Instant.EPOCH));
    }

    // ── Analyte matching (mirrors CriticalValueEngine) ──────────────────

    private boolean isLactate(Candidate c) {
        String n = lower(c.name());
        String code = lower(c.code());
        return n.contains("lactate") || n.contains("lactic acid")
                || code.equals("lactate") || code.equals("lact");
    }

    private boolean isWbc(Candidate c) {
        String n = lower(c.name());
        String code = lower(c.code());
        // Exclude differential sub-analytes (percentages / named cell lines) — their
        // value is a fraction, not the total count the SIRS criterion needs.
        if (n.contains("differential") || n.contains("%")
                || n.contains("neutrophil") || n.contains("lymphocyte")
                || n.contains("monocyte") || n.contains("eosinophil") || n.contains("basophil")) {
            return false;
        }
        return n.contains("wbc") || n.contains("white blood cell") || n.contains("white cell count")
                || code.equals("wbc") || code.equals("wcc");
    }

    // ── Unit normalisation ──────────────────────────────────────────────

    /** Lactate → mmol/L. Blank unit is treated as the canonical mmol/L (as elsewhere). */
    private LabSuggestion toLactate(Candidate c) {
        String u = lower(c.unit());
        double norm = c.value();
        boolean needsConfirm = false;
        if (u.isEmpty() || u.contains("mmol")) {
            norm = c.value();
        } else if (u.contains("mg/dl")) {
            norm = c.value() / 9.008; // mg/dL → mmol/L (lactate molar mass basis)
        } else {
            needsConfirm = true; // unrecognised unit — surface for review, pass value through
        }
        return build(c, round1(norm), "mmol/L", needsConfirm);
    }

    /**
     * WBC → absolute count in cells/µL. Labs commonly report ×10⁹/L (e.g. 11.2),
     * which the sepsis engine would reject as implausible leukopenia — so convert.
     * When the unit is blank/unknown, infer from magnitude and flag for confirmation
     * (the &lt;100 server-side floor remains the final safety net).
     */
    private LabSuggestion toWbc(Candidate c) {
        String u = lower(c.unit());
        boolean absolute = u.contains("µl") || u.contains("/ul") || u.contains("cells/ul")
                || u.contains("cmm") || u.contains("mm3") || u.contains("/mm");
        boolean gigaPerL = u.contains("10^9") || u.contains("10*9") || u.contains("10⁹")
                || u.contains("e9") || u.contains("giga") || u.contains("/nl") || u.contains("x10");
        double norm;
        boolean needsConfirm = false;
        if (absolute) {
            norm = c.value();
        } else if (gigaPerL) {
            norm = c.value() * 1000.0;
        } else {
            // Unit blank/unknown: a value < 100 is almost certainly ×10⁹/L; otherwise
            // treat as an absolute count. Either way, flag it so the clinician verifies.
            norm = c.value() < 100.0 ? c.value() * 1000.0 : c.value();
            needsConfirm = true;
        }
        return build(c, (double) Math.round(norm), "cells/µL", needsConfirm);
    }

    private LabSuggestion build(Candidate c, double normalizedValue, String normalizedUnit, boolean needsConfirm) {
        return LabSuggestion.builder()
                .value(c.value())
                .unit(c.unit())
                .normalizedValue(normalizedValue)
                .normalizedUnit(normalizedUnit)
                .source(c.source())
                .resultedAt(c.at())
                .needsUnitConfirmation(needsConfirm)
                .build();
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
