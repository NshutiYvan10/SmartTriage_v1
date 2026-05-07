package com.smartTriage.smartTriage_server.module.triage.dto;

import com.smartTriage.smartTriage_server.common.enums.EdZone;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Result of a Direct Resus Admission. Returned to the frontend so the UI
 * can pick the right next-step state:
 *
 * <ul>
 *   <li><b>Bed assigned</b>: {@code bedId/bedCode} set, {@code overflow=false}.
 *       UI shows "Patient placed in bed X". Done.</li>
 *   <li><b>Resus full / overflow</b>: {@code overflow=true},
 *       {@code transferCandidates} populated. UI shows the transfer
 *       prompt — pick someone to move out so this patient can take
 *       their bed. The patient is admitted regardless.</li>
 * </ul>
 *
 * <p>For unidentified patients, {@code placeholderLabel} is set ("Alpha",
 * "Bravo-2") so the UI can render "Unknown Alpha (child)" or similar.
 * The {@code identityRequired} flag tells the UI to surface the
 * "Set Patient Identity" CTA.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DirectResusAdmissionResponse {

    /** The newly created visit. */
    private UUID visitId;
    private String visitNumber;

    /** The patient (existing or freshly created placeholder). */
    private UUID patientId;
    private String patientFirstName;
    private String patientLastName;
    private boolean isUnidentified;

    /** Phonetic placeholder, if the patient was created as unidentified. */
    private String placeholderLabel;

    /**
     * The auto-RED triage record created for this admission. Has minimal
     * clinical content; the resus team back-fills it via the standard
     * retriage path once the patient is stabilised.
     */
    private UUID triageRecordId;

    /**
     * Bed the patient was placed in. NULL when {@code overflow=true}
     * (no bed was free).
     */
    private UUID bedId;
    private String bedCode;
    private EdZone bedZone;
    private boolean bedHasMonitor;

    /**
     * TRUE when no RESUS bed was available at admission time. The patient
     * is still admitted (clinical care does not wait); the UI surfaces
     * {@link #transferCandidates} so the charge nurse can free up a bed.
     */
    private boolean overflow;

    /**
     * Lowest-acuity current resus occupants, ranked as transfer-out
     * candidates so the charge nurse can free up a bed. Only populated
     * when {@code overflow=true}. Empty when all current RESUS occupants
     * are still RED — in that case the team must escalate manually.
     */
    private List<TransferCandidate> transferCandidates;

    /**
     * TRUE when the patient was admitted as unidentified — the UI should
     * show the "Set Patient Identity" CTA on the visit page.
     */
    private boolean identityRequired;

    /**
     * Door clock anchor. For walk-in admissions this is now. For ambulance
     * pre-arrivals this is NULL until the nurse confirms physical arrival.
     */
    private Instant arrivalTime;

    private boolean ambulancePreArrival;

    /**
     * One-row description of an existing resus occupant who could be
     * moved out to make room. Ranked by:
     *   1. Re-triaged DOWN patients first (current category < admit cat)
     *   2. Then by time-in-bed (longest first)
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransferCandidate {
        private UUID visitId;
        private String visitNumber;
        private UUID bedId;
        private String bedCode;
        private String patientDisplayName;
        private String currentCategory;       // RED / ORANGE / YELLOW
        private String admitCategory;         // category at original placement
        private Instant placedAt;
        private long minutesInBed;
        /** Suggested zone to transfer this patient to (ACUTE / GENERAL / PEDIATRIC). */
        private EdZone suggestedDestinationZone;
        /** Plain-English line the UI can render verbatim. */
        private String rationale;
    }
}
