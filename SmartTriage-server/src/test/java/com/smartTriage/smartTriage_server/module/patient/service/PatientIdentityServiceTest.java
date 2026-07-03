package com.smartTriage.smartTriage_server.module.patient.service;

import com.smartTriage.smartTriage_server.common.exception.IdentityConflictException;
import com.smartTriage.smartTriage_server.module.patient.dto.ResolveIdentityRequest;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.entity.PersonIdentity;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientRepository;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link PatientIdentityService#resolveIdentity} — the placeholder-resolution
 * safety rules the adversarial review surfaced:
 *   • an RFID card already owned by ANOTHER patient must NOT be silently grafted (409 → use merge);
 *   • a card-ONLY resolve (no real name) records the anchor but keeps the patient UNIDENTIFIED so
 *     the overdue-reminder machinery keeps chasing a name;
 *   • a real name flips the patient to IDENTIFIED.
 */
class PatientIdentityServiceTest {

    private final PatientRepository patientRepository = mock(PatientRepository.class);
    private final VisitRepository visitRepository = mock(VisitRepository.class);
    private final PersonIdentityService personIdentityService = mock(PersonIdentityService.class);
    private final PatientIdentityService service =
            new PatientIdentityService(patientRepository, visitRepository, personIdentityService);

    private Patient placeholder() {
        Patient p = new Patient();
        p.setId(UUID.randomUUID());
        p.setUnidentified(true);
        p.setPlaceholderLabel("Bravo");
        p.setFirstName("Unknown");
        p.setLastName("Bravo");
        return p;
    }

    @Test
    void cardBelongingToAnotherPatient_isRejected_notGrafted() {
        Patient ph = placeholder();
        when(patientRepository.findByIdAndIsActiveTrue(ph.getId())).thenReturn(Optional.of(ph));

        PersonIdentity othersIdentity = new PersonIdentity();
        othersIdentity.setId(UUID.randomUUID());
        othersIdentity.setRfidCardId("CARD-B");
        Patient someoneElse = new Patient();
        someoneElse.setId(UUID.randomUUID());
        when(personIdentityService.findByRfidCardId("CARD-B")).thenReturn(Optional.of(othersIdentity));
        when(patientRepository.findByPersonIdentityIdAndIsActiveTrue(othersIdentity.getId()))
                .thenReturn(List.of(someoneElse));

        ResolveIdentityRequest req = ResolveIdentityRequest.builder().rfidCardId("CARD-B").build();

        assertThatThrownBy(() -> service.resolveIdentity(ph.getId(), req))
                .isInstanceOf(IdentityConflictException.class);
        // The placeholder must NOT have been flipped to identified or grafted.
        assertThat(ph.isUnidentified()).isTrue();
    }

    @Test
    void cardOnlyResolve_recordsAnchor_butKeepsUnidentified() {
        Patient ph = placeholder();
        when(patientRepository.findByIdAndIsActiveTrue(ph.getId())).thenReturn(Optional.of(ph));
        // Fresh, unowned card — no existing owner.
        when(personIdentityService.findByRfidCardId("CARD-NEW")).thenReturn(Optional.empty());
        PersonIdentity created = new PersonIdentity();
        created.setId(UUID.randomUUID());
        created.setRfidCardId("CARD-NEW");
        when(personIdentityService.findOrCreate(eq(null), eq("CARD-NEW"))).thenReturn(created);
        when(patientRepository.save(any(Patient.class))).thenAnswer(i -> i.getArgument(0));

        ResolveIdentityRequest req = ResolveIdentityRequest.builder().rfidCardId("CARD-NEW").build();
        Patient result = service.resolveIdentity(ph.getId(), req);

        assertThat(result.getPersonIdentity()).isSameAs(created); // card anchor attached
        assertThat(result.isUnidentified()).isTrue();             // still no name → keep chasing
        assertThat(result.getIdentifiedAt()).isNull();
    }

    @Test
    void nameResolve_flipsToIdentified() {
        Patient ph = placeholder();
        when(patientRepository.findByIdAndIsActiveTrue(ph.getId())).thenReturn(Optional.of(ph));
        when(patientRepository.save(any(Patient.class))).thenAnswer(i -> i.getArgument(0));

        ResolveIdentityRequest req = ResolveIdentityRequest.builder()
                .firstName("Marie").lastName("Uwimana").build();
        Patient result = service.resolveIdentity(ph.getId(), req);

        assertThat(result.isUnidentified()).isFalse();
        assertThat(result.getIdentifiedAt()).isNotNull();
        assertThat(result.getFirstName()).isEqualTo("Marie");
        assertThat(result.getLastName()).isEqualTo("Uwimana");
    }
}
