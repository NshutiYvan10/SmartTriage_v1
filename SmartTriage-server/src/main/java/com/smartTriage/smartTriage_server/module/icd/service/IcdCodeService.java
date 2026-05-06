package com.smartTriage.smartTriage_server.module.icd.service;

import com.smartTriage.smartTriage_server.module.icd.dto.IcdCodeResponse;
import com.smartTriage.smartTriage_server.module.icd.entity.IcdCode;
import com.smartTriage.smartTriage_server.module.icd.repository.IcdCodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * IcdCodeService — exposes search and browse over the ICD-10 catalog.
 * Reference data only; no write operations from clinicians (catalog is
 * curated centrally and updated via SUPER_ADMIN endpoints / migrations).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IcdCodeService {

    private final IcdCodeRepository icdCodeRepository;

    public List<IcdCodeResponse> search(String query) {
        if (query == null || query.trim().isEmpty()) {
            // Empty query — surface the curated common-in-Rwanda list as
            // the starting state so the doctor sees the most useful codes
            // before typing.
            return icdCodeRepository
                    .findByIsCommonInRwandaTrueAndIsActiveTrueOrderByDescriptionAsc()
                    .stream()
                    .map(this::toResponse)
                    .collect(Collectors.toList());
        }
        return icdCodeRepository
                .searchActive(query.trim())
                .stream()
                .limit(50)
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public Page<IcdCodeResponse> browse(Pageable pageable) {
        return icdCodeRepository.findAllActive(pageable).map(this::toResponse);
    }

    public List<IcdCodeResponse> getCommonInRwanda() {
        return icdCodeRepository
                .findByIsCommonInRwandaTrueAndIsActiveTrueOrderByDescriptionAsc()
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private IcdCodeResponse toResponse(IcdCode i) {
        return IcdCodeResponse.builder()
                .id(i.getId())
                .code(i.getCode())
                .description(i.getDescription())
                .category(i.getCategory())
                .isCommonInRwanda(i.isCommonInRwanda())
                .clinicalNotes(i.getClinicalNotes())
                .build();
    }
}
