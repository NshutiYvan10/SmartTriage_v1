package com.smartTriage.smartTriage_server.module.triage.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.ArrivalMode;
import com.smartTriage.smartTriage_server.common.enums.BedStatus;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.Gender;
import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.common.enums.VisitStatus;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.mapper.ClinicalAlertMapper;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.bed.dto.PlacePatientRequest;
import com.smartTriage.smartTriage_server.module.bed.entity.Bed;
import com.smartTriage.smartTriage_server.module.bed.repository.BedRepository;
import com.smartTriage.smartTriage_server.module.bed.service.BedService;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.service.HospitalService;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientRepository;
import com.smartTriage.smartTriage_server.module.patient.service.UnidentifiedPatientNameService;
import com.smartTriage.smartTriage_server.module.triage.dto.DirectResusAdmissionRequest;
import com.smartTriage.smartTriage_server.module.triage.dto.DirectResusAdmissionResponse;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.triage.repository.TriageRecordRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import com.smartTriage.smartTriage_server.module.visit.service.VisitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * DirectResusService — the Red-patient bypass admission pipeline.
 *
 * <p>In a real ED, when a patient arrives in obvious extremis (cardiac
 * arrest at the door, severe trauma, obstructed airway), the nurse does
 * not stop to fill a triage form. They take the patient straight to the
 * resuscitation bay and clinical intervention starts immediately.
 * This service mirrors that reality.
 *
 * <p>One {@link #admit} call performs the full atomic admission:
 * <ol>
 *   <li>Resolve identity — either an existing patient or a freshly
 *       created NATO-phonetic placeholder ("Unknown Alpha", ...).</li>
 *   <li>Create the {@link Visit} with status {@code TRIAGED} and
 *       {@code currentTriageCategory = RED} — bypassing the normal
 *       REGISTERED → AWAITING_TRIAGE → TRIAGED progression.</li>
 *   <li>Create an auto-RED {@link TriageRecord} with
 *       {@code isSystemTriggered=true} and a defensible
 *       {@code decisionPath}. Clinical fields are zero/false; the
 *       resus team back-fills a real triage record retrospectively
 *       once the patient is stabilised.</li>
 *   <li>Find an available RESUS-zone bed (preferring monitored).
 *       If found, place the patient via {@link BedService#placePatient}
 *       — this also auto-opens the bed's IoT session.</li>
 *   <li>If no bed is available, mark the visit
 *       {@code pendingResusOverflow=true} and compute a ranked list of
 *       transfer-candidate occupants for the charge nurse to free
 *       space. The patient is admitted regardless — clinical care
 *       does not wait on bed availability.</li>
 *   <li>Raise a CRITICAL {@code DIRECT_RESUS_ADMISSION} alert fanned
 *       to the resus zone topic. Raise a CRITICAL
 *       {@code RESUS_OVERFLOW} alert if applicable.</li>
 *   <li>Publish WebSocket events so every connected dashboard
 *       reflects the change in real time.</li>
 * </ol>
 *
 * <p><b>Why one big transactional method?</b> Because every step except
 * the bed-placement is non-optional and must succeed together. If the
 * triage record can't be created, the visit must roll back too — we
 * cannot leave a half-admitted patient. Bed placement is the one step
 * that's allowed to "fail" gracefully into overflow mode; that branch
 * is handled inside the transaction without throwing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DirectResusService {

    private final PatientRepository patientRepository;
    private final VisitRepository visitRepository;
    private final TriageRecordRepository triageRecordRepository;
    private final BedRepository bedRepository;
    private final ClinicalAlertRepository alertRepository;
    private final HospitalService hospitalService;
    private final BedService bedService;
    private final VisitService visitService;
    private final UnidentifiedPatientNameService nameService;
    private final RealTimeEventPublisher eventPublisher;

    /**
     * Admit a Red patient straight to RESUS, bypassing the standard
     * triage form. See class Javadoc for the full step list. Returns
     * a {@link DirectResusAdmissionResponse} the frontend uses to drive
     * the next-step UI (success banner, transfer-candidate prompt,
     * identity-resolution CTA).
     */
    @Transactional
    public DirectResusAdmissionResponse admit(DirectResusAdmissionRequest request) {
        // ── 1. Validate inputs ────────────────────────────────────────
        if (request == null) {
            throw new ClinicalBusinessException("Request body is required for Direct Resus Admission");
        }
        if (request.getReason() == null || request.getReason().isBlank()) {
            throw new ClinicalBusinessException("A clinical reason is required for Direct Resus Admission");
        }
        if (request.getPatientId() == null && request.getHospitalId() == null) {
            throw new ClinicalBusinessException(
                    "Either patientId (existing patient) or hospitalId (unidentified arrival) is required");
        }

        User actor = resolveAuthenticatedUser().orElse(null);
        String actorName = actor != null ? formatActorName(actor) : "System";

        // ── 2. Resolve / create patient ───────────────────────────────
        Patient patient;
        Hospital hospital;
        boolean placeholderCreated = false;
        String placeholderLabel = null;

        if (request.getPatientId() != null) {
            patient = patientRepository.findByIdAndIsActiveTrue(request.getPatientId())
                    .orElseThrow(() -> new ResourceNotFoundException("Patient", "id", request.getPatientId()));
            hospital = patient.getHospital();
            // If caller also supplied hospitalId, sanity-check it matches the patient's
            if (request.getHospitalId() != null && !request.getHospitalId().equals(hospital.getId())) {
                throw new ClinicalBusinessException(
                        "Patient " + patient.getId() + " belongs to a different hospital than requested");
            }
        } else {
            hospital = hospitalService.findHospitalOrThrow(request.getHospitalId());
            UnidentifiedPatientNameService.PlaceholderLabel claimed = nameService.claimNext(hospital.getId());
            placeholderLabel = claimed.label();
            placeholderCreated = true;

            patient = Patient.builder()
                    .firstName("Unknown")
                    .lastName(placeholderLabel)
                    .hospital(hospital)
                    .gender(parseGenderSafely(request.getEstimatedGender()))
                    .isUnidentified(true)
                    .placeholderLabel(placeholderLabel)
                    .placeholderAssignedAt(Instant.now())
                    .build();
            patient = patientRepository.save(patient);

            log.info("[direct-resus] Created placeholder patient '{}' (id={}) at hospital {}",
                    UnidentifiedPatientNameService.buildDisplayName(placeholderLabel, request.isPediatric()),
                    patient.getId(),
                    hospital.getHospitalCode());
        }

        // ── 3. Create the Visit ───────────────────────────────────────
        Instant now = Instant.now();
        ArrivalMode arrivalMode = request.getArrivalMode() != null
                ? request.getArrivalMode()
                : (request.isAmbulancePreArrival() ? ArrivalMode.AMBULANCE : ArrivalMode.WALK_IN);

        // For pre-arrivals: arrivalTime is the call-ahead time (now);
        // arrivalConfirmedAt stays null until the patient physically arrives.
        // For walk-ins: arrivalTime = now and arrivalConfirmedAt = now both.
        Visit visit = Visit.builder()
                .patient(patient)
                .hospital(hospital)
                .visitNumber(visitService.nextVisitNumber(hospital.getHospitalCode()))
                .arrivalMode(arrivalMode)
                .arrivalTime(now)
                .arrivalConfirmedAt(request.isAmbulancePreArrival() ? null : now)
                .ambulancePreArrival(request.isAmbulancePreArrival())
                .chiefComplaint(buildChiefComplaint(request))
                .status(VisitStatus.TRIAGED)                // already triaged — by clinical eye
                .currentTriageCategory(TriageCategory.RED)
                .triageTime(now)
                .isPediatric(request.isPediatric() || patient.isPediatric())
                .pendingResusOverflow(false)                // set true below if overflow
                .build();
        visit = visitRepository.save(visit);

        // ── 4. Create the auto-RED TriageRecord ───────────────────────
        // Minimal clinical content — the resus team retrospectively
        // back-fills a richer record via the standard retriage path
        // once the patient is stabilised. The decisionPath here is the
        // anchor for any later QI / medico-legal review.
        String decisionPath = "DIRECT_RESUS_ADMISSION: " + request.getReason().trim()
                + " | declared by " + actorName
                + " at " + now;
        TriageRecord triageRecord = TriageRecord.builder()
                .visit(visit)
                .triagedBy(actor)
                .triageTime(now)
                .tewsScore(0)                               // not computed for Direct Resus
                .triageCategory(TriageCategory.RED)
                .isRetriage(false)
                .isSystemTriggered(true)                    // declared by clinical eye + system orchestration
                .decisionPath(decisionPath)
                .presentingComplaints(request.getReason().trim())
                .clinicalNotes(buildPreArrivalNotesText(request))
                .triageNurseName(actor != null ? formatActorName(actor) : null)
                .build();
        triageRecord = triageRecordRepository.save(triageRecord);

        log.info("[direct-resus] Visit {} admitted RED. patient={}, isPediatric={}, preArrival={}, reason='{}'",
                visit.getVisitNumber(),
                patient.getId(),
                visit.isPediatric(),
                visit.isAmbulancePreArrival(),
                request.getReason().trim());

        // ── 5. Try to place in a RESUS bed ────────────────────────────
        BedPlacementOutcome outcome = tryPlaceInResusBed(visit, hospital.getId(), actorName);

        // If overflow: persist the flag and compute transfer candidates
        List<DirectResusAdmissionResponse.TransferCandidate> candidates = List.of();
        if (outcome.overflow) {
            visit.setPendingResusOverflow(true);
            visit = visitRepository.save(visit);
            candidates = computeTransferCandidates(hospital.getId());
        }

        // ── 6. Raise CRITICAL alerts ──────────────────────────────────
        ClinicalAlert admissionAlert = buildDirectResusAlert(visit, request, placeholderLabel);
        admissionAlert = alertRepository.save(admissionAlert);
        eventPublisher.publishHospitalAlert(hospital.getId(), ClinicalAlertMapper.toResponse(admissionAlert));
        eventPublisher.publishZoneAlert(hospital.getId(), EdZone.RESUS, ClinicalAlertMapper.toResponse(admissionAlert));

        if (outcome.overflow) {
            ClinicalAlert overflowAlert = buildOverflowAlert(visit, candidates);
            overflowAlert = alertRepository.save(overflowAlert);
            eventPublisher.publishHospitalAlert(hospital.getId(), ClinicalAlertMapper.toResponse(overflowAlert));
            eventPublisher.publishZoneAlert(hospital.getId(), EdZone.RESUS, ClinicalAlertMapper.toResponse(overflowAlert));
        }

        // ── 7. Publish a triage-change event so dashboards refresh ────
        Map<String, Object> triageEvent = new HashMap<>();
        triageEvent.put("visitId", visit.getId().toString());
        triageEvent.put("category", "RED");
        triageEvent.put("source", "DIRECT_RESUS_ADMISSION");
        triageEvent.put("isSystemTriggered", true);
        eventPublisher.publishTriageChange(visit.getId(), triageEvent);

        // ── 8. Build response ─────────────────────────────────────────
        return DirectResusAdmissionResponse.builder()
                .visitId(visit.getId())
                .visitNumber(visit.getVisitNumber())
                .patientId(patient.getId())
                .patientFirstName(patient.getFirstName())
                .patientLastName(patient.getLastName())
                .isUnidentified(patient.isUnidentified())
                .placeholderLabel(placeholderCreated ? placeholderLabel : null)
                .triageRecordId(triageRecord.getId())
                .bedId(outcome.bed != null ? outcome.bed.getId() : null)
                .bedCode(outcome.bed != null ? outcome.bed.getCode() : null)
                .bedZone(outcome.bed != null ? outcome.bed.getZone() : null)
                .bedHasMonitor(outcome.bed != null && outcome.bed.isHasMonitor())
                .overflow(outcome.overflow)
                .transferCandidates(candidates)
                .identityRequired(patient.isUnidentified())
                .arrivalTime(visit.getArrivalConfirmedAt())  // null for pre-arrivals (door clock not started)
                .ambulancePreArrival(visit.isAmbulancePreArrival())
                .build();
    }

    /**
     * Attempt to place the visit in an available RESUS bed (preferring
     * monitored). If placement succeeds, returns the placed bed. If no
     * bed is available, or {@link BedService#placePatient} fails for any
     * reason (race condition, validation), returns overflow=true so the
     * admission still proceeds.
     */
    private BedPlacementOutcome tryPlaceInResusBed(Visit visit, UUID hospitalId, String actorName) {
        // Pediatric Direct Resus prefers RESUS first; if no resus bed,
        // pediatric arrest does not get demoted to PEDIATRIC ward — that
        // would lose the resus-team mobilisation. Overflow first, then
        // the human can decide.
        List<Bed> available = bedRepository.findAvailableInZone(hospitalId, EdZone.RESUS);
        if (available.isEmpty()) {
            log.warn("[direct-resus] No available RESUS beds at hospital {}. Patient admitted to overflow.",
                    hospitalId);
            return BedPlacementOutcome.asOverflow();
        }

        Bed chosen = available.stream()
                .filter(Bed::isHasMonitor)
                .findFirst()
                .orElse(available.get(0));

        try {
            PlacePatientRequest req = new PlacePatientRequest();
            req.setVisitId(visit.getId());
            bedService.placePatient(chosen.getId(), req, actorName);

            // Re-load bed to capture the post-placement state (status=OCCUPIED).
            Bed placed = bedRepository.findByIdAndIsActiveTrue(chosen.getId()).orElse(chosen);
            log.info("[direct-resus] Placed visit {} in RESUS bed {} (monitor={})",
                    visit.getVisitNumber(), placed.getCode(), placed.isHasMonitor());
            return BedPlacementOutcome.placed(placed);
        } catch (RuntimeException e) {
            // Most likely: a concurrent placement won the race for the bed.
            // Fall through to overflow — the patient is still admitted, the
            // charge nurse just gets a transfer prompt instead.
            log.warn("[direct-resus] Bed placement failed for visit {} on bed {}: {}. "
                            + "Falling through to overflow.",
                    visit.getVisitNumber(), chosen.getCode(), e.getMessage());
            return BedPlacementOutcome.asOverflow();
        }
    }

    /**
     * Compute the ranked transfer-candidate list when RESUS is full.
     * A candidate is any current RESUS occupant whose situation makes
     * them a reasonable move-out target. Ranked by:
     * <ol>
     *   <li>Re-triaged-DOWN patients first (currentCategory < the category
     *       at original placement — they're now stable enough for ACUTE
     *       or GENERAL).</li>
     *   <li>Then by time-in-bed (longest first — those who've been there
     *       a while are more likely to be ready to move).</li>
     * </ol>
     *
     * <p>The system surfaces the ranked list. The human picks. The system
     * does not transfer anyone automatically — bed transfer is a clinical
     * decision.
     */
    private List<DirectResusAdmissionResponse.TransferCandidate> computeTransferCandidates(UUID hospitalId) {
        List<Bed> resusBeds = bedRepository.findByHospitalAndZone(hospitalId, EdZone.RESUS).stream()
                .filter(b -> b.getStatus() == BedStatus.OCCUPIED && b.getCurrentVisit() != null)
                .toList();

        List<DirectResusAdmissionResponse.TransferCandidate> candidates = new ArrayList<>();
        Instant now = Instant.now();

        for (Bed bed : resusBeds) {
            Visit occupant = bed.getCurrentVisit();
            if (occupant == null) continue;

            TriageCategory current = occupant.getCurrentTriageCategory();
            // We don't have the original-placement category stored on the visit
            // (no admit_triage_category field). We approximate "down-triaged"
            // by checking retriageCount > 0 AND current is non-RED.
            boolean downTriaged = occupant.getRetriageCount() > 0
                    && current != null
                    && current != TriageCategory.RED;

            // Use updatedAt of the bed as a proxy for "time placed in this bed"
            // (set when bed.currentVisit was last assigned). Not perfectly
            // accurate but good enough for triage-out ranking.
            long minutesInBed = Duration.between(
                    bed.getUpdatedAt() != null ? bed.getUpdatedAt() : bed.getCreatedAt(),
                    now
            ).toMinutes();

            EdZone destZone = suggestDestinationZone(current, occupant.isPediatric());

            String rationale;
            if (downTriaged) {
                rationale = "Re-triaged to " + current + " — ready for " + (destZone != null ? destZone.name() : "step-down");
            } else if (current == TriageCategory.RED) {
                rationale = "Still RED — keep in resus unless team confirms transfer";
            } else {
                rationale = "Stable, has been in bed " + minutesInBed + " min";
            }

            candidates.add(DirectResusAdmissionResponse.TransferCandidate.builder()
                    .visitId(occupant.getId())
                    .visitNumber(occupant.getVisitNumber())
                    .bedId(bed.getId())
                    .bedCode(bed.getCode())
                    .patientDisplayName(buildPatientDisplayName(occupant))
                    .currentCategory(current != null ? current.name() : "UNKNOWN")
                    .admitCategory("RED")              // resus admissions are RED by definition
                    .placedAt(bed.getUpdatedAt())
                    .minutesInBed(minutesInBed)
                    .suggestedDestinationZone(destZone)
                    .rationale(rationale)
                    .build());
        }

        // Sort: down-triaged first (rationale starts with "Re-triaged"),
        // then by minutesInBed descending.
        candidates.sort(Comparator
                .<DirectResusAdmissionResponse.TransferCandidate>comparingInt(c ->
                        c.getRationale() != null && c.getRationale().startsWith("Re-triaged") ? 0 : 1)
                .thenComparing(Comparator.comparingLong(
                        DirectResusAdmissionResponse.TransferCandidate::getMinutesInBed).reversed()));

        return candidates;
    }

    /** Suggest a step-down zone for a re-triaged patient. */
    private EdZone suggestDestinationZone(TriageCategory current, boolean isPediatric) {
        if (current == null) return null;
        return switch (current) {
            case ORANGE -> isPediatric ? EdZone.PEDIATRIC : EdZone.ACUTE;
            case YELLOW -> isPediatric ? EdZone.PEDIATRIC : EdZone.GENERAL;
            case GREEN, BLUE -> EdZone.GENERAL;
            case RED -> null;  // shouldn't be transferred out
        };
    }

    /** Build a human-readable display for a Visit's patient. */
    private String buildPatientDisplayName(Visit visit) {
        if (visit.getPatient() == null) return "Unknown patient";
        Patient p = visit.getPatient();
        if (p.isUnidentified()) {
            return UnidentifiedPatientNameService.buildDisplayName(p.getPlaceholderLabel(), visit.isPediatric());
        }
        return ((p.getFirstName() != null ? p.getFirstName() : "") + " " +
                (p.getLastName() != null ? p.getLastName() : "")).trim();
    }

    /** Compose the visit's chief complaint from the admission reason + pre-arrival notes. */
    private String buildChiefComplaint(DirectResusAdmissionRequest request) {
        StringBuilder sb = new StringBuilder("[Direct Resus] ").append(request.getReason().trim());
        if (request.isAmbulancePreArrival()) {
            sb.append(" (ambulance pre-arrival)");
        }
        return sb.toString();
    }

    /** Compose the triage record's clinicalNotes from any pre-arrival info. */
    private String buildPreArrivalNotesText(DirectResusAdmissionRequest request) {
        if (request.getPreArrivalNotes() == null || request.getPreArrivalNotes().isBlank()) {
            return null;
        }
        return "Pre-arrival notes: " + request.getPreArrivalNotes().trim();
    }

    /** CRITICAL alert fanned to the resus zone the moment the admission lands. */
    private ClinicalAlert buildDirectResusAlert(Visit visit,
                                                DirectResusAdmissionRequest request,
                                                String placeholderLabel) {
        String displayName = visit.getPatient().isUnidentified()
                ? UnidentifiedPatientNameService.buildDisplayName(placeholderLabel, visit.isPediatric())
                : visit.getPatient().getFirstName() + " " + visit.getPatient().getLastName();

        String title = visit.isAmbulancePreArrival()
                ? "Ambulance pre-arrival — Direct Resus expected"
                : "Direct Resus Admission";

        String message = displayName + " — " + request.getReason().trim() + ". "
                + (visit.isAmbulancePreArrival()
                        ? "Patient inbound by ambulance. Resus team prepare."
                        : "Resus team to attend immediately.");

        return ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.DIRECT_RESUS_ADMISSION)
                .severity(AlertSeverity.CRITICAL)
                .title(title)
                .message(message)
                .targetZone(EdZone.RESUS)
                .escalationTier(1)
                .autoGenerated(true)
                .build();
    }

    /** CRITICAL alert when the new admission has no available RESUS bed. */
    private ClinicalAlert buildOverflowAlert(Visit visit,
                                             List<DirectResusAdmissionResponse.TransferCandidate> candidates) {
        StringBuilder msg = new StringBuilder("RESUS at capacity. New Direct Resus admission ")
                .append(visit.getVisitNumber())
                .append(" placed in overflow. ");
        if (candidates.isEmpty()) {
            msg.append("All current resus occupants are still RED — escalate manually.");
        } else {
            msg.append("Suggested move-out: bed ")
                    .append(candidates.get(0).getBedCode())
                    .append(" — ")
                    .append(candidates.get(0).getRationale())
                    .append(".");
        }
        return ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.RESUS_OVERFLOW)
                .severity(AlertSeverity.CRITICAL)
                .title("Resus overflow")
                .message(msg.toString())
                .targetZone(EdZone.RESUS)
                .escalationTier(1)
                .autoGenerated(true)
                .build();
    }

    /**
     * Mark an ambulance pre-arrival visit as physically arrived. The
     * door clock starts here — {@code arrivalConfirmedAt} is set, the
     * pre-arrival flag is left as a historical marker.
     */
    @Transactional
    public DirectResusAdmissionResponse confirmArrival(UUID visitId) {
        Visit visit = visitRepository.findByIdAndIsActiveTrue(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("Visit", "id", visitId));

        if (!visit.isAmbulancePreArrival()) {
            throw new ClinicalBusinessException(
                    "Visit " + visit.getVisitNumber() + " is not an ambulance pre-arrival");
        }
        if (visit.getArrivalConfirmedAt() != null) {
            throw new ClinicalBusinessException(
                    "Visit " + visit.getVisitNumber() + " arrival has already been confirmed");
        }

        visit.setArrivalConfirmedAt(Instant.now());
        visit = visitRepository.save(visit);

        log.info("[direct-resus] Ambulance pre-arrival visit {} arrival confirmed at {}",
                visit.getVisitNumber(), visit.getArrivalConfirmedAt());

        Bed bed = visit.getCurrentBed();
        return DirectResusAdmissionResponse.builder()
                .visitId(visit.getId())
                .visitNumber(visit.getVisitNumber())
                .patientId(visit.getPatient().getId())
                .patientFirstName(visit.getPatient().getFirstName())
                .patientLastName(visit.getPatient().getLastName())
                .isUnidentified(visit.getPatient().isUnidentified())
                .placeholderLabel(visit.getPatient().getPlaceholderLabel())
                .bedId(bed != null ? bed.getId() : null)
                .bedCode(bed != null ? bed.getCode() : null)
                .bedZone(bed != null ? bed.getZone() : null)
                .bedHasMonitor(bed != null && bed.isHasMonitor())
                .overflow(visit.isPendingResusOverflow())
                .identityRequired(visit.getPatient().isUnidentified())
                .arrivalTime(visit.getArrivalConfirmedAt())
                .ambulancePreArrival(true)
                .transferCandidates(List.of())
                .build();
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private Optional<User> resolveAuthenticatedUser() {
        try {
            Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            if (principal instanceof User user) return Optional.of(user);
        } catch (Exception ignored) {
            // SecurityContext may be empty (background jobs, tests)
        }
        return Optional.empty();
    }

    private String formatActorName(User user) {
        String full = ((user.getFirstName() != null ? user.getFirstName() : "") + " "
                + (user.getLastName() != null ? user.getLastName() : "")).trim();
        return full.isEmpty() ? user.getUsername() : full;
    }

    private Gender parseGenderSafely(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Gender.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Internal — outcome of trying to place the new admission. */
    private record BedPlacementOutcome(Bed bed, boolean overflow) {
        static BedPlacementOutcome placed(Bed b) { return new BedPlacementOutcome(b, false); }
        static BedPlacementOutcome asOverflow()  { return new BedPlacementOutcome(null, true); }
    }
}
