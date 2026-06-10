package com.smartTriage.smartTriage_server.module.patient.mapper;

import com.smartTriage.smartTriage_server.module.patient.dto.CreatePatientRequest;
import com.smartTriage.smartTriage_server.module.patient.dto.PatientResponse;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;

public final class PatientMapper {

    private PatientMapper() {}

    public static Patient toEntity(CreatePatientRequest request) {
        return Patient.builder()
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
                .weightKg(request.getWeightKg())
                .guardianNationalId(blankToNull(request.getGuardianNationalId()))
                .guardianPhone(blankToNull(request.getGuardianPhone()))
                .guardianName(blankToNull(request.getGuardianName()))
                .guardianRelationship(blankToNull(request.getGuardianRelationship()))
                .build();
    }

    public static PatientResponse toResponse(Patient patient) {
        return PatientResponse.builder()
                .id(patient.getId())
                .firstName(patient.getFirstName())
                .lastName(patient.getLastName())
                .dateOfBirth(patient.getDateOfBirth())
                .ageInYears(patient.getAgeInYears())
                .isPediatric(patient.isPediatric())
                .gender(patient.getGender())
                .nationalId(patient.getNationalId())
                .passportNumber(patient.getPassportNumber())
                .birthCertificateNumber(patient.getBirthCertificateNumber())
                .medicalRecordNumber(patient.getMedicalRecordNumber())
                .phoneNumber(patient.getPhoneNumber())
                .address(patient.getAddress())
                .emergencyContactName(patient.getEmergencyContactName())
                .emergencyContactPhone(patient.getEmergencyContactPhone())
                .bloodType(patient.getBloodType())
                .knownAllergies(patient.getKnownAllergies())
                .chronicConditions(patient.getChronicConditions())
                .weightKg(patient.getWeightKg())
                .pregnancyStatus(patient.getPregnancyStatus())
                .pregnancyStatusRecordedAt(patient.getPregnancyStatusRecordedAt())
                .guardianNationalId(patient.getGuardianNationalId())
                .guardianPhone(patient.getGuardianPhone())
                .guardianName(patient.getGuardianName())
                .guardianRelationship(patient.getGuardianRelationship())
                .hospitalId(patient.getHospital().getId())
                // Direct Resus placeholder (V44)
                .isUnidentified(patient.isUnidentified())
                .placeholderLabel(patient.getPlaceholderLabel())
                .placeholderAssignedAt(patient.getPlaceholderAssignedAt())
                .identifiedAt(patient.getIdentifiedAt())
                .identifiedByName(patient.getIdentifiedBy() != null
                        ? formatUserDisplayName(patient.getIdentifiedBy().getFirstName(),
                                                patient.getIdentifiedBy().getLastName(),
                                                patient.getIdentifiedBy().getUsername())
                        : null)
                .createdAt(patient.getCreatedAt())
                .updatedAt(patient.getUpdatedAt())
                .build();
    }

    private static String formatUserDisplayName(String firstName, String lastName, String username) {
        String full = ((firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "")).trim();
        return full.isEmpty() ? username : full;
    }

    /**
     * Empty strings from form posts must become NULL on the way into the DB,
     * otherwise our partial-unique indexes (which fire on
     * `WHERE nid IS NOT NULL`) treat "" as a real value and will reject the
     * second blank registration as a duplicate.
     */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
