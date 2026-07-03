package com.smartTriage.smartTriage_server.module.registrar;

import com.smartTriage.smartTriage_server.AbstractIntegrationTest;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.ArrivalMode;
import com.smartTriage.smartTriage_server.common.enums.Designation;
import com.smartTriage.smartTriage_server.common.enums.DeviceStatus;
import com.smartTriage.smartTriage_server.common.enums.DeviceType;
import com.smartTriage.smartTriage_server.common.enums.Gender;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.common.enums.VisitStatus;
import com.smartTriage.smartTriage_server.common.exception.DuplicateResourceException;
import com.smartTriage.smartTriage_server.common.exception.IdentityConflictException;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.iot.dto.OpenVisitForCardRequest;
import com.smartTriage.smartTriage_server.module.iot.dto.RfidTapResponse;
import com.smartTriage.smartTriage_server.module.iot.entity.IoTDevice;
import com.smartTriage.smartTriage_server.module.iot.repository.IoTDeviceRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RfidService;
import com.smartTriage.smartTriage_server.module.patient.dto.GlobalPatientRow;
import com.smartTriage.smartTriage_server.module.patient.dto.RegisterPatientRequest;
import com.smartTriage.smartTriage_server.module.patient.dto.ResolveIdentityRequest;
import com.smartTriage.smartTriage_server.module.patient.dto.UpdatePatientRequest;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.entity.PersonIdentity;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientRepository;
import com.smartTriage.smartTriage_server.module.patient.repository.PersonIdentityRepository;
import com.smartTriage.smartTriage_server.module.patient.service.PatientIdentityService;
import com.smartTriage.smartTriage_server.module.patient.service.PatientService;
import com.smartTriage.smartTriage_server.module.patient.service.IdentityOverdueScheduler;
import com.smartTriage.smartTriage_server.module.user.dto.CreateUserRequest;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import com.smartTriage.smartTriage_server.module.user.service.UserService;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import com.smartTriage.smartTriage_server.security.ClinicalAuthz;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end registrar workflow suite — the goal's test matrix, exercised against a real
 * Postgres (Testcontainers). Each test is one scenario with explicit pass/fail assertions:
 *   • register with national ID + RFID card (identity anchored by both)
 *   • return at another hospital via card tap → cross-hospital lookup + fresh local visit
 *   • unidentified patient → resolve via RFID tap (name + card), audit recorded
 *   • unidentified patient → resolve via manual national ID
 *   • escalating identity reminders: registrar (30m) then charge nurse (2h)
 *   • duplicate national ID rejected
 *   • duplicate RFID card (different person) rejected
 *   • card-not-found tap → NOT_FOUND (manual fallback unaffected)
 *   • global registry search is REGISTRAR/SUPER_ADMIN only — doctors/nurses denied
 *   • registrar demographic correction persists + dup-NID guard
 * (Return-tap / card-not-found / duplicate-card are also covered in RfidIntegrationTest;
 *  repeated here so the registrar journey reads end-to-end in one place.)
 */
@Transactional
class RegistrarWorkflowIntegrationTest extends AbstractIntegrationTest {

    private static final String PW = "password123";

    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private PatientService patientService;
    @Autowired private PatientRepository patientRepository;
    @Autowired private PersonIdentityRepository personIdentityRepository;
    @Autowired private PatientIdentityService patientIdentityService;
    @Autowired private VisitRepository visitRepository;
    @Autowired private IdentityOverdueScheduler identityOverdueScheduler;
    @Autowired private ClinicalAlertRepository alertRepository;
    @Autowired private IoTDeviceRepository ioTDeviceRepository;
    @Autowired private RfidService rfidService;
    @Autowired private ClinicalAuthz clinicalAuthz;

    @AfterEach
    void clearSecurity() { SecurityContextHolder.clearContext(); }

    // ── seeding helpers ──
    private Hospital hospital(String suffix) {
        return hospitalRepository.save(Hospital.builder()
                .name("Reg Test " + suffix).hospitalCode("RGT-" + suffix).build());
    }

    private User user(String email, Role role, Designation designation, UUID hospitalId) {
        userService.createUser(CreateUserRequest.builder()
                .firstName("T").lastName(role.name()).email(email).password(PW)
                .role(role).designation(designation).hospitalId(hospitalId).build());
        return userRepository.findByEmailAndIsActiveTrue(email).orElseThrow();
    }

