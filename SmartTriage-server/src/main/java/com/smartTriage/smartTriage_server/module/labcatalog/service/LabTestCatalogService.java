package com.smartTriage.smartTriage_server.module.labcatalog.service;

import com.smartTriage.smartTriage_server.common.enums.InvestigationType;
import com.smartTriage.smartTriage_server.module.labcatalog.dto.LabTestCatalogResponse;
import com.smartTriage.smartTriage_server.module.labcatalog.entity.LabTestCatalog;
import com.smartTriage.smartTriage_server.module.labcatalog.repository.LabTestCatalogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Read-only catalog service. New entries flow in through migrations or
 * SUPER_ADMIN tooling — clinicians don't add tests at order-time.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LabTestCatalogService {

    private final LabTestCatalogRepository repository;

    public List<LabTestCatalogResponse> search(String query) {
        if (query == null || query.trim().isEmpty()) {
            // No query — return the curated common list as the starting state.
            return repository
                    .findByIsCommonInRwandaTrueAndIsActiveTrueOrderByTestNameAsc()
                    .stream()
                    .map(this::toResponse)
                    .collect(Collectors.toList());
        }
        return repository
                .searchActive(query.trim())
                .stream()
                .limit(50)
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public List<LabTestCatalogResponse> findByType(InvestigationType type) {
        return repository.findByType(type)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public List<LabTestCatalogResponse> getCommonInRwanda() {
        return repository
                .findByIsCommonInRwandaTrueAndIsActiveTrueOrderByTestNameAsc()
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private LabTestCatalogResponse toResponse(LabTestCatalog t) {
        return LabTestCatalogResponse.builder()
                .id(t.getId())
                .testName(t.getTestName())
                .shortName(t.getShortName())
                .investigationType(t.getInvestigationType())
                .category(t.getCategory())
                .specimenType(t.getSpecimenType())
                .statTurnaroundMinutes(t.getStatTurnaroundMinutes())
                .routineTurnaroundMinutes(t.getRoutineTurnaroundMinutes())
                .clinicalUse(t.getClinicalUse())
                .isCommonInRwanda(t.isCommonInRwanda())
                .resultUnit(t.getResultUnit())
                .referenceLow(t.getReferenceLow())
                .referenceHigh(t.getReferenceHigh())
                .criticalLow(t.getCriticalLow())
                .criticalHigh(t.getCriticalHigh())
                .build();
    }
}
