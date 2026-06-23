package com.smartTriage.smartTriage_server.module.consent.service;

import com.smartTriage.smartTriage_server.common.enums.ConsentGrantor;
import com.smartTriage.smartTriage_server.common.enums.DataSharingConsentStatus;
import com.smartTriage.smartTriage_server.common.enums.DataSharingScope;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.module.consent.dto.DataSharingConsentResponse;
import com.smartTriage.smartTriage_server.module.consent.dto.RecordDataSharingConsentRequest;
import com.smartTriage.smartTriage_server.module.consent.dto.WithdrawConsentRequest;
import com.smartTriage.smartTriage_server.module.consent.entity.DataSharingConsent;
import com.smartTriage.smartTriage_server.module.consent.repository.DataSharingConsentRepository;
import com.smartTriage.smartTriage_server.module.patient.entity.PersonIdentity;
import com.smartTriage.smartTriage_server.module.patient.repository.PersonIdentityRepository;
import com.smartTriage.smartTriage_server.module.patient.service.PersonIdentityService;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link DataSharingConsentService} — the cross-hospital data-sharing consent that
 * gates the Phase-2 deep-record read. Verifies: a GRANTED record becomes the effective consent; a
 * second grant supersedes the first (at-most-one effective); withdraw makes it no longer effective;
 * recording-as-WITHDRAWN is rejected; and the obtaining clinician is fail-closed to the principal.
 */
class DataSharingConsentServiceTest {

    private final DataSharingConsentRepository consentRepository = mock(DataSharingConsentRepository.class);
    private final PersonIdentityRepository personIdentityRepository = mock(PersonIdentityRepository.class);
    private final PersonIdentityService personIdentityService = mock(PersonIdentityService.class);

    private final DataSharingConsentService service =
            new DataSharingConsentService(consentRepository, personIdentityRepository, personIdentityService);

    private static final String NID = "1199870012345678";
    private PersonIdentity identity;

