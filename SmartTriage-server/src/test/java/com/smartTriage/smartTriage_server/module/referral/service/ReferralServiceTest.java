package com.smartTriage.smartTriage_server.module.referral.service;

import com.smartTriage.smartTriage_server.common.enums.ReferralStatus;
import com.smartTriage.smartTriage_server.common.enums.ReferralType;
import com.smartTriage.smartTriage_server.common.enums.ReferralUrgency;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.module.referral.dto.CreateReferralRequest;
import com.smartTriage.smartTriage_server.module.referral.dto.ReferralResponse;
import com.smartTriage.smartTriage_server.module.referral.dto.RespondReferralRequest;
import com.smartTriage.smartTriage_server.module.referral.entity.Referral;
import com.smartTriage.smartTriage_server.module.referral.repository.ReferralRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.service.VisitService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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

/** Item #5B — Referral / consultation: structured request + authenticated reply. */
class ReferralServiceTest {

    private final ReferralRepository referralRepository = mock(ReferralRepository.class);
    private final VisitService visitService = mock(VisitService.class);
    private final ReferralService service = new ReferralService(referralRepository, visitService);

    private final UUID VISIT = UUID.randomUUID();

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private User doctor(String first, String last) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setFirstName(first);
        u.setLastName(last);
        u.setEmail((first + "@h.rw").toLowerCase());
        u.setRole(Role.DOCTOR);
        return u;
    }

    private void authenticateAs(User u) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u, null, List.of()));
    }

    private Visit visit() {
        Visit v = new Visit();
        v.setId(VISIT);
        v.setVisitNumber("V-REF-1");
        return v;
    }

    private CreateReferralRequest req() {
        return CreateReferralRequest.builder()
                .visitId(VISIT)
                .referralType(ReferralType.INTERNAL_CONSULT)
                .specialty("Cardiology")
                .urgency(ReferralUrgency.URGENT)
                .reasonForReferral("New AF with RVR")
                .clinicalQuestion("Rate vs rhythm control?")
                .build();
    }

    private Referral requested(User requester) {
        Referral r = Referral.builder()
                .visit(visit()).referralType(ReferralType.INTERNAL_CONSULT).specialty("Cardiology")
                .urgency(ReferralUrgency.URGENT).reasonForReferral("New AF").status(ReferralStatus.REQUESTED)
                .requestedByUserId(requester.getId()).requestedByName("Req").requestedAt(Instant.now())
                .build();
        r.setId(UUID.randomUUID());
        return r;
    }

    @Test
    void requestReferral_recordsAuthenticatedRequester() {
        User alice = doctor("Alice", "Mwangi");
        authenticateAs(alice);
        when(visitService.findVisitOrThrow(VISIT)).thenReturn(visit());
        when(referralRepository.save(any(Referral.class))).thenAnswer(i -> i.getArgument(0));

        ReferralResponse resp = service.requestReferral(VISIT, req());

        assertThat(resp.getStatus()).isEqualTo(ReferralStatus.REQUESTED);
        assertThat(resp.getRequestedByUserId()).isEqualTo(alice.getId());
        assertThat(resp.getRequestedByName()).isEqualTo("Alice Mwangi");
        assertThat(resp.getSpecialty()).isEqualTo("Cardiology");
        assertThat(resp.getRequestedAt()).isNotNull();
    }

    @Test
    void requestReferral_withNoAuthenticatedUser_throwsAccessDenied() {
        SecurityContextHolder.clearContext();
        when(visitService.findVisitOrThrow(VISIT)).thenReturn(visit());
        assertThatThrownBy(() -> service.requestReferral(VISIT, req()))
                .isInstanceOf(AccessDeniedException.class);
        verify(referralRepository, never()).save(any());
    }

    @Test
    void respondToReferral_accepted_recordsAuthenticatedResponder() {
        User requester = doctor("Alice", "Mwangi");
        Referral r = requested(requester);
        when(referralRepository.findByIdAndIsActiveTrue(r.getId())).thenReturn(Optional.of(r));
        when(referralRepository.save(any(Referral.class))).thenAnswer(i -> i.getArgument(0));

        User consultant = doctor("Carol", "Cardio");
        authenticateAs(consultant);

        ReferralResponse resp = service.respondToReferral(r.getId(), RespondReferralRequest.builder()
                .outcome(ReferralStatus.ACCEPTED).responseNotes("Will review, start rate control").build());

        assertThat(resp.getStatus()).isEqualTo(ReferralStatus.ACCEPTED);
        assertThat(resp.getRespondedByUserId()).isEqualTo(consultant.getId());
        assertThat(resp.getRespondedByName()).isEqualTo("Carol Cardio");
        assertThat(resp.getRespondedAt()).isNotNull();
        assertThat(resp.getResponseNotes()).contains("rate control");
    }

    @Test
    void respondToReferral_decline_requiresReason() {
        User requester = doctor("Alice", "Mwangi");
        Referral r = requested(requester);
        when(referralRepository.findByIdAndIsActiveTrue(r.getId())).thenReturn(Optional.of(r));
        authenticateAs(doctor("Carol", "Cardio"));

        assertThatThrownBy(() -> service.respondToReferral(r.getId(), RespondReferralRequest.builder()
                .outcome(ReferralStatus.DECLINED).build()))
                .isInstanceOf(ClinicalBusinessException.class)
                .hasMessageContaining("reason");
        verify(referralRepository, never()).save(any());
    }

    @Test
    void respondToReferral_withRequestedOutcome_isRejected() {
        User requester = doctor("Alice", "Mwangi");
        Referral r = requested(requester);
        when(referralRepository.findByIdAndIsActiveTrue(r.getId())).thenReturn(Optional.of(r));
        authenticateAs(doctor("Carol", "Cardio"));

        assertThatThrownBy(() -> service.respondToReferral(r.getId(), RespondReferralRequest.builder()
                .outcome(ReferralStatus.REQUESTED).build()))
                .isInstanceOf(ClinicalBusinessException.class);
        verify(referralRepository, never()).save(any());
    }

    @Test
    void respondToReferral_onCancelledReferral_isRejected() {
        User requester = doctor("Alice", "Mwangi");
        Referral r = requested(requester);
        r.setStatus(ReferralStatus.CANCELLED);
        when(referralRepository.findByIdAndIsActiveTrue(r.getId())).thenReturn(Optional.of(r));
        authenticateAs(doctor("Carol", "Cardio"));

        assertThatThrownBy(() -> service.respondToReferral(r.getId(), RespondReferralRequest.builder()
                .outcome(ReferralStatus.ACCEPTED).build()))
                .isInstanceOf(ClinicalBusinessException.class)
                .hasMessageContaining("CANCELLED");
        verify(referralRepository, never()).save(any());
    }

    @Test
    void cancelReferral_marksCancelled() {
        User requester = doctor("Alice", "Mwangi");
        Referral r = requested(requester);
        when(referralRepository.findByIdAndIsActiveTrue(r.getId())).thenReturn(Optional.of(r));
        when(referralRepository.save(any(Referral.class))).thenAnswer(i -> i.getArgument(0));
        authenticateAs(requester);

        ReferralResponse resp = service.cancelReferral(r.getId());
        assertThat(resp.getStatus()).isEqualTo(ReferralStatus.CANCELLED);
    }
}
