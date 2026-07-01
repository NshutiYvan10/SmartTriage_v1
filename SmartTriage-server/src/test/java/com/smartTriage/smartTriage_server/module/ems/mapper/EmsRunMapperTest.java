package com.smartTriage.smartTriage_server.module.ems.mapper;

import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.EmsRunStatus;
import com.smartTriage.smartTriage_server.module.ems.dto.EmsRunResponse;
import com.smartTriage.smartTriage_server.module.ems.entity.EmsRun;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The server-derived case-lifecycle stage + acuity-split routing target the dashboard
 * renders as a stepper/badge. This is the single source of truth for "where is this
 * ambulance case" and "where is the patient headed", so it's worth locking.
 */
class EmsRunMapperTest {

    private EmsRun run(EmsRunStatus status) {
        return EmsRun.builder().hospital(new Hospital()).status(status).build();
    }

    // ── lifecycleStage ──

    @Test
    void stage_dispatched() {
        assertEquals("DISPATCHED", EmsRunMapper.toResponse(run(EmsRunStatus.DISPATCHED)).getLifecycleStage());
    }

    @Test
    void stage_enRoute() {
        assertEquals("EN_ROUTE", EmsRunMapper.toResponse(run(EmsRunStatus.EN_ROUTE)).getLifecycleStage());
    }

    @Test
    void stage_atDoor_whenArrivedAndNotYetAcknowledged() {
        assertEquals("AT_DOOR", EmsRunMapper.toResponse(run(EmsRunStatus.ARRIVED)).getLifecycleStage());
    }

    @Test
    void stage_received_whenArrivalAcknowledged() {
        EmsRun r = run(EmsRunStatus.ARRIVED);
        r.setArrivalAckedAt(Instant.now());
        assertEquals("RECEIVED", EmsRunMapper.toResponse(r).getLifecycleStage());
    }

    @Test
    void stage_handedOff_and_cancelled_areTerminal() {
        assertEquals("HANDED_OFF", EmsRunMapper.toResponse(run(EmsRunStatus.HANDED_OFF)).getLifecycleStage());
        assertEquals("CANCELLED", EmsRunMapper.toResponse(run(EmsRunStatus.CANCELLED)).getLifecycleStage());
    }

    // ── routingTarget (acuity-split) ──

    @Test
    void routing_red_projectsToResus() {
        EmsRun r = run(EmsRunStatus.EN_ROUTE);
        r.setFieldTriageCategory("RED");
        assertEquals("RESUS", EmsRunMapper.toResponse(r).getRoutingTarget());
    }

    @Test
    void routing_orange_projectsToAcute() {
        EmsRun r = run(EmsRunStatus.EN_ROUTE);
        r.setFieldTriageCategory("ORANGE");
        assertEquals("ACUTE", EmsRunMapper.toResponse(r).getRoutingTarget());
    }

    @Test
    void routing_lowerAcuity_projectsToTriageQueue() {
        for (String cat : new String[]{"YELLOW", "GREEN", "BLUE"}) {
            EmsRun r = run(EmsRunStatus.EN_ROUTE);
            r.setFieldTriageCategory(cat);
            assertEquals("TRIAGE_QUEUE", EmsRunMapper.toResponse(r).getRoutingTarget(), cat + " → triage queue");
        }
    }

    @Test
    void routing_nullUntilFieldTriaged() {
        assertNull(EmsRunMapper.toResponse(run(EmsRunStatus.EN_ROUTE)).getRoutingTarget());
    }

    @Test
    void routing_prefersActualZonePlacementOverProjection() {
        // Once the visit is placed (RED/ORANGE on arrival), that real zone is authoritative.
        EmsRun r = run(EmsRunStatus.ARRIVED);
        r.setFieldTriageCategory("RED");
        Visit v = new Visit();
        v.setCurrentEdZone(EdZone.RESUS);
        r.setVisit(v);
        assertEquals("RESUS", EmsRunMapper.toResponse(r).getRoutingTarget());
    }
}
