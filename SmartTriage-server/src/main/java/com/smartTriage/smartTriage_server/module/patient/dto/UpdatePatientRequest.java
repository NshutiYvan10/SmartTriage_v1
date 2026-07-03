package com.smartTriage.smartTriage_server.module.patient.dto;

import com.smartTriage.smartTriage_server.common.enums.Gender;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Registrar demographic-correction request — edit an existing patient's identity/contact
 * fields to fix data-entry errors (misspelled name, wrong DOB, transposed national ID,
 * updated phone). PARTIAL update: only NON-NULL fields are applied; a null field means
 * "leave unchanged" (send an empty string to explicitly clear an optional field).
 *
 * <p>RFID card reassignment is deliberately NOT here — it lives on its own audited endpoint
 * ({@code PUT /iot/rfid/replace-card}) because the card is a shared-identity anchor with its
 * own conflict rules. This request only touches the hospital-local Patient row (+ re-links the
 * shared identity if the national ID changes).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdatePatientRequest {

    @Size(max = 100) private String firstName;
    @Size(max = 100) private String lastName;
    private LocalDate dateOfBirth;
    private Gender gender;

    @Size(max = 30) private String nationalId;
    @Size(max = 30) private String passportNumber;
    @Size(max = 30) private String birthCertificateNumber;

    @Size(max = 20) private String phoneNumber;
    private String address;
    @Size(max = 200) private String emergencyContactName;
    @Size(max = 20)  private String emergencyContactPhone;
    @Size(max = 5)   private String bloodType;
}
