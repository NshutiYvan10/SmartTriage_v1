package com.smartTriage.smartTriage_server.module.iot;

import com.smartTriage.smartTriage_server.AbstractIntegrationTest;
import com.smartTriage.smartTriage_server.common.enums.ArrivalMode;
import com.smartTriage.smartTriage_server.common.enums.DeviceStatus;
import com.smartTriage.smartTriage_server.common.enums.DeviceType;
import com.smartTriage.smartTriage_server.common.enums.Gender;
import com.smartTriage.smartTriage_server.common.exception.IdentityConflictException;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.iot.dto.OpenVisitForCardRequest;
import com.smartTriage.smartTriage_server.module.iot.dto.RfidTapResponse;
import com.smartTriage.smartTriage_server.module.iot.entity.IoTDevice;
import com.smartTriage.smartTriage_server.module.iot.repository.IoTDeviceRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RfidService;
import com.smartTriage.smartTriage_server.module.patient.dto.CrossHospitalSafetySummaryResponse;
import com.smartTriage.smartTriage_server.module.patient.dto.RegisterPatientRequest;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.entity.PersonIdentity;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientRepository;
import com.smartTriage.smartTriage_server.module.patient.repository.PersonIdentityRepository;
import com.smartTriage.smartTriage_server.module.patient.service.CrossHospitalIdentityService;
import com.smartTriage.smartTriage_server.module.patient.service.PatientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end against REAL PostgreSQL for the RFID tap-to-identify loop (V95):
 * register a patient + card at hospital A → tap "at hospital B" resolves them SYSTEM-WIDE → opening
 * a visit at B creates a fresh local record linked to the same shared identity (never re-registered
 * blank) while A's history is visible by card. Plus: unknown card → NOT_FOUND; a duplicate card on a
 * different person is rejected (no silent merge); and a card-only patient (no national ID) is still
 * found cross-hospital — the high-acuity case the feature exists for.
 */
@Transactional
class RfidIntegrationTest extends AbstractIntegrationTest {

    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private PatientService patientService;
    @Autowired private PatientRepository patientRepository;
    @Autowired private PersonIdentityRepository personIdentityRepository;
    @Autowired private IoTDeviceRepository ioTDeviceRepository;
    @Autowired private RfidService rfidService;
    @Autowired private CrossHospitalIdentityService crossHospitalIdentityService;

    private Hospital hospital(String suffix) {
        return hospitalRepository.save(Hospital.builder()
                .name("RF " + suffix).hospitalCode("RF-" + suffix).build());
    }

    private IoTDevice rfidReader(Hospital h, String suffix) {
        return ioTDeviceRepository.save(IoTDevice.builder()
                .serialNumber("RFID-" + suffix)
                .deviceName("Desk Reader " + suffix)
                .deviceType(DeviceType.RFID_READER)
                .apiKey("key-" + suffix)
                .hospital(h)
                .status(DeviceStatus.REGISTERED)
                .build());
    }

    private RegisterPatientRequest reg(UUID hospitalId, String first, String last, String nid, String card) {
        return RegisterPatientRequest.builder()
                .firstName(first).lastName(last)
                .dateOfBirth(LocalDate.now().minusYears(30)).gender(Gender.MALE)
                .nationalId(nid).rfidCardId(card)
                .hospitalId(hospitalId).chiefComplaint("test").build();
    }

