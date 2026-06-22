package com.smartTriage.smartTriage_server.module.patient.service;

import com.smartTriage.smartTriage_server.module.patient.entity.PersonIdentity;
import com.smartTriage.smartTriage_server.module.patient.repository.PersonIdentityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the SHARED cross-hospital {@link PersonIdentity} for a national ID (Phase 1).
 *
 * Matching is national-ID-exact only — deterministic, because a wrong probabilistic merge of
 * two different people would be a patient-safety incident. A blank/absent national ID yields no
 * identity (the caller keeps the patient purely local).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonIdentityService {

    private final PersonIdentityRepository personIdentityRepository;

    /**
     * Find the shared identity for this national ID, creating it on first sight. Returns null for
     * a blank/absent national ID. Handles the first-seen-at-two-hospitals-at-once race by catching
     * the unique-constraint violation and re-reading (mirrors the dedup-index catch used elsewhere).
     */
    @Transactional
    public PersonIdentity findOrCreate(String nationalId) {
        String nid = nationalId == null ? null : nationalId.trim();
        if (nid == null || nid.isEmpty()) {
            return null;
        }
        return personIdentityRepository.findByNationalIdAndIsActiveTrue(nid)
                .orElseGet(() -> createOrReread(nid));
    }

    private PersonIdentity createOrReread(String nid) {
        try {
            return personIdentityRepository.saveAndFlush(PersonIdentity.builder().nationalId(nid).build());
        } catch (DataIntegrityViolationException race) {
            // Another transaction created the same identity concurrently — adopt theirs.
            return personIdentityRepository.findByNationalIdAndIsActiveTrue(nid)
                    .orElseThrow(() -> race);
        }
    }
}
