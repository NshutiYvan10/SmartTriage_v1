package com.smartTriage.smartTriage_server.module.iot.service;

import com.smartTriage.smartTriage_server.common.enums.DeviceStatus;
import com.smartTriage.smartTriage_server.common.enums.DeviceType;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
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
    private final VisitService visitService;
    private final RealTimeEventPublisher realTimeEventPublisher;

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

        Map<String, Object> evt = new HashMap<>();
        evt.put("type", "CARD_FOUND");
        evt.put("cardId", card);
        evt.put("identityId", identity.getId().toString());
        evt.put("patientName", name);
        evt.put("linkedHospitalCount", hospitalCount);
        if (identity.getNationalId() != null) evt.put("nationalId", identity.getNationalId());
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
        return patientService.registerPatientWithVisit(reg);
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
}
