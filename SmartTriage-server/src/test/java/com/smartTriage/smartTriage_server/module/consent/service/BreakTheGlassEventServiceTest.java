package com.smartTriage.smartTriage_server.module.consent.service;

import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.consent.dto.BreakTheGlassEventResponse;
import com.smartTriage.smartTriage_server.module.consent.entity.BreakTheGlassEvent;
import com.smartTriage.smartTriage_server.module.consent.repository.BreakTheGlassEventRepository;
import com.smartTriage.smartTriage_server.module.patient.entity.PersonIdentity;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link BreakTheGlassEventService} — the governance read + sign-off over break-the-
 * glass overrides. Verifies the range→from mapping, that acknowledgement is attributed to the
 * authenticated reviewer and leaves the forensic facts untouched, that a cross-hospital
 * acknowledge is denied, and that an unauthenticated acknowledge fails closed.
 */
class BreakTheGlassEventServiceTest {

    private final BreakTheGlassEventRepository repository = mock(BreakTheGlassEventRepository.class);
    private final BreakTheGlassEventService service = new BreakTheGlassEventService(repository);

    private final UUID hospitalId = UUID.randomUUID();

    @BeforeEach
    void auth() {
        authenticateAs(reviewer());
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rangeMapsToFromWindow_allMeansNoLowerBound() {
        when(repository.findByActorHospitalIdAndAccessedAtGreaterThanEqualAndIsActiveTrueOrderByAccessedAtDesc(
                eq(hospitalId), any(Instant.class), any(Pageable.class))).thenReturn(Page.empty());
        when(repository.findByActorHospitalIdAndIsActiveTrueOrderByAccessedAtDesc(
                eq(hospitalId), any(Pageable.class))).thenReturn(Page.empty());

        // "7d" → time-bounded query with a non-null lower bound.
        service.getEventsForHospital(hospitalId, "7d", PageRequest.of(0, 50));
        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        verify(repository).findByActorHospitalIdAndAccessedAtGreaterThanEqualAndIsActiveTrueOrderByAccessedAtDesc(
                eq(hospitalId), from.capture(), any());
        assertThat(from.getValue()).isNotNull();

        // "all" → unbounded query (returns everything).
        service.getEventsForHospital(hospitalId, "all", PageRequest.of(0, 50));
        verify(repository).findByActorHospitalIdAndIsActiveTrueOrderByAccessedAtDesc(eq(hospitalId), any());
    }

    @Test
    void acknowledge_setsReviewOverlayFromPrincipal_leavingForensicFactsUntouched() {
        UUID id = UUID.randomUUID();
        BreakTheGlassEvent event = event(id, hospitalId, "Unconscious trauma, prior allergies needed");
        when(repository.findByIdAndIsActiveTrue(id)).thenReturn(Optional.of(event));
        when(repository.save(any(BreakTheGlassEvent.class))).thenAnswer(i -> i.getArgument(0));

        BreakTheGlassEventResponse res = service.acknowledgeEvent(id, hospitalId, "Reviewed at M&M");

        assertThat(res.isAcknowledged()).isTrue();
        assertThat(event.isAcknowledged()).isTrue();
        assertThat(event.getAcknowledgedByName()).isEqualTo("Sara Officer");
        assertThat(event.getAcknowledgmentNote()).isEqualTo("Reviewed at M&M");
        assertThat(event.getAcknowledgedAt()).isNotNull();
        // Forensic facts are NEVER mutated by a review sign-off.
        assertThat(event.getReason()).isEqualTo("Unconscious trauma, prior allergies needed");
        assertThat(event.getPriorConsentState()).isEqualTo("NONE");
    }

    @Test
    void acknowledge_crossHospital_isDenied() {
        UUID id = UUID.randomUUID();
        BreakTheGlassEvent event = event(id, UUID.randomUUID(), "x"); // belongs to a DIFFERENT hospital
        when(repository.findByIdAndIsActiveTrue(id)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> service.acknowledgeEvent(id, hospitalId, "note"))
                .isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void acknowledge_unknownEvent_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndIsActiveTrue(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acknowledgeEvent(id, hospitalId, "note"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void acknowledge_failsClosedWhenNoAuthenticatedReviewer() {
        SecurityContextHolder.clearContext();
        UUID id = UUID.randomUUID();
        BreakTheGlassEvent event = event(id, hospitalId, "x");
        when(repository.findByIdAndIsActiveTrue(id)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> service.acknowledgeEvent(id, hospitalId, "note"))
                .isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).save(any());
    }

    // ── helpers ──
    private BreakTheGlassEvent event(UUID id, UUID actorHospitalId, String reason) {
        PersonIdentity identity = new PersonIdentity();
        identity.setId(UUID.randomUUID());
        identity.setNationalId("1199870012345678");
        BreakTheGlassEvent e = BreakTheGlassEvent.builder()
                .personIdentity(identity)
                .actorUserId(UUID.randomUUID()).actorName("Dr Emergency").actorRole("DOCTOR")
                .actorHospitalId(actorHospitalId)
                .reason(reason).priorConsentState("NONE").accessedAt(Instant.now())
                .build();
        e.setId(id);
        return e;
    }

    private User reviewer() {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(Role.HOSPITAL_ADMIN);
        u.setFirstName("Sara");
        u.setLastName("Officer");
        u.setEmail("sara@chuk.rw");
        return u;
    }

    private void authenticateAs(User user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null));
    }
}
