package com.smartTriage.smartTriage_server.module.patient.dto;

import com.smartTriage.smartTriage_server.common.enums.Gender;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of the REGISTRAR-only global patient registry (system-wide search).
 *
 * <p>Each row is one hospital's local record; rows sharing {@code identityId}
 * are the same person registered at several hospitals (the frontend groups
 * them). {@code hospitalName} is the record's originating hospital — the
 * "first registered at CHUK" context the desk needs before reusing a record.
 * {@code hasOpenVisitAtMyHospital} powers the "already has an open visit here"
 * badge so the registrar is never confused about whether to start a new visit.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GlobalPatientRow {

    private UUID patientId;
    private String firstName;
    private String lastName;
    private LocalDate dateOfBirth;
    private Gender gender;
    private String nationalId;
    private String phoneNumber;
    private String medicalRecordNumber;

    /** Originating hospital of THIS record. */
    private UUID hospitalId;
    private String hospitalName;
    private String hospitalCode;

    /** When this record was first registered at that hospital. */
    private Instant registeredAt;

    /** Shared cross-hospital identity (rows with the same id are one person). */
    private UUID identityId;
    private boolean hasRfidCard;

    private boolean unidentified;

    /** True when the record is local to the searching registrar's hospital. */
    private boolean localToMyHospital;

    /** True when this patient already has an OPEN visit at the searching registrar's hospital. */
    private boolean hasOpenVisitAtMyHospital;

    /**
     * The open visit's id when {@link #hasOpenVisitAtMyHospital} is true —
     * lets the registry's "go to it" action open the visit chart directly,
     * even when the open visit belongs to the person's LOCAL record and this
     * row is their record from another hospital.
     */
    private UUID openVisitIdAtMyHospital;
}
