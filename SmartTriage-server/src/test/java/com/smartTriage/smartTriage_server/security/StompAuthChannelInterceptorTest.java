package com.smartTriage.smartTriage_server.security;

import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Security unit tests for the STOMP CONNECT auth + per-destination SUBSCRIBE authorisation.
 * A denied subscription must be DROPPED (preSend returns null) so the cross-tenant leak is
 * closed without tearing down the whole session; an allowed one passes through unchanged.
 */
class StompAuthChannelInterceptorTest {

    private final JwtService jwtService = mock(JwtService.class);
    private final UserDetailsService userDetailsService = mock(UserDetailsService.class);
    private final ClinicalAuthz clinicalAuthz = mock(ClinicalAuthz.class);
    private final StompAuthChannelInterceptor interceptor =
            new StompAuthChannelInterceptor(jwtService, userDetailsService, clinicalAuthz);

    private final UUID MY_HOSPITAL = UUID.randomUUID();
    private final UUID OTHER_HOSPITAL = UUID.randomUUID();
    private final UUID MY_ID = UUID.randomUUID();
    private final UUID OTHER_ID = UUID.randomUUID();
    private final UUID VISIT = UUID.randomUUID();

    private User user(Role role, UUID id) {
        User u = mock(User.class);
        when(u.getId()).thenReturn(id);
        when(u.getRole()).thenReturn(role);
        when(u.isEnabled()).thenReturn(true); // active account by default (lenient)
        return u;
    }

