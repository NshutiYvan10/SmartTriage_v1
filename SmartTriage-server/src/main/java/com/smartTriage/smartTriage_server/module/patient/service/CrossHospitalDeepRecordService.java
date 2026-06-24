package com.smartTriage.smartTriage_server.module.patient.service;

import com.smartTriage.smartTriage_server.common.enums.ClinicalDocumentType;
import com.smartTriage.smartTriage_server.common.enums.NoteType;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.module.audit.service.AuditService;
import com.smartTriage.smartTriage_server.module.clinical.repository.ClinicalNoteRepository;
import com.smartTriage.smartTriage_server.module.clinical.repository.DiagnosisRepository;
import com.smartTriage.smartTriage_server.module.clinical.repository.InvestigationRepository;
import com.smartTriage.smartTriage_server.module.consent.entity.BreakTheGlassEvent;
import com.smartTriage.smartTriage_server.module.consent.repository.BreakTheGlassEventRepository;
import com.smartTriage.smartTriage_server.module.consent.service.DataSharingConsentService;
import com.smartTriage.smartTriage_server.module.documentation.repository.ClinicalDocumentRepository;
import com.smartTriage.smartTriage_server.module.lab.repository.LabOrderRepository;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationAdministration;
import com.smartTriage.smartTriage_server.module.medication.repository.MedicationAdministrationRepository;
import com.smartTriage.smartTriage_server.module.patient.dto.CrossHospitalDeepRecordResponse;
import com.smartTriage.smartTriage_server.module.patient.dto.CrossHospitalDeepRecordResponse.HospitalSection;
import com.smartTriage.smartTriage_server.module.patient.dto.CrossHospitalDeepRecordResponse.VisitSummary;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.entity.PersonIdentity;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientRepository;
import com.smartTriage.smartTriage_server.module.patient.repository.PersonIdentityRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 2 — cross-hospital DEEP-record read, gated by consent or break-the-glass.
 *
 * Resolves an access basis: CONSENT (effective GRANTED) serves the bounded history; otherwise a
 * non-blank break-the-glass reason serves it and records an immutable {@link BreakTheGlassEvent};
 * otherwise DENIED (no clinical data). Every attempt is audited with its basis. The bounded history
 * walks PersonIdentity → linked Patients → Visits → per-visit clinical finders (mirroring the
 * handover-report assembly), provenance-tagged per hospital. Read-only; never opens the raw record.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CrossHospitalDeepRecordService {

    private static final int MAX_VISITS_PER_PATIENT = 25;
    private static final int MAX_MEDS = 25;

    private final PersonIdentityRepository personIdentityRepository;
    private final PatientRepository patientRepository;
    private final VisitRepository visitRepository;
    private final DiagnosisRepository diagnosisRepository;
    private final ClinicalNoteRepository clinicalNoteRepository;
    private final InvestigationRepository investigationRepository;
    private final LabOrderRepository labOrderRepository;
    private final ClinicalDocumentRepository clinicalDocumentRepository;
    private final MedicationAdministrationRepository medicationAdministrationRepository;
    private final DataSharingConsentService dataSharingConsentService;
    private final BreakTheGlassEventRepository breakTheGlassEventRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher realTimeEventPublisher;

    /** Deep cross-hospital record resolved by national ID (consent / break-the-glass gated). */
    @Transactional
    public CrossHospitalDeepRecordResponse getByNationalId(String nationalId, String breakTheGlassReason) {
        String nid = normalize(nationalId);
        if (nid == null) {
            audit("nid=(none)", "DENIED");
            return CrossHospitalDeepRecordResponse.builder().found(false).accessGranted(false)
                    .accessBasis("DENIED").build();
        }
        PersonIdentity identity = personIdentityRepository.findByNationalIdAndIsActiveTrue(nid).orElse(null);
        if (identity == null) {
            audit("nid=" + mask(nid), "DENIED");
            return notFound(nid);
        }
        return serve(identity, "nid=" + mask(nid), breakTheGlassReason);
    }

    /**
     * Deep cross-hospital record resolved by RFID card UID (V95) — same consent / break-the-glass
     * gate and bounded-history assembly as the national-ID path. Works for card-anchored patients.
     */
    @Transactional
    public CrossHospitalDeepRecordResponse getByRfidCardId(String rfidCardId, String breakTheGlassReason) {
        String card = normalize(rfidCardId);
        if (card == null) {
            audit("card=(none)", "DENIED");
            return CrossHospitalDeepRecordResponse.builder().found(false).accessGranted(false)
                    .accessBasis("DENIED").build();
        }
        PersonIdentity identity = personIdentityRepository.findByRfidCardIdAndIsActiveTrue(card).orElse(null);
        if (identity == null) {
            audit("card=" + mask(card), "DENIED");
            return CrossHospitalDeepRecordResponse.builder().found(false).accessGranted(false)
                    .accessBasis("DENIED").build();
        }
        return serve(identity, "card=" + mask(card), breakTheGlassReason);
    }

    /** Shared gate + bounded-history assembly for a resolved identity (national-ID or card path). */
    private CrossHospitalDeepRecordResponse serve(PersonIdentity identity, String auditLabel,
                                                  String breakTheGlassReason) {
        List<Patient> linked = patientRepository.findByPersonIdentityIdAndIsActiveTrue(identity.getId());
        if (linked.isEmpty()) {
            audit(auditLabel, "DENIED");
            return notFound(identity.getNationalId());
        }

        boolean hasConsent = dataSharingConsentService.getCurrentEffectiveConsent(identity.getId()).isPresent();
        String reason = normalize(breakTheGlassReason);

        String basis;
        if (hasConsent) {
            basis = "CONSENT";
        } else if (reason != null) {
            basis = "BREAK_THE_GLASS";
            recordBreakTheGlass(identity, reason);
        } else {
            basis = "DENIED";
        }
        audit(auditLabel, basis);

        if (basis.equals("DENIED")) {
            return CrossHospitalDeepRecordResponse.builder()
                    .found(true).accessGranted(false).accessBasis("DENIED").consentRequired(true)
                    .nationalId(mask(identity.getNationalId())).linkedHospitalCount(linked.size()).build();
        }
        return assemble(identity.getNationalId(), basis, linked);
    }

    // ── break-the-glass forensic record ──
    private void recordBreakTheGlass(PersonIdentity identity, String reason) {
        User actor = resolveCurrentUser();
        if (actor == null) {
            // Never serve an unattributed emergency override.
            throw new ClinicalBusinessException(
                    "Break-the-glass requires an authenticated clinician — cannot resolve the actor.");
        }
        String priorState = priorConsentState(identity.getNationalId());
        UUID hospitalId = userRepository.findHospitalIdByUserId(actor.getId()).orElse(null);
        BreakTheGlassEvent event = breakTheGlassEventRepository.save(BreakTheGlassEvent.builder()
                .personIdentity(identity)
                .actorUserId(actor.getId())
                .actorName(displayNameOf(actor))
                .actorRole(actor.getRole() != null ? actor.getRole().name() : null)
                .actorHospitalId(hospitalId)
                .reason(reason)
                .priorConsentState(priorState)
                .accessedAt(Instant.now())
                .build());
        log.warn("BREAK-THE-GLASS deep-record access by {} for identity {} (prior consent: {})",
                actor.getId(), identity.getId(), priorState);

        // Notify the actor's-hospital governance team in real time — AFTER COMMIT so a rolled-back
        // override never pushes a phantom. Audience = actor's hospital only (the team with authority
        // to review this clinician). Visitless governance event, not a ClinicalAlert.
        if (hospitalId != null) {
            realTimeEventPublisher.publishGovernanceEventAfterCommit(hospitalId, java.util.Map.of(
                    "eventType", "BREAK_THE_GLASS_ACCESS",
                    "eventId", event.getId().toString(),
                    "actorName", event.getActorName(),
                    "accessedAt", event.getAccessedAt().toString()));
        }
    }

    private String priorConsentState(String nationalId) {
        var history = dataSharingConsentService.getConsentsForNationalId(nationalId);
        if (history.isEmpty()) return "NONE";
        // Not in CONSENT branch, so the latest is DENIED or WITHDRAWN; report it for governance.
        return history.get(0).getStatus() != null ? history.get(0).getStatus().name() : "NONE";
    }

    // ── bounded-history assembly ──
    private CrossHospitalDeepRecordResponse assemble(String nid, String basis, List<Patient> linked) {
        List<HospitalSection> sections = new ArrayList<>();
        List<String> medHistory = new ArrayList<>();
        Set<String> hospitalNames = new LinkedHashSet<>();

        for (Patient p : linked) {
            String hospitalName = hospitalNameOf(p);
            hospitalNames.add(hospitalName);

            medicationAdministrationRepository.findByPatientIdAcrossVisits(p.getId()).stream()
                    .filter(CrossHospitalDeepRecordService::isActiveMed)
                    .limit(MAX_MEDS)
                    .forEach(m -> medHistory.add(medLine(m, hospitalName)));

            var page = visitRepository.findByPatientIdAndIsActiveTrue(p.getId(),
                    PageRequest.of(0, MAX_VISITS_PER_PATIENT, Sort.by(Sort.Direction.DESC, "arrivalTime")));
            List<VisitSummary> visitSummaries = new ArrayList<>();
            for (Visit v : page.getContent()) {
                visitSummaries.add(VisitSummary.builder()
                        .visitNumber(v.getVisitNumber())
                        .arrivalTime(v.getArrivalTime())
                        .status(v.getStatus() != null ? v.getStatus().name() : null)
                        .diagnoses(diagnoses(v.getId()))
                        .dischargeSummaries(dischargeSummaries(v.getId()))
                        .criticalLabs(criticalLabs(v.getId()))
                        .keyNotes(keyNotes(v.getId()))
                        .build());
            }
            sections.add(HospitalSection.builder()
                    .sourceHospital(hospitalName)
                    .truncated(page.getTotalElements() > MAX_VISITS_PER_PATIENT)
                    .visits(visitSummaries)
                    .build());
        }

        Patient newest = linked.stream()
                .max(Comparator.comparing(CrossHospitalDeepRecordService::lastTouched))
                .orElse(linked.get(0));

        return CrossHospitalDeepRecordResponse.builder()
                .found(true).accessGranted(true).accessBasis(basis).consentRequired(false)
                .nationalId(mask(nid))
                .firstName(newest.getFirstName()).lastName(newest.getLastName())
                .dateOfBirth(newest.getDateOfBirth()).gender(newest.getGender())
                .linkedHospitalCount(hospitalNames.size())
                .hospitals(sections)
                .medicationHistory(medHistory)
                .build();
    }

    private List<String> diagnoses(UUID visitId) {
        List<String> out = new ArrayList<>();
        diagnosisRepository.findByVisitIdAndIsActiveTrueOrderByDiagnosedAtAsc(visitId).forEach(d -> {
            StringBuilder s = new StringBuilder();
            if (Boolean.TRUE.equals(d.getIsPrimary())) s.append("[PRIMARY] ");
            if (d.getDescription() != null) s.append(d.getDescription());
            if (d.getIcdCode() != null && !d.getIcdCode().isBlank()) s.append(" (").append(d.getIcdCode()).append(")");
            if (d.getDiagnosedByName() != null) s.append(" — ").append(d.getDiagnosedByName());
            out.add(s.toString().trim());
        });
        return out;
    }

    private List<String> dischargeSummaries(UUID visitId) {
        List<String> out = new ArrayList<>();
        clinicalDocumentRepository.findByVisitIdAndDocumentTypeAndIsActiveTrueOrderByCreatedAtDesc(
                        visitId, ClinicalDocumentType.DISCHARGE_SUMMARY)
                .forEach(doc -> out.add((doc.getTitle() != null ? doc.getTitle() : "Discharge summary")
                        + (doc.isSigned() ? " (signed)" : " (unsigned)")));
        return out;
    }

    private List<String> criticalLabs(UUID visitId) {
        List<String> out = new ArrayList<>();
        investigationRepository.findByVisitIdAndIsActiveTrueOrderByOrderedAtAsc(visitId).stream()
                .filter(i -> Boolean.TRUE.equals(i.getIsCritical()) || Boolean.TRUE.equals(i.getIsAbnormal()))
                .forEach(i -> out.add(i.getTestName()
                        + (Boolean.TRUE.equals(i.getIsCritical()) ? " [CRITICAL]" : " [ABNORMAL]")));
        labOrderRepository.findByVisitIdAndInvestigationIsNullAndIsActiveTrueOrderByOrderedAtDesc(visitId).stream()
                .filter(l -> l.isCritical() || l.isAbnormal())
                .forEach(l -> out.add(l.getTestName()
                        + (l.getResultValue() != null ? " = " + l.getResultValue() : "")
                        + (l.isCritical() ? " [CRITICAL]" : " [ABNORMAL]")));
        return out;
    }

    private List<String> keyNotes(UUID visitId) {
        List<String> out = new ArrayList<>();
        for (NoteType t : List.of(NoteType.DOCTOR_NOTE, NoteType.TREATMENT_PLAN)) {
            clinicalNoteRepository.findByVisitIdAndNoteTypeAndIsActiveTrueOrderByRecordedAtDesc(visitId, t)
                    .stream().limit(2)
                    .forEach(n -> out.add(t.name() + ": "
                            + (n.getContent() != null ? n.getContent() : "")));
        }
        return out;
    }

    private static boolean isActiveMed(MedicationAdministration m) {
        if (m.getStatus() == null) return true;
        String s = m.getStatus().name();
        return !s.contains("CANCEL") && !s.contains("REFUS");
    }

    private static String medLine(MedicationAdministration m, String hospital) {
        StringBuilder d = new StringBuilder(m.getDrugName() != null ? m.getDrugName() : "(drug)");
        if (m.getDose() != null && !m.getDose().isBlank()) d.append(" ").append(m.getDose());
        if (m.getFrequency() != null && !m.getFrequency().isBlank()) d.append(" ").append(m.getFrequency());
        if (m.getStatus() != null) d.append(" [").append(m.getStatus()).append("]");
        d.append(" @ ").append(hospital);
        return d.toString();
    }

    private static Instant lastTouched(Patient p) {
        return p.getUpdatedAt() != null ? p.getUpdatedAt()
                : (p.getCreatedAt() != null ? p.getCreatedAt() : Instant.EPOCH);
    }

    private String hospitalNameOf(Patient p) {
        try {
            return p.getHospital() != null && p.getHospital().getName() != null
                    ? p.getHospital().getName() : "Unknown hospital";
        } catch (Exception e) {
            return "Unknown hospital";
        }
    }

    private CrossHospitalDeepRecordResponse notFound(String nid) {
        return CrossHospitalDeepRecordResponse.builder()
                .found(false).accessGranted(false).accessBasis("DENIED").nationalId(mask(nid)).build();
    }

    private void audit(String identifierLabel, String basis) {
        auditService.record("GET", "/api/v1/patient-identity/deep-record",
                "CROSS_HOSPITAL_DEEP_RECORD_READ " + identifierLabel + " basis=" + basis, 200);
    }

    private static String mask(String value) {
        return value == null || value.length() < 4 ? "(none)" : "***" + value.substring(value.length() - 4);
    }

    private static String normalize(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private User resolveCurrentUser() {
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null) return null;
            Object principal = auth.getPrincipal();
            return (principal instanceof User user) ? user : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String displayNameOf(User user) {
        String first = user.getFirstName() != null ? user.getFirstName().trim() : "";
        String last = user.getLastName() != null ? user.getLastName().trim() : "";
        String joined = (first + " " + last).trim();
        return joined.isEmpty() ? user.getEmail() : joined;
    }
}
