package com.smartTriage.smartTriage_server.module.patient.service;

import com.smartTriage.smartTriage_server.common.enums.MatchType;
import com.smartTriage.smartTriage_server.module.patient.dto.PatientLookupCandidate;
import com.smartTriage.smartTriage_server.module.patient.dto.PatientLookupQuery;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientRepository;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Federated patient lookup — ranks existing patient rows against the
 * identifiers a triage nurse has on hand.
 *
 * <h3>Matching tiers</h3>
 * <ol>
 *   <li><b>Tier 1 — deterministic.</b> NID / passport / birth-certificate.
 *       Each is partial-unique within hospital, so the result is at most
 *       one row, confidence = 1.00.</li>
 *   <li><b>Tier 2 — MRN.</b> Hospital-internal, also at most one row,
 *       confidence = 0.99 (a hair below NID because MRNs can be re-used
 *       on legacy data).</li>
 *   <li><b>Tier 3 — soft identifiers.</b> Phone, guardian NID, guardian
 *       phone. Each can match multiple rows. Confidence is bumped up when
 *       supporting fields (DOB, first name) corroborate.</li>
 *   <li><b>Tier 4 — demographic.</b> firstName + lastName + DOB exact
 *       (case-insensitive). Last resort, confidence = 0.65.</li>
 * </ol>
 *
 * <h3>Hospital scoping</h3>
 * The hospital UUID is supplied at the controller layer (route param) and
 * is never trusted from the query body. Cross-hospital federation is
 * out of scope for this phase.
 *
 * <h3>De-duplication</h3>
 * If a single patient row matches several matchers (e.g. NID lookup AND
 * demographic lookup both fire), the duplicate is collapsed to a single
 * candidate retaining the highest-confidence matcher.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PatientLookupService {

    /** Maximum candidates returned to the UI — pages of results help nobody at triage. */
    private static final int MAX_CANDIDATES = 20;

    private final PatientRepository patientRepository;
    private final VisitRepository visitRepository;

    public List<PatientLookupCandidate> lookup(UUID hospitalId, PatientLookupQuery q) {
        if (hospitalId == null) {
            throw new IllegalArgumentException("hospitalId is required");
        }
        if (q == null || isBlankQuery(q)) {
            return List.of();
        }

        List<Match> matches = new ArrayList<>();

        // ── Tier 1: deterministic ────────────────────────────────────────
        if (notBlank(q.getNationalId())) {
            patientRepository
                    .findByNationalIdAndHospitalIdAndIsActiveTrue(q.getNationalId().trim(), hospitalId)
                    .ifPresent(p -> matches.add(new Match(p, MatchType.NATIONAL_ID, 1.00)));
        }
        if (notBlank(q.getPassport())) {
            patientRepository
                    .findByPassportNumberAndHospitalIdAndIsActiveTrue(q.getPassport().trim(), hospitalId)
                    .ifPresent(p -> matches.add(new Match(p, MatchType.PASSPORT, 1.00)));
        }
        if (notBlank(q.getBirthCertificate())) {
            patientRepository
                    .findByBirthCertificateNumberAndHospitalIdAndIsActiveTrue(
                            q.getBirthCertificate().trim(), hospitalId)
                    .ifPresent(p -> matches.add(new Match(p, MatchType.BIRTH_CERTIFICATE, 1.00)));
        }

        // ── Tier 2: MRN ──────────────────────────────────────────────────
        if (notBlank(q.getMrn())) {
            patientRepository
                    .findByMedicalRecordNumberAndHospitalIdAndIsActiveTrue(q.getMrn().trim(), hospitalId)
                    .ifPresent(p -> matches.add(new Match(p, MatchType.MRN, 0.99)));
        }

        // ── Tier 3: phone (with optional DOB corroboration) ──────────────
        if (notBlank(q.getPhone())) {
            List<Patient> phoneMatches = patientRepository
                    .findAllByPhoneNumberAndHospitalIdAndIsActiveTrue(q.getPhone().trim(), hospitalId);
            for (Patient p : phoneMatches) {
                if (q.getDob() != null && q.getDob().equals(p.getDateOfBirth())) {
                    matches.add(new Match(p, MatchType.PHONE_AND_DOB, 0.85));
                } else {
                    matches.add(new Match(p, MatchType.PHONE, 0.70));
                }
            }
        }

        // ── Tier 3: guardian NID (pediatric) ─────────────────────────────
        if (notBlank(q.getGuardianNationalId())) {
            List<Patient> kidMatches = patientRepository
                    .findAllByGuardianNationalIdAndHospitalIdAndIsActiveTrue(
                            q.getGuardianNationalId().trim(), hospitalId);
            for (Patient p : kidMatches) {
                matches.add(new Match(
                        p, MatchType.GUARDIAN_NATIONAL_ID,
                        scoreGuardianMatch(q, p, /* baseConfidence */ 0.75)));
            }
        }

        // ── Tier 3: guardian phone (pediatric, lower confidence) ─────────
        if (notBlank(q.getGuardianPhone())) {
            List<Patient> kidMatches = patientRepository
                    .findAllByGuardianPhoneAndHospitalIdAndIsActiveTrue(
                            q.getGuardianPhone().trim(), hospitalId);
            for (Patient p : kidMatches) {
                matches.add(new Match(
                        p, MatchType.GUARDIAN_PHONE,
                        scoreGuardianMatch(q, p, /* baseConfidence */ 0.60)));
            }
        }

        // ── Tier 4: demographic fallback ─────────────────────────────────
        if (notBlank(q.getFirstName()) && notBlank(q.getLastName()) && q.getDob() != null) {
            List<Patient> demo = patientRepository.findDemographicMatch(
                    hospitalId, q.getFirstName().trim(), q.getLastName().trim(), q.getDob());
            for (Patient p : demo) {
                matches.add(new Match(p, MatchType.DEMOGRAPHIC, 0.65));
            }
        }

        // ── De-duplicate by patientId, keep highest-confidence matcher ───
        Map<UUID, Match> dedup = new LinkedHashMap<>();
        for (Match m : matches) {
            UUID id = m.patient.getId();
            Match existing = dedup.get(id);
            if (existing == null || m.confidence > existing.confidence) {
                dedup.put(id, m);
            }
        }

        return dedup.values().stream()
                .sorted(Comparator.comparingDouble((Match m) -> m.confidence).reversed())
                .limit(MAX_CANDIDATES)
                .map(this::toCandidate)
                .toList();
    }

    /**
     * Bump a guardian-mediated match's confidence based on how many
     * supporting fields (child first name, child DOB) corroborate. The
     * base score reflects the strength of the guardian identifier itself
     * (NID stronger than phone).
     */
    private double scoreGuardianMatch(PatientLookupQuery q, Patient p, double base) {
        boolean firstNameMatch = notBlank(q.getFirstName())
                && p.getFirstName() != null
                && q.getFirstName().trim().equalsIgnoreCase(p.getFirstName());
        boolean dobMatch = q.getDob() != null && q.getDob().equals(p.getDateOfBirth());

        double score = base;
        if (firstNameMatch) score += 0.10;
        if (dobMatch)       score += 0.10;
        // Cap below 1.00 — guardian-mediated matches are never as strong as
        // a direct NID hit on the patient's own identity.
        return Math.min(score, 0.95);
    }

    private PatientLookupCandidate toCandidate(Match m) {
        Patient p = m.patient;
        return PatientLookupCandidate.builder()
                .patientId(p.getId())
                .medicalRecordNumber(p.getMedicalRecordNumber())
                .firstName(p.getFirstName())
                .lastName(p.getLastName())
                .dateOfBirth(p.getDateOfBirth())
                .ageInYears(p.getDateOfBirth() == null ? null : p.getAgeInYears())
                .isPediatric(p.isPediatric())
                .gender(p.getGender())
                .nationalIdLast4(last4(p.getNationalId()))
                .lastVisitAt(visitRepository.findLastArrivalByPatientId(p.getId()).orElse(null))
                .hospitalId(p.getHospital().getId())
                .matchType(m.matchType)
                .confidence(round2(m.confidence))
                .build();
    }

    private static boolean isBlankQuery(PatientLookupQuery q) {
        return !notBlank(q.getNationalId())
                && !notBlank(q.getPassport())
                && !notBlank(q.getBirthCertificate())
                && !notBlank(q.getMrn())
                && !notBlank(q.getPhone())
                && !notBlank(q.getGuardianNationalId())
                && !notBlank(q.getGuardianPhone())
                && !(notBlank(q.getFirstName()) && notBlank(q.getLastName()) && q.getDob() != null);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String last4(String s) {
        if (s == null || s.length() < 4) return null;
        return s.substring(s.length() - 4);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * Accumulator used internally so we can de-duplicate across matchers
     * before allocating the heavier {@link PatientLookupCandidate} (which
     * does the per-row "last visit" lookup).
     */
    private record Match(Patient patient, MatchType matchType, double confidence) {}

    // ── Convenience overloads — useful for unit tests / direct callers ──

    /** Look up by NID only — most common deterministic call. */
    public java.util.Optional<PatientLookupCandidate> lookupByNationalId(UUID hospitalId, String nationalId) {
        if (!notBlank(nationalId)) return java.util.Optional.empty();
        List<PatientLookupCandidate> hits = lookup(hospitalId,
                PatientLookupQuery.builder().nationalId(nationalId).build());
        return hits.stream().findFirst();
    }

    @SuppressWarnings("unused") // kept for symmetry; date-only is rarely useful, but keeps the API regular
    private static boolean dateMatch(LocalDate a, LocalDate b) {
        return a != null && a.equals(b);
    }
}
