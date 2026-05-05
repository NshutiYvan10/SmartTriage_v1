package com.smartTriage.smartTriage_server.module.patient.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartTriage.smartTriage_server.common.enums.Gender;
import com.smartTriage.smartTriage_server.common.enums.MatchType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A single candidate row in the response of a patient-lookup query.
 *
 * The intent is to render in the triage UI as a compact card that the nurse
 * can scan — display name, DOB, MRN, when this patient was last seen, and
 * <em>why</em> they showed up in the result set ({@link #matchType} +
 * {@link #confidence}). The nurse picks one or chooses "register new".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatientLookupCandidate {

    private UUID patientId;
    private String medicalRecordNumber;
    private String firstName;
    private String lastName;
    private LocalDate dateOfBirth;
    private Integer ageInYears;

    /**
     * See note on {@code PatientResponse.isPediatric} for why @JsonProperty
     * is required to keep the wire field name stable across Jackson defaults.
     */
    @JsonProperty("isPediatric")
    private boolean isPediatric;

    private Gender gender;

    /** Last-4 of NID — full NID is omitted from candidate cards on purpose. */
    private String nationalIdLast4;

    /** Most recent active-visit arrival time, null if the patient has no visits. */
    private Instant lastVisitAt;

    private UUID hospitalId;

    /** Which matcher fired for this candidate. */
    private MatchType matchType;

    /**
     * 0.00 – 1.00. 1.00 = deterministic exact identifier match (NID,
     * passport, birth-cert, MRN). Lower values are fuzzy matches.
     */
    private double confidence;
}
