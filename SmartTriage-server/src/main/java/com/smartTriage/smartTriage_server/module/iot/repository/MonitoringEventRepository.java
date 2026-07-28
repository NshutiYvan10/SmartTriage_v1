package com.smartTriage.smartTriage_server.module.iot.repository;

import com.smartTriage.smartTriage_server.module.iot.entity.MonitoringEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface MonitoringEventRepository extends JpaRepository<MonitoringEvent, UUID> {

    List<MonitoringEvent> findByVisitIdAndIsActiveTrueAndOccurredAtAfterOrderByOccurredAtAsc(
            UUID visitId, Instant after);
}
