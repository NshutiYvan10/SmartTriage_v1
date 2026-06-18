package com.smartTriage.smartTriage_server.module.audit.service;

import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.module.audit.entity.AuditLog;
import com.smartTriage.smartTriage_server.module.audit.repository.AuditLogRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditServiceTest {

    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuditService service = new AuditService(auditLogRepository, userRepository);

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private User user(Role role) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setFirstName("Alice");
        u.setLastName("Mwangi");
        u.setEmail("alice@hospital.rw");
        u.setRole(role);
        return u;
    }

    private void authenticateAs(User u) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u, null, List.of()));
    }

    @Test
    void record_capturesAuthenticatedActorHospitalAndOutcome() {
        User alice = user(Role.HOSPITAL_ADMIN);
        UUID hospitalId = UUID.randomUUID();
        when(userRepository.findHospitalIdByUserId(alice.getId())).thenReturn(Optional.of(hospitalId));
        authenticateAs(alice);

        service.record("POST", "/api/v1/visits/abc/disposition", "POST /visits/{id}/disposition", 201);

        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(cap.capture());
        AuditLog a = cap.getValue();
        assertThat(a.getActorUserId()).isEqualTo(alice.getId());
        assertThat(a.getActorName()).isEqualTo("Alice Mwangi");
        assertThat(a.getActorRole()).isEqualTo("HOSPITAL_ADMIN");
        assertThat(a.getHospitalId()).isEqualTo(hospitalId);
        assertThat(a.getHttpMethod()).isEqualTo("POST");
        assertThat(a.getAction()).isEqualTo("POST /visits/{id}/disposition");
        assertThat(a.getStatusCode()).isEqualTo(201);
        assertThat(a.getOutcome()).isEqualTo("SUCCESS");
    }

    @Test
    void record_marksFailedOutcomeForErrorStatus() {
        authenticateAs(user(Role.DOCTOR));
        when(userRepository.findHospitalIdByUserId(any())).thenReturn(Optional.empty());

        service.record("PUT", "/api/v1/documents/x/sign", "PUT /documents/{id}/sign", 403);

        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(cap.capture());
        assertThat(cap.getValue().getOutcome()).isEqualTo("FAILED");
    }

    @Test
    void record_withNoAuthenticatedUser_savesAnonymous_andDoesNotThrow() {
        SecurityContextHolder.clearContext();
        assertThatCode(() -> service.record("DELETE", "/api/v1/x", "DELETE /x", 204))
                .doesNotThrowAnyException();
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(cap.capture());
        assertThat(cap.getValue().getActorUserId()).isNull();
        assertThat(cap.getValue().getActorName()).isEqualTo("anonymous");
        assertThat(cap.getValue().getHospitalId()).isNull();
    }

    @Test
    void record_neverPropagatesARepositoryFailure() {
        authenticateAs(user(Role.NURSE));
        when(userRepository.findHospitalIdByUserId(any())).thenReturn(Optional.empty());
        when(auditLogRepository.save(any())).thenThrow(new RuntimeException("db down"));
        // Audit must never break the request it is recording.
        assertThatCode(() -> service.record("POST", "/api/v1/x", "POST /x", 200))
                .doesNotThrowAnyException();
    }
}
