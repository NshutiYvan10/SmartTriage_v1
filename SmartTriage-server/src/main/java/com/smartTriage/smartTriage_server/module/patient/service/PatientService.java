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

    // Simple MRN counter — in production, this would use a database sequence
    private static final AtomicLong mrnCounter = new AtomicLong(100000);
    private static final AtomicLong visitCounter = new AtomicLong(0);

    @Transactional
    public PatientResponse createPatient(CreatePatientRequest request) {
        Hospital hospital = hospitalService.findHospitalOrThrow(request.getHospitalId());

        // Duplicate detection by national ID
        if (request.getNationalId() != null && !request.getNationalId().isBlank()) {
            patientRepository.findByNationalIdAndHospitalIdAndIsActiveTrue(
                    request.getNationalId(), hospital.getId()).ifPresent(existing -> {
                        throw new DuplicateResourceException("Patient", "nationalId", request.getNationalId());
                    });
        }

        Patient patient = PatientMapper.toEntity(request);
        patient.setHospital(hospital);
        patient.setMedicalRecordNumber(generateMRN(hospital.getHospitalCode()));

        patient = patientRepository.save(patient);

        log.info("Patient registered: {} {} (MRN: {}) at hospital {}",
                patient.getFirstName(), patient.getLastName(),
                patient.getMedicalRecordNumber(), hospital.getHospitalCode());

        return PatientMapper.toResponse(patient);
    }

    /**
     * Atomic registration — creates both Patient and Visit in a single
     * database transaction. If either step fails the entire operation
     * rolls back, preventing orphaned patient records with no visit.
     */
    @Transactional
    public RegisterPatientResponse registerPatientWithVisit(RegisterPatientRequest request) {
        Hospital hospital = hospitalService.findHospitalOrThrow(request.getHospitalId());

        // Duplicate detection by national ID
        if (request.getNationalId() != null && !request.getNationalId().isBlank()) {
            patientRepository.findByNationalIdAndHospitalIdAndIsActiveTrue(
                    request.getNationalId(), hospital.getId()).ifPresent(existing -> {
                        throw new DuplicateResourceException("Patient", "nationalId", request.getNationalId());
                    });
        }

        // 1. Create Patient
        Patient patient = Patient.builder()
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .dateOfBirth(request.getDateOfBirth())
                .gender(request.getGender())
                .nationalId(request.getNationalId())
                .phoneNumber(request.getPhoneNumber())
                .address(request.getAddress())
                .emergencyContactName(request.getEmergencyContactName())
                .emergencyContactPhone(request.getEmergencyContactPhone())
                .guardianName(request.getGuardianName())
                .guardianPhone(request.getGuardianPhone())
                .guardianRelationship(request.getGuardianRelationship())
                .guardianNationalId(request.getGuardianNationalId())
                .bloodType(request.getBloodType())
                .knownAllergies(request.getKnownAllergies())
                .chronicConditions(request.getChronicConditions())
                // Clinical-safety default — see PregnancyStatus.defaultFor.
                // recorded_at stays null until a clinician explicitly affirms.
                .pregnancyStatus(PregnancyStatus.defaultFor(request.getGender()))
                .build();
        patient.setHospital(hospital);
        patient.setMedicalRecordNumber(generateMRN(hospital.getHospitalCode()));
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

    @Transactional
    public PatientResponse updatePregnancyStatus(UUID id, PregnancyStatus pregnancyStatus) {
        Patient patient = findPatientOrThrow(id);
        patient.setPregnancyStatus(pregnancyStatus);
        patient.setPregnancyStatusRecordedAt(Instant.now());
        patient = patientRepository.save(patient);
        log.info("Pregnancy status updated for patient {} to {}", id, pregnancyStatus);
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
    public PatientResponse updateKnownAllergies(UUID id, String knownAllergies) {
        Patient patient = findPatientOrThrow(id);
        patient.setKnownAllergies(knownAllergies);
        patient = patientRepository.save(patient);
        log.info("Known allergies updated for patient {}", id);
        return PatientMapper.toResponse(patient);
    }

    /**
     * Replaces the patient's free-text chronic conditions. Same semantics
     * as updateKnownAllergies — full replacement, null is intentional.
     */
    @Transactional
    public PatientResponse updateChronicConditions(UUID id, String chronicConditions) {
        Patient patient = findPatientOrThrow(id);
        patient.setChronicConditions(chronicConditions);
        patient = patientRepository.save(patient);
        log.info("Chronic conditions updated for patient {}", id);
        return PatientMapper.toResponse(patient);
    }

    public Patient findPatientOrThrow(UUID id) {
        return patientRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Patient", "id", id));
    }

    private String generateMRN(String hospitalCode) {
        return hospitalCode + "-" + mrnCounter.incrementAndGet();
    }

    private String generateVisitNumber(String hospitalCode) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long sequence = visitCounter.incrementAndGet();
        return String.format("V-%s-%s-%05d", hospitalCode, date, sequence);
    }
}
