package com.smartTriage.smartTriage_server.module.override.service;

import com.smartTriage.smartTriage_server.module.consent.entity.BreakTheGlassEvent;
import com.smartTriage.smartTriage_server.module.consent.repository.BreakTheGlassEventRepository;
import com.smartTriage.smartTriage_server.module.lab.entity.LabOrder;
import com.smartTriage.smartTriage_server.module.lab.repository.LabOrderRepository;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationAdministration;
import com.smartTriage.smartTriage_server.module.medication.repository.MedicationAdministrationRepository;
import com.smartTriage.smartTriage_server.module.medication.repository.MedicationDoseRepository;
import com.smartTriage.smartTriage_server.module.medsafety.entity.MedicationSafetyCheck;
import com.smartTriage.smartTriage_server.module.medsafety.repository.MedicationSafetyCheckRepository;
import com.smartTriage.smartTriage_server.module.override.dto.OverrideRecordResponse;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.entity.PersonIdentity;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Evidence that the unified Override Register merges every source into typed rows that
 * each answer who / on whom / when / why, that break-the-glass is masked (never leaks a
 * patient name / full national id), that one prescription carrying both an allergy AND an
 * interaction override emits two distinct rows, and that the type / patient filters work.
 */
class OverrideRegisterServiceTest {

    private MedicationSafetyCheckRepository safetyCheckRepo;
    private LabOrderRepository labOrderRepo;
    private MedicationDoseRepository doseRepo;
    private MedicationAdministrationRepository medicationRepo;
    private BreakTheGlassEventRepository btgRepo;
    private OverrideRegisterService service;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        safetyCheckRepo = mock(MedicationSafetyCheckRepository.class);
        labOrderRepo = mock(LabOrderRepository.class);
        doseRepo = mock(MedicationDoseRepository.class);
        medicationRepo = mock(MedicationAdministrationRepository.class);
        btgRepo = mock(BreakTheGlassEventRepository.class);
        service = new OverrideRegisterService(safetyCheckRepo, labOrderRepo, doseRepo, medicationRepo, btgRepo);

