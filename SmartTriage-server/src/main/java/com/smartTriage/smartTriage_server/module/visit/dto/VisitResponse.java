package com.smartTriage.smartTriage_server.module.visit.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartTriage.smartTriage_server.common.enums.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisitResponse {

    private UUID id;
    private String visitNumber;
    private UUID patientId;
    private String patientName;
    /**
     * Identity fields that travel with every visit-list payload so the
     * triage queue, patients list, monitoring grid, etc. can render age
     * and gender correctly without an N+1 fetch per row. The list
     * mapper on the frontend previously hardcoded `age: 0` and
     * `gender: 'MALE'` because these weren't on the wire — that's the
     * "0mo · M" rendering the user saw on Luna Gisa even though her
     * actual DOB was stored correctly. Carrying DOB rather than a
     * pre-computed age lets the frontend render months-granular ages
     * for infants and stays correct as time passes between fetches.
     */
    private LocalDate patientDateOfBirth;
    private Gender patientGender;
    private UUID hospitalId;
    private ArrivalMode arrivalMode;
    private Instant arrivalTime;
    private String chiefComplaint;
    private VisitStatus status;
    private TriageCategory currentTriageCategory;
    private Integer currentTewsScore;
    private Instant triageTime;
    private Instant assessmentStartTime;
    private DispositionType dispositionType;
    private Instant dispositionTime;
    private String dispositionNotes;
    private String dispositionDestinationWard;
    private String dispositionReceivingFacility;
    private String referringFacility;
    @JsonProperty("isPediatric")
    private boolean isPediatric;
    private int retriageCount;
    /** Phase 1 zone routing — canonical zone the patient is currently in. */
    private EdZone currentEdZone;
    /**
     * Human bed label (Bed.code, e.g. "A-12") of the patient's current
     * bed; NULL when no bed is assigned. Denormalised onto the list
     * payload — alongside {@link #currentEdZone} — so every patient row
     * (triage queue, patients list, monitoring grid, doctor workspace)
     * can show WHERE the patient is without an N+1 fetch per row.
     */
    private String currentBedLabel;
    /** Doctor of record (soft binding); null until first clinical action. */
    private UUID primaryClinicianId;
    private String primaryClinicianName;

    // ── Direct Resus Admission flags (V44) ──
    /**
     * TRUE when the visit was admitted to RESUS but no bed was available.
     * Frontend renders the resus-overflow banner + transfer prompt.
     */
    @JsonProperty("pendingResusOverflow")
    private boolean pendingResusOverflow;

    /**
     * TRUE when this visit was created from an ambulance call-ahead
     * before the patient physically arrived. Door clock has not started
     * unless arrivalConfirmedAt is non-null.
     */
    @JsonProperty("ambulancePreArrival")
    private boolean ambulancePreArrival;

    /**
     * For ambulance pre-arrival visits: when the patient was confirmed
     * to have physically arrived. NULL until confirmed. Used as the
     * door-clock anchor for arrival-time metrics.
     */
    private Instant arrivalConfirmedAt;

    /** Phase 1 EMS — link to the pre-hospital run record. Null for walk-ins. */
    private java.util.UUID emsRunId;

    /** Paramedic's field triage call. Authoritative ED triage is on TriageRecord. */
    private String fieldTriageCategory;

    /** When the ED nurse must re-triage by. Powers the 15-min alert. */
    private Instant edRetriageDueAt;

    private Instant createdAt;
    private Instant updatedAt;

    // ── Shift-handoff priority signals ──
    // Aggregate counts so the frontend can render at-a-glance priority
    // badges on patient cards (Doctor Workspace, Monitoring, dashboard
    // lists) without an N+1 fetch per card. Computed by a single
    // batched query when an active-visits list is loaded; not
    // populated on individual visit-by-id reads (would be wasteful
    // for the detail page that already loads the full collections).

    /**
     * Investigations on this visit with status ORDERED or
     * SPECIMEN_COLLECTED — i.e., labs that have been requested but
     * have not yet returned a result. The most-missed handoff signal:
     * a CBC ordered at 14:22 by the day team that is still pending at
     * 19:00 when the night doctor takes over.
     */
    private Integer pendingInvestigationsCount;

    /**
     * Investigations whose result came back in the last 4 hours but
     * are still flagged abnormal/critical and not yet acknowledged.
     * Distinct from "pending" because the inheriting doctor has a
     * different action: read it, decide, ack.
     */
    private Integer unacknowledgedCriticalResultsCount;

    /**
     * Medications with status PRESCRIBED but no administeredAt — i.e.,
     * orders waiting on nursing administration. Surfaced as a badge so
     * an inheriting doctor sees "3 meds awaiting administration"
     * without opening the medication tab.
     */
    private Integer pendingMedicationsCount;

    /**
     * True when the visit currently has an open ICU escalation in any
     * non-terminal state (REQUESTED, ICU_NOTIFIED, ICU_RESPONDED,
     * BED_ASSIGNED). Lets the doctor card render a red "ICU pending"
     * badge so an outstanding escalation cannot be lost across a shift
     * boundary.
     */
    private Boolean hasOpenIcuEscalation;
}
