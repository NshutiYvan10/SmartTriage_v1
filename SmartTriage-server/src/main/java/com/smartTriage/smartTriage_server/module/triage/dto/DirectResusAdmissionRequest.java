package com.smartTriage.smartTriage_server.module.triage.dto;

import com.smartTriage.smartTriage_server.common.enums.ArrivalMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Direct Resus Admission request — fired when a nurse declares a patient
 * RED by clinical eye and skips the standard triage form. The resus team
 * starts work immediately; paperwork is back-filled later.
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>Existing patient</b>: {@code patientId} set. Used when the
 *       nurse can identify the patient (returning visitor, family present,
 *       ID found at the door).</li>
 *   <li><b>Unidentified</b>: {@code patientId} null + {@code hospitalId}
 *       set. The system creates a placeholder Patient ("Unknown Alpha"
 *       etc.) under the given hospital. Identity is resolved later via
 *       {@code POST /api/v1/patients/{id}/resolve-identity}.</li>
 * </ul>
 *
 * <p>For ambulance call-aheads, set {@code ambulancePreArrival=true}.
 * The visit is created and the resus team alerted, but the door clock
 * does not start until {@code POST /api/v1/admissions/{visitId}/confirm-arrival}
 * is called when the patient physically rolls in.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DirectResusAdmissionRequest {

    /**
     * Existing patient ID. Leave null for an unidentified arrival —
     * the system will create a placeholder. If set, hospitalId may
     * be omitted (we read it from the patient).
     */
    private UUID patientId;

    /**
     * Required when {@code patientId} is null (unidentified arrival).
     * Determines which hospital the placeholder Patient is created under
     * and which RESUS-zone bed is searched.
     */
    private UUID hospitalId;

    /**
     * One short clinical phrase capturing why this is Direct Resus
     * ("cardiac arrest", "GSW to chest", "severe airway compromise").
     * Required — every Direct Resus admission needs an audit-defensible
     * reason. This becomes the visit's chiefComplaint and the
     * triage record's decisionPath.
     */
    @NotBlank(message = "Reason is required for Direct Resus Admission")
    @Size(max = 500)
    private String reason;

    /**
     * Whether this is a pediatric patient (under 13). Affects bed
     * routing (PEDIATRIC zone fallback), pediatric kit prep, and the
     * "(child)" marker on the placeholder display name.
     */
    @Builder.Default
    private boolean isPediatric = false;

    /**
     * How the patient arrived. Optional; defaults to WALK_IN if not
     * specified. Note: ambulance pre-arrival uses
     * {@code ambulancePreArrival=true} below — {@code arrivalMode}
     * still describes the physical mode.
     */
    private ArrivalMode arrivalMode;

    /**
     * TRUE for ambulance call-aheads where the patient has not yet
     * arrived. The visit is created with {@code arrivalConfirmedAt=NULL};
     * the door clock starts only on confirm-arrival.
     */
    @Builder.Default
    private boolean ambulancePreArrival = false;

    /**
     * Optional: free-text notes from the receiving nurse (pre-hospital
     * vitals, drugs given, ETA). Stored on the visit as initial
     * clinical notes; safe to omit.
     */
    @Size(max = 2000)
    private String preArrivalNotes;

    /**
     * Required only when patientId is null. Used to populate
     * gender on the placeholder Patient when known by clinical eye
     * (helps med-dosing decisions later).
     */
    private String estimatedGender;
}
