package com.smartTriage.smartTriage_server.module.patient.mapper;

import com.smartTriage.smartTriage_server.common.enums.PregnancyStatus;
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
                // Clinical-safety default. recorded_at stays null — no clinician
                // has affirmed the status yet; the timestamp must not lie about
                // provenance. Updated when a clinician explicitly sets it.
                .pregnancyStatus(PregnancyStatus.defaultFor(request.getGender()))
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
                .medicalRecordNumber(patient.getMedicalRecordNumber())
                .phoneNumber(patient.getPhoneNumber())
                .address(patient.getAddress())
                .emergencyContactName(patient.getEmergencyContactName())
                .emergencyContactPhone(patient.getEmergencyContactPhone())
                .guardianName(patient.getGuardianName())
                .guardianPhone(patient.getGuardianPhone())
                .guardianRelationship(patient.getGuardianRelationship())
                .guardianNationalId(patient.getGuardianNationalId())
                .bloodType(patient.getBloodType())
                .knownAllergies(patient.getKnownAllergies())
                .chronicConditions(patient.getChronicConditions())
                .pregnancyStatus(patient.getPregnancyStatus())
                .pregnancyStatusRecordedAt(patient.getPregnancyStatusRecordedAt())
                .hospitalId(patient.getHospital().getId())
                // Direct Resus placeholder (V28)
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
}
