package com.smartTriage.smartTriage_server.module.patient.service;

import com.smartTriage.smartTriage_server.module.audit.service.AuditService;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationAdministration;
import com.smartTriage.smartTriage_server.module.medication.repository.MedicationAdministrationRepository;
import com.smartTriage.smartTriage_server.module.patient.dto.CrossHospitalSafetySummaryResponse;
import com.smartTriage.smartTriage_server.module.patient.dto.CrossHospitalSafetySummaryResponse.SafetyItem;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.entity.PersonIdentity;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientAllergyRepository;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientChronicConditionRepository;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientRepository;
import com.smartTriage.smartTriage_server.module.patient.repository.PersonIdentityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 1 — assembles the cross-hospital minimal SAFETY SUMMARY for a national ID by fanning out
 * over every local {@link Patient} row linked to the shared {@link PersonIdentity}, calling the
 * existing per-patient safety finders, and tagging each item with its source hospital.
 *
 * This is the deliberate "safety floor": demographics + allergies + blood type + active meds +
 * chronic problems + emergency contacts only — NOT the deep clinical record. Every read is
 * explicitly written to the audit log (the GET is not covered by the mutating-request interceptor).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CrossHospitalIdentityService {

    private final PersonIdentityRepository personIdentityRepository;
    private final PatientRepository patientRepository;
    private final PatientAllergyRepository patientAllergyRepository;
    private final PatientChronicConditionRepository patientChronicConditionRepository;
    private final MedicationAdministrationRepository medicationAdministrationRepository;
    private final AuditService auditService;

    /** Cross-hospital safety summary resolved by national ID. */
    public CrossHospitalSafetySummaryResponse getByNationalId(String nationalId) {
        String nid = normalize(nationalId);
        auditCrossHospitalRead("nid", nid);
        if (nid == null) {
            return CrossHospitalSafetySummaryResponse.builder().found(false).nationalId(null).build();
        }
        return personIdentityRepository.findByNationalIdAndIsActiveTrue(nid)
                .map(this::assemble)
                .orElseGet(() -> CrossHospitalSafetySummaryResponse.builder().found(false).nationalId(nid).build());
    }

    /**
     * Cross-hospital safety summary resolved by RFID card UID — the system-wide tap-to-identify
     * read (V95). Works for card-anchored patients with no national ID. Reuses the same assembly.
     */
    public CrossHospitalSafetySummaryResponse getByRfidCardId(String rfidCardId) {
        String card = normalize(rfidCardId);
        auditCrossHospitalRead("card", card);
        if (card == null) {
            return CrossHospitalSafetySummaryResponse.builder().found(false).build();
        }
        return personIdentityRepository.findByRfidCardIdAndIsActiveTrue(card)
                .map(this::assemble)
                .orElseGet(() -> CrossHospitalSafetySummaryResponse.builder().found(false).build());
    }

    /** Fan out over every local patient linked to the shared identity and assemble the safety floor. */
    private CrossHospitalSafetySummaryResponse assemble(PersonIdentity identity) {
        String nid = identity.getNationalId();
        List<Patient> linked = patientRepository.findByPersonIdentityIdAndIsActiveTrue(identity.getId());
        if (linked.isEmpty()) {
            return CrossHospitalSafetySummaryResponse.builder().found(false).nationalId(nid).build();
        }

        List<SafetyItem> allergies = new ArrayList<>();
        List<SafetyItem> conditions = new ArrayList<>();
        List<SafetyItem> meds = new ArrayList<>();
        Set<String> hospitals = new LinkedHashSet<>();

        for (Patient p : linked) {
            String hospitalName = hospitalNameOf(p);
            hospitals.add(hospitalName);

            patientAllergyRepository.findActiveByPatientId(p.getId()).forEach(a -> {
                StringBuilder d = new StringBuilder(a.getAllergenName() != null ? a.getAllergenName() : "(allergen)");
                if (a.getSeverity() != null) d.append(" — ").append(a.getSeverity());
                if (a.getReaction() != null && !a.getReaction().isBlank()) d.append(" (").append(a.getReaction()).append(")");
                allergies.add(SafetyItem.builder().detail(d.toString()).sourceHospital(hospitalName).build());
            });

            patientChronicConditionRepository.findActiveByPatientId(p.getId()).forEach(c -> {
                StringBuilder d = new StringBuilder(c.getConditionName() != null ? c.getConditionName() : "(condition)");
                if (c.getStatus() != null) d.append(" — ").append(c.getStatus());
                conditions.add(SafetyItem.builder().detail(d.toString()).sourceHospital(hospitalName).build());
            });

            medicationAdministrationRepository.findByPatientIdAcrossVisits(p.getId()).stream()
                    .filter(CrossHospitalIdentityService::isActiveMed)
                    .limit(25)
                    .forEach(m -> {
                        StringBuilder d = new StringBuilder(m.getDrugName() != null ? m.getDrugName() : "(drug)");
                        if (m.getDose() != null && !m.getDose().isBlank()) d.append(" ").append(m.getDose());
                        if (m.getFrequency() != null && !m.getFrequency().isBlank()) d.append(" ").append(m.getFrequency());
                        if (m.getStatus() != null) d.append(" [").append(m.getStatus()).append("]");
                        meds.add(SafetyItem.builder().detail(d.toString()).sourceHospital(hospitalName).build());
                    });
        }

        Patient newest = linked.stream()
                .max(Comparator.comparing(CrossHospitalIdentityService::lastTouched))
                .orElse(linked.get(0));

        return CrossHospitalSafetySummaryResponse.builder()
                .found(true)
                .nationalId(nid)
                .firstName(newest.getFirstName())
                .lastName(newest.getLastName())
                .dateOfBirth(newest.getDateOfBirth())
                .gender(newest.getGender())
                .bloodType(newest.getBloodType())
                .emergencyContactName(newest.getEmergencyContactName())
                .emergencyContactPhone(newest.getEmergencyContactPhone())
                .linkedHospitalCount(hospitals.size())
                .sourceHospitals(new ArrayList<>(hospitals))
                .allergies(dedupe(allergies))
                .chronicConditions(dedupe(conditions))
                .activeMedications(dedupe(meds))
                .build();
    }

    private static boolean isActiveMed(MedicationAdministration m) {
        if (m.getStatus() == null) return true;
        String s = m.getStatus().name();
        return !s.contains("CANCEL") && !s.contains("REFUS"); // exclude cancelled/refused; keep ongoing + recently given
    }

    private static Instant lastTouched(Patient p) {
        return p.getUpdatedAt() != null ? p.getUpdatedAt()
                : (p.getCreatedAt() != null ? p.getCreatedAt() : Instant.EPOCH);
    }

    private String hospitalNameOf(Patient p) {
        try {
            return p.getHospital() != null && p.getHospital().getName() != null ? p.getHospital().getName() : "Unknown hospital";
        } catch (Exception e) {
            return "Unknown hospital";
        }
    }

    /** Collapse exact-duplicate details (same item recorded at multiple hospitals), keeping the first source. */
    private static List<SafetyItem> dedupe(List<SafetyItem> items) {
        List<SafetyItem> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (SafetyItem i : items) {
            if (seen.add(i.getDetail() == null ? "" : i.getDetail().toLowerCase())) out.add(i);
        }
        return out;
    }

    private static String normalize(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private void auditCrossHospitalRead(String keyType, String value) {
        // The GET is not covered by AuditInterceptor (mutating requests only); log it explicitly.
        // REQUIRES_NEW + fail-safe inside AuditService — never breaks the read. Identifier masked.
        String masked = value == null || value.length() < 4 ? "(none)" : "***" + value.substring(value.length() - 4);
        auditService.record("GET", "/api/v1/patient-identity/safety-summary",
                "CROSS_HOSPITAL_SAFETY_SUMMARY_READ " + keyType + "=" + masked, 200);
    }
}
