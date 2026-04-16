package com.smartTriage.smartTriage_server.module.patient.dto;

import com.smartTriage.smartTriage_server.common.enums.Gender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePatientRequest {

    @NotBlank(message = "First name is required")
    @Size(max = 100)
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(max = 100)
    private String lastName;

    private LocalDate dateOfBirth;
    private Gender gender;

    @Size(max = 30)
    private String nationalId;

    @Size(max = 20)
    private String phoneNumber;

    private String address;
    private String emergencyContactName;
    private String emergencyContactPhone;
    private String bloodType;
    private String knownAllergies;
    private String chronicConditions;

    @NotNull(message = "Hospital ID is required")
    private UUID hospitalId;
}
