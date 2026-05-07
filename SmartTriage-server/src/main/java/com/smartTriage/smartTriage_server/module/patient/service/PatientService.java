package com.smartTriage.smartTriage_server.module.patient.service;

import com.smartTriage.smartTriage_server.common.enums.PregnancyStatus;
import com.smartTriage.smartTriage_server.common.enums.VisitStatus;
import com.smartTriage.smartTriage_server.common.exception.DuplicateResourceException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.service.HospitalService;
import com.smartTriage.smartTriage_server.module.patient.dto.CreatePatientRequest;
import com.smartTriage.smartTriage_server.module.patient.dto.PatientResponse;
import com.smartTriage.smartTriage_server.module.patient.dto.RegisterPatientRequest;
import com.smartTriage.smartTriage_server.module.patient.dto.RegisterPatientResponse;
import com.smartTriage.smartTriage_server.module.patient.dto.UpdatePregnancyStatusRequest;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.mapper.PatientMapper;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.mapper.VisitMapper;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Patient service — manages patient registration and identity.
 * Critical responsibilities:
 * - Duplicate detection (by national ID within hospital)
 * - MRN generation
 * - Pediatric flag computation
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PatientService {

    private final PatientRepository patientRepository;
    private final VisitRepository visitRepository;
    private final HospitalService hospitalService;
    private final com.smartTriage.smartTriage_server.module.location.repository.RwProvinceRepository rwProvinceRepository;
    private final com.smartTriage.smartTriage_server.module.location.repository.RwDistrictRepository rwDistrictRepository;
    private final com.smartTriage.smartTriage_server.module.location.repository.RwSectorRepository rwSectorRepository;
    private final com.smartTriage.smartTriage_server.module.location.repository.RwCellRepository rwCellRepository;
    private final com.smartTriage.smartTriage_server.module.location.repository.RwVillageRepository rwVillageRepository;

    // Simple MRN counter — in production, this would use a database sequence
    private static final AtomicLong mrnCounter = new AtomicLong(100000);
    private static final AtomicLong visitCounter = new AtomicLong(0);

    @Transactional
    public PatientResponse createPatient(CreatePatientRequest request) {
        Hospital hospital = hospitalService.findHospitalOrThrow(request.getHospitalId());

        // Duplicate detection across all Tier-1 deterministic identifiers.
        // The DB will also enforce these via partial-unique indexes (V22),
        // but checking up-front gives a friendlier error message and avoids
        // the constraint-violation stack trace.
        assertNoDuplicate(request.getNationalId(),            "nationalId",            hospital.getId(),
                () -> patientRepository.findByNationalIdAndHospitalIdAndIsActiveTrue(
                        request.getNationalId(), hospital.getId()));
        assertNoDuplicate(request.getPassportNumber(),        "passportNumber",        hospital.getId(),
                () -> patientRepository.findByPassportNumberAndHospitalIdAndIsActiveTrue(
                        request.getPassportNumber(), hospital.getId()));
        assertNoDuplicate(request.getBirthCertificateNumber(), "birthCertificateNumber", hospital.getId(),
                () -> patientRepository.findByBirthCertificateNumberAndHospitalIdAndIsActiveTrue(
                        request.getBirthCertificateNumber(), hospital.getId()));

        Patient patient = PatientMapper.toEntity(request);
        patient.setHospital(hospital);
        patient.setMedicalRecordNumber(generateMRN(hospital.getHospitalCode()));
        applyStructuredLocation(patient,
                request.getProvinceId(), request.getDistrictId(),
                request.getSectorId(), request.getCellId(), request.getVillageId());

        patient = patientRepository.save(patient);

        log.info("Patient registered: {} {} (MRN: {}) at hospital {}",
                patient.getFirstName(), patient.getLastName(),
                patient.getMedicalRecordNumber(), hospital.getHospitalCode());

        return PatientMapper.toResponse(patient);
    }

    private void assertNoDuplicate(String value, String field, UUID hospitalId,
                                   java.util.function.Supplier<java.util.Optional<Patient>> finder) {
        if (value == null || value.isBlank()) return;
        finder.get().ifPresent(existing -> {
            throw new DuplicateResourceException("Patient", field, value);
        });
    }

    /**
     * Atomic registration — creates both Patient and Visit in a single
     * database transaction. If either step fails the entire operation
     * rolls back, preventing orphaned patient records with no visit.
     */
    @Transactional
    public RegisterPatientResponse registerPatientWithVisit(RegisterPatientRequest request) {
        Hospital hospital = hospitalService.findHospitalOrThrow(request.getHospitalId());

        // Duplicate detection across all Tier-1 deterministic identifiers.
        assertNoDuplicate(request.getNationalId(),             "nationalId",             hospital.getId(),
                () -> patientRepository.findByNationalIdAndHospitalIdAndIsActiveTrue(
                        request.getNationalId(), hospital.getId()));
        assertNoDuplicate(request.getPassportNumber(),         "passportNumber",         hospital.getId(),
                () -> patientRepository.findByPassportNumberAndHospitalIdAndIsActiveTrue(
                        request.getPassportNumber(), hospital.getId()));
        assertNoDuplicate(request.getBirthCertificateNumber(), "birthCertificateNumber", hospital.getId(),
                () -> patientRepository.findByBirthCertificateNumberAndHospitalIdAndIsActiveTrue(
                        request.getBirthCertificateNumber(), hospital.getId()));

        // 1. Create Patient. Empty strings are coerced to NULL so the
        //    partial-unique indexes (which fire on `WHERE col IS NOT NULL`)
        //    treat blank fields as absent rather than as a reserved value.
        Patient patient = Patient.builder()
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .dateOfBirth(request.getDateOfBirth())
                .gender(request.getGender())
                .nationalId(blankToNull(request.getNationalId()))
                .passportNumber(blankToNull(request.getPassportNumber()))
                .birthCertificateNumber(blankToNull(request.getBirthCertificateNumber()))
                .phoneNumber(blankToNull(request.getPhoneNumber()))
                .address(request.getAddress())
                .emergencyContactName(request.getEmergencyContactName())
                .emergencyContactPhone(request.getEmergencyContactPhone())
                .bloodType(request.getBloodType())
                .knownAllergies(request.getKnownAllergies())
                .chronicConditions(request.getChronicConditions())
                .guardianNationalId(blankToNull(request.getGuardianNationalId()))
                .guardianPhone(blankToNull(request.getGuardianPhone()))
                .guardianName(blankToNull(request.getGuardianName()))
                .guardianRelationship(blankToNull(request.getGuardianRelationship()))
                .build();
        patient.setHospital(hospital);
        patient.setMedicalRecordNumber(generateMRN(hospital.getHospitalCode()));
        applyStructuredLocation(patient,
                request.getProvinceId(), request.getDistrictId(),
                request.getSectorId(), request.getCellId(), request.getVillageId());
        patient = patientRepository.save(patient);

        // 2. Create Visit (same transaction — atomic with the patient)
        Visit visit = Visit.builder()
                .patient(patient)
                .hospital(hospital)
                .visitNumber(generateVisitNumber(hospital.getHospitalCode()))
                .arrivalMode(request.getArrivalMode())
                .arrivalTime(Instant.now())
                .chiefComplaint(request.getChiefComplaint())
                .status(VisitStatus.REGISTERED)
                .isPediatric(patient.isPediatric())
                .referringFacility(request.getReferringFacility())
                .build();
        visit = visitRepository.save(visit);

        log.info("Registration complete: Patient {} {} (MRN: {}) + Visit {} at {}",
                patient.getFirstName(), patient.getLastName(),
                patient.getMedicalRecordNumber(), visit.getVisitNumber(),
                hospital.getHospitalCode());

        return RegisterPatientResponse.builder()
                .patient(PatientMapper.toResponse(patient))
                .visit(VisitMapper.toResponse(visit))
                .build();
    }

    public PatientResponse getPatientById(UUID id) {
        Patient patient = findPatientOrThrow(id);
        return PatientMapper.toResponse(patient);
    }

    public Page<PatientResponse> getPatientsByHospital(UUID hospitalId, Pageable pageable) {
        return patientRepository.findByHospitalIdAndIsActiveTrue(hospitalId, pageable)
                .map(PatientMapper::toResponse);
    }

    public Page<PatientResponse> searchPatients(UUID hospitalId, String query, Pageable pageable) {
        return patientRepository.searchPatients(hospitalId, query, pageable)
                .map(PatientMapper::toResponse);
    }

    public Patient findPatientOrThrow(UUID id) {
        return patientRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Patient", "id", id));
    }

    /**
     * Phase 13b — structured pregnancy-status update. The frontend
     * teratogen check reads `pregnancyStatus` first and falls back to
     * the legacy free-text `chronicConditions` scan only when the
     * structured value is null or UNKNOWN. Without this endpoint the
     * structured column would always be null and the structured-first
     * path would never fire.
     *
     * The audit timestamp is set server-side rather than accepted from
     * the client — that's the only path consistent with "the chart
     * timestamps are produced by the system, not the user".
     *
     * Logged at INFO so a safety officer can grep for who toggled a
     * patient between PREGNANT and NOT_PREGNANT and when. Per
     * SmartTriage convention we log the visit-number / MRN, not the
     * UUIDs that aren't human-readable.
     */
    @Transactional
    public PatientResponse updatePregnancyStatus(UUID patientId, UpdatePregnancyStatusRequest request) {
        Patient patient = findPatientOrThrow(patientId);
        PregnancyStatus previous = patient.getPregnancyStatus();
        patient.setPregnancyStatus(request.getPregnancyStatus());
        patient.setPregnancyStatusRecordedAt(Instant.now());
        patient = patientRepository.save(patient);

        log.info("Pregnancy status updated for patient {} (MRN {}): {} → {}",
                patient.getId(),
                patient.getMedicalRecordNumber(),
                previous,
                patient.getPregnancyStatus());

        return PatientMapper.toResponse(patient);
    }

    /**
     * Replaces the patient's free-text known allergies. Null is intentional —
     * a clinician may need to clear a previously-recorded allergy that turned
     * out to be wrong (e.g. patient reported a "penicillin allergy" that was
     * actually a side effect, not an immune reaction).
     *
     * The medication safety engine reads from this field on every prescribe;
     * a stale or wrong allergy here translates directly into incorrect cross-
     * reactivity warnings, which is why mid-visit edit needs to be possible.
     */
    @Transactional
    public PatientResponse updateKnownAllergies(UUID patientId, String knownAllergies) {
        Patient patient = findPatientOrThrow(patientId);
        patient.setKnownAllergies(knownAllergies);
        patient = patientRepository.save(patient);
        log.info("Known allergies updated for patient {} (MRN {})",
                patient.getId(), patient.getMedicalRecordNumber());
        return PatientMapper.toResponse(patient);
    }

    /**
     * Replaces the patient's free-text chronic conditions. Same semantics
     * as updateKnownAllergies — full replacement, null is intentional.
     */
    @Transactional
    public PatientResponse updateChronicConditions(UUID patientId, String chronicConditions) {
        Patient patient = findPatientOrThrow(patientId);
        patient.setChronicConditions(chronicConditions);
        patient = patientRepository.save(patient);
        log.info("Chronic conditions updated for patient {} (MRN {})",
                patient.getId(), patient.getMedicalRecordNumber());
        return PatientMapper.toResponse(patient);
    }

    private String generateMRN(String hospitalCode) {
        return hospitalCode + "-" + mrnCounter.incrementAndGet();
    }

    private String generateVisitNumber(String hospitalCode) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long sequence = visitCounter.incrementAndGet();
        return String.format("V-%s-%s-%05d", hospitalCode, date, sequence);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * Resolve any provided Rwanda-location IDs into entity references and
     * set them on the patient. All five levels are independent inputs;
     * the caller may supply any subset (e.g. only provinceId+districtId
     * when the user knows that much). An ID that doesn't resolve is
     * silently dropped — the rest of the registration must succeed
     * because location is supplemental, not blocking. The error is
     * logged so an operator can diagnose stale IDs from a partially
     * loaded CSV.
     */
    private void applyStructuredLocation(
            Patient patient,
            UUID provinceId, UUID districtId,
            UUID sectorId, UUID cellId, UUID villageId) {
        if (provinceId != null) {
            rwProvinceRepository.findById(provinceId).ifPresentOrElse(
                    patient::setProvince,
                    () -> log.warn("[patient.location] unknown province id {}", provinceId));
        }
        if (districtId != null) {
            rwDistrictRepository.findById(districtId).ifPresentOrElse(
                    patient::setDistrict,
                    () -> log.warn("[patient.location] unknown district id {}", districtId));
        }
        if (sectorId != null) {
            rwSectorRepository.findById(sectorId).ifPresentOrElse(
                    patient::setSector,
                    () -> log.warn("[patient.location] unknown sector id {}", sectorId));
        }
        if (cellId != null) {
            rwCellRepository.findById(cellId).ifPresentOrElse(
                    patient::setCell,
                    () -> log.warn("[patient.location] unknown cell id {}", cellId));
        }
        if (villageId != null) {
            rwVillageRepository.findById(villageId).ifPresentOrElse(
                    patient::setVillage,
                    () -> log.warn("[patient.location] unknown village id {}", villageId));
        }
    }
}