    @Test
    void cardTappedAtHospitalB_findsPatientFromHospitalA_andOpensLocalVisit() {
        String s = UUID.randomUUID().toString().substring(0, 6);
        Hospital a = hospital("A-" + s);
        Hospital b = hospital("B-" + s);
        String card = "CARD-" + s;
        String nid = "119" + s;

        UUID patientAId = patientService.registerPatientWithVisit(reg(a.getId(), "Jean", "Bosco", nid, card))
                .getPatient().getId();
        PersonIdentity identity = personIdentityRepository.findByRfidCardIdAndIsActiveTrue(card).orElseThrow();
        assertEquals(nid, identity.getNationalId(), "identity is anchored by both national ID and card");

        // Tap "at hospital B" — system-wide lookup finds the person first seen at A.
        IoTDevice readerB = rfidReader(b, s);
        RfidTapResponse tap = rfidService.tap(readerB.getId(), card);
        assertEquals("FOUND", tap.getResult());
        assertTrue(tap.getPatientName().contains("Jean"), "the device shows the patient's name");

        // No local record at B yet.
        assertTrue(patientRepository
                .findByPersonIdentityIdAndHospitalIdAndIsActiveTrue(identity.getId(), b.getId()).isEmpty());

        // Registrar opens a visit at B → fresh local patient + visit, linked to the SAME identity.
        var opened = rfidService.openVisitForCard(OpenVisitForCardRequest.builder()
                .cardId(card).hospitalId(b.getId()).arrivalMode(ArrivalMode.WALK_IN).build());
        assertNotNull(opened.getVisit());
        List<Patient> localB = patientRepository
                .findByPersonIdentityIdAndHospitalIdAndIsActiveTrue(identity.getId(), b.getId());
        assertEquals(1, localB.size(), "a local B record is created for the returning patient");
        assertNotEquals(patientAId, localB.get(0).getId(), "distinct local row from hospital A's");
        assertEquals(identity.getId(), localB.get(0).getPersonIdentity().getId(), "linked to the shared identity");
        assertEquals("Jean", localB.get(0).getFirstName(), "demographics copied from the shared identity");

        // A's history is visible at B by card.
        CrossHospitalSafetySummaryResponse summary = crossHospitalIdentityService.getByRfidCardId(card);
        assertTrue(summary.isFound());
        assertTrue(summary.getLinkedHospitalCount() >= 2, "summary spans both hospitals after the B visit");
    }

    @Test
    void tapUnknownCard_returnsNotFound() {
        String s = UUID.randomUUID().toString().substring(0, 6);
        IoTDevice reader = rfidReader(hospital("C-" + s), "C" + s);
        assertEquals("NOT_FOUND", rfidService.tap(reader.getId(), "NOPE-" + s).getResult());
    }

    @Test
    void duplicateCardOnADifferentPerson_isRejected_neverSilentlyMerged() {
        String s = UUID.randomUUID().toString().substring(0, 6);
        Hospital a = hospital("D-" + s);
        String card = "DUP-" + s;
        patientService.registerPatientWithVisit(reg(a.getId(), "First", "Person", "nidA" + s, card));
        // A genuinely different person (different national ID) presenting the same card UID must be
        // rejected — a wrong merge would surface the first patient's record.
        assertThrows(IdentityConflictException.class, () ->
                patientService.registerPatientWithVisit(reg(a.getId(), "Second", "Person", "nidB" + s, card)));
    }

    @Test
    void cardOnlyPatient_withNoNationalId_isFoundCrossHospital() {
        String s = UUID.randomUUID().toString().substring(0, 6);
        Hospital a = hospital("E-" + s);
        Hospital b = hospital("F-" + s);
        String card = "CO-" + s;

        patientService.registerPatientWithVisit(reg(a.getId(), "Unconscious", "Arrival", null, card));
        PersonIdentity identity = personIdentityRepository.findByRfidCardIdAndIsActiveTrue(card).orElseThrow();
        assertNull(identity.getNationalId(), "anchored by card alone — the no-ID ED case");

        IoTDevice readerB = rfidReader(b, "F" + s);
        RfidTapResponse tap = rfidService.tap(readerB.getId(), card);
        assertEquals("FOUND", tap.getResult(), "a card-only patient is still found at another hospital");

        CrossHospitalSafetySummaryResponse summary = crossHospitalIdentityService.getByRfidCardId(card);
        assertTrue(summary.isFound());
        assertFalse(summary.isFound() && summary.getFirstName() == null);
    }
}
