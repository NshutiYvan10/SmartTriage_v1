package com.smartTriage.smartTriage_server.module.consent.service;

import com.smartTriage.smartTriage_server.common.enums.ConsentStatus;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.consent.dto.ConsentResponse;
import com.smartTriage.smartTriage_server.module.consent.dto.RecordConsentRequest;
import com.smartTriage.smartTriage_server.module.consent.dto.WithdrawConsentRequest;
import com.smartTriage.smartTriage_server.module.consent.entity.InformedConsent;
import com.smartTriage.smartTriage_server.module.consent.mapper.ConsentMapper;
import com.smartTriage.smartTriage_server.module.consent.repository.InformedConsentRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.service.VisitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Informed-consent service. Records structured consent (or refusal) and supports
 * withdrawal. The obtaining/withdrawing clinician is ALWAYS the authenticated
 * principal — never a client-supplied name — mirroring the documentation module.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InformedConsentService {

    private final InformedConsentRepository consentRepository;
    private final VisitService visitService;

    @Transactional
    public ConsentResponse recordConsent(UUID visitId, RecordConsentRequest request) {
        Visit visit = visitService.findVisitOrThrow(visitId);
        User clinician = resolveCurrentUserOrThrow();

        ConsentStatus status = request.getStatus() != null ? request.getStatus() : ConsentStatus.GIVEN;
        if (status == ConsentStatus.WITHDRAWN) {
            throw new ClinicalBusinessException(
                    "A consent cannot be recorded as WITHDRAWN. Record it as GIVEN or REFUSED, "
                    + "then withdraw it via the withdraw endpoint.");
        }

        InformedConsent consent = InformedConsent.builder()
                .visit(visit)
                .consentType(request.getConsentType())
                .procedureName(request.getProcedureName())
                .description(request.getDescription())
                .risksExplained(request.getRisksExplained())
                .benefitsExplained(request.getBenefitsExplained())
                .alternativesExplained(request.getAlternativesExplained())
                .questionsAnswered(request.isQuestionsAnswered())
                .interpreterUsed(request.isInterpreterUsed())
                .interpreterName(request.getInterpreterName())
                .language(request.getLanguage())
                .consentGrantor(request.getConsentGrantor())
                .grantorName(request.getGrantorName())
                .grantorRelationship(request.getGrantorRelationship())
                .witnessName(request.getWitnessName())
                .status(status)
                // Obtaining clinician derived from the authenticated user.
                .obtainedByUserId(clinician.getId())
                .obtainedByName(displayNameOf(clinician))
                .obtainedByRole(roleOf(clinician))
                .obtainedByLicenseNumber(clinician.getProfessionalLicense())
                .obtainedAt(Instant.now())
                .notes(request.getNotes())
                .build();

        consent = consentRepository.save(consent);
        log.info("Informed consent recorded — visit:{} type:{} status:{} obtainedBy:{}",
                visit.getVisitNumber(), consent.getConsentType(), consent.getStatus(),
                consent.getObtainedByUserId());
        return ConsentMapper.toResponse(consent);
    }

    @Transactional
    public ConsentResponse withdrawConsent(UUID consentId, WithdrawConsentRequest request) {
        InformedConsent consent = findConsentOrThrow(consentId);

        if (consent.getStatus() != ConsentStatus.GIVEN) {
            throw new ClinicalBusinessException(
                    "Only a GIVEN consent can be withdrawn (current status: " + consent.getStatus() + ").");
        }

        User clinician = resolveCurrentUserOrThrow();
        consent.setStatus(ConsentStatus.WITHDRAWN);
        consent.setWithdrawnByUserId(clinician.getId());
        consent.setWithdrawnByName(displayNameOf(clinician));
        consent.setWithdrawnAt(Instant.now());
        consent.setWithdrawalReason(request.getReason());

        consent = consentRepository.save(consent);
        log.info("Informed consent withdrawn — id:{} withdrawnBy:{}", consent.getId(), clinician.getId());
        return ConsentMapper.toResponse(consent);
    }

    public List<ConsentResponse> getConsentsForVisit(UUID visitId) {
        return consentRepository.findByVisitIdAndIsActiveTrueOrderByObtainedAtDesc(visitId)
                .stream().map(ConsentMapper::toResponse).collect(Collectors.toList());
    }

    public ConsentResponse getConsent(UUID consentId) {
        return ConsentMapper.toResponse(findConsentOrThrow(consentId));
    }

    public InformedConsent findConsentOrThrow(UUID id) {
        return consentRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("InformedConsent", "id", id));
    }

    // ── authenticated-author resolution (mirrors ClinicalNoteService) ──

    private User resolveCurrentUserOrThrow() {
        Object principal = null;
        try {
            principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        } catch (Exception e) {
            log.warn("No authentication present when recording informed consent");
        }
        if (principal instanceof User) {
            return (User) principal;
        }
        throw new AccessDeniedException(
                "Informed consent must be recorded by an authenticated clinician; principal=" + principal);
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
