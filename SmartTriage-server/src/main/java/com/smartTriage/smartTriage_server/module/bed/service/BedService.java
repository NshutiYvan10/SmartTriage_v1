package com.smartTriage.smartTriage_server.module.bed.service;

import com.smartTriage.smartTriage_server.common.enums.BedStatus;
import com.smartTriage.smartTriage_server.common.enums.DeviceStatus;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.common.enums.VisitStatus;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.DuplicateResourceException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.bed.dto.AssignDeviceRequest;
import com.smartTriage.smartTriage_server.module.bed.dto.BedResponse;
import com.smartTriage.smartTriage_server.module.bed.dto.CreateBedRequest;
import com.smartTriage.smartTriage_server.module.bed.dto.PlacePatientRequest;
import com.smartTriage.smartTriage_server.module.bed.dto.TransferPatientRequest;
import com.smartTriage.smartTriage_server.module.bed.dto.UpdateBedRequest;
import com.smartTriage.smartTriage_server.module.bed.dto.ZoneOccupancyResponse;
import com.smartTriage.smartTriage_server.module.bed.entity.Bed;
import com.smartTriage.smartTriage_server.module.bed.mapper.BedMapper;
import com.smartTriage.smartTriage_server.module.bed.repository.BedRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.service.HospitalService;
import com.smartTriage.smartTriage_server.module.iot.entity.DeviceSession;
import com.smartTriage.smartTriage_server.module.iot.entity.IoTDevice;
import com.smartTriage.smartTriage_server.module.iot.repository.DeviceSessionRepository;
import com.smartTriage.smartTriage_server.module.iot.repository.IoTDeviceRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * BedService — orchestrates the bed-based placement workflow that binds
 * patients to physical treatment spaces and, transitively, to the monitors
 * that live at those spaces.
 *
 * This is the clinical heart of the routing layer. When a triaged patient
 * is placed in a bed:
 *   1. bed.currentVisit ← visit  AND  visit.currentBed ← bed (mirrored)
 *   2. bed.status → OCCUPIED
 *   3. If the bed has an assigned IoTDevice, a DeviceSession is auto-opened
 *      so vitals start flowing to the patient chart — no nurse click needed.
 *   4. A /topic/beds/{hospitalId} event fires so every connected dashboard
 *      re-fetches the affected zone.
 *
 * Lifecycle guarantees:
 *   - Placement requires bed.status == AVAILABLE (DB partial-unique index
 *     uk_bed_one_active_visit enforces this at the storage layer too).
 *   - Discharge / transfer always leaves the source bed in CLEANING — a
 *     mandatory hygiene step for infection control and to prevent vitals
 *     contamination between patients.
 *   - A patient cannot occupy two beds; the uk_visit_one_bed partial index
 *     rejects the race.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BedService {

    private final BedRepository bedRepository;
    private final VisitRepository visitRepository;
    private final IoTDeviceRepository deviceRepository;
    private final DeviceSessionRepository sessionRepository;
    private final HospitalService hospitalService;
    private final RealTimeEventPublisher eventPublisher;

    // ====================================================================
    // ADMIN CRUD
    // ====================================================================

    @Transactional
    public BedResponse createBed(CreateBedRequest request) {
        Hospital hospital = hospitalService.findHospitalOrThrow(request.getHospitalId());

        bedRepository.findByHospitalIdAndCodeAndIsActiveTrue(hospital.getId(), request.getCode())
                .ifPresent(existing -> {
                    throw new DuplicateResourceException("Bed", "code", request.getCode());
                });

        Bed bed = Bed.builder()
                .hospital(hospital)
                .zone(request.getZone())
                .code(request.getCode())
                .label(request.getLabel())
                .status(BedStatus.AVAILABLE)
                .hasMonitor(request.isHasMonitor())
                .displayOrder(request.getDisplayOrder() != null ? request.getDisplayOrder() : 0)
                .notes(request.getNotes())
                .build();

        bed = bedRepository.save(bed);
        log.info("Bed created: {} ({} / {}) in hospital {}",
                bed.getCode(), bed.getZone(), bed.getLabel(), hospital.getHospitalCode());

        publishBedChange(bed, "CREATED");
        return toResponse(bed);
    }

    @Transactional
    public BedResponse updateBed(UUID bedId, UpdateBedRequest request) {
        Bed bed = findBedOrThrow(bedId);

        if (request.getCode() != null && !request.getCode().equals(bed.getCode())) {
            bedRepository.findByHospitalIdAndCodeAndIsActiveTrue(bed.getHospital().getId(), request.getCode())
                    .ifPresent(existing -> {
                        if (!existing.getId().equals(bedId)) {
                            throw new DuplicateResourceException("Bed", "code", request.getCode());
                        }
                    });
            bed.setCode(request.getCode());
        }
        if (request.getLabel() != null) {
            bed.setLabel(request.getLabel());
        }
        if (request.getHasMonitor() != null) {
            bed.setHasMonitor(request.getHasMonitor());
        }
        if (request.getDisplayOrder() != null) {
            bed.setDisplayOrder(request.getDisplayOrder());
        }
        if (request.getNotes() != null) {
            bed.setNotes(request.getNotes());
        }

        bed = bedRepository.save(bed);
        log.info("Bed updated: {} ({})", bed.getCode(), bed.getId());

        publishBedChange(bed, "UPDATED");
        return toResponse(bed);
    }

    /**
     * Soft-delete a bed. Rejected if currently occupied — discharge first.
     * Also detaches any assigned device so it becomes portable again.
     */
    @Transactional
    public void deleteBed(UUID bedId) {
        Bed bed = findBedOrThrow(bedId);

        if (bed.isOccupied()) {
            throw new ClinicalBusinessException(
                    "Cannot delete bed " + bed.getCode() + " while a patient is placed in it. Discharge first.");
        }

        // Detach any device assigned to this bed
        deviceRepository.findByAssignedBedIdAndIsActiveTrue(bedId)
                .ifPresent(device -> {
                    device.setAssignedBed(null);
                    deviceRepository.save(device);
                    log.info("Detached device {} from deleted bed {}", device.getSerialNumber(), bed.getCode());
                });

        bed.softDelete();
        bedRepository.save(bed);
        log.info("Bed soft-deleted: {} ({})", bed.getCode(), bed.getId());

        publishBedChange(bed, "DELETED");
    }

    // ====================================================================
    // QUERIES
    // ====================================================================

    public BedResponse getBed(UUID bedId) {
        Bed bed = findBedOrThrow(bedId);
        return toResponse(bed);
    }

    public List<BedResponse> getBedsForHospital(UUID hospitalId) {
        List<Bed> beds = bedRepository.findAllByHospital(hospitalId);
        return enrichAll(beds);
    }

    public List<BedResponse> getBedsByZone(UUID hospitalId, EdZone zone) {
        List<Bed> beds = bedRepository.findByHospitalAndZone(hospitalId, zone);
        return enrichAll(beds);
    }

    public List<BedResponse> getAvailableInZone(UUID hospitalId, EdZone zone) {
        List<Bed> beds = bedRepository.findAvailableInZone(hospitalId, zone);
        return enrichAll(beds);
    }

    /**
     * Aggregated snapshot of a zone — every bed plus headline capacity metrics
     * so the bed-grid UI can render without a second request.
     */
    public ZoneOccupancyResponse getZoneOccupancy(UUID hospitalId, EdZone zone) {
        List<Bed> beds = bedRepository.findByHospitalAndZone(hospitalId, zone);
        List<BedResponse> bedResponses = enrichAll(beds);

        int occupied = 0, available = 0, cleaning = 0, outOfService = 0;
        for (Bed b : beds) {
            switch (b.getStatus()) {
                case OCCUPIED -> occupied++;
                case AVAILABLE -> available++;
                case CLEANING -> cleaning++;
                case OUT_OF_SERVICE -> outOfService++;
            }
        }

        return ZoneOccupancyResponse.builder()
                .zone(zone)
                .zoneLabel(zone.name())
                .totalBeds(beds.size())
                .occupied(occupied)
                .available(available)
                .cleaning(cleaning)
                .outOfService(outOfService)
                .beds(bedResponses)
                .build();
    }

    // ====================================================================
    // PLACEMENT WORKFLOW
    // ====================================================================

    /**
     * Place a triaged patient in a bed. Atomically:
     *   - bed.currentVisit ← visit, bed.status → OCCUPIED
     *   - visit.currentBed ← bed, visit.status progresses if still at TRIAGED
     *   - If the bed has an assigned monitor: a DeviceSession is opened and
     *     the device transitions to MONITORING.
     */
    @Transactional
    public BedResponse placePatient(UUID bedId, PlacePatientRequest request, String actorName) {
        Bed bed = findBedOrThrow(bedId);
        Visit visit = visitRepository.findByIdAndIsActiveTrue(request.getVisitId())
                .orElseThrow(() -> new ResourceNotFoundException("Visit", "id", request.getVisitId()));

        // Hospital boundary check — beds and visits must belong to the same hospital
        if (!bed.getHospital().getId().equals(visit.getHospital().getId())) {
            throw new ClinicalBusinessException(
                    "Bed " + bed.getCode() + " and visit " + visit.getVisitNumber() +
                            " belong to different hospitals.");
        }

        // Bed must be occupiable
        if (!bed.isOccupiable()) {
            throw new ClinicalBusinessException(
                    "Bed " + bed.getCode() + " is not available (status: " + bed.getStatus() + ")");
        }

        // Visit must not already be placed elsewhere
        bedRepository.findByCurrentVisitIdAndIsActiveTrue(visit.getId())
                .ifPresent(other -> {
                    throw new ClinicalBusinessException(
                            "Visit " + visit.getVisitNumber() + " is already placed in bed " + other.getCode() +
                                    ". Use transfer instead.");
                });

        // Visit must be in a status that makes placement sensible
        if (isTerminalStatus(visit.getStatus())) {
            throw new ClinicalBusinessException(
                    "Visit " + visit.getVisitNumber() + " has already ended (" + visit.getStatus() + ").");
        }

        // Link both sides
        bed.setCurrentVisit(visit);
        bed.setStatus(BedStatus.OCCUPIED);
        visit.setCurrentBed(bed);

        // Promote status — once a patient is in a bed they are at minimum awaiting
        // assessment (a doctor is expected to see them).
        if (visit.getStatus() == VisitStatus.TRIAGED) {
            visit.setStatus(VisitStatus.AWAITING_ASSESSMENT);
        }

        bedRepository.save(bed);
        visitRepository.save(visit);

        log.info("Placed visit {} in bed {} ({})", visit.getVisitNumber(), bed.getCode(), bed.getZone());

        // Continuous monitoring is now clinician-initiated — see
        // MonitoringState.NOT_STARTED in the design audit. Placement
        // no longer opens a DeviceSession; instead a clinician explicitly
        // presses "Start Monitoring" from the Constant Monitoring page
        // once they've placed the sensors on the patient. This stops
        // the previous failure mode where a session went LIVE with a
        // bare cart-side probe and auto-retriage fired on noise.

        publishBedChange(bed, "PLACED");
        return toResponse(bed);
    }

    /**
     * Transfer a patient from one bed to another (e.g. Acute → Resus on
     * deterioration). Atomically closes the source session, sets source to
     * CLEANING, places the patient in the destination, and opens a new
     * session on the destination monitor if one is assigned.
     */
    @Transactional
    public BedResponse transferPatient(UUID sourceBedId, TransferPatientRequest request, String actorName) {
        Bed source = findBedOrThrow(sourceBedId);
        Bed dest = findBedOrThrow(request.getDestinationBedId());

        if (source.getId().equals(dest.getId())) {
            throw new ClinicalBusinessException("Source and destination beds are the same.");
        }

        if (!source.isOccupied()) {
            throw new ClinicalBusinessException(
                    "Bed " + source.getCode() + " has no patient to transfer.");
        }

        if (!dest.isOccupiable()) {
            throw new ClinicalBusinessException(
                    "Destination bed " + dest.getCode() + " is not available (status: " + dest.getStatus() + ")");
        }

        if (!source.getHospital().getId().equals(dest.getHospital().getId())) {
            throw new ClinicalBusinessException(
                    "Transfers across hospitals are not allowed.");
        }

        Visit visit = source.getCurrentVisit();
        String reason = request.getReason() != null && !request.getReason().isBlank()
                ? request.getReason()
                : ("Patient transferred to " + dest.getCode());

        // 1) Close the active session on the source bed's monitor (if
        //    any) and capture its continuity group id so the next
        //    session inherits it — the chart is one timeline across
        //    the move.
        java.util.UUID continuityGroup = closeActiveSessionForVisit(visit, "Transfer: " + reason);

        // 2) Unlink source
        source.setCurrentVisit(null);
        source.setStatus(BedStatus.CLEANING);
        bedRepository.save(source);

        // 3) Link destination
        dest.setCurrentVisit(visit);
        dest.setStatus(BedStatus.OCCUPIED);
        visit.setCurrentBed(dest);
        bedRepository.save(dest);
        visitRepository.save(visit);

        log.info("Transferred visit {} from bed {} ({}) to {} ({}) — {}",
                visit.getVisitNumber(),
                source.getCode(), source.getZone(),
                dest.getCode(), dest.getZone(), reason);

        // 4) Auto-open a new session on the destination monitor (if
        //    any) AND propagate the continuity group from the source
        //    session so the chart treats the two as one record. This
        //    is the one auto-start that we deliberately keep under
        //    the clinician-initiated model: a transfer preserves
        //    ongoing monitoring; the clinician's original Start
        //    intent flows across the bed move.
        autoStartSessionForBed(dest, visit, actorName, continuityGroup);

        publishBedChange(source, "TRANSFERRED_OUT");
        publishBedChange(dest, "TRANSFERRED_IN");

        return toResponse(dest);
    }

    /**
     * Discharge the patient from a bed — clears occupancy and moves the bed
     * to CLEANING. The visit itself is NOT closed here (that's the
     * disposition workflow). This is used when a patient leaves the bed for
     * imaging/procedure and won't be coming back to that specific bed.
     */
    @Transactional
    public BedResponse dischargePatient(UUID bedId, String reason) {
        Bed bed = findBedOrThrow(bedId);

        if (!bed.isOccupied()) {
            throw new ClinicalBusinessException(
                    "Bed " + bed.getCode() + " has no patient to discharge.");
        }

        Visit visit = bed.getCurrentVisit();
        String actualReason = reason != null && !reason.isBlank() ? reason : "Patient discharged from bed";

        // Close monitoring session first
        closeActiveSessionForVisit(visit, actualReason);

        // Unlink both sides
        bed.setCurrentVisit(null);
        bed.setStatus(BedStatus.CLEANING);
        visit.setCurrentBed(null);
        bedRepository.save(bed);
        visitRepository.save(visit);

        log.info("Discharged visit {} from bed {} — {}",
                visit.getVisitNumber(), bed.getCode(), actualReason);

        publishBedChange(bed, "DISCHARGED");
        return toResponse(bed);
    }

    /**
     * Internal helper invoked by VisitService.recordDisposition so that
     * final patient disposition also frees the bed. Safe to call when the
     * visit has no bed — it's a no-op.
     */
    @Transactional
    public void releaseVisitFromBed(UUID visitId, String reason) {
        bedRepository.findByCurrentVisitIdAndIsActiveTrue(visitId).ifPresent(bed -> {
            Visit visit = bed.getCurrentVisit();
            bed.setCurrentVisit(null);
            bed.setStatus(BedStatus.CLEANING);
            bedRepository.save(bed);
            if (visit != null) {
                visit.setCurrentBed(null);
                visitRepository.save(visit);
            }
            log.info("Bed {} released on disposition — {}", bed.getCode(), reason);
            publishBedChange(bed, "RELEASED");
        });
    }

    // ====================================================================
    // BED STATUS TRANSITIONS (housekeeping / maintenance)
    // ====================================================================

    /** Mark a CLEANING bed as AVAILABLE — housekeeping signal. */
    @Transactional
    public BedResponse markCleaned(UUID bedId) {
        Bed bed = findBedOrThrow(bedId);

        if (bed.getStatus() != BedStatus.CLEANING) {
            throw new ClinicalBusinessException(
                    "Bed " + bed.getCode() + " is not in CLEANING state (current: " + bed.getStatus() + ")");
        }
        if (bed.getCurrentVisit() != null) {
            // Defensive: should never happen because CLEANING implies no occupant
            throw new ClinicalBusinessException(
                    "Bed " + bed.getCode() + " still has an occupant — cannot mark clean.");
        }

        bed.setStatus(BedStatus.AVAILABLE);
        bedRepository.save(bed);
        log.info("Bed {} marked AVAILABLE (cleaning complete)", bed.getCode());

        publishBedChange(bed, "CLEANED");
        emitBedAvailableAlertIfHighAcuity(bed, "Bed cleaned and ready");
        return toResponse(bed);
    }

    /** Take a bed out of service (maintenance, broken monitor, etc.). */
    @Transactional
    public BedResponse markOutOfService(UUID bedId, String reason) {
        Bed bed = findBedOrThrow(bedId);

        if (bed.isOccupied()) {
            throw new ClinicalBusinessException(
                    "Cannot take bed " + bed.getCode() + " out of service while occupied. Discharge first.");
        }

        bed.setStatus(BedStatus.OUT_OF_SERVICE);
        if (reason != null && !reason.isBlank()) {
            String prefix = bed.getNotes() != null && !bed.getNotes().isBlank() ? bed.getNotes() + "\n" : "";
            bed.setNotes(prefix + "[OOS " + Instant.now() + "] " + reason);
        }
        bedRepository.save(bed);
        log.info("Bed {} marked OUT_OF_SERVICE — {}", bed.getCode(), reason);

        publishBedChange(bed, "OUT_OF_SERVICE");
        return toResponse(bed);
    }

    /** Return an OUT_OF_SERVICE bed to AVAILABLE. */
    @Transactional
    public BedResponse markAvailable(UUID bedId) {
        Bed bed = findBedOrThrow(bedId);

        if (bed.getStatus() != BedStatus.OUT_OF_SERVICE) {
            throw new ClinicalBusinessException(
                    "Bed " + bed.getCode() + " is not OUT_OF_SERVICE (current: " + bed.getStatus() + ")");
        }

        bed.setStatus(BedStatus.AVAILABLE);
        bedRepository.save(bed);
        log.info("Bed {} returned to AVAILABLE", bed.getCode());

        publishBedChange(bed, "AVAILABLE");
        emitBedAvailableAlertIfHighAcuity(bed, "Bed returned from out-of-service");
        return toResponse(bed);
    }

    // ====================================================================
    // DEVICE ASSIGNMENT
    // ====================================================================

    /**
     * Assign an IoTDevice to this bed permanently, or detach (deviceId == null).
     * If a patient is currently in the bed, a fresh session is opened on the
     * newly-assigned device so monitoring starts right away.
     */
    @Transactional
    public BedResponse assignDevice(UUID bedId, AssignDeviceRequest request, String actorName) {
        Bed bed = findBedOrThrow(bedId);

        // Detach any currently-assigned device
        deviceRepository.findByAssignedBedIdAndIsActiveTrue(bedId)
                .ifPresent(current -> {
                    // If the current device was monitoring this bed's patient, close session
                    if (bed.isOccupied()) {
                        sessionRepository
                                .findByDeviceIdAndSessionActiveTrueAndIsActiveTrue(current.getId())
                                .ifPresent(session -> {
                                    session.endSession(
                                            actorName != null ? actorName : "System",
                                            "Device reassigned from bed " + bed.getCode());
                                    sessionRepository.save(session);
                                    IoTDevice fresh = deviceRepository.findById(current.getId()).orElse(current);
                                    fresh.setStatus(DeviceStatus.ONLINE);
                                    deviceRepository.save(fresh);
                                    publishDeviceStatusChangeAfterCommit(fresh);
                                });
                    }
                    current.setAssignedBed(null);
                    deviceRepository.save(current);
                    // Clear the bed.hasMonitor flag — keep it in sync
                    // with reality so autoStartSessionForBed and the UI
                    // don't claim the bed has a monitor when it doesn't.
                    bed.setHasMonitor(false);
                    bedRepository.save(bed);
                    log.info("Detached device {} from bed {} (hasMonitor cleared)",
                            current.getSerialNumber(), bed.getCode());
                });

        if (request.getDeviceId() != null) {
            IoTDevice device = deviceRepository.findByIdAndIsActiveTrue(request.getDeviceId())
                    .orElseThrow(() -> new ResourceNotFoundException("IoTDevice", "id", request.getDeviceId()));

            if (!device.getHospital().getId().equals(bed.getHospital().getId())) {
                throw new ClinicalBusinessException(
                        "Device " + device.getSerialNumber() + " does not belong to this hospital.");
            }

            // Is this device already assigned to a different bed?
            if (device.getAssignedBed() != null && !device.getAssignedBed().getId().equals(bedId)) {
                throw new ClinicalBusinessException(
                        "Device " + device.getSerialNumber() + " is already assigned to bed " +
                                device.getAssignedBed().getCode() + ". Detach first.");
            }

            device.setAssignedBed(bed);
            bed.setHasMonitor(true);
            deviceRepository.save(device);
            bedRepository.save(bed);
            log.info("Assigned device {} to bed {}", device.getSerialNumber(), bed.getCode());

            // Monitoring start is clinician-initiated under the new
            // workflow — see Phase 1 audit. Admin pairing a monitor
            // makes the bed *capable* of monitoring; the clinician's
            // explicit Start (with the "sensors placed" attestation
            // modal) is what opens the session. If a patient is
            // already in the bed, the Constant Monitoring page will
            // show the NOT_STARTED pill + Start button.
        } else {
            // Pure detach — hasMonitor remains as-is (admin decision)
            bedRepository.save(bed);
        }

        publishBedChange(bed, "DEVICE_ASSIGNMENT");
        return toResponse(bed);
    }

    // ====================================================================
    // INTERNAL
    // ====================================================================

    public Bed findBedOrThrow(UUID id) {
        return bedRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bed", "id", id));
    }

    /**
     * Open a monitoring session for a bed's assigned device (if any).
     * Mirrors the core of DeviceService.startMonitoring, inlined here so
     * placement is a single-transaction atomic unit.
     */
    /**
     * Opens a DeviceSession on the bed's paired monitor (if any).
     *
     * @return true when a session was actually opened; false when the bed
     *         has no paired monitor, the monitor is offline, or any
     *         expected-no-op branch fired. Caller uses this to build an
     *         accurate auto-placement note (so we never tell the nurse
     *         "monitor streaming" when nothing actually started).
     */
    private boolean autoStartSessionForBed(Bed bed, Visit visit, String startedByName) {
        return autoStartSessionForBed(bed, /* prefetchedDevice */ null, visit, startedByName, null);
    }

    /**
     * Overload for transfer-driven auto-start that propagates a
     * continuity group id from the source session onto the new
     * destination session. The chart then renders both sessions as
     * one continuous monitoring record.
     */
    private boolean autoStartSessionForBed(Bed bed, Visit visit, String startedByName,
                                           java.util.UUID continuityGroupId) {
        return autoStartSessionForBed(bed, /* prefetchedDevice */ null, visit, startedByName,
                continuityGroupId);
    }

    /**
     * Overload that accepts a pre-fetched device. Callers that have just
     * saved the device → bed pairing in the same transaction should pass
     * the device in directly to avoid a re-query that may miss the unsaved
     * pairing due to JPA persistence-context flush timing.
     */
    private boolean autoStartSessionForBed(Bed bed, IoTDevice prefetchedDevice, Visit visit, String startedByName) {
        return autoStartSessionForBed(bed, prefetchedDevice, visit, startedByName, null);
    }

    /**
     * Full overload — accepts both the pre-fetched device and the
     * continuity group id. Internal helper; callers pick the lighter
     * overload that matches what they have.
     */
    private boolean autoStartSessionForBed(Bed bed, IoTDevice prefetchedDevice, Visit visit,
                                           String startedByName, java.util.UUID continuityGroupId) {
        IoTDevice device = prefetchedDevice != null
                ? prefetchedDevice
                : deviceRepository.findByAssignedBedIdAndIsActiveTrue(bed.getId()).orElse(null);
        if (device == null) {
            // Bed.hasMonitor can be a stale flag (assignDevice sets it
            // true, detach never clears it). Reflect reality back to the
            // bed row here so the UI's "this bed has a monitor" indicator
            // doesn't keep lying after a detach.
            if (bed.isHasMonitor()) {
                bed.setHasMonitor(false);
                bedRepository.save(bed);
                log.warn("Bed {} had hasMonitor=true but no device is paired — "
                        + "cleared the stale flag.", bed.getCode());
            } else {
                log.info("Bed {} has no monitor paired — auto-pair skipped.", bed.getCode());
            }
            return false;
        }

        // Device must be reachable (ONLINE or previously REGISTERED). If it's OFFLINE
        // we skip auto-start silently — the session will start when the device next
        // heartbeats, and the nurse can manually pair in the meantime.
        if (device.getStatus() == DeviceStatus.OFFLINE
                || device.getStatus() == DeviceStatus.DECOMMISSIONED) {
            log.warn("Bed {} has assigned device {} but it is {} — skipping auto-pair. "
                    + "Will pair on next heartbeat.",
                    bed.getCode(), device.getSerialNumber(), device.getStatus());
            return false;
        }

        // Close any stale session on the device
        sessionRepository.findByDeviceIdAndSessionActiveTrueAndIsActiveTrue(device.getId())
                .ifPresent(stale -> {
                    log.warn("Auto-closing stale session {} on device {} before bed auto-pair",
                            stale.getId(), device.getSerialNumber());
                    stale.endSession("System", "Auto-closed: bed re-pairing");
                    sessionRepository.save(stale);
                });

        // Close any session on the visit from a different device (should be rare)
        sessionRepository.findByVisitIdAndSessionActiveTrueAndIsActiveTrue(visit.getId())
                .ifPresent(stale -> {
                    log.warn("Auto-closing prior session {} on visit {} before bed auto-pair",
                            stale.getId(), visit.getVisitNumber());
                    stale.endSession("System", "Auto-closed: bed re-pairing");
                    sessionRepository.save(stale);
                });

        DeviceSession session = DeviceSession.builder()
                .device(device)
                .visit(visit)
                .startedAt(Instant.now())
                .sessionActive(true)
                .startedByName(startedByName != null ? startedByName : "Bed placement (auto)")
                // Carry continuity from the source session when this is
                // a transfer-driven auto-start so the doctor's view
                // renders one continuous monitoring record.
                .continuityGroupId(continuityGroupId)
                .monitoringState(com.smartTriage.smartTriage_server.common.enums.MonitoringState.STARTING)
                .monitoringStateAt(Instant.now())
                .build();
        sessionRepository.save(session);

        // Re-fetch device for latest version, then flip status
        IoTDevice fresh = deviceRepository.findById(device.getId()).orElse(device);
        fresh.setStatus(DeviceStatus.MONITORING);
        deviceRepository.save(fresh);

        log.info("Auto-paired device {} → visit {} via bed {}",
                fresh.getSerialNumber(), visit.getVisitNumber(), bed.getCode());

        // Critical timing fix — push the device-status change to any
        // already-mounted Monitoring page so it switches from
        // "Device Connected" to "Live Monitoring Active" without
        // requiring a manual re-fetch. Deferred to afterCommit by the
        // helper below so subscribers reading device state via HTTP see
        // post-commit data.
        publishDeviceStatusChangeAfterCommit(fresh);
        return true;
    }

    /**
     * Close the active monitoring session for a visit, if one exists.
     *
     * <p>Returns the closed session's continuityGroupId (creating one
     * if the session didn't have one yet) so callers — notably
     * {@link #transferPatient} — can carry the same id onto the
     * destination-bed session and keep the clinical chart as one
     * continuous timeline across the move. Returns {@code null} when
     * there was no active session to close.
     */
    private java.util.UUID closeActiveSessionForVisit(Visit visit, String reason) {
        if (visit == null) return null;
        return sessionRepository.findByVisitIdAndSessionActiveTrueAndIsActiveTrue(visit.getId())
                .map(session -> {
                    // Allocate a continuity group on the session if it
                    // doesn't have one yet, so the next session in the
                    // group can be linked.
                    if (session.getContinuityGroupId() == null) {
                        session.setContinuityGroupId(java.util.UUID.randomUUID());
                    }
                    java.util.UUID groupId = session.getContinuityGroupId();
                    session.endSession("System", reason);
                    sessionRepository.save(session);

                    IoTDevice device = session.getDevice();
                    IoTDevice fresh = deviceRepository.findById(device.getId()).orElse(device);
                    boolean statusChanged = false;
                    if (fresh.getStatus() == DeviceStatus.MONITORING) {
                        fresh.setStatus(DeviceStatus.ONLINE);
                        deviceRepository.save(fresh);
                        statusChanged = true;
                    }
                    log.info("Closed session for visit {} — {}", visit.getVisitNumber(), reason);

                    if (statusChanged) {
                        publishDeviceStatusChangeAfterCommit(fresh);
                    }
                    return groupId;
                })
                .orElse(null);
    }

    /**
     * Bed-service-local version of DeviceService.publishDeviceStatus.
     *
     * <p>Identical semantics: snapshot the entity inside the TX, defer the
     * actual publish to afterCommit so frontend re-fetches triggered by
     * the WebSocket event see post-commit DB state. Lives here (instead
     * of being delegated to DeviceService) so the bed flows aren't
     * forced into a cross-service circular dependency.
     */
    private void publishDeviceStatusChangeAfterCommit(IoTDevice device) {
        final UUID hospitalId;
        // RealTimeEventPublisher.publishDeviceStatusChange takes
        // Map<String,Object>; keep this loose-typed to match.
        final Map<String, Object> payload;
        try {
            hospitalId = device.getHospital().getId();
            payload = Map.of(
                    "deviceId", device.getId().toString(),
                    "serialNumber", device.getSerialNumber(),
                    "deviceName", device.getDeviceName(),
                    "status", device.getStatus().name(),
                    "timestamp", Instant.now().toString());
        } catch (Exception e) {
            log.warn("Failed to build device status payload from bed flow: {}", e.getMessage());
            return;
        }
        Runnable fire = () -> {
            try {
                eventPublisher.publishDeviceStatusChange(hospitalId, payload);
            } catch (Exception e) {
                log.warn("Failed to publish device status change from bed flow: {}", e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() { fire.run(); }
                    });
        } else {
            fire.run();
        }
    }

    /** Build a BedResponse with device + session info filled in. */
    private BedResponse toResponse(Bed bed) {
        IoTDevice device = deviceRepository.findByAssignedBedIdAndIsActiveTrue(bed.getId()).orElse(null);
        UUID sessionId = null;
        if (bed.getCurrentVisit() != null) {
            sessionId = sessionRepository
                    .findByVisitIdAndSessionActiveTrueAndIsActiveTrue(bed.getCurrentVisit().getId())
                    .map(DeviceSession::getId)
                    .orElse(null);
        }
        return BedMapper.toResponse(bed, device, sessionId);
    }

    /**
     * Batch-enrich a list of beds. Pulls all assigned devices and active
     * sessions for the hospital in two queries instead of N+1 lookups.
     */
    private List<BedResponse> enrichAll(List<Bed> beds) {
        if (beds.isEmpty()) return List.of();

        UUID hospitalId = beds.get(0).getHospital().getId();

        // deviceId lookup: bedId → device
        List<IoTDevice> assignedDevices = deviceRepository.findAllAssignedToBeds(hospitalId);
        Map<UUID, IoTDevice> deviceByBedId = new HashMap<>();
        for (IoTDevice d : assignedDevices) {
            if (d.getAssignedBed() != null) {
                deviceByBedId.put(d.getAssignedBed().getId(), d);
            }
        }

        // session lookup: visitId → sessionId
        List<DeviceSession> activeSessions = sessionRepository
                .findByDeviceHospitalIdAndSessionActiveTrueAndIsActiveTrue(hospitalId);
        Map<UUID, UUID> sessionByVisitId = new HashMap<>();
        for (DeviceSession s : activeSessions) {
            sessionByVisitId.put(s.getVisit().getId(), s.getId());
        }

        List<BedResponse> out = new ArrayList<>(beds.size());
        for (Bed b : beds) {
            IoTDevice dev = deviceByBedId.get(b.getId());
            UUID sessId = b.getCurrentVisit() != null
                    ? sessionByVisitId.get(b.getCurrentVisit().getId())
                    : null;
            out.add(BedMapper.toResponse(b, dev, sessId));
        }
        return out;
    }

    /** True when the visit has reached an end state — no new placements allowed. */
    private boolean isTerminalStatus(VisitStatus status) {
        return switch (status) {
            case DISCHARGED, ADMITTED, TRANSFERRED, ICU_ADMITTED,
                 LEFT_WITHOUT_BEING_SEEN, DECEASED -> true;
            default -> false;
        };
    }

    /**
     * Fire a WebSocket event so every dashboard can re-fetch the affected zone.
     *
     * <p><b>Critical timing fix:</b> Defer the publish to <em>after</em> the
     * surrounding transaction commits. The previous version fired the event
     * synchronously from inside the TX, which meant the frontend received
     * "PLACED" and re-fetched device state via HTTP <em>before</em> the
     * device-session writes (made in {@code autoStartSessionForBed}) had
     * committed — so the re-fetch saw stale data: no active session,
     * device still {@code ONLINE} instead of {@code MONITORING}. This is
     * the root cause of the "Demo (not live)" bug: vitals don't stream
     * because the frontend's device cache is populated from a pre-commit
     * snapshot.
     *
     * <p>If no transaction is active (defensive path), publish immediately.
     */
    private void publishBedChange(Bed bed, String eventType) {
        // Capture all the data we need INSIDE the TX, while the entities
        // are still attached. The actual publish reads no entity state —
        // it reads only this snapshot map.
        final Map<String, Object> payload;
        final UUID hospitalId;
        try {
            payload = new HashMap<>();
            payload.put("bedId", bed.getId().toString());
            payload.put("code", bed.getCode());
            payload.put("zone", bed.getZone().name());
            payload.put("status", bed.getStatus().name());
            payload.put("event", eventType);
            payload.put("hasOccupant", bed.getCurrentVisit() != null);
            payload.put("timestamp", Instant.now().toString());
            hospitalId = bed.getHospital().getId();
        } catch (Exception e) {
            log.warn("Failed to build bed change payload for {}: {}",
                    bed.getCode(), e.getMessage());
            return;
        }

        Runnable fire = () -> {
            try {
                eventPublisher.publishBedChange(hospitalId, payload);
            } catch (Exception e) {
                log.warn("Failed to publish bed change for {}: {}",
                        payload.get("code"), e.getMessage());
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() { fire.run(); }
                    });
        } else {
            // No active TX (e.g. test harness, async caller) — publish now.
            fire.run();
        }
    }

    /**
     * Broadcast a transient BED_AVAILABLE alert when a high-acuity bed
     * (RESUS / ACUTE / PEDIATRIC / NEONATAL) transitions to AVAILABLE.
     * The on-duty charge nurse + zone staff need to know capacity has
     * been restored so an overflow patient can be transferred in or a
     * pending admission can advance.
     *
     * <p>We deliberately do NOT alert for low-acuity zones (GENERAL,
     * AMBULATORY, OBSERVATION, ISOLATION, TRIAGE) — there the operational
     * tempo is slower and a per-bed alert would create more noise than
     * value. The plain {@code publishBedChange} broadcast still covers
     * those via the bed-grid live update.
     *
     * <p>Not persisted as a {@code ClinicalAlert} row because the alert
     * schema requires a non-null {@code visit_id}; bed-availability is
     * a fleet-level operational event that isn't bound to one patient.
     * Same pattern the stale-pending-request scheduler uses: transient
     * WebSocket frame, no DB row. The frontend alert store accepts the
     * payload and renders it like any other alert; "acknowledgement" is
     * effectively the next bed assignment, which closes the loop.
     *
     * <p>Failure-isolated: a bug in alert publishing must never block
     * the bed status transition that already persisted.
     */
    private void emitBedAvailableAlertIfHighAcuity(Bed bed, String reason) {
        try {
            EdZone zone = bed.getZone();
            boolean highAcuity = zone == EdZone.RESUS
                    || zone == EdZone.ACUTE
                    || zone == EdZone.PEDIATRIC
                    || zone == EdZone.NEONATAL;
            if (!highAcuity) {
                return;
            }
            UUID hospitalId = bed.getHospital().getId();
            // Build a minimal ClinicalAlertResponse payload directly —
            // no entity, no save. The frontend treats it the same as a
            // persisted alert because its UI is field-driven, not
            // identity-driven.
            com.smartTriage.smartTriage_server.module.alert.dto.ClinicalAlertResponse payload =
                com.smartTriage.smartTriage_server.module.alert.dto.ClinicalAlertResponse.builder()
                    .id(java.util.UUID.randomUUID())
                    .alertType(com.smartTriage.smartTriage_server.common.enums.AlertType.BED_AVAILABLE)
                    .severity(com.smartTriage.smartTriage_server.common.enums.AlertSeverity.MEDIUM)
                    .targetZone(zone)
                    .title(zone + " bed available — " + bed.getCode())
                    .message(reason + ". Bed " + bed.getCode() + " in " + zone
                            + " zone is ready for the next patient.")
                    .autoGenerated(true)
                    .acknowledged(false)
                    .createdAt(Instant.now())
                    .build();
            eventPublisher.publishHospitalAlert(hospitalId, payload);
            eventPublisher.publishZoneAlert(hospitalId, zone, payload);
        } catch (Exception e) {
            log.warn("Failed to broadcast BED_AVAILABLE alert for {}: {}",
                    bed.getCode(), e.getMessage());
        }
    }

    // ====================================================================
    // SEED DEFAULTS  (Phase G #4 + #5)
    // ====================================================================

    /**
     * Result of a seed run — surfaced to the admin UI so the empty-state
     * button can show "Seeded 11 beds across 5 zones" or "Seeded 4 beds
     * (skipped 3 zones that already had beds)".
     */
    public record SeedResult(
            int bedsCreated,
            List<EdZone> zonesSeeded,
            List<EdZone> zonesSkipped,
            String tierUsed
    ) {}

    /**
     * Seed the default bed inventory for a hospital, keyed by the hospital's
     * tier string ({@link BedDefaultsConfig#normalise}). <b>Idempotent
     * per-zone</b>: if a zone already has any beds, it is skipped entirely
     * (see Phase G design note). This protects manual admin edits — a
     * Charge Nurse who deleted a bed because it's structurally unusable
     * doesn't have it silently regenerated when a teammate hits "Seed
     * defaults" or when the hospital was just re-saved.
     *
     * <p>Called from two places:
     * <ul>
     *   <li>{@code HospitalService.createHospital} — auto-seed on create
     *       so newly-onboarded hospitals don't sit at zero beds and break
     *       triage placement.</li>
     *   <li>{@code POST /api/v1/beds/hospital/{id}/seed-defaults} — admin
     *       backfill for any hospital that pre-dates the auto-seed (e.g.
     *       hospitals created post-V18 but before this code shipped) or
     *       had its tier corrected.</li>
     * </ul>
     *
     * <p>Wrapped at the call-site (HospitalService) in try/catch so a seed
     * failure doesn't roll back hospital creation — the hospital still
     * persists and the admin can retry via the backfill endpoint.
     */
    @Transactional
    public SeedResult seedDefaultBedsForHospital(UUID hospitalId) {
        Hospital hospital = hospitalService.findHospitalOrThrow(hospitalId);
        String tier = hospital.getTier();
        BedDefaultsConfig.Tier resolvedTier = BedDefaultsConfig.normalise(tier);
        List<BedDefaultsConfig.ZoneDefault> defaults =
                BedDefaultsConfig.defaultsForTier(tier);

        int totalCreated = 0;
        List<EdZone> seeded = new ArrayList<>();
        List<EdZone> skipped = new ArrayList<>();

        for (BedDefaultsConfig.ZoneDefault def : defaults) {
            // Idempotency guard: any beds in this zone → leave it alone.
            // Treats the admin's count as authoritative once they've
            // touched a zone. Top-up logic (insert until target) was
            // explicitly rejected in Phase G design — too easy to undo a
            // deliberate manual deletion.
            long existing = bedRepository
                    .countByHospitalIdAndZoneAndIsActiveTrue(hospitalId, def.zone());
            if (existing > 0) {
                skipped.add(def.zone());
                continue;
            }

            String codePrefix = BedDefaultsConfig.codePrefixFor(def.zone());
            String labelPrefix = BedDefaultsConfig.labelPrefixFor(def.zone());

            for (int i = 1; i <= def.count(); i++) {
                String code = codePrefix + i;
                String label = labelPrefix + " " + i;

                // Defensive: if for some reason the (hospital, code) pair
                // already exists from a partial historical seed (e.g. V18
                // populated before tier matched), skip that one bed rather
                // than fail the whole batch with DuplicateResourceException.
                if (bedRepository.findByHospitalIdAndCodeAndIsActiveTrue(hospitalId, code).isPresent()) {
                    log.warn("[bedseed] Hospital {} already has bed {} — skipping",
                            hospitalId, code);
                    continue;
                }

                Bed bed = Bed.builder()
                        .hospital(hospital)
                        .zone(def.zone())
                        .code(code)
                        .label(label)
                        .status(BedStatus.AVAILABLE)
                        .hasMonitor(def.hasMonitor())
                        .displayOrder(i)
                        .build();
                bed = bedRepository.save(bed);
                publishBedChange(bed, "CREATED");
                totalCreated++;
            }
            seeded.add(def.zone());
        }

        log.info("[bedseed] Hospital {} ({} tier → {}): created {} beds across {} zones, skipped {}",
                hospital.getHospitalCode(), tier, resolvedTier,
                totalCreated, seeded.size(), skipped.size());

        return new SeedResult(totalCreated, seeded, skipped, resolvedTier.name());
    }

    // ====================================================================
    // BED SUGGESTION  (Phase G #2)
    // ====================================================================

    /**
     * Suggest an available bed for a triaged visit. Returns
     * {@code Optional.empty()} when no suitable bed exists (zone full, or
     * the category doesn't route to a bed-bearing zone — YELLOW/GREEN
     * adults flow through GENERAL which is bed-less by design).
     *
     * <p>Routing rules (Phase G design — there is no separate
     * ZoneRoutingService in this codebase, so the rules live here):
     * <ul>
     *   <li>RED → RESUS (life-threat overrides age)</li>
     *   <li>ORANGE → PEDIATRIC if visit.isPediatric, else ACUTE</li>
     *   <li>YELLOW → PEDIATRIC if visit.isPediatric, else no suggestion
     *       (YELLOW adults go to GENERAL which has no beds in the V18
     *       data model)</li>
     *   <li>GREEN → no suggestion (ambulatory)</li>
     *   <li>BLUE → no suggestion (DOA)</li>
     *   <li>null category → no suggestion (visit not yet triaged)</li>
     * </ul>
     *
     * <p>Within the chosen zone, available beds are returned in
     * {@code displayOrder ASC}. For RED and ORANGE, the suggestion
     * <em>prefers</em> a bed with {@code hasMonitor=true} when one is
     * available; if all monitored beds are taken, it falls back to the
     * first available bed in the zone rather than returning empty —
     * placement in any RESUS/ACUTE bed beats no placement.
     *
     * <p>Failure-mode: if RED returns empty, that's a surge signal worth
     * logging (the surge dashboard already covers ED-wide capacity, so
     * no separate alert here, but the audit trace shows what the system
     * showed the nurse).
     */
    /**
     * Definitive truth — is a bedside monitor currently streaming for
     * this visit? Used by TriageService to build an accurate
     * auto-placement note ("monitor streaming" vs "no monitor"). We
     * check session existence rather than bed.hasMonitor because that
     * cosmetic flag can be stale; an active DeviceSession means the
     * simulator (or real device) is actually pushing vitals.
     */
    public boolean hasActiveSessionForVisit(UUID visitId) {
        return sessionRepository
                .findByVisitIdAndSessionActiveTrueAndIsActiveTrue(visitId)
                .isPresent();
    }

    public Optional<Bed> suggestBedForVisit(UUID visitId) {
        Visit visit = visitRepository.findByIdAndIsActiveTrue(visitId).orElse(null);
        if (visit == null) return Optional.empty();

        TriageCategory category = visit.getCurrentTriageCategory();
        if (category == null) return Optional.empty();

        // Use the visit's currentEdZone — ZoneRoutingService has already
        // decided it from category + hospital config (peds-resus,
        // ambulatory zone, neonatal unit). Trusting that decision keeps
        // bed suggestion in lockstep with the visit's actual routing.
        //
        // Previous version had a duplicate switch (pickTargetZone) that
        // disagreed for adult YELLOW (returned null → no suggestion at
        // all) even though the visit was routed to GENERAL. That left
        // adult YELLOW patients triaged but unplaced.
        EdZone targetZone = visit.getCurrentEdZone();
        if (targetZone == null || targetZone == EdZone.TRIAGE) {
            // No destination set (pre-triage) or still in the holding
            // zone — don't auto-suggest. Shouldn't happen in the
            // post-triage path but defensive.
            return Optional.empty();
        }

        UUID hospitalId = visit.getHospital().getId();
        List<Bed> available = bedRepository.findAvailableInZone(hospitalId, targetZone);
        if (available.isEmpty()) {
            log.warn("[bedsuggest] No available beds in {} for {} visit {} — "
                    + "manual placement required", targetZone, category, visit.getVisitNumber());
            return Optional.empty();
        }

        // Always prefer a monitored bed when one is available, regardless
        // of category. Continuous monitoring is the safer default; any
        // patient who DOESN'T need it can be transferred to a non-
        // monitored bed later, but the reverse (a deteriorating patient
        // on an unmonitored bed) is the harder failure to recover from.
        Optional<Bed> monitored = available.stream()
                .filter(Bed::isHasMonitor)
                .findFirst();
        if (monitored.isPresent()) return monitored;

        // Fallback: first available in the zone (already sorted by displayOrder).
        return Optional.of(available.get(0));
    }
}