    private Authentication auth(User principal) {
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    private Message<byte[]> subscribe(String destination, Authentication user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setSessionId("sess-1");
        accessor.setUser(user);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private boolean allowed(String destination, Authentication user) {
        return interceptor.preSend(subscribe(destination, user), null) != null;
    }

    // ── Hospital-scoped ──

    @Test
    void allowsHospitalWideAlertFirehoseForOversightOnly() {
        // The hospital-wide alerts topic is now oversight-only (canSeeAllZones).
        Authentication a = auth(user(Role.NURSE, MY_ID));
        when(clinicalAuthz.canSeeAllZonesAtHospital(eq(a), eq(MY_HOSPITAL))).thenReturn(true);
        assertThat(allowed("/topic/alerts/" + MY_HOSPITAL, a)).isTrue();
    }

    @Test
    void deniesHospitalWideAlertFirehoseForZoneBoundUser() {
        // The crux of the scoping fix: a zone-bound nurse may NOT subscribe to the
        // hospital-wide firehose (their SUBSCRIBE is silently dropped).
        Authentication a = auth(user(Role.NURSE, MY_ID));
        when(clinicalAuthz.canSeeAllZonesAtHospital(eq(a), eq(MY_HOSPITAL))).thenReturn(false);
        assertThat(allowed("/topic/alerts/" + MY_HOSPITAL, a)).isFalse();
    }

    @Test
    void deniesOtherHospitalAlertTopic() {
        Authentication a = auth(user(Role.DOCTOR, MY_ID));
        when(clinicalAuthz.canSeeAllZonesAtHospital(eq(a), eq(OTHER_HOSPITAL))).thenReturn(false);
        assertThat(allowed("/topic/alerts/" + OTHER_HOSPITAL, a)).isFalse();
    }

    @Test
    void deniesOtherHospitalLabTopic() {
        Authentication a = auth(user(Role.LAB_TECHNICIAN, MY_ID));
        when(clinicalAuthz.canAccessHospital(any(), eq(OTHER_HOSPITAL))).thenReturn(false);
        assertThat(allowed("/topic/lab/" + OTHER_HOSPITAL, a)).isFalse();
    }

    @Test
    void allowsZoneAlertTopicWhenCallerCoversThatZone() {
        Authentication a = auth(user(Role.DOCTOR, MY_ID));
        when(clinicalAuthz.canReceiveZoneAlerts(eq(a), eq(MY_HOSPITAL), eq(EdZone.RESUS))).thenReturn(true);
        assertThat(allowed("/topic/alerts/" + MY_HOSPITAL + "/RESUS", a)).isTrue();
    }

    @Test
    void deniesZoneAlertTopicWhenCallerDoesNotCoverThatZone() {
        // A General-zone nurse subscribing to the RESUS zone topic is denied.
        Authentication a = auth(user(Role.NURSE, MY_ID));
        when(clinicalAuthz.canReceiveZoneAlerts(eq(a), eq(MY_HOSPITAL), eq(EdZone.RESUS))).thenReturn(false);
        assertThat(allowed("/topic/alerts/" + MY_HOSPITAL + "/RESUS", a)).isFalse();
    }

    // ── User-targeted ──

    @Test
    void allowsOwnUserTopic() {
        Authentication a = auth(user(Role.DOCTOR, MY_ID));
        assertThat(allowed("/topic/alerts/user/" + MY_ID, a)).isTrue();
    }

    @Test
    void deniesAnotherUsersTopic() {
        Authentication a = auth(user(Role.DOCTOR, MY_ID));
        assertThat(allowed("/topic/alerts/user/" + OTHER_ID, a)).isFalse();
    }

    // ── Visit-scoped ──

    @Test
    void allowsAccessibleVisitVitals() {
        Authentication a = auth(user(Role.NURSE, MY_ID));
        when(clinicalAuthz.canAccessVisit(eq(a), eq(VISIT))).thenReturn(true);
        assertThat(allowed("/topic/vitals/" + VISIT, a)).isTrue();
    }

    @Test
    void deniesInaccessibleVisitNotes() {
        Authentication a = auth(user(Role.NURSE, MY_ID));
        when(clinicalAuthz.canAccessVisit(any(), eq(VISIT))).thenReturn(false);
        assertThat(allowed("/topic/visit/" + VISIT + "/notes", a)).isFalse();
    }

    // ── SUPER_ADMIN + edge cases ──

    @Test
    void superAdminBypassesCrossHospital() {
        Authentication a = auth(user(Role.SUPER_ADMIN, MY_ID));
        assertThat(allowed("/topic/alerts/" + OTHER_HOSPITAL, a)).isTrue();
    }

    @Test
    void deniesUnknownTopicByDefault() {
        Authentication a = auth(user(Role.DOCTOR, MY_ID));
        assertThat(allowed("/topic/something/" + MY_HOSPITAL, a)).isFalse();
    }

    @Test
    void allowsNonTopicDestination() {
        Authentication a = auth(user(Role.DOCTOR, MY_ID));
        assertThat(allowed("/app/something", a)).isTrue();
    }

    @Test
    void deniesWhenUnauthenticated() {
        assertThat(allowed("/topic/alerts/" + MY_HOSPITAL, null)).isFalse();
    }

    // ── CONNECT authentication ──

    @Test
    void connectWithValidTokenSetsPrincipal() {
        UserDetails ud = user(Role.DOCTOR, MY_ID);
        when(jwtService.extractUsername("good")).thenReturn("doc@h.rw");
        when(userDetailsService.loadUserByUsername("doc@h.rw")).thenReturn(ud);
        when(jwtService.isTokenValid("good", ud)).thenReturn(true);

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId("sess-1");
        accessor.setNativeHeader("Authorization", "Bearer good");
        accessor.setLeaveMutable(true);
        Message<byte[]> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> out = interceptor.preSend(msg, null);
        assertThat(out).isNotNull();
        StompHeaderAccessor result = StompHeaderAccessor.wrap(out);
        assertThat(result.getUser()).isInstanceOf(Authentication.class);
    }

    @Test
    void connectWithoutTokenIsRejected() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId("sess-1");
        accessor.setLeaveMutable(true);
        Message<byte[]> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(msg, null))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void connectWithDisabledUserIsRejected() {
        User disabled = mock(User.class);
        when(disabled.isEnabled()).thenReturn(false); // soft-deleted user OR deactivated hospital
        when(jwtService.isRefreshToken("good")).thenReturn(false);
        when(jwtService.extractUsername("good")).thenReturn("gone@h.rw");
        when(userDetailsService.loadUserByUsername("gone@h.rw")).thenReturn(disabled);
        when(jwtService.isTokenValid("good", disabled)).thenReturn(true);

        assertThatThrownBy(() -> interceptor.preSend(connect("Bearer good"), null))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void connectWithRefreshTokenIsRejected() {
        when(jwtService.isRefreshToken("refresh")).thenReturn(true); // long-lived refresh token

        assertThatThrownBy(() -> interceptor.preSend(connect("Bearer refresh"), null))
                .isInstanceOf(MessagingException.class);
    }

    private Message<byte[]> connect(String authHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId("sess-1");
        accessor.setNativeHeader("Authorization", authHeader);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
