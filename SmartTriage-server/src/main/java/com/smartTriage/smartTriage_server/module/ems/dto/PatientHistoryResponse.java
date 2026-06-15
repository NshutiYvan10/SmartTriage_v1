package com.smartTriage.smartTriage_server.module.ems.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Quick patient context for the paramedic when a run is linked to a known
 * patient (matched by ID at preregister). Surfaces the safety-critical
 * facts a crew wants before they arrive: allergies, chronic conditions,
 * blood type and whether the patient has been seen here before.
 *
 * <p>For an unidentified placeholder there is nothing to show —
 * {@code known=false} and the clinical fields stay null.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatientHistoryResponse {

    /** True when the run is linked to a real (identified) patient record. */
    private boolean known;

    /** Display name (real name, or "Unknown Alpha" for a placeholder). */
    private String displayName;

    private boolean unidentified;

    private String knownAllergies;
    private String chronicConditions;
    private String bloodType;

    /** Count of the patient's prior active visits (excludes the current one). */
    private long priorVisitCount;

    /** ISO instant of the most recent prior visit, if any. */
    private String lastVisitAt;
}
