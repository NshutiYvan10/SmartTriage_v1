package com.smartTriage.smartTriage_server.module.medsafety.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.service.HospitalService;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationAdministration;
import com.smartTriage.smartTriage_server.module.medication.service.MedicationService;
import com.smartTriage.smartTriage_server.module.medsafety.engine.MedicationSafetyEngine;
import com.smartTriage.smartTriage_server.module.medsafety.entity.MedicationSafetyCheck;
import com.smartTriage.smartTriage_server.module.medsafety.repository.DrugFormularyRepository;
import com.smartTriage.smartTriage_server.module.medsafety.repository.MedicationSafetyCheckRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.service.VisitService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link MedicationSafetyService#overrideSafetyCheck} — proves a safety-check
 * override (a point-of-harm bypass) now: (1) attributes the override to the AUTHENTICATED
 * clinician, never a client-supplied string; (2) emits a MEDICATION_EMERGENCY_OVERRIDE alert
 * onto the forensic feed; (3) pushes it in real time; and (4) fails closed when no clinician
 * can be resolved.
 */
class MedicationSafetyServiceTest {

    private final MedicationSafetyEngine safetyEngine = mock(MedicationSafetyEngine.class);
    private final MedicationSafetyCheckRepository safetyCheckRepository = mock(MedicationSafetyCheckRepository.class);
    private final DrugFormularyRepository formularyRepository = mock(DrugFormularyRepository.class);
    private final ClinicalAlertRepository clinicalAlertRepository = mock(ClinicalAlertRepository.class);
    private final VisitService visitService = mock(VisitService.class);
    private final MedicationService medicationService = mock(MedicationService.class);
    private final HospitalService hospitalService = mock(HospitalService.class);
    private final RealTimeEventPublisher realTimeEventPublisher = mock(RealTimeEventPublisher.class);

    private final MedicationSafetyService service = new MedicationSafetyService(
            safetyEngine, safetyCheckRepository, formularyRepository, clinicalAlertRepository,
            visitService, medicationService, hospitalService, realTimeEventPublisher);

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private void authenticateAs(String first, String last, Role role) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setFirstName(first);
        u.setLastName(last);
        u.setEmail("clinician@hospital.rw");
        u.setRole(role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u, null, List.of()));
    }

    private MedicationSafetyCheck unsafeCheck() {
        Hospital h = new Hospital();
        h.setId(UUID.randomUUID());
        h.setName("Kigali Emergency Hospital");
        Visit v = new Visit();
        v.setId(UUID.randomUUID());
        v.setVisitNumber("V-OV-1");
        v.setHospital(h);
        MedicationAdministration med = new MedicationAdministration();
        med.setId(UUID.randomUUID());
        med.setDrugName("Heparin");
        return MedicationSafetyCheck.builder()
                .visit(v)
                .medication(med)
                .drugName("Heparin")
                .overallSafe(false)
                .allergyCheckPassed(false)
                .allergyWarning("Documented allergy: heparin")
                .doseCheckPassed(true)
                .interactionCheckPassed(true)
                .duplicateTherapyCheckPassed(true)
                .build();
    }

    @Test
    void overrideSafetyCheck_attributesAuthenticatedActor_emitsEmergencyOverrideAlert_andPublishes() {
        UUID checkId = UUID.randomUUID();
        MedicationSafetyCheck check = unsafeCheck();
        when(safetyCheckRepository.findByIdAndIsActiveTrue(checkId)).thenReturn(Optional.of(check));
        when(safetyCheckRepository.save(any(MedicationSafetyCheck.class))).thenAnswer(i -> i.getArgument(0));
        when(clinicalAlertRepository.save(any(ClinicalAlert.class))).thenAnswer(i -> i.getArgument(0));
        authenticateAs("Marie", "Uwimana", Role.DOCTOR);

        service.overrideSafetyCheck(checkId, "Active PE, anticoagulation indicated despite the flag");

        // (1) actor from principal, NOT a client string
        assertThat(check.getOverriddenBy()).isEqualTo("Marie Uwimana");
        assertThat(check.getOverrideReason()).contains("anticoagulation");
        assertThat(check.getOverriddenAt()).isNotNull();

        // (2) forensic alert of the right type/severity/shape
        ArgumentCaptor<ClinicalAlert> cap = ArgumentCaptor.forClass(ClinicalAlert.class);
        verify(clinicalAlertRepository).save(cap.capture());
        ClinicalAlert alert = cap.getValue();
        assertThat(alert.getAlertType()).isEqualTo(AlertType.MEDICATION_EMERGENCY_OVERRIDE);
        assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.CRITICAL);
        assertThat(alert.getMessage()).contains("Marie Uwimana overrode").contains("'Heparin'");

        // (3) real-time push to the hospital topic
        verify(realTimeEventPublisher).publishHospitalAlert(eq(check.getVisit().getHospital().getId()), any());
    }

    @Test
    void overrideSafetyCheck_failsClosed_whenNoAuthenticatedClinician() {
        UUID checkId = UUID.randomUUID();
        when(safetyCheckRepository.findByIdAndIsActiveTrue(checkId)).thenReturn(Optional.of(unsafeCheck()));
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> service.overrideSafetyCheck(checkId, "valid reason text"))
                .isInstanceOf(ClinicalBusinessException.class);

        // No spoofed attribution, no alert when the actor can't be resolved.
        verify(clinicalAlertRepository, never()).save(any());
        verify(safetyCheckRepository, never()).save(any());
    }

    @Test
    void overrideSafetyCheck_rejectsAlreadySafeCheck() {
        UUID checkId = UUID.randomUUID();
        MedicationSafetyCheck safe = MedicationSafetyCheck.builder().drugName("Saline").overallSafe(true).build();
        when(safetyCheckRepository.findByIdAndIsActiveTrue(checkId)).thenReturn(Optional.of(safe));
        authenticateAs("Marie", "Uwimana", Role.DOCTOR);

        assertThatThrownBy(() -> service.overrideSafetyCheck(checkId, "reason"))
                .isInstanceOf(ClinicalBusinessException.class);
        verify(clinicalAlertRepository, never()).save(any());
    }
}
