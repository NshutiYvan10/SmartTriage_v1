package com.smartTriage.smartTriage_server.module.consent.service;

import com.smartTriage.smartTriage_server.common.enums.DataSharingConsentStatus;
import com.smartTriage.smartTriage_server.common.enums.DataSharingScope;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.consent.dto.DataSharingConsentResponse;
import com.smartTriage.smartTriage_server.module.consent.dto.RecordDataSharingConsentRequest;
import com.smartTriage.smartTriage_server.module.consent.dto.WithdrawConsentRequest;
import com.smartTriage.smartTriage_server.module.consent.entity.DataSharingConsent;
import com.smartTriage.smartTriage_server.module.consent.mapper.DataSharingConsentMapper;
import com.smartTriage.smartTriage_server.module.consent.repository.DataSharingConsentRepository;
import com.smartTriage.smartTriage_server.module.patient.entity.PersonIdentity;
import com.smartTriage.smartTriage_server.module.patient.repository.PersonIdentityRepository;
import com.smartTriage.smartTriage_server.module.patient.service.PersonIdentityService;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cross-hospital DATA-SHARING consent (Phase 2) — opt-in/refusal to share the deep clinical record
 * across SmartTriage hospitals, keyed on the shared {@link PersonIdentity}. Actor is always the
 * authenticated principal (mirrors InformedConsentService). At most one effective GRANTED at a time.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DataSharingConsentService {

    private final DataSharingConsentRepository consentRepository;
    private final PersonIdentityRepository personIdentityRepository;
    private final PersonIdentityService personIdentityService;

    @Transactional
    public DataSharingConsentResponse recordConsent(String nationalId, RecordDataSharingConsentRequest request) {
        PersonIdentity identity = personIdentityService.findOrCreate(nationalId);
        if (identity == null) {
            throw new ClinicalBusinessException("A national ID is required to record a data-sharing consent.");
        }
        User clinician = resolveCurrentUserOrThrow();

        DataSharingConsentStatus status = request.getStatus() != null
                ? request.getStatus() : DataSharingConsentStatus.GRANTED;
        if (status == DataSharingConsentStatus.WITHDRAWN) {
            throw new ClinicalBusinessException(
                    "A consent cannot be recorded as WITHDRAWN. Record it as GRANTED or DENIED, "
                    + "then withdraw it via the withdraw endpoint.");
        }

        // One effective grant at a time: supersede any existing live GRANTED before granting anew.
        if (status == DataSharingConsentStatus.GRANTED) {
            getCurrentEffectiveConsent(identity.getId()).ifPresent(existing -> {
                existing.setStatus(DataSharingConsentStatus.WITHDRAWN);
                existing.setWithdrawnByUserId(clinician.getId());
                existing.setWithdrawnByName(displayNameOf(clinician));
                existing.setWithdrawnAt(Instant.now());
                existing.setWithdrawalReason("Superseded by a new consent grant");
                consentRepository.save(existing);
            });
        }

        DataSharingConsent consent = DataSharingConsent.builder()
                .personIdentity(identity)
                .status(status)
                .scope(request.getScope() != null ? request.getScope() : DataSharingScope.FULL_RECORD)
                .consentGrantor(request.getConsentGrantor())
                .grantorName(request.getGrantorName())
                .grantorRelationship(request.getGrantorRelationship())
                .obtainedByUserId(clinician.getId())
                .obtainedByName(displayNameOf(clinician))
                .obtainedByRole(roleOf(clinician))
                .obtainedByLicenseNumber(clinician.getProfessionalLicense())
                .obtainedAt(Instant.now())
                .notes(request.getNotes())
                .build();
        try {
            consent = consentRepository.saveAndFlush(consent);
        } catch (DataIntegrityViolationException race) {
            // Partial-unique backstop — a concurrent grant slipped in between supersede and save.
            throw new ClinicalBusinessException("A concurrent consent change occurred; please retry.");
        }
        log.info("Data-sharing consent recorded — identity:{} status:{} obtainedBy:{}",
                identity.getId(), consent.getStatus(), consent.getObtainedByUserId());
        return DataSharingConsentMapper.toResponse(consent);
    }

    @Transactional
    public DataSharingConsentResponse withdrawConsent(UUID consentId, WithdrawConsentRequest request) {
        DataSharingConsent consent = consentRepository.findByIdAndIsActiveTrue(consentId)
                .orElseThrow(() -> new ResourceNotFoundException("DataSharingConsent", "id", consentId));
        if (consent.getStatus() != DataSharingConsentStatus.GRANTED) {
            throw new ClinicalBusinessException(
                    "Only a GRANTED consent can be withdrawn (current status: " + consent.getStatus() + ").");
        }
        User clinician = resolveCurrentUserOrThrow();
        consent.setStatus(DataSharingConsentStatus.WITHDRAWN);
        consent.setWithdrawnByUserId(clinician.getId());
        consent.setWithdrawnByName(displayNameOf(clinician));
        consent.setWithdrawnAt(Instant.now());
        consent.setWithdrawalReason(request.getReason());
        consent = consentRepository.save(consent);
        log.info("Data-sharing consent withdrawn — id:{} by:{}", consent.getId(), clinician.getId());
        return DataSharingConsentMapper.toResponse(consent);
    }

    /** The current effective (live GRANTED) consent for a person, if any. Used by the deep-record gate. */
    public Optional<DataSharingConsent> getCurrentEffectiveConsent(UUID personIdentityId) {
        return consentRepository.findFirstByPersonIdentityIdAndStatusAndIsActiveTrueOrderByObtainedAtDesc(
                personIdentityId, DataSharingConsentStatus.GRANTED);
    }

    /** Consent history for a national ID (empty if no shared identity exists yet). */
    public List<DataSharingConsentResponse> getConsentsForNationalId(String nationalId) {
        String nid = nationalId == null ? null : nationalId.trim();
        if (nid == null || nid.isEmpty()) return List.of();
        return personIdentityRepository.findByNationalIdAndIsActiveTrue(nid)
                .map(pi -> consentRepository.findByPersonIdentityIdAndIsActiveTrueOrderByObtainedAtDesc(pi.getId())
                        .stream().map(DataSharingConsentMapper::toResponse).collect(Collectors.toList()))
                .orElseGet(List::of);
    }

    // ── authenticated-author resolution (mirrors InformedConsentService) ──
    private User resolveCurrentUserOrThrow() {
        Object principal = null;
        try {
            principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        } catch (Exception e) {
            log.warn("No authentication present when recording data-sharing consent");
        }
        if (principal instanceof User user) {
            return user;
        }
        throw new AccessDeniedException(
                "Data-sharing consent must be recorded by an authenticated clinician; principal=" + principal);
    }

    private static String roleOf(User user) {
        return user.getRole() != null ? user.getRole().name() : null;
    }

    private static String displayNameOf(User user) {
        String first = user.getFirstName();
        String last = user.getLastName();
        if ((first == null || first.isBlank()) && (last == null || last.isBlank())) {
            return user.getEmail();
        }
        StringBuilder sb = new StringBuilder();
        if (first != null && !first.isBlank()) sb.append(first.trim());
        if (last != null && !last.isBlank()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(last.trim());
        }
        return sb.toString();
    }
}
