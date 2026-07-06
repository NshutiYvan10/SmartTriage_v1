package com.smartTriage.smartTriage_server.module.override.service;

import com.smartTriage.smartTriage_server.module.consent.entity.BreakTheGlassEvent;
import com.smartTriage.smartTriage_server.module.consent.repository.BreakTheGlassEventRepository;
import com.smartTriage.smartTriage_server.module.lab.entity.LabOrder;
import com.smartTriage.smartTriage_server.module.lab.repository.LabOrderRepository;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationAdministration;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationDose;
import com.smartTriage.smartTriage_server.module.medication.repository.MedicationAdministrationRepository;
import com.smartTriage.smartTriage_server.module.medication.repository.MedicationDoseRepository;
import com.smartTriage.smartTriage_server.module.medsafety.entity.MedicationSafetyCheck;
import com.smartTriage.smartTriage_server.module.medsafety.repository.MedicationSafetyCheckRepository;
import com.smartTriage.smartTriage_server.module.override.dto.OverrideRecordResponse;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Unified Override Register — the single, authoritative "who overrode what safety gate,
 * on whom, when, and why" record for incident investigation.
 *
 * <p>Reads the AUTHORITATIVE domain tables (not fragile alert-title parsing): the
 * medication safety-check override, the lab verification bypass, the dose-administration
 * gate override, the emergency approval-gate skip, prescribe-despite-allergy and
 * prescribe-despite-interaction, and break-the-glass record access. Each source already
 * persists its own mandatory justification (allergy/interaction closed in V109); this
 * service normalises them into one typed, chronological list an auditor can filter by
 * patient, override type, and time window.
 *
 * <p>Read-only and maps to DTOs INSIDE the transaction, so lazy visit/patient access is
 * safe without JOIN FETCH.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OverrideRegisterService {

    private final MedicationSafetyCheckRepository safetyCheckRepository;
    private final LabOrderRepository labOrderRepository;
    private final MedicationDoseRepository doseRepository;
    private final MedicationAdministrationRepository medicationRepository;
    private final BreakTheGlassEventRepository breakTheGlassRepository;

    private static final long DEFAULT_WINDOW_DAYS = 30;

    /**
     * @param from/to optional window (defaults to the last 30 days)
     * @param patientId optional patient filter (excludes break-the-glass, which is keyed
     *                  to a person-identity, not a visit/patient)
     * @param type optional single override-type filter (the stable machine key)
     */
    public List<OverrideRecordResponse> getOverrides(
            UUID hospitalId, Instant from, Instant to, UUID patientId, String type) {

        Instant rangeTo = to != null ? to : Instant.now();
        Instant rangeFrom = from != null ? from : rangeTo.minus(DEFAULT_WINDOW_DAYS, ChronoUnit.DAYS);

        List<OverrideRecordResponse> out = new ArrayList<>();

        // ── Medication safety-check overrides ──
        for (MedicationSafetyCheck c : safetyCheckRepository.findOverriddenForHospital(hospitalId, rangeFrom, rangeTo)) {
            Visit v = c.getVisit();
            out.add(base("MED_SAFETY_CHECK", "Medication", "Overrode medication safety check", v)
                    .actorName(c.getOverriddenBy())
                    .occurredAt(c.getOverriddenAt())
                    .justification(c.getOverrideReason())
                    .detail(joinNonBlank(" • ", c.getDrugName(),
                            firstWarning(c.getAllergyWarning(), c.getDoseWarning(),
                                    c.getInteractionWarning(), c.getDuplicateWarning())))
                    .sourceId(c.getId())
                    .build());
        }

        // ── Lab verification bypass ──
        for (LabOrder o : labOrderRepository.findVerificationOverriddenForHospital(hospitalId, rangeFrom, rangeTo)) {
            Visit v = o.getVisit();
            out.add(base("LAB_VERIFICATION_BYPASS", "Lab", "Released lab result without senior verification", v)
                    .actorName(o.getVerificationOverrideByName())
                    .occurredAt(o.getVerificationOverrideAt())
                    .justification(o.getVerificationOverrideReason())
                    .detail(joinNonBlank(" • ", o.getTestName(), "Order " + o.getOrderNumber()))
                    .severity(o.isCritical() ? "CRITICAL" : "HIGH")
                    .sourceId(o.getId())
                    .build());
        }

        // ── Dose-administration gate override ──
        for (MedicationDose d : doseRepository.findOverriddenForHospital(hospitalId, rangeFrom, rangeTo)) {
            Visit v = d.getVisit();
            String drug = d.getMedication() != null ? d.getMedication().getDrugName() : null;
            out.add(base("DOSE_ADMINISTRATION", "Medication", "Administered dose despite a safety gate", v)
                    .actorName(d.getGivenByName())
                    .occurredAt(d.getGivenAt())
                    .justification(d.getOverrideJustification())
                    .detail(drug)
                    .sourceId(d.getId())
                    .build());
        }

        // ── Prescription-time overrides (one row can carry up to three) ──
        for (MedicationAdministration m : medicationRepository.findOverridesForHospital(hospitalId, rangeFrom, rangeTo)) {
            Visit v = m.getVisit();
            if (m.isEmergencyOverride()) {
                out.add(base("EMERGENCY_APPROVAL", "Medication", "Skipped high-alert approval (emergency)", v)
                        .actorName(m.getPrescribedByName())
                        .occurredAt(m.getPrescribedAt())
                        .justification(m.getEmergencyJustification())
                        .detail(m.getDrugName())
                        .severity("HIGH")
                        .sourceId(m.getId())
                        .build());
            }
            if (Boolean.TRUE.equals(m.getPrescribedDespiteAllergy())) {
                out.add(base("PRESCRIBE_ALLERGY", "Medication", "Prescribed despite documented allergy", v)
                        .actorName(m.getPrescribedByName())
                        .occurredAt(m.getAllergyOverrideAcknowledgedAt() != null
                                ? m.getAllergyOverrideAcknowledgedAt() : m.getPrescribedAt())
                        .justification(m.getAllergyOverrideReason())
                        .detail(joinNonBlank(" • ", m.getDrugName(), m.getAllergyOverrideMatches()))
                        .severity("CRITICAL")
                        .sourceId(m.getId())
                        .build());
            }
            if (Boolean.TRUE.equals(m.getPrescribedDespiteInteraction())) {
                out.add(base("PRESCRIBE_INTERACTION", "Medication", "Prescribed despite known interaction", v)
                        .actorName(m.getPrescribedByName())
                        .occurredAt(m.getInteractionOverrideAcknowledgedAt() != null
                                ? m.getInteractionOverrideAcknowledgedAt() : m.getPrescribedAt())
                        .justification(m.getInteractionOverrideReason())
                        .detail(joinNonBlank(" • ", m.getDrugName(), m.getInteractionOverrideMatches()))
                        .severity("HIGH")
                        .sourceId(m.getId())
                        .build());
            }
        }

        // ── Break-the-glass (privacy) — keyed to a person-identity, no visit/patient ──
        for (BreakTheGlassEvent e : breakTheGlassRepository.findForHospitalRange(hospitalId, rangeFrom, rangeTo)) {
            out.add(OverrideRecordResponse.builder()
                    .overrideType("BREAK_THE_GLASS").category("Privacy")
                    .label("Break-the-glass record access")
                    .actorName(e.getActorName()).actorRole(e.getActorRole())
                    .maskedSubject(maskNationalId(e.getPersonIdentity() != null
                            ? e.getPersonIdentity().getNationalId() : null))
                    .occurredAt(e.getAccessedAt())
                    .justification(e.getReason())
                    .severity("HIGH")
                    .governanceAcknowledged(e.isAcknowledged())
                    .acknowledgedByName(e.getAcknowledgedByName())
                    .acknowledgedAt(e.getAcknowledgedAt())
                    .sourceId(e.getId())
                    .build());
        }

        // Filters + newest-first.
        return out.stream()
                .filter(r -> patientId == null || patientId.equals(r.getPatientId()))
                .filter(r -> type == null || type.isBlank() || type.equalsIgnoreCase(r.getOverrideType()))
                .sorted(Comparator.comparing(OverrideRecordResponse::getOccurredAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /** Prefill actor-free, patient-linked fields shared by every visit-scoped override type. */
    private OverrideRecordResponse.OverrideRecordResponseBuilder base(
            String type, String category, String label, Visit v) {
        OverrideRecordResponse.OverrideRecordResponseBuilder b = OverrideRecordResponse.builder()
                .overrideType(type).category(category).label(label);
        if (v != null) {
            b.visitId(v.getId()).visitNumber(v.getVisitNumber());
            Patient p = v.getPatient();
            if (p != null) {
                b.patientId(p.getId());
                b.patientName((safe(p.getFirstName()) + " " + safe(p.getLastName())).trim());
            }
        }
        return b;
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String firstWarning(String... warnings) {
        for (String w : warnings) if (w != null && !w.isBlank()) return w;
        return null;
    }

    private static String joinNonBlank(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(p.trim());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** "…2780" → "National ID ***2780"; never expose the full identifier in the register. */
    private static String maskNationalId(String nid) {
        if (nid == null || nid.isBlank()) return "Unidentified person";
        String t = nid.trim();
        String last4 = t.length() >= 4 ? t.substring(t.length() - 4) : t;
        return "National ID ***" + last4;
    }
}