    private Authentication authOf(User u) {
        return new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities());
    }

    private void actingAs(User u) {
        SecurityContextHolder.getContext().setAuthentication(authOf(u));
    }

    private RegisterPatientRequest reg(UUID hospitalId, String first, String last, String nid, String card) {
        return RegisterPatientRequest.builder()
                .firstName(first).lastName(last)
                .dateOfBirth(LocalDate.now().minusYears(30)).gender(Gender.MALE)
                .nationalId(nid).rfidCardId(card)
                .hospitalId(hospitalId).chiefComplaint("test").build();
    }

    private Patient unidentified(Hospital h, String label, Instant assignedAt) {
        return patientRepository.save(Patient.builder()
                .firstName("Unknown").lastName(label)
                .hospital(h)
                .isUnidentified(true)
                .placeholderLabel(label)
                .placeholderAssignedAt(assignedAt)
                .build());
    }

    private Visit visitFor(Patient p, Hospital h) {
        return visitRepository.save(Visit.builder()
                .patient(p).hospital(h)
                .visitNumber("V-" + UUID.randomUUID().toString().substring(0, 8))
                .arrivalTime(Instant.now())
                .status(VisitStatus.REGISTERED)
                .build());
    }

    // ── 1. Register with national ID + RFID card at Hospital A ──
    @Test
    void register_withNationalIdAndCard_anchorsSharedIdentity_andQueuesForTriage() {
        String s = UUID.randomUUID().toString().substring(0, 6);
        Hospital a = hospital("A" + s);
        var resp = patientService.registerPatientWithVisit(reg(a.getId(), "Jean", "Bosco", "nid" + s, "CARD" + s));

        assertNotNull(resp.getPatient(), "patient created");
        assertNotNull(resp.getVisit(), "visit created atomically");
        assertEquals(VisitStatus.REGISTERED, resp.getVisit().getStatus(), "visit enters the triage queue as REGISTERED");
        PersonIdentity id = personIdentityRepository.findByRfidCardIdAndIsActiveTrue("CARD" + s).orElseThrow();
        assertEquals("nid" + s, id.getNationalId(), "shared identity anchored by BOTH national ID and card");
    }

    // ── 2. Return at Hospital B via card tap → system-wide lookup + fresh local visit ──
    @Test
    void returnAtHospitalB_cardTapFindsPatient_opensLocalVisit_priorHistoryLinked() {
        String s = UUID.randomUUID().toString().substring(0, 6);
        Hospital a = hospital("A" + s);
        Hospital b = hospital("B" + s);
        String card = "CARD" + s;
        UUID patientA = patientService.registerPatientWithVisit(reg(a.getId(), "Marie", "Uwimana", "nid" + s, card))
                .getPatient().getId();

        RfidTapResponse tap = rfidService.tap(
                ioTDeviceRepository.save(IoTDevice.builder().serialNumber("RD" + s).deviceName("Desk " + s)
                        .deviceType(DeviceType.RFID_READER).apiKey("k" + s).hospital(b).status(DeviceStatus.REGISTERED).build())
                        .getId(), card);
        assertEquals("FOUND", tap.getResult(), "system-wide lookup finds the person at hospital B");

        var opened = rfidService.openVisitForCard(OpenVisitForCardRequest.builder()
                .cardId(card).hospitalId(b.getId()).arrivalMode(ArrivalMode.WALK_IN).build());
        assertEquals(VisitStatus.REGISTERED, opened.getVisit().getStatus(), "fresh visit at B in the triage queue");
        PersonIdentity id = personIdentityRepository.findByRfidCardIdAndIsActiveTrue(card).orElseThrow();
        List<Patient> localB = patientRepository.findByPersonIdentityIdAndHospitalIdAndIsActiveTrue(id.getId(), b.getId());
        assertEquals(1, localB.size(), "a local B record was created for the returning patient");
        assertNotEquals(patientA, localB.get(0).getId(), "distinct from the hospital-A row");
        assertEquals(id.getId(), localB.get(0).getPersonIdentity().getId(), "linked to the same cross-hospital identity");
    }

    // ── 3. Unidentified patient → resolve via RFID tap (name + card), audit recorded ──
    @Test
    void unidentifiedPatient_resolvedViaRfid_updatesRecord_withAuditTrail() {
        String s = UUID.randomUUID().toString().substring(0, 6);
        Hospital a = hospital("A" + s);
        User registrar = user("reg" + s + "@t.rw", Role.REGISTRAR, Designation.REGISTRAR, a.getId());
        Patient ph = unidentified(a, "Alpha", Instant.now());
        visitFor(ph, a);
        assertTrue(ph.isUnidentified(), "starts flagged unidentified");

        actingAs(registrar);
        Patient resolved = patientIdentityService.resolveIdentity(ph.getId(), ResolveIdentityRequest.builder()
                .firstName("Claude").lastName("Habimana").rfidCardId("RESQ" + s)
                .resolutionNote("Family arrived with card").build());

        assertFalse(resolved.isUnidentified(), "flag cleared after resolution");
        assertEquals("Claude", resolved.getFirstName());
        assertNotNull(resolved.getIdentifiedAt(), "identified timestamp recorded (audit)");
        assertNotNull(resolved.getIdentifiedBy(), "identified-by actor recorded (audit)");
        assertEquals(registrar.getId(), resolved.getIdentifiedBy().getId(), "audit attributes the resolving registrar");
        assertNotNull(resolved.getPersonIdentity(), "card anchor attached → findable cross-hospital");
        assertEquals("RESQ" + s, resolved.getPersonIdentity().getRfidCardId());
    }

    // ── 4. Unidentified patient → resolve via manual national ID ──
    @Test
    void unidentifiedPatient_resolvedViaManualNationalId_linksSharedIdentity() {
        String s = UUID.randomUUID().toString().substring(0, 6);
        Hospital a = hospital("A" + s);
        User registrar = user("reg" + s + "@t.rw", Role.REGISTRAR, Designation.REGISTRAR, a.getId());
        Patient ph = unidentified(a, "Bravo", Instant.now());
        visitFor(ph, a);

        actingAs(registrar);
        Patient resolved = patientIdentityService.resolveIdentity(ph.getId(), ResolveIdentityRequest.builder()
                .firstName("Alice").lastName("Mukamana").nationalId("nid" + s).build());

        assertFalse(resolved.isUnidentified());
        assertEquals("nid" + s, resolved.getNationalId());
        assertNotNull(resolved.getPersonIdentity(), "linked to shared identity by the entered national ID");
        assertEquals("nid" + s, resolved.getPersonIdentity().getNationalId());
    }

    // ── 5. Escalating reminders: registrar (30m) then charge nurse (2h) ──
    @Test
    void identityOverdue_escalates_registrarThenChargeNurse() {
        String s = UUID.randomUUID().toString().substring(0, 6);
        Hospital a = hospital("A" + s);

        // Tier 1 only: unidentified 35 min → registrar reminder, no charge-nurse escalation yet.
        Patient p1 = unidentified(a, "Charlie", Instant.now().minus(35, ChronoUnit.MINUTES));
        Visit v1 = visitFor(p1, a);
        // Tier 2: unidentified 2h5m → both the registrar reminder AND the charge-nurse escalation.
        Patient p2 = unidentified(a, "Delta", Instant.now().minus(125, ChronoUnit.MINUTES));
        Visit v2 = visitFor(p2, a);

        identityOverdueScheduler.scanForOverdueIdentities();

        assertTrue(alertRepository.existsByVisitIdAndAlertTypeAndIsActiveTrue(v1.getId(), AlertType.IDENTITY_UNRESOLVED),
                "tier-1 registrar reminder raised at 30m");
        assertFalse(alertRepository.existsByVisitIdAndAlertTypeAndIsActiveTrue(v1.getId(), AlertType.IDENTITY_UNRESOLVED_ESCALATED),
                "no charge-nurse escalation before 2h");
        assertTrue(alertRepository.existsByVisitIdAndAlertTypeAndIsActiveTrue(v2.getId(), AlertType.IDENTITY_UNRESOLVED),
                "tier-1 registrar reminder also present at 2h");
        assertTrue(alertRepository.existsByVisitIdAndAlertTypeAndIsActiveTrue(v2.getId(), AlertType.IDENTITY_UNRESOLVED_ESCALATED),
                "tier-2 charge-nurse escalation raised at 2h");
    }

    // ── 6. Duplicate national ID rejected ──
    @Test
    void duplicateNationalId_atSameHospital_isRejected() {
        String s = UUID.randomUUID().toString().substring(0, 6);
        Hospital a = hospital("A" + s);
        patientService.registerPatientWithVisit(reg(a.getId(), "First", "Person", "dupnid" + s, null));
        assertThrows(DuplicateResourceException.class, () ->
                patientService.registerPatientWithVisit(reg(a.getId(), "Second", "Person", "dupnid" + s, null)));
    }

    // ── 7. Duplicate RFID card (different person) rejected ──
    @Test
    void duplicateCard_onADifferentPerson_isRejected() {
        String s = UUID.randomUUID().toString().substring(0, 6);
        Hospital a = hospital("A" + s);
        String card = "DUP" + s;
        patientService.registerPatientWithVisit(reg(a.getId(), "First", "Person", "nidA" + s, card));
        assertThrows(IdentityConflictException.class, () ->
                patientService.registerPatientWithVisit(reg(a.getId(), "Second", "Person", "nidB" + s, card)));
    }

    // ── 8. Card-not-found tap → NOT_FOUND (manual fallback unaffected) ──
    @Test
    void cardNotFound_tapReturnsNotFound() {
        String s = UUID.randomUUID().toString().substring(0, 6);
        Hospital a = hospital("A" + s);
        IoTDevice reader = ioTDeviceRepository.save(IoTDevice.builder().serialNumber("RD" + s).deviceName("Desk " + s)
                .deviceType(DeviceType.RFID_READER).apiKey("k" + s).hospital(a).status(DeviceStatus.REGISTERED).build());
        assertEquals("NOT_FOUND", rfidService.tap(reader.getId(), "NOPE" + s).getResult());
    }

    // ── 9. Global registry search: cross-hospital, REGISTRAR/SUPER_ADMIN only ──
    @Test
    void globalRegistrySearch_findsCrossHospital_andIsRegistrarOnly() {
        String s = UUID.randomUUID().toString().substring(0, 6);
        Hospital a = hospital("A" + s);
        Hospital b = hospital("B" + s);
        patientService.registerPatientWithVisit(reg(a.getId(), "Zawadi", "Ndoli", "nid" + s, null));

        // A registrar at B finds the A-registered patient system-wide.
        var page = patientService.globalRegistrySearch(b.getId(), "Ndoli", PageRequest.of(0, 20));
        assertTrue(page.getContent().stream().anyMatch(r -> "Ndoli".equals(r.getLastName())),
                "global search crosses hospitals");
        GlobalPatientRow row = page.getContent().stream().filter(r -> "Ndoli".equals(r.getLastName())).findFirst().orElseThrow();
        assertFalse(row.isLocalToMyHospital(), "flagged as NOT local to the searching (B) registrar");
        assertEquals(a.getName(), row.getHospitalName(), "originating hospital shown");

        // Authorization: the gate the @PreAuthorize calls admits only registrar-class roles.
        User registrar = user("reg" + s + "@t.rw", Role.REGISTRAR, Designation.REGISTRAR, b.getId());
        User doctor = user("doc" + s + "@t.rw", Role.DOCTOR, Designation.MEDICAL_OFFICER, b.getId());
        User nurse = user("nur" + s + "@t.rw", Role.NURSE, Designation.STAFF_NURSE, b.getId());
        assertTrue(clinicalAuthz.canSearchGlobalRegistry(authOf(registrar)), "registrar may search the registry");
        assertFalse(clinicalAuthz.canSearchGlobalRegistry(authOf(doctor)), "doctor may NOT search the global registry");
        assertFalse(clinicalAuthz.canSearchGlobalRegistry(authOf(nurse)), "nurse may NOT search the global registry");
    }

    // ── 10. Registrar demographic correction persists + dup-NID guard ──
    @Test
    void demographicCorrection_persists_andBlocksDuplicateNationalId() {
        String s = UUID.randomUUID().toString().substring(0, 6);
        Hospital a = hospital("A" + s);
        User registrar = user("reg" + s + "@t.rw", Role.REGISTRAR, Designation.REGISTRAR, a.getId());
        UUID pid = patientService.registerPatientWithVisit(reg(a.getId(), "Jon", "Doe", "nid" + s, null)).getPatient().getId();
        // Another patient occupies a different NID we'll try to collide with.
        patientService.registerPatientWithVisit(reg(a.getId(), "Other", "Person", "taken" + s, null));

        actingAs(registrar);
        // Correct a misspelled name + phone.
        var updated = patientService.updateDemographics(pid, UpdatePatientRequest.builder()
                .firstName("John").phoneNumber("0788000111").build());
        assertEquals("John", updated.getFirstName(), "name correction persisted");
        assertEquals("0788000111", updated.getPhoneNumber(), "phone correction persisted");

        // Correcting to a national ID already held by another patient here is rejected.
        assertThrows(DuplicateResourceException.class, () ->
                patientService.updateDemographics(pid, UpdatePatientRequest.builder().nationalId("taken" + s).build()));
    }
}