        when(safetyCheckRepo.findOverriddenForHospital(any(), any(), any())).thenReturn(List.of());
        when(labOrderRepo.findVerificationOverriddenForHospital(any(), any(), any())).thenReturn(List.of());
        when(doseRepo.findOverriddenForHospital(any(), any(), any())).thenReturn(List.of());
        when(medicationRepo.findOverridesForHospital(any(), any(), any())).thenReturn(List.of());
        when(btgRepo.findForHospitalRange(any(), any(), any())).thenReturn(List.of());
    }

    private Visit visit() {
        Patient p = Patient.builder().firstName("Jane").lastName("Doe").build();
        p.setId(patientId);
        Visit v = new Visit();
        v.setId(UUID.randomUUID());
        v.setVisitNumber("V-1");
        v.setPatient(p);
        return v;
    }

    @Test
    @DisplayName("A prescription with BOTH allergy and interaction overrides emits two typed rows, each with who/whom/when/why")
    void allergyAndInteractionEmitTwoRows() {
        MedicationAdministration m = MedicationAdministration.builder()
                .visit(visit()).drugName("Amoxicillin").prescribedByName("Dr A").prescribedAt(Instant.now())
                .prescribedDespiteAllergy(true).allergyOverrideReason("prior tolerance").allergyOverrideMatches("penicillin")
                .prescribedDespiteInteraction(true).interactionOverrideReason("benefit outweighs").interactionOverrideMatches("warfarin")
                .build();
        m.setId(UUID.randomUUID());
        when(medicationRepo.findOverridesForHospital(any(), any(), any())).thenReturn(List.of(m));

        List<OverrideRecordResponse> out = service.getOverrides(hospitalId, null, null, null, null);

        Map<String, OverrideRecordResponse> byType = index(out);
        assertThat(byType).containsKeys("PRESCRIBE_ALLERGY", "PRESCRIBE_INTERACTION");
        OverrideRecordResponse allergy = byType.get("PRESCRIBE_ALLERGY");
        assertThat(allergy.getActorName()).isEqualTo("Dr A");           // who
        assertThat(allergy.getPatientName()).isEqualTo("Jane Doe");     // on whom
        assertThat(allergy.getOccurredAt()).isNotNull();                // when
        assertThat(allergy.getJustification()).isEqualTo("prior tolerance"); // why
        assertThat(allergy.getSeverity()).isEqualTo("CRITICAL");
        assertThat(byType.get("PRESCRIBE_INTERACTION").getJustification()).isEqualTo("benefit outweighs");
    }

    @Test
    @DisplayName("Break-the-glass is masked — no patient name, national id reduced to last 4")
    void breakTheGlassIsMasked() {
        PersonIdentity pi = new PersonIdentity();
        pi.setNationalId("1199080123456189");
        BreakTheGlassEvent e = BreakTheGlassEvent.builder()
                .personIdentity(pi).actorName("Dr B").actorRole("DOCTOR").actorHospitalId(hospitalId)
                .reason("unconscious trauma").accessedAt(Instant.now()).acknowledged(false)
                .build();
        e.setId(UUID.randomUUID());
        when(btgRepo.findForHospitalRange(any(), any(), any())).thenReturn(List.of(e));

        OverrideRecordResponse r = service.getOverrides(hospitalId, null, null, null, null).get(0);
        assertThat(r.getOverrideType()).isEqualTo("BREAK_THE_GLASS");
        assertThat(r.getActorRole()).isEqualTo("DOCTOR");
        assertThat(r.getPatientName()).isNull();                  // never leak a name
        assertThat(r.getMaskedSubject()).isEqualTo("National ID ***6189");
        assertThat(r.getJustification()).isEqualTo("unconscious trauma");
    }

    @Test
    @DisplayName("Type filter returns only that override type")
    void typeFilter() {
        MedicationSafetyCheck c = MedicationSafetyCheck.builder()
                .visit(visit()).drugName("X").overriddenBy("Dr C").overrideReason("clinical call").overriddenAt(Instant.now())
                .build();
        c.setId(UUID.randomUUID());
        when(safetyCheckRepo.findOverriddenForHospital(any(), any(), any())).thenReturn(List.of(c));
        LabOrder o = new LabOrder();
        o.setId(UUID.randomUUID());
        o.setVisit(visit());
        o.setTestName("CBC");
        o.setOrderNumber("LAB-1");
        o.setVerificationOverrideByName("Tech D");
        o.setVerificationOverrideReason("stat, senior unavailable");
        o.setVerificationOverrideAt(Instant.now());
        when(labOrderRepo.findVerificationOverriddenForHospital(any(), any(), any())).thenReturn(List.of(o));

        List<OverrideRecordResponse> all = service.getOverrides(hospitalId, null, null, null, null);
        assertThat(all).hasSize(2);
        List<OverrideRecordResponse> labOnly = service.getOverrides(hospitalId, null, null, null, "LAB_VERIFICATION_BYPASS");
        assertThat(labOnly).hasSize(1);
        assertThat(labOnly.get(0).getOverrideType()).isEqualTo("LAB_VERIFICATION_BYPASS");
    }

    @Test
    @DisplayName("Patient filter keeps that patient's medication overrides and excludes break-the-glass (no patient)")
    void patientFilterExcludesBreakTheGlass() {
        MedicationSafetyCheck c = MedicationSafetyCheck.builder()
                .visit(visit()).drugName("X").overriddenBy("Dr C").overrideReason("r").overriddenAt(Instant.now())
                .build();
        c.setId(UUID.randomUUID());
        when(safetyCheckRepo.findOverriddenForHospital(any(), any(), any())).thenReturn(List.of(c));
        PersonIdentity pi = new PersonIdentity();
        pi.setNationalId("1199080123456189");
        BreakTheGlassEvent e = BreakTheGlassEvent.builder()
                .personIdentity(pi).actorName("Dr B").actorHospitalId(hospitalId).reason("r").accessedAt(Instant.now())
                .build();
        e.setId(UUID.randomUUID());
        when(btgRepo.findForHospitalRange(any(), any(), any())).thenReturn(List.of(e));

        List<OverrideRecordResponse> filtered = service.getOverrides(hospitalId, null, null, patientId, null);
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).getOverrideType()).isEqualTo("MED_SAFETY_CHECK");
    }

    private Map<String, OverrideRecordResponse> index(List<OverrideRecordResponse> rows) {
        java.util.HashMap<String, OverrideRecordResponse> m = new java.util.HashMap<>();
        for (OverrideRecordResponse r : rows) m.put(r.getOverrideType(), r);
        return m;
    }
}
