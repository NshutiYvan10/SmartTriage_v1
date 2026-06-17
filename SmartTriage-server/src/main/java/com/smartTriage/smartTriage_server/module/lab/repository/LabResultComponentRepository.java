package com.smartTriage.smartTriage_server.module.lab.repository;

import com.smartTriage.smartTriage_server.module.lab.entity.LabResultComponent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface LabResultComponentRepository extends JpaRepository<LabResultComponent, UUID> {

    /** All analyte values entered for an order, in display order. */
    List<LabResultComponent> findByLabOrder_IdAndIsActiveTrueOrderByDisplayOrderAsc(UUID labOrderId);

    /** Batch variant — components for a page of orders in ONE query (avoids N+1). */
    List<LabResultComponent> findByLabOrder_IdInAndIsActiveTrueOrderByDisplayOrderAsc(Collection<UUID> labOrderIds);

    /** Used when re-entering results: clear the prior component set for an order. */
    List<LabResultComponent> findByLabOrder_Id(UUID labOrderId);
}
