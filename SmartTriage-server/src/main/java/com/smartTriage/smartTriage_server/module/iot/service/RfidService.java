package com.smartTriage.smartTriage_server.module.iot.service;

import com.smartTriage.smartTriage_server.common.enums.DeviceStatus;
import com.smartTriage.smartTriage_server.common.enums.DeviceType;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.iot.dto.DeviceResponse;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import com.smartTriage.smartTriage_server.module.audit.service.AuditService;
import com.smartTriage.smartTriage_server.module.patient.dto.PatientResponse;
import com.smartTriage.smartTriage_server.module.patient.service.PersonIdentityService;
import com.smartTriage.smartTriage_server.module.iot.dto.OpenVisitForCardRequest;
import com.smartTriage.smartTriage_server.module.iot.dto.RfidTapResponse;
import com.smartTriage.smartTriage_server.module.iot.entity.IoTDevice;
import com.smartTriage.smartTriage_server.module.iot.mapper.IoTMapper;
import com.smartTriage.smartTriage_server.module.iot.repository.IoTDeviceRepository;
import com.smartTriage.smartTriage_server.module.patient.dto.RegisterPatientRequest;
import com.smartTriage.smartTriage_server.module.patient.dto.RegisterPatientResponse;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.entity.PersonIdentity;
import com.smartTriage.smartTriage_server.module.patient.mapper.PatientMapper;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientRepository;
import com.smartTriage.smartTriage_server.module.patient.repository.PersonIdentityRepository;
import com.smartTriage.smartTriage_server.module.patient.service.PatientService;
import com.smartTriage.smartTriage_server.module.visit.dto.CreateVisitRequest;
import com.smartTriage.smartTriage_server.module.visit.dto.VisitResponse;
import com.smartTriage.smartTriage_server.module.visit.service.VisitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RFID registration-reader workflow (V95). One physical ESP32+RFID device per registration desk taps
 * patient cards; the backend resolves identity SYSTEM-WIDE (a card first seen at hospital A resolves
 * the same person at hospital B) and pushes the result to the registrar's dashboard
 * ({@code /topic/rfid/{hospitalId}}), where the registrar confirms and opens a fresh local visit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RfidService {

    private static final Duration BIND_WINDOW = Duration.ofSeconds(30);

    private final IoTDeviceRepository ioTDeviceRepository;
    private final PersonIdentityRepository personIdentityRepository;
    private final PatientRepository patientRepository;
    private final PatientService patientService;
    private final PersonIdentityService personIdentityService;
    private final VisitService visitService;
    private final RealTimeEventPublisher realTimeEventPublisher;
    private final AuditService auditService;
    private final UserRepository userRepository;

    /**
     * Process a card tap from a reader (device already authenticated by API key in the controller).
     * Bind mode → capture the UID for the registration form; otherwise a system-wide identify lookup.
     * Always pushes a {@code /topic/rfid/{hospitalId}} event and returns the device-facing result.
     */
    @Transactional
    public RfidTapResponse tap(UUID deviceId, String rawCardId) {
        IoTDevice device = ioTDeviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("IoTDevice", "id", String.valueOf(deviceId)));
        UUID hospitalId = device.getHospital().getId();
        String card = normalize(rawCardId);

        // Heartbeat — the reader is alive.
        device.setLastHeartbeatAt(Instant.now());
        if (device.getStatus() == DeviceStatus.REGISTERED || device.getStatus() == DeviceStatus.OFFLINE) {
            device.setStatus(DeviceStatus.ONLINE);
        }

        // Bind mode (registration tap-to-capture): capture the UID, don't identify.
        if (device.getRfidBindUntil() != null && device.getRfidBindUntil().isAfter(Instant.now())) {
            device.setRfidBindUntil(null);
            ioTDeviceRepository.save(device);
            if (card != null) {
                // A card that ALREADY belongs to someone must not be captured onto a second
                // patient — a wristband has to resolve to exactly one person. Registration
                // would reject it anyway (PatientService.assertCardAssignable), but telling
                // the registrar HERE, at the tap, saves them filling a whole form first.
                var holder = patientService.describeCardHolder(card, hospitalId);
                if (holder.isPresent()) {
                    Map<String, Object> busy = new HashMap<>();
                    busy.put("type", "CARD_BIND");
                    busy.put("cardId", card);
                    busy.put("inUse", true);
                    busy.put("inUseMessage", holder.get().message());
                    if (holder.get().patientId() != null) {
                        busy.put("inUsePatientId", holder.get().patientId().toString());
                        busy.put("inUsePatientName", holder.get().patientName());
                    }
                    realTimeEventPublisher.publishRfidEvent(hospitalId, busy);
                    return RfidTapResponse.builder().result("CARD_IN_USE").build();
                }
                realTimeEventPublisher.publishRfidEvent(hospitalId, Map.of("type", "CARD_BIND", "cardId", card));
            }
            return RfidTapResponse.builder().result("CARD_CAPTURED").build();
        }
        ioTDeviceRepository.save(device);

        if (card == null) {
            return RfidTapResponse.builder().result("NOT_FOUND").build();
        }

        // System-wide identify lookup by card UID.
        PersonIdentity identity = personIdentityRepository.findByRfidCardIdAndIsActiveTrue(card).orElse(null);
        if (identity == null) {
            realTimeEventPublisher.publishRfidEvent(hospitalId, Map.of("type", "CARD_NOT_FOUND", "cardId", card));
            return RfidTapResponse.builder().result("NOT_FOUND").build();
        }

        List<Patient> linked = patientRepository.findByPersonIdentityIdAndIsActiveTrue(identity.getId());
        Patient newest = newestOf(linked);
        String name = displayName(newest);
        long hospitalCount = linked.stream()
                .map(p -> p.getHospital() != null ? p.getHospital().getId() : null)
                .filter(java.util.Objects::nonNull).distinct().count();
        // A card that resolves to a STILL-unidentified placeholder at THIS hospital (a
        // card was bound before a name was known) — steer the registrar to confirm +
        // resolve that temporary record rather than open a brand-new visit (goal 4.4).
        Patient localUnidentified = linked.stream()
                .filter(p -> p.isUnidentified() && p.getHospital() != null
                        && p.getHospital().getId().equals(hospitalId))
                .findFirst().orElse(null);

        Map<String, Object> evt = new HashMap<>();
        evt.put("type", "CARD_FOUND");
        evt.put("cardId", card);
        evt.put("identityId", identity.getId().toString());
        evt.put("patientName", name);
        evt.put("linkedHospitalCount", hospitalCount);
        if (identity.getNationalId() != null) evt.put("nationalId", identity.getNationalId());
        if (localUnidentified != null) {
            evt.put("unidentified", true);
            evt.put("unidentifiedPatientId", localUnidentified.getId().toString());
        }
        realTimeEventPublisher.publishRfidEvent(hospitalId, evt);

        return RfidTapResponse.builder()
                .result("FOUND")
                .patientName(name)
                .dateOfBirth(newest != null && newest.getDateOfBirth() != null ? newest.getDateOfBirth().toString() : null)
                .gender(newest != null && newest.getGender() != null ? newest.getGender().name() : null)
                .build();
    }

    /** Arm the registration tap-to-capture window on a reader (the next tap binds the UID). */
    @Transactional
    public void armBindMode(UUID deviceId) {
        IoTDevice device = ioTDeviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("IoTDevice", "id", String.valueOf(deviceId)));
        device.setRfidBindUntil(Instant.now().plus(BIND_WINDOW));
        ioTDeviceRepository.save(device);
        log.info("RFID bind mode armed on device {} for {}s", deviceId, BIND_WINDOW.toSeconds());
    }

    /**
     * Assign this RFID reader to a registrar (or clear the assignment when {@code registrarUserId}
     * is null). Admin-owned action (authorized in the controller). The registrar must be an ACTIVE
     * REGISTRAR at the SAME hospital as the reader, and the device must be an RFID_READER. Audited.
     */
    @Transactional
    public DeviceResponse assignRegistrar(UUID deviceId, UUID registrarUserId) {
        IoTDevice device = ioTDeviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("IoTDevice", "id", String.valueOf(deviceId)));
        if (device.getDeviceType() != DeviceType.RFID_READER) {
            throw new ClinicalBusinessException("Only a registration RFID reader can be assigned to a registrar.");
        }
        if (registrarUserId != null) {
            User registrar = userRepository.findById(registrarUserId)
                    .orElseThrow(() -> new ResourceNotFoundException("User", "id", String.valueOf(registrarUserId)));
            if (!registrar.isActive()) {
                throw new ClinicalBusinessException("That user account is inactive.");
            }
            if (registrar.getRole() != Role.REGISTRAR) {
                throw new ClinicalBusinessException("A registration reader can only be assigned to a Registrar.");
            }
            UUID readerHospital = device.getHospital().getId();
            UUID registrarHospital = userRepository.findHospitalIdByUserId(registrarUserId).orElse(null);
            if (registrarHospital == null || !registrarHospital.equals(readerHospital)) {
                throw new ClinicalBusinessException("The registrar must belong to the same hospital as the reader.");
            }
        }
        device.setAssignedRegistrarUserId(registrarUserId);
        ioTDeviceRepository.save(device);
        auditService.record("PATCH", "/api/v1/iot/rfid/devices/" + deviceId + "/assign-registrar",
                registrarUserId != null
                        ? "RFID_READER_ASSIGNED device=" + deviceId + " registrar=" + registrarUserId
                        : "RFID_READER_UNASSIGNED device=" + deviceId,
                200);
        return IoTMapper.toResponse(device);
    }

    /**
     * Registrar confirmed an RFID-found patient → open a fresh visit at this hospital. Reuses the
     * local row if one exists here; otherwise registers the returning patient locally from the shared
     * identity's demographics (linked to the same PersonIdentity) so they're never re-registered blank.
     */
    @Transactional
    public RegisterPatientResponse openVisitForCard(OpenVisitForCardRequest request) {
        String card = normalize(request.getCardId());
        PersonIdentity identity = card == null ? null
                : personIdentityRepository.findByRfidCardIdAndIsActiveTrue(card).orElse(null);
        if (identity == null) {
            throw new ResourceNotFoundException("Patient (RFID card)", "cardId", "***");
        }

        List<Patient> localHere = patientRepository
                .findByPersonIdentityIdAndHospitalIdAndIsActiveTrue(identity.getId(), request.getHospitalId());
        if (!localHere.isEmpty()) {
            Patient local = localHere.get(0);
            VisitResponse visit = visitService.createVisit(CreateVisitRequest.builder()
                    .patientId(local.getId())
                    .hospitalId(request.getHospitalId())
                    .arrivalMode(request.getArrivalMode())
                    .chiefComplaint(request.getChiefComplaint())
                    .build());
            return RegisterPatientResponse.builder()
                    .patient(PatientMapper.toResponse(local))
                    .visit(visit)
                    .build();
        }

        // No local record here yet — register the returning patient from the shared identity.
        Patient source = newestOf(patientRepository.findByPersonIdentityIdAndIsActiveTrue(identity.getId()));
        if (source == null) {
            // Identity exists but has no linked patient rows (shouldn't happen) — fail safe.
            throw new ResourceNotFoundException("Patient (RFID card)", "cardId", "***");
        }
        RegisterPatientRequest reg = RegisterPatientRequest.builder()
                .firstName(source.getFirstName())
                .lastName(source.getLastName())
                .dateOfBirth(source.getDateOfBirth())
                .gender(source.getGender())
                .nationalId(identity.getNationalId())
                .rfidCardId(identity.getRfidCardId())
                .bloodType(source.getBloodType())
                .phoneNumber(source.getPhoneNumber())
                .hospitalId(request.getHospitalId())
                .arrivalMode(request.getArrivalMode())
                .chiefComplaint(request.getChiefComplaint())
                .build();
        // adoptExistingCard = true: this card ALREADY anchors this identity — we are
        // re-registering the same person at this hospital, not assigning a taken card.
        return patientService.registerPatientWithVisit(reg, true);
    }

    /**
     * Replace a patient's RFID card (lost/damaged-card workflow). Sets the new card on the patient's
     * shared identity so the OLD card immediately stops resolving anywhere; rejects a card already
     * held by another patient; audits old → new. The new card is typically tap-captured.
     */
    @Transactional
    public PatientResponse replaceCardForPatient(UUID patientId, String newCardId) {
        Patient patient = patientService.findPatientOrThrow(patientId);
        PersonIdentity identity = patient.getPersonIdentity();
        if (identity == null) {
            // The card lives on the shared identity, which is created from a national ID / first card
            // at registration — there's nothing to "replace" yet.
            throw new ClinicalBusinessException(
                    "This patient has no shared identity yet — assign a card via registration first.");
        }
        String oldCard = personIdentityService.replaceCard(identity, newCardId);
        auditService.record("PUT", "/api/v1/iot/rfid/replace-card",
                "RFID_CARD_REPLACED patient=" + patientId + " old=" + mask(oldCard) + " new=" + mask(newCardId), 200);
        return PatientMapper.toResponse(patient);
    }

    /** RFID readers at a hospital — for the registration desk-device picker. */
    @Transactional(readOnly = true)
    public List<com.smartTriage.smartTriage_server.module.iot.dto.DeviceResponse> listDevices(UUID hospitalId) {
        return ioTDeviceRepository
                .findByHospitalIdAndDeviceTypeAndIsActiveTrueOrderByDeviceNameAsc(hospitalId, DeviceType.RFID_READER)
                .stream().map(IoTMapper::toResponse).toList();
    }

    // ── helpers ──
    private static Patient newestOf(List<Patient> linked) {
        return linked.stream().max(Comparator.comparing(RfidService::lastTouched)).orElse(null);
    }

    private static Instant lastTouched(Patient p) {
        return p.getUpdatedAt() != null ? p.getUpdatedAt()
                : (p.getCreatedAt() != null ? p.getCreatedAt() : Instant.EPOCH);
    }

    private static String displayName(Patient p) {
        if (p == null) return "Unknown patient";
        String n = ((p.getFirstName() != null ? p.getFirstName() : "") + " "
                + (p.getLastName() != null ? p.getLastName() : "")).trim();
        return n.isEmpty() ? "Unknown patient" : n;
    }

    private static String normalize(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Mask a card UID for audit (last 4 only). */
    private static String mask(String v) {
        return v == null || v.length() < 4 ? "(none)" : "***" + v.substring(v.length() - 4);
    }
}