    @BeforeEach
    void setUp() {
        identity = new PersonIdentity();
        identity.setId(UUID.randomUUID());
        identity.setNationalId(NID);
        when(personIdentityService.findOrCreate(NID)).thenReturn(identity);
        when(consentRepository.saveAndFlush(any(DataSharingConsent.class))).thenAnswer(i -> i.getArgument(0));
        when(consentRepository.save(any(DataSharingConsent.class))).thenAnswer(i -> i.getArgument(0));
        authenticateAs(clinician());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void recordingGranted_becomesTheEffectiveConsent() {
        // No existing live grant.
        when(consentRepository.findFirstByPersonIdentityIdAndStatusAndIsActiveTrueOrderByObtainedAtDesc(
                identity.getId(), DataSharingConsentStatus.GRANTED)).thenReturn(Optional.empty());

        DataSharingConsentResponse res = service.recordConsent(NID, grantRequest());

        assertThat(res.getStatus()).isEqualTo(DataSharingConsentStatus.GRANTED);
        assertThat(res.getScope()).isEqualTo(DataSharingScope.FULL_RECORD);
        // Obtaining clinician is snapshotted from the principal — never the request.
        assertThat(res.getObtainedByName()).isEqualTo("Amina Doctor");
        assertThat(res.getObtainedByRole()).isEqualTo("DOCTOR");
        verify(consentRepository).saveAndFlush(any(DataSharingConsent.class));
    }

    @Test
    void secondGrant_supersedesTheExistingLiveGrant() {
        DataSharingConsent existing = DataSharingConsent.builder()
                .personIdentity(identity).status(DataSharingConsentStatus.GRANTED)
                .scope(DataSharingScope.FULL_RECORD).obtainedAt(Instant.EPOCH).build();
        existing.setId(UUID.randomUUID());
        when(consentRepository.findFirstByPersonIdentityIdAndStatusAndIsActiveTrueOrderByObtainedAtDesc(
                identity.getId(), DataSharingConsentStatus.GRANTED)).thenReturn(Optional.of(existing));

        service.recordConsent(NID, grantRequest());

        // The prior grant is withdrawn (superseded) before the new one is flushed.
        assertThat(existing.getStatus()).isEqualTo(DataSharingConsentStatus.WITHDRAWN);
        assertThat(existing.getWithdrawalReason()).contains("Superseded");
        verify(consentRepository).save(existing);                       // supersede write
        verify(consentRepository).saveAndFlush(any(DataSharingConsent.class)); // new grant
    }

    @Test
    void recordingAsWithdrawn_isRejected() {
        RecordDataSharingConsentRequest req = grantRequest();
        req.setStatus(DataSharingConsentStatus.WITHDRAWN);

        assertThatThrownBy(() -> service.recordConsent(NID, req))
                .isInstanceOf(ClinicalBusinessException.class);
        verify(consentRepository, never()).saveAndFlush(any());
    }

    @Test
    void withdraw_makesConsentNoLongerEffective() {
        UUID id = UUID.randomUUID();
        DataSharingConsent granted = DataSharingConsent.builder()
                .personIdentity(identity).status(DataSharingConsentStatus.GRANTED)
                .scope(DataSharingScope.FULL_RECORD).obtainedAt(Instant.now()).build();
        granted.setId(id);
        when(consentRepository.findByIdAndIsActiveTrue(id)).thenReturn(Optional.of(granted));

        DataSharingConsentResponse res = service.withdrawConsent(id,
                WithdrawConsentRequest.builder().reason("Patient revoked at follow-up").build());

        assertThat(res.getStatus()).isEqualTo(DataSharingConsentStatus.WITHDRAWN);
        assertThat(granted.getWithdrawnByName()).isEqualTo("Amina Doctor");
        assertThat(granted.getWithdrawalReason()).isEqualTo("Patient revoked at follow-up");
    }

    @Test
    void withdraw_rejectsNonGrantedConsent() {
        UUID id = UUID.randomUUID();
        DataSharingConsent denied = DataSharingConsent.builder()
                .personIdentity(identity).status(DataSharingConsentStatus.DENIED).build();
        denied.setId(id);
        when(consentRepository.findByIdAndIsActiveTrue(id)).thenReturn(Optional.of(denied));

        assertThatThrownBy(() -> service.withdrawConsent(id,
                WithdrawConsentRequest.builder().reason("x").build()))
                .isInstanceOf(ClinicalBusinessException.class);
        verify(consentRepository, never()).save(any());
    }

    @Test
    void recordingConsent_failsClosedWhenNoAuthenticatedClinician() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> service.recordConsent(NID, grantRequest()))
                .isInstanceOf(AccessDeniedException.class);
        verify(consentRepository, never()).saveAndFlush(any());
    }

    @Test
    void getCurrentEffectiveConsent_delegatesToLiveGrantedFinder() {
        DataSharingConsent live = DataSharingConsent.builder()
                .personIdentity(identity).status(DataSharingConsentStatus.GRANTED).build();
        when(consentRepository.findFirstByPersonIdentityIdAndStatusAndIsActiveTrueOrderByObtainedAtDesc(
                identity.getId(), DataSharingConsentStatus.GRANTED)).thenReturn(Optional.of(live));

        assertThat(service.getCurrentEffectiveConsent(identity.getId())).containsSame(live);
        verify(consentRepository, times(1))
                .findFirstByPersonIdentityIdAndStatusAndIsActiveTrueOrderByObtainedAtDesc(
                        identity.getId(), DataSharingConsentStatus.GRANTED);
    }

    // ── helpers ──
    private RecordDataSharingConsentRequest grantRequest() {
        return RecordDataSharingConsentRequest.builder()
                .status(DataSharingConsentStatus.GRANTED)
                .scope(DataSharingScope.FULL_RECORD)
                .consentGrantor(ConsentGrantor.PATIENT)
                .grantorName("Self")
                .notes("Opted in at registration")
                .build();
    }

    private User clinician() {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(Role.DOCTOR);
        u.setFirstName("Amina");
        u.setLastName("Doctor");
        u.setEmail("amina@chuk.rw");
        return u;
    }

    private void authenticateAs(User user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null));
    }
}
