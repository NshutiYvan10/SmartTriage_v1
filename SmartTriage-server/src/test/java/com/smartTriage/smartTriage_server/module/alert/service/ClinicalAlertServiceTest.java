package com.smartTriage.smartTriage_server.module.alert.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.module.alert.dto.ClinicalAlertResponse;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.ems.repository.EmsRunRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Reliability tests for the alert-feed read path that powers the Alert Center.
 *
 * <p>Regression context: ClinicalAlert.visit / targetDoctor / acknowledgedBy are
 * all {@code @ManyToOne(LAZY)}. The controller used to map entity→DTO AFTER the
 * service transaction closed, so the enriched mapper threw
 * {@link LazyInitializationException} on any escalated/acknowledged alert →
 * HTTP 500 → "Alert feed unavailable" for every role (the Charge Nurse included).
 * Mapping now happens INSIDE this @Transactional service, and is resilient
 * per-row so one bad alert can never blank the whole feed.
 */
class ClinicalAlertServiceTest {

    private final ClinicalAlertRepository repo = mock(ClinicalAlertRepository.class);
    private final EmsRunRepository emsRepo = mock(EmsRunRepository.class);
    private final RealTimeEventPublisher publisher = mock(RealTimeEventPublisher.class);
    private final AlertScopeResolver scopeResolver = mock(AlertScopeResolver.class);
    private final ClinicalAlertService service =
            new ClinicalAlertService(repo, emsRepo, publisher, scopeResolver);

    private final UUID hospitalId = UUID.randomUUID();
    private final Pageable pageable = PageRequest.of(0, 50);

    @BeforeEach
    void defaultScope() {
        // These tests exercise the resilient-mapping path; resolve to ALL so
        // getAllAlerts routes through findAllAlertsByHospital (scoping itself is
        // covered by AlertScopeResolverTest).
        when(scopeResolver.resolve(any(), any())).thenReturn(
                new AlertScopeResolver.AlertScope(
                        AlertScopeResolver.Kind.ALL, Set.of(), null, Set.of()));
    }

    @Test
    void getAllAlerts_skipsUnmappableAlert_keepsFeedAvailable() {
        // A normal alert (bare mock → mapper is null-safe → maps fine).
        ClinicalAlert good = mock(ClinicalAlert.class);
        UUID goodId = UUID.randomUUID();
        when(good.getId()).thenReturn(goodId);

        // A row that blows up exactly like the production bug: touching a LAZY
        // association after the (real) session would have closed.
        ClinicalAlert bad = mock(ClinicalAlert.class);
        when(bad.getId()).thenReturn(UUID.randomUUID());
        when(bad.getAlertType()).thenThrow(new LazyInitializationException("could not initialize proxy"));

        when(repo.findAllAlertsByHospital(hospitalId, pageable))
                .thenReturn(new PageImpl<>(List.of(good, bad), pageable, 2));

        Page<ClinicalAlertResponse> result = service.getAllAlerts(hospitalId, pageable);

        // RELIABILITY GUARANTEE: the bad row is skipped (and logged), the good row
        // is still delivered — the Alert Center NEVER goes fully dark. (We assert
        // the delivered content, not totalElements: PageImpl derives the total from
        // content size when pageSize > total, so a skipped row legitimately lowers it.)
        assertEquals(1, result.getContent().size());
        assertEquals(goodId, result.getContent().get(0).getId());
    }

    @Test
    void getAllAlerts_mapsEscalatedAndAcknowledgedAlert_withoutFailing() {
        // The exact graph that used to LazyInit-500: an alert carrying both a
        // targetDoctor (escalated) and an acknowledgedBy (acknowledged). With the
        // fields populated, the mapper must read them cleanly.
        ClinicalAlert a = mock(ClinicalAlert.class);
        when(a.getId()).thenReturn(UUID.randomUUID());
        when(a.getAlertType()).thenReturn(AlertType.DOCTOR_NOTIFICATION);
        when(a.getSeverity()).thenReturn(AlertSeverity.HIGH);
        when(a.isAcknowledged()).thenReturn(true);

        com.smartTriage.smartTriage_server.module.user.entity.User doctor =
                new com.smartTriage.smartTriage_server.module.user.entity.User();
        doctor.setId(UUID.randomUUID());
        doctor.setFirstName("Greg");
        doctor.setLastName("House");
        when(a.getTargetDoctor()).thenReturn(doctor);

        com.smartTriage.smartTriage_server.module.user.entity.User acker =
                new com.smartTriage.smartTriage_server.module.user.entity.User();
        acker.setId(UUID.randomUUID());
        acker.setFirstName("Aline");
        acker.setLastName("Uwase");
        when(a.getAcknowledgedBy()).thenReturn(acker);

        when(repo.findAllAlertsByHospital(hospitalId, pageable))
                .thenReturn(new PageImpl<>(List.of(a), pageable, 1));

        Page<ClinicalAlertResponse> result = service.getAllAlerts(hospitalId, pageable);

        assertEquals(1, result.getContent().size());
        ClinicalAlertResponse r = result.getContent().get(0);
        assertEquals("Greg House", r.getTargetDoctorName());
        assertNotNull(r.getAcknowledgedByName());
    }

    @Test
    void getUnacknowledged_noneScope_returnsEmpty_andNeverQueriesUnscoped() {
        // Paramedic / Registrar → scope NONE. The alert STORE seeds from this
        // endpoint, so it MUST return empty and must NOT touch the hospital-wide
        // unscoped query (the leak that showed a paramedic all 36 alerts).
        when(scopeResolver.resolve(any(), any())).thenReturn(
                new AlertScopeResolver.AlertScope(
                        AlertScopeResolver.Kind.NONE, Set.of(), null, Set.of()));

        Page<ClinicalAlertResponse> result = service.getUnacknowledgedAlerts(hospitalId, pageable);

        assertEquals(0, result.getContent().size());
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never())
                .findUnacknowledgedAlerts(any(), any());
    }

    @Test
    void getUnacknowledged_zoneScope_usesZoneScopedQuery_notHospitalWide() {
        when(scopeResolver.resolve(any(), any())).thenReturn(
                new AlertScopeResolver.AlertScope(
                        AlertScopeResolver.Kind.ZONE,
                        Set.of(com.smartTriage.smartTriage_server.common.enums.EdZone.GENERAL),
                        UUID.randomUUID(), Set.of()));
        when(repo.findZoneScopedUnacknowledged(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        service.getUnacknowledgedAlerts(hospitalId, pageable);

        org.mockito.Mockito.verify(repo).findZoneScopedUnacknowledged(any(), any(), any(), any());
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never())
                .findUnacknowledgedAlerts(any(), any());
    }
}
