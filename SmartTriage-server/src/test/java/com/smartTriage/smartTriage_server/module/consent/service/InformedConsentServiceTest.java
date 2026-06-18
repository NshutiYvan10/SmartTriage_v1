package com.smartTriage.smartTriage_server.module.consent.service;

import com.smartTriage.smartTriage_server.common.enums.ConsentGrantor;
import com.smartTriage.smartTriage_server.common.enums.ConsentStatus;
import com.smartTriage.smartTriage_server.common.enums.ConsentType;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.module.consent.dto.ConsentResponse;
import com.smartTriage.smartTriage_server.module.consent.dto.RecordConsentRequest;
import com.smartTriage.smartTriage_server.module.consent.dto.WithdrawConsentRequest;
import com.smartTriage.smartTriage_server.module.consent.entity.InformedConsent;
import com.smartTriage.smartTriage_server.module.consent.repository.InformedConsentRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.service.VisitService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Item #5A — Informed consent: structured, authenticated, withdrawable. */
class InformedConsentServiceTest {

    private final InformedConsentRepository consentRepository = mock(InformedConsentRepository.class);
    private final VisitService visitService = mock(VisitService.class);
    private final InformedConsentService service = new InformedConsentService(consentRepository, visitService);

    private final UUID VISIT = UUID.randomUUID();

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private User clinician() {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setFirstName("Alice");
        u.setLastName("Mwangi");
        u.setEmail("alice@hospital.rw");
        u.setRole(Role.DOCTOR);
        u.setProfessionalLicense("RW-DOC-001");
        return u;
    }

    private void authenticateAs(User u) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u, null, List.of()));
    }

    private Visit visit() {
        Visit v = new Visit();
        v.setId(VISIT);
        v.setVisitNumber("V-CONSENT-1");
        return v;
    }

    private RecordConsentRequest req() {
        return RecordConsentRequest.builder()
                .visitId(VISIT)
                .consentType(ConsentType.BLOOD_TRANSFUSION)
                .procedureName("Packed red cell transfusion")
                .risksExplained("Transfusion reaction, infection")
                .benefitsExplained("Correct anaemia")
                .alternativesExplained("Iron therapy")
                .questionsAnswered(true)
                .consentGrantor(ConsentGrantor.PATIENT)
                .grantorName("John Doe")
                .build();
    }

    @Test
    void recordConsent_recordsAuthenticatedObtainer_defaultsToGiven() {
        User alice = clinician();
        authenticateAs(alice);
        when(visitService.findVisitOrThrow(VISIT)).thenReturn(visit());
        when(consentRepository.save(any(InformedConsent.class))).thenAnswer(i -> i.getArgument(0));

        ConsentResponse resp = service.recordConsent(VISIT, req());

        ArgumentCaptor<InformedConsent> cap = ArgumentCaptor.forClass(InformedConsent.class);
        verify(consentRepository).save(cap.capture());
        InformedConsent saved = cap.getValue();
        assertThat(saved.getStatus()).isEqualTo(ConsentStatus.GIVEN);
        assertThat(saved.getObtainedByUserId()).isEqualTo(alice.getId());
        assertThat(saved.getObtainedByName()).isEqualTo("Alice Mwangi");
        assertThat(saved.getObtainedByLicenseNumber()).isEqualTo("RW-DOC-001");
        assertThat(saved.getObtainedAt()).isNotNull();
        assertThat(saved.getConsentType()).isEqualTo(ConsentType.BLOOD_TRANSFUSION);
        assertThat(resp.getObtainedByUserId()).isEqualTo(alice.getId());
    }

    @Test
    void recordConsent_withNoAuthenticatedUser_throwsAccessDenied() {
        SecurityContextHolder.clearContext();
        when(visitService.findVisitOrThrow(VISIT)).thenReturn(visit());
        assertThatThrownBy(() -> service.recordConsent(VISIT, req()))
                .isInstanceOf(AccessDeniedException.class);
        verify(consentRepository, never()).save(any());
    }

    @Test
    void recordConsent_asWithdrawn_isRejected() {
        authenticateAs(clinician());
        when(visitService.findVisitOrThrow(VISIT)).thenReturn(visit());
        RecordConsentRequest r = req();
        r.setStatus(ConsentStatus.WITHDRAWN);
        assertThatThrownBy(() -> service.recordConsent(VISIT, r))
                .isInstanceOf(ClinicalBusinessException.class);
        verify(consentRepository, never()).save(any());
    }

    @Test
    void withdrawConsent_marksWithdrawn_byAuthenticatedUser() {
        UUID consentId = UUID.randomUUID();
        InformedConsent given = InformedConsent.builder()
                .visit(visit()).consentType(ConsentType.PROCEDURE).procedureName("LP")
                .consentGrantor(ConsentGrantor.PATIENT).status(ConsentStatus.GIVEN)
                .obtainedByName("Alice Mwangi").obtainedAt(Instant.now()).build();
        given.setId(consentId);
        when(consentRepository.findByIdAndIsActiveTrue(consentId)).thenReturn(Optional.of(given));
        when(consentRepository.save(any(InformedConsent.class))).thenAnswer(i -> i.getArgument(0));

        User bob = clinician();
        bob.setFirstName("Bob"); bob.setLastName("Otieno");
        authenticateAs(bob);

        ConsentResponse resp = service.withdrawConsent(consentId, WithdrawConsentRequest.builder()
                .reason("Patient changed their mind").build());

        assertThat(resp.getStatus()).isEqualTo(ConsentStatus.WITHDRAWN);
        assertThat(resp.getWithdrawnByUserId()).isEqualTo(bob.getId());
        assertThat(resp.getWithdrawnByName()).isEqualTo("Bob Otieno");
        assertThat(resp.getWithdrawnAt()).isNotNull();
        assertThat(resp.getWithdrawalReason()).isEqualTo("Patient changed their mind");
    }

    @Test
    void withdrawConsent_whenNotGiven_isRejected() {
        UUID consentId = UUID.randomUUID();
        InformedConsent refused = InformedConsent.builder()
                .visit(visit()).consentType(ConsentType.PROCEDURE).procedureName("LP")
                .consentGrantor(ConsentGrantor.PATIENT).status(ConsentStatus.REFUSED)
                .obtainedByName("Alice").obtainedAt(Instant.now()).build();
        refused.setId(consentId);
        when(consentRepository.findByIdAndIsActiveTrue(consentId)).thenReturn(Optional.of(refused));
        authenticateAs(clinician());

        assertThatThrownBy(() -> service.withdrawConsent(consentId, WithdrawConsentRequest.builder()
                .reason("x").build()))
                .isInstanceOf(ClinicalBusinessException.class);
        verify(consentRepository, never()).save(any());
    }
}
