package com.smartTriage.smartTriage_server.module.lab.dto;

import com.smartTriage.smartTriage_server.common.enums.EdZone;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A patient the LAB is actively working — the row shape for the scoped
 * "Lab Patients" view that REPLACES full hospital-registry access for a
 * LAB_TECHNICIAN.
 *
 * <p>Privacy by construction: this DTO carries only what a lab tech needs to
 * connect a specimen/result to the right patient — display name, visit number,
 * current location, and the counts of outstanding work. It deliberately OMITS
 * the full-registry PHI (national ID, phone, address, emergency contacts, blood
 * type, allergies, clinical history) that {@code PatientResponse} exposes and
 * that a lab tech has no need to see.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LabPatientSummaryResponse {

    private UUID visitId;
    // NB: no patientId — the scoped view keys navigation by visitId only. Exposing the
    // patient UUID would hand a lab tech an enumeration key into the by-id endpoints.
    /** Display name (or "Unknown Alpha" style placeholder for an unidentified patient). */
    private String patientName;
    private String visitNumber;
    private EdZone currentZone;
    private String currentBedLabel;

    /** Pending/in-progress lab orders (specimen → result) for this patient. */
    private int activeLabCount;
    /** Ordered/in-progress imaging or ECG studies for this patient. */
    private int activeImagingCount;
    /** Resulted-but-unacknowledged CRITICAL lab values — the most urgent. */
    private int criticalUnackCount;

    /** Most recent order time across this patient's lab/imaging work — for newest-first sort. */
    private Instant lastActivityAt;
}
