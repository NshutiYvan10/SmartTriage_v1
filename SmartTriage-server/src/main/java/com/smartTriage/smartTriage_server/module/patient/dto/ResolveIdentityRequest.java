package com.smartTriage.smartTriage_server.module.patient.dto;

import com.smartTriage.smartTriage_server.common.enums.Gender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Request to resolve an unidentified patient's identity (Direct Resus
 * follow-up, V28).
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>Type real identity</b>: caller provides firstName + lastName
 *       and optionally other fields. The placeholder Patient is updated
 *       in place — its UUID is preserved so all downstream references
 *       (visit, triage record, bed placement, alerts) remain valid.</li>
 *   <li><b>Merge into existing</b>: caller sets {@code mergeIntoPatientId}
 *       to an MPI match. All visits attached to the placeholder are
 *       re-pointed at the existing patient and the placeholder is
 *       soft-deleted. Used when the patient already has a record from
 *       a previous visit.</li>
 * </ul>
 *
 * <p>Either firstName/lastName OR mergeIntoPatientId is required — not
 * both. The controller validates this.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResolveIdentityRequest {

    /**
     * Real first name. Required unless {@code mergeIntoPatientId} is set.
     * If both are provided, mergeIntoPatientId wins and these are
     * ignored.
     */
    @Size(max = 100)
    private String firstName;

    @Size(max = 100)
    private String lastName;

    private LocalDate dateOfBirth;
    private Gender gender;

    @Size(max = 30)
    private String nationalId;

    @Size(max = 20)
    private String phoneNumber;

    @Size(max = 500)
    private String address;

    /**
     * Optional MPI match. If set, all visits/data on the placeholder
     * patient are merged into this existing patient and the placeholder
     * is soft-deleted. Use this when the patient was already registered
     * from a prior visit.
     */
    private UUID mergeIntoPatientId;

    /**
     * Optional one-line note for the audit trail
     * ("Family arrived with ID", "Patient woke up and gave name").
     * Stored on the resolved Patient.
     */
    @Size(max = 500)
    private String resolutionNote;
}
