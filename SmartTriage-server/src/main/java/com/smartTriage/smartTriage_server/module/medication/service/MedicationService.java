package com.smartTriage.smartTriage_server.module.medication.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.AllergySeverity;
import com.smartTriage.smartTriage_server.common.enums.MedicationPriority;
import com.smartTriage.smartTriage_server.common.enums.MedicationStatus;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.medication.dto.AdministerMedicationRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.CountersignMedicationRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.MedicationResponse;
import com.smartTriage.smartTriage_server.module.medication.dto.PrescribeMedicationRequest;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationAdministration;
import com.smartTriage.smartTriage_server.module.medication.mapper.MedicationMapper;
import com.smartTriage.smartTriage_server.module.medication.repository.MedicationAdministrationRepository;
import com.smartTriage.smartTriage_server.module.medsafety.engine.MedicationSafetyEngine;
import com.smartTriage.smartTriage_server.module.medsafety.repository.MedicationSafetyCheckRepository;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.service.VisitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Medication Administration Record (MAR) service.
 *
 * Handles the full lifecycle of medication entries as they appear on the
 * Rwanda national triage forms:
 *   1. Prescribe  → create entry with drug, dose, route, frequency
 *   2. Administer → record who gave it and when
 *   3. Countersign→ second clinician verification (patient-safety)
 *
 * Also supports holding, cancelling, and listing medications per visit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MedicationService {

    private final MedicationAdministrationRepository medicationRepository;
    private final VisitService visitService;
    /**
     * Auto-creates a ClinicalAlert per safety override at prescribe
     * time so the safety officer's daily report has structured rows
     * to query (instead of grep'ing application logs). The alerts
     * surface in AlertsTab on the visit and the global AlertsView.
     */
    private final ClinicalAlertRepository clinicalAlertRepository;
    /**
     * Workflow 3 — real-time push for the nurse medication queue.
     * Broadcasts on {@code /topic/medications/{hospitalId}} every
     * time a medication is created or transitions state so the
     * nurse's queue and any open visit-detail page update without
     * polling.
     */
    private final RealTimeEventPublisher realTimeEventPublisher;
    /**
     * S1 — authoritative server-side medication safety detection. We use
     * only its focused {@code assessAllergyForPrescription} entry point on
     * the prescribe path to ENFORCE the allergy block that the frontend
     * dialog can otherwise bypass. The engine depends only on repositories
     * (no back-edge to this service), so there is no circular bean wiring.
     */
    private final MedicationSafetyEngine medicationSafetyEngine;
    /**
     * Enforce an un-overridden medication-safety BLOCK at administration time.
     * The {@code /med-safety/validate} flow persists a MedicationSafetyCheck per
     * medication; this lets administer() honour an unresolved CRITICAL block
     * (making the documented "BLOCKS administration until overridden" true).
     * Repository, not the service, to avoid a circular bean dependency.
     */
    private final MedicationSafetyCheckRepository medicationSafetyCheckRepository;

    // ====================================================================
    // PRESCRIBE
    // ====================================================================

    @Transactional
    public MedicationResponse prescribe(PrescribeMedicationRequest request) {
        Visit visit = visitService.findVisitOrThrow(request.getVisitId());

        // Override capture (V23 allergy + V24 interaction). Booleans
        // on the wire can be null — treat null as "no override". When
        // a flag is true we stamp the acknowledgement timestamp
        // server-side rather than trusting client clock. Matches
        // snapshots are only persisted when their flag is true —
        // defensive against a frontend that sends matches alongside a
        // false flag.
        boolean allergyOverride = Boolean.TRUE.equals(request.getPrescribedDespiteAllergy());
        Instant allergyOverrideAt = allergyOverride ? Instant.now() : null;
        String allergyMatches = allergyOverride ? request.getAllergyOverrideMatches() : null;

        boolean interactionOverride = Boolean.TRUE.equals(request.getPrescribedDespiteInteraction());
        Instant interactionOverrideAt = interactionOverride ? Instant.now() : null;
        String interactionMatches = interactionOverride ? request.getInteractionOverrideMatches() : null;

        // ── S1: server-side allergy ENFORCEMENT (defense-in-depth) ──
        // The frontend PrescribeSafetyDialog is the first line of defence,
        // but it can be bypassed: a direct API call, a buggy/old client, or
        // a client-side allergy check that diverges from the server's. Re-run
        // the AUTHORITATIVE allergy detection server-side (the same engine
        // backing /med-safety/validate) and HARD-BLOCK a blocking (non-MILD)
        // match unless the prescriber has explicitly acknowledged the
        // override (prescribedDespiteAllergy=true). This closes the gap where
        // an anaphylaxis-triggering drug could be prescribed with the flag
        // false/absent and produce no alert at all.
        //
        // Only the ALLERGY blocker is enforced here. Dose-range blocks are
        // intentionally NOT enforced on this path: the formulary's non-mg
        // dose units (see S2 / DrugFormulary.doseUnit) make a server-side
        // dose block unsafe (it could reject a correct magnesium/insulin
        // order). Allergy detection is dose-unit-independent.
        Patient patient = visit.getPatient();
        MedicationSafetyEngine.AllergyAssessment allergyAssessment =
                medicationSafetyEngine.assessAllergyForPrescription(
                        patient, visit, request.getDrugName());
        if (allergyAssessment.isBlocking() && !allergyOverride) {
            log.warn("PRESCRIBE BLOCKED (allergy) — visit:{} drug:{} severity:{} detail:{}",
                    visit.getVisitNumber(), request.getDrugName(),
                    allergyAssessment.severity(), allergyAssessment.message());
            throw new ClinicalBusinessException(
                    "Prescription blocked by the allergy safety check. "
                            + allergyAssessment.message()
                            + " The prescriber must explicitly acknowledge and override "
                            + "this allergy warning before the medication can be prescribed.");
        }

        // Workflow 3 — resolve the authenticated user so we can stamp
        // the prescribed_by_id FK. Without it the separation-of-duties
        // check on administer can't reliably compare prescriber vs
        // administerer (names are typo-prone). Falls back to a null
        // FK gracefully — old clients that don't carry auth keep
        // working through the legacy name field.
        User prescriber = resolveCurrentUser();

        MedicationAdministration med = MedicationAdministration.builder()
                .visit(visit)
                .drugName(request.getDrugName())
                .dose(request.getDose())
                .route(request.getRoute())
                .frequency(request.getFrequency())
                .priority(request.getPriority() != null
                        ? request.getPriority() : MedicationPriority.ROUTINE)
                .prescribedAt(Instant.now())
                .prescribedBy(prescriber)
                .prescribedByName(request.getPrescribedByName() != null
                        ? request.getPrescribedByName()
                        : formatUserName(prescriber))
                .status(MedicationStatus.PRESCRIBED)
                .notes(request.getNotes())
                .prescribedDespiteAllergy(allergyOverride)
                .allergyOverrideMatches(allergyMatches)
                .allergyOverrideAcknowledgedAt(allergyOverrideAt)
                .prescribedDespiteInteraction(interactionOverride)
                .interactionOverrideMatches(interactionMatches)
                .interactionOverrideAcknowledgedAt(interactionOverrideAt)
                .build();

        med = medicationRepository.save(med);

        // WARN, not INFO — these are the lines a clinical safety
        // officer should see grep'd out of the daily report. Logged
        // separately so each override is independently searchable.
        // The structured ClinicalAlert created below is the durable,
        // queryable form of the same fact; the WARN log is the
        // ops/grep-friendly form. Both are kept on purpose.
        if (allergyOverride) {
            // Workflow 2 — severity-aware alert. The frontend now passes
            // the structured AllergySeverity from the safety dialog when
            // it has one; if the field is null (old frontend, or the
            // legacy free-text fallback fired without a known severity)
            // we anchor at CRITICAL to fail safe.
            AlertSeverity allergyAlertSeverity = mapAllergyOverrideSeverity(
                    request.getAllergyOverrideSeverity());
            log.warn("ALLERGY OVERRIDE — visit:{} drug:{} prescriber:{} severity:{} matches:{}",
                    visit.getVisitNumber(), med.getDrugName(),
                    med.getPrescribedByName(),
                    request.getAllergyOverrideSeverity(),
                    allergyMatches);
            String label = request.getAllergyOverrideSeverity() != null
                    ? "Allergy override (" + request.getAllergyOverrideSeverity().getLabel() + ")"
                    : "Allergy override";
            createOverrideAlert(visit, med, label, allergyMatches, allergyAlertSeverity);
        }
        if (interactionOverride) {
            log.warn("INTERACTION OVERRIDE — visit:{} drug:{} prescriber:{} matches:{}",
                    visit.getVisitNumber(), med.getDrugName(),
                    med.getPrescribedByName(), interactionMatches);
            // The V24 column is shared between true interactions,
            // duplicate-therapy, and paediatric dose hits — each tagged
            // with a `[…]` prefix in the snapshot. We split that here
            // so each conflict class becomes its own ClinicalAlert with
            // calibrated severity, instead of one undifferentiated row
            // that flattens "10× overdose" with "duplicate NSAID".
            createInteractionScopedAlerts(visit, med, interactionMatches);
        }
        if (!allergyOverride && !interactionOverride) {
            log.info("Medication prescribed for visit {} — drug:{} dose:{} route:{} freq:{} priority:{}",
                    visit.getVisitNumber(), med.getDrugName(), med.getDose(),
                    med.getRoute(), med.getFrequency(), med.getPriority());
        }

        MedicationResponse response = MedicationMapper.toResponse(med);
        broadcastMedication(med, response);
        return response;
    }

    /**
     * Persist a single MEDICATION_SAFETY_WARNING alert summarizing one
     * override class (allergy, interaction, duplicate, or dose). The
     * alertType is WARNING — not BLOCK — because the prescription went
     * through after the prescriber acknowledged the dialog. BLOCK is
     * reserved for medications the system actually refused.
     *
     * Severity is calibrated by the caller; the message embeds the raw
     * snapshot so a consumer of the alert can read what fired without
     * joining back to the medication row.
     */
    /**
     * Map the structured {@link AllergySeverity} the frontend
     * captured at decision time into the alert pipeline's {@link
     * AlertSeverity} scale. Null defaults to CRITICAL — when we don't
     * know the severity we treat the override as the highest-regret
     * class so the alert is impossible to miss.
     */
    private AlertSeverity mapAllergyOverrideSeverity(AllergySeverity allergySeverity) {
        if (allergySeverity == null) return AlertSeverity.CRITICAL;
        return switch (allergySeverity) {
            case ANAPHYLAXIS, SEVERE -> AlertSeverity.CRITICAL;
            case MODERATE, UNKNOWN -> AlertSeverity.HIGH;
            case MILD -> AlertSeverity.MEDIUM;
        };
    }

    private void createOverrideAlert(
            Visit visit,
            MedicationAdministration med,
            String label,
            String matchesSnapshot,
            AlertSeverity severity) {
        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.MEDICATION_SAFETY_WARNING)
                .severity(severity)
                .title(label + ": " + med.getDrugName())
                .message(String.format(
                        "Prescriber: %s. Drug: %s%s%s. Acknowledged conflict: %s",
                        med.getPrescribedByName(),
                        med.getDrugName(),
                        med.getDose() != null ? " " + med.getDose() : "",
                        med.getRoute() != null ? " " + med.getRoute() : "",
                        matchesSnapshot != null ? matchesSnapshot : "(no detail)"))
                .autoGenerated(true)
                .build();
        clinicalAlertRepository.save(alert);
    }

    /**
     * The V24 `interaction_override_matches` column is a piggyback
     * surface — five different conflict classes share it, each tagged
     * with a literal token in the snapshot:
     *   - `[duplicate]` — same-class duplicate therapy
     *   - `[overdose]` / `[underdose]` — paediatric dose out of range
     *   - `[renal]` — renal-risk screening (CKD or AKI-likely vitals)
     *   - `[renal-egfr][avoid|caution]` — Cockcroft-Gault eGFR dose check
     *   - `[teratogen][X|D|D-late|caution]` — pregnancy / lactation risk
     *   - `[geriatric][avoid|caution]` — Beers Criteria for patients ≥ 65
     *   - (no tag) — true drug–drug interaction
     *
     * Splitting them here gives the safety officer separate alert rows
     * with calibrated severity. Severity ladder:
     *   - `[teratogen][X]` → CRITICAL (irreversible fetal harm at
     *     standard dose — the highest-regret prescribing class)
     *   - `[overdose]` ≥ 2× max → CRITICAL (decimal-shift territory)
     *   - true interaction with `[contraindicated]` token → CRITICAL
     *   - `[teratogen][D]` / `[teratogen][D-late]` → HIGH (clear risk;
     *     occasionally an acceptable trade-off)
     *   - `[overdose]` < 2× max → HIGH
     *   - true interaction with `[major]` token → HIGH
     *   - `[renal]` → HIGH (renal-dangerous drug in CKD or AKI-likely
     *     state — the prescriber should have confirmed renal function)
     *   - `[renal-egfr][avoid]` → HIGH (eGFR below published threshold —
     *     metformin < 30, NSAIDs < 30, nitrofurantoin < 30, etc.)
     *   - `[geriatric][avoid]` → HIGH (Beers strongly recommends against
     *     in elderly — anticholinergics, long-acting benzos, etc.)
     *   - `[teratogen][caution]` → MEDIUM (well-evidenced concern,
     *     not a formal D/X)
     *   - `[renal-egfr][caution]` → MEDIUM (eGFR-driven dose adjustment
     *     needed — LMWH, vancomycin, fluoroquinolones, allopurinol)
     *   - `[geriatric][caution]` → MEDIUM (Beers caution tier — NSAIDs,
     *     short-acting benzos, α-blockers, tramadol)
     *   - `[underdose]` → MEDIUM (subtherapeutic, not lethal)
     *   - `[duplicate]` → MEDIUM (often clinically intentional)
     *   - any other line → HIGH (fail safe upward)
     *
     * If the snapshot is null/blank we still write one HIGH-severity
     * alert so the audit trail isn't silently empty.
     */
    private void createInteractionScopedAlerts(
            Visit visit,
            MedicationAdministration med,
            String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            createOverrideAlert(visit, med, "Interaction override", snapshot,
                    AlertSeverity.HIGH);
            return;
        }
        // Snapshot is semicolon-delimited; each segment is one conflict.
        String[] segments = snapshot.split(";");
        for (String raw : segments) {
            String seg = raw.trim();
            if (seg.isEmpty()) continue;

            String label;
            AlertSeverity severity;
            // Teratogen first — Category X is the highest-severity
            // class in our ladder. Order matters: [teratogen] also
            // contains [overdose]'s prefix character class, but Java's
            // String#contains is literal-substring so this is safe;
            // ordering still helps readability.
            if (seg.contains("[teratogen][X]")) {
                severity = AlertSeverity.CRITICAL;
                label = "Pregnancy override (Category X)";
            } else if (seg.contains("[teratogen][D-late]")) {
                severity = AlertSeverity.HIGH;
                label = "Pregnancy override (Category D late)";
            } else if (seg.contains("[teratogen][D]")) {
                severity = AlertSeverity.HIGH;
                label = "Pregnancy override (Category D)";
            } else if (seg.contains("[teratogen][caution]")) {
                severity = AlertSeverity.MEDIUM;
                label = "Pregnancy/lactation override (caution)";
            } else if (seg.contains("[teratogen]")) {
                // Tag present but category unrecognised — fail safe
                // upward. Don't silently downgrade.
                severity = AlertSeverity.HIGH;
                label = "Pregnancy override";
            } else if (seg.contains("[overdose]")) {
                // Heuristic: a "Nx max" suffix above 2 indicates a severe
                // overdose. Below 2× still HIGH but not auto-CRITICAL.
                severity = looksSevereOverdose(seg)
                        ? AlertSeverity.CRITICAL
                        : AlertSeverity.HIGH;
                label = "Overdose override";
            } else if (seg.contains("[underdose]")) {
                severity = AlertSeverity.MEDIUM;
                label = "Underdose override";
            } else if (seg.contains("[duplicate]")) {
                severity = AlertSeverity.MEDIUM;
                label = "Duplicate-therapy override";
            } else if (seg.contains("[renal-egfr][avoid]")) {
                // Phase 12b — Cockcroft-Gault eGFR-driven dose check.
                // "Avoid" tier means the patient's calculated eGFR sits
                // below the published safety threshold for this drug:
                // metformin < 30 (lactic acidosis), NSAIDs < 30, etc.
                // Higher severity than the screening-only [renal] tag
                // because we have a concrete number, not just a hint.
                severity = AlertSeverity.HIGH;
                label = "Renal-eGFR override (avoid)";
            } else if (seg.contains("[renal-egfr][caution]")) {
                // "Caution" tier — drug needs dose reduction at this
                // eGFR but isn't outright contraindicated (LMWH,
                // vancomycin, fluoroquinolones, allopurinol).
                severity = AlertSeverity.MEDIUM;
                label = "Renal-eGFR override (caution)";
            } else if (seg.contains("[renal-egfr]")) {
                // Tag present but tier unrecognised — fail safe upward.
                severity = AlertSeverity.HIGH;
                label = "Renal-eGFR override";
            } else if (seg.contains("[renal]")) {
                severity = AlertSeverity.HIGH;
                label = "Renal-precaution override";
            } else if (seg.contains("[geriatric][avoid]")) {
                // Phase 16 — Beers Criteria. "Avoid" tier means strong
                // recommendation against use in patients ≥ 65: long-acting
                // benzos, anticholinergics, pethidine, glyburide, etc.
                // Common ED drugs with elderly-specific harm.
                severity = AlertSeverity.HIGH;
                label = "Geriatric prescribing override (avoid)";
            } else if (seg.contains("[geriatric][caution]")) {
                // "Caution" tier — short-acting benzos, NSAIDs, α-blockers,
                // tramadol. Often still appropriate but warrant the
                // prescriber's deliberate consent.
                severity = AlertSeverity.MEDIUM;
                label = "Geriatric prescribing override (caution)";
            } else if (seg.contains("[geriatric]")) {
                // Tag present but tier unrecognised — fail safe upward.
                severity = AlertSeverity.MEDIUM;
                label = "Geriatric prescribing override";
            } else if (seg.contains("[contraindicated]")) {
                severity = AlertSeverity.CRITICAL;
                label = "Interaction override";
            } else if (seg.contains("[major]")) {
                severity = AlertSeverity.HIGH;
                label = "Interaction override";
            } else {
                // Unrecognised tag — fail safe upward rather than swallow.
                severity = AlertSeverity.HIGH;
                label = "Interaction override";
            }
            createOverrideAlert(visit, med, label, seg, severity);
        }
    }

    /**
     * Looks for a "Nx max" suffix in an overdose snapshot segment and
     * returns true when N >= 2. Permissive parser — the snapshot is
     * human-readable, so a regex miss just degrades severity to HIGH
     * (which is still a CRITICAL-adjacent alert), never to silence.
     */
    private boolean looksSevereOverdose(String segment) {
        // Matches "— 3× max", "— 10× max", "— 2.5× max"
        java.util.regex.Matcher m =
                java.util.regex.Pattern
                        .compile("([0-9]+(?:\\.[0-9]+)?)×\\s*max")
                        .matcher(segment);
        if (!m.find()) return false;
        try {
            return Double.parseDouble(m.group(1)) >= 2.0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ====================================================================
    // ADMINISTER
    // ====================================================================

    @Transactional
    public MedicationResponse administer(UUID medicationId, AdministerMedicationRequest request) {
        MedicationAdministration med = findMedicationOrThrow(medicationId);

        if (med.getStatus() != MedicationStatus.PRESCRIBED) {
            throw new ClinicalBusinessException(
                    "Cannot administer medication in status: " + med.getStatus()
                            + ". Only PRESCRIBED medications can be administered.");
        }

        // Enforce an un-overridden medication safety BLOCK before administration.
        // The /med-safety/validate flow persists a MedicationSafetyCheck per med;
        // if the latest one for THIS medication is a CRITICAL block (overallSafe
        // = false) that no clinician has overridden, administration must not
        // proceed — making the system's documented "BLOCKS administration until
        // overridden" actually true. (No check recorded → no gate; the routine
        // prescribe path is already guarded server-side by the S1 allergy block.)
        boolean unresolvedSafetyBlock = medicationSafetyCheckRepository
                .findByMedicationIdAndIsActiveTrueOrderByCheckedAtDesc(medicationId)
                .filter(check -> !check.isOverallSafe() && check.getOverriddenBy() == null)
                .isPresent();
        if (unresolvedSafetyBlock) {
            throw new ClinicalBusinessException(
                    "Administration blocked: an unresolved medication safety check exists for '"
                            + med.getDrugName() + "'. A clinician must override the safety "
                            + "check (with a documented reason) before it can be administered.");
        }

        // Workflow 3 — separation of duties. The clinician who prescribed
        // the order must not be the same one to record administration:
        // the second pair of eyes is the whole point of the MAR chain of
        // custody. Compared by user FK because names are typo-prone.
        // Backward compat: if the legacy row has no prescribedBy FK
        // (older prescriptions pre-Workflow-3), the check is skipped —
        // we can't enforce what we can't identify, and we don't want to
        // freeze pre-existing pending orders.
        User administerer = resolveCurrentUser();
        if (administerer != null && med.getPrescribedBy() != null
                && administerer.getId().equals(med.getPrescribedBy().getId())) {
            throw new ClinicalBusinessException(
                    "Separation of duties: the clinician who prescribed '"
                            + med.getDrugName()
                            + "' cannot also record its administration. "
                            + "A second clinician must complete this step.");
        }

        med.setAdministeredAt(Instant.now());
        med.setAdministeredBy(administerer);
        med.setAdministeredByName(request.getAdministeredByName() != null
                ? request.getAdministeredByName()
                : formatUserName(administerer));
        med.setStatus(MedicationStatus.ADMINISTERED);

        if (request.getNotes() != null && !request.getNotes().isBlank()) {
            String existingNotes = med.getNotes() != null ? med.getNotes() + " | " : "";
            med.setNotes(existingNotes + "Admin: " + request.getNotes());
        }

        med = medicationRepository.save(med);

        log.info("Medication administered — id:{} drug:{} visit:{} by:{}",
                med.getId(), med.getDrugName(), med.getVisit().getVisitNumber(),
                med.getAdministeredByName());

        MedicationResponse response = MedicationMapper.toResponse(med);
        broadcastMedication(med, response);
        return response;
    }

    // ====================================================================
    // COUNTERSIGN
    // ====================================================================

    @Transactional
    public MedicationResponse countersign(UUID medicationId, CountersignMedicationRequest request) {
        MedicationAdministration med = findMedicationOrThrow(medicationId);

        if (med.getStatus() != MedicationStatus.ADMINISTERED) {
            throw new ClinicalBusinessException(
                    "Cannot countersign medication in status: " + med.getStatus()
                            + ". Only ADMINISTERED medications can be countersigned.");
        }

        // Workflow 3 — separation of duties extends to the countersign
        // step: a third pair of eyes. The countersigner must differ
        // from BOTH the prescriber and the administerer. Same FK-based
        // comparison + backward-compat skip when the legacy row lacks
        // a User FK.
        User countersigner = resolveCurrentUser();
        if (countersigner != null) {
            if (med.getPrescribedBy() != null
                    && countersigner.getId().equals(med.getPrescribedBy().getId())) {
                throw new ClinicalBusinessException(
                        "Separation of duties: the prescribing clinician for '"
                                + med.getDrugName()
                                + "' cannot also countersign its administration.");
            }
            if (med.getAdministeredBy() != null
                    && countersigner.getId().equals(med.getAdministeredBy().getId())) {
                throw new ClinicalBusinessException(
                        "Separation of duties: the administering clinician for '"
                                + med.getDrugName()
                                + "' cannot countersign their own administration. "
                                + "A different clinician must complete this step.");
            }
        }

        med.setCountersignedAt(Instant.now());
        med.setCountersignedBy(countersigner);
        med.setCountersignedByName(request.getCountersignedByName() != null
                ? request.getCountersignedByName()
                : formatUserName(countersigner));

        if (request.getNotes() != null && !request.getNotes().isBlank()) {
            String existingNotes = med.getNotes() != null ? med.getNotes() + " | " : "";
            med.setNotes(existingNotes + "Countersign: " + request.getNotes());
        }

        med = medicationRepository.save(med);

        log.info("Medication countersigned — id:{} drug:{} by:{}",
                med.getId(), med.getDrugName(), med.getCountersignedByName());

        MedicationResponse response = MedicationMapper.toResponse(med);
        broadcastMedication(med, response);
        return response;
    }

    // ====================================================================
    // STATUS CHANGES (Hold / Cancel)
    // ====================================================================

    @Transactional
    public MedicationResponse holdMedication(UUID medicationId, String reason) {
        MedicationAdministration med = findMedicationOrThrow(medicationId);

        if (med.getStatus() != MedicationStatus.PRESCRIBED) {
            throw new ClinicalBusinessException(
                    "Only PRESCRIBED medications can be held. Current status: " + med.getStatus());
        }

        med.setStatus(MedicationStatus.HELD);
        if (reason != null && !reason.isBlank()) {
            String existingNotes = med.getNotes() != null ? med.getNotes() + " | " : "";
            med.setNotes(existingNotes + "HELD: " + reason);
        }

        med = medicationRepository.save(med);
        log.info("Medication held — id:{} drug:{} reason:{}", med.getId(), med.getDrugName(), reason);
        MedicationResponse response = MedicationMapper.toResponse(med);
        broadcastMedication(med, response);
        return response;
    }

    @Transactional
    public MedicationResponse cancelMedication(UUID medicationId, String reason) {
        MedicationAdministration med = findMedicationOrThrow(medicationId);

        med.setStatus(MedicationStatus.CANCELLED);
        if (reason != null && !reason.isBlank()) {
            String existingNotes = med.getNotes() != null ? med.getNotes() + " | " : "";
            med.setNotes(existingNotes + "CANCELLED: " + reason);
        }

        med = medicationRepository.save(med);
        log.info("Medication cancelled — id:{} drug:{} reason:{}", med.getId(), med.getDrugName(), reason);
        MedicationResponse response = MedicationMapper.toResponse(med);
        broadcastMedication(med, response);
        return response;
    }

    @Transactional
    public MedicationResponse refuseMedication(UUID medicationId, String reason) {
        MedicationAdministration med = findMedicationOrThrow(medicationId);

        if (med.getStatus() != MedicationStatus.PRESCRIBED) {
            throw new ClinicalBusinessException(
                    "Only PRESCRIBED medications can be refused. Current status: " + med.getStatus());
        }

        med.setStatus(MedicationStatus.REFUSED);
        if (reason != null && !reason.isBlank()) {
            String existingNotes = med.getNotes() != null ? med.getNotes() + " | " : "";
            med.setNotes(existingNotes + "REFUSED: " + reason);
        }

        med = medicationRepository.save(med);
        log.info("Medication refused — id:{} drug:{} reason:{}", med.getId(), med.getDrugName(), reason);
        MedicationResponse response = MedicationMapper.toResponse(med);
        broadcastMedication(med, response);
        return response;
    }

    // ====================================================================
    // QUERIES
    // ====================================================================

    public Page<MedicationResponse> getMedicationsByVisit(UUID visitId, Pageable pageable) {
        return medicationRepository
                .findByVisitIdAndIsActiveTrueOrderByPrescribedAtDesc(visitId, pageable)
                .map(MedicationMapper::toResponse);
    }

    public List<MedicationResponse> getAllMedicationsForVisit(UUID visitId) {
        return medicationRepository
                .findByVisitIdAndIsActiveTrueOrderByPrescribedAtAsc(visitId)
                .stream()
                .map(MedicationMapper::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Patient-level medication history across every visit, newest first.
     * Drives the doctor's "Reorder" affordance on the prescribe form —
     * one tap to copy an earlier prescription's drugName / dose / route /
     * frequency into a new order.
     */
    public List<MedicationResponse> getMedicationHistoryForPatient(UUID patientId) {
        return medicationRepository
                .findByPatientIdAcrossVisits(patientId)
                .stream()
                .map(MedicationMapper::toResponse)
                .collect(Collectors.toList());
    }

    public MedicationResponse getMedication(UUID medicationId) {
        MedicationAdministration med = findMedicationOrThrow(medicationId);
        return MedicationMapper.toResponse(med);
    }

    /**
     * Nurse medication queue — every PRESCRIBED medication across the
     * hospital that has not yet been administered (or held / refused /
     * cancelled). Sorted STAT → URGENT → ROUTINE then oldest first
     * within each tier so the most overdue STAT bubbles to the top.
     */
    public List<MedicationResponse> getPendingQueueForHospital(UUID hospitalId) {
        return medicationRepository.findPendingForHospital(hospitalId)
                .stream()
                .map(MedicationMapper::toResponse)
                .collect(Collectors.toList());
    }

    // ====================================================================
    // INTERNAL
    // ====================================================================

    public MedicationAdministration findMedicationOrThrow(UUID id) {
        return medicationRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "MedicationAdministration", "id", id));
    }

    /**
     * Resolve the User entity from the SecurityContext, if any.
     * Returns null gracefully so callers can keep working with the
     * legacy free-text name path (e.g. tests with no auth, or
     * older API clients).
     */
    private User resolveCurrentUser() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null) return null;
            Object principal = auth.getPrincipal();
            return (principal instanceof User user) ? user : null;
        } catch (Exception e) {
            log.debug("Could not resolve current user from SecurityContext: {}", e.getMessage());
            return null;
        }
    }

    /** "Dr First Last" — falls back to email when names are blank. */
    private String formatUserName(User u) {
        if (u == null) return null;
        String first = u.getFirstName() != null ? u.getFirstName().trim() : "";
        String last = u.getLastName() != null ? u.getLastName().trim() : "";
        String joined = (first + " " + last).trim();
        return joined.isEmpty() ? u.getEmail() : joined;
    }

    /**
     * Broadcast a medication event on {@code /topic/medications/{hospitalId}}
     * so the nurse queue and any open visit-detail page can react in
     * real time. Wrapped in try/catch — a STOMP failure must never
     * roll back the persistence transaction. Called from every
     * lifecycle transition (prescribe / administer / countersign /
     * hold / cancel / refuse).
     */
    private void broadcastMedication(MedicationAdministration med, MedicationResponse response) {
        try {
            if (med.getVisit() == null || med.getVisit().getHospital() == null) return;
            UUID hospitalId = med.getVisit().getHospital().getId();
            realTimeEventPublisher.publishMedication(hospitalId, response);
        } catch (Exception e) {
            log.warn("Failed to broadcast medication event for {}: {}",
                    med.getId(), e.getMessage());
        }
    }
}
