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
                .nationalId(request.getNationalId())
                .phoneNumber(request.getPhoneNumber())
                .address(request.getAddress())
                .emergencyContactName(request.getEmergencyContactName())
                .emergencyContactPhone(request.getEmergencyContactPhone())
                .bloodType(request.getBloodType())
                .knownAllergies(request.getKnownAllergies())
                .chronicConditions(request.getChronicConditions())
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
                .bloodType(patient.getBloodType())
                .knownAllergies(patient.getKnownAllergies())
                .chronicConditions(patient.getChronicConditions())
                .hospitalId(patient.getHospital().getId())
                .createdAt(patient.getCreatedAt())
                .updatedAt(patient.getUpdatedAt())
                .build();
    }
}
