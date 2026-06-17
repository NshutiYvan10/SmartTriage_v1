package com.smartTriage.smartTriage_server.module.lab.repository;

import com.smartTriage.smartTriage_server.module.lab.entity.LabPanelComponent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LabPanelComponentRepository extends JpaRepository<LabPanelComponent, UUID> {

    /** Analyte definitions for a panel, in display order. Case-insensitive on the panel name. */
    List<LabPanelComponent> findByPanelTestNameIgnoreCaseAndIsActiveTrueOrderByDisplayOrderAsc(String panelTestName);
}
