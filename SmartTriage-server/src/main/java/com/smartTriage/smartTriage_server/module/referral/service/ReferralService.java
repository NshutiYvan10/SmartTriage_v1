package com.smartTriage.smartTriage_server.module.referral.service;

import com.smartTriage.smartTriage_server.common.enums.ReferralStatus;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.referral.dto.CreateReferralRequest;
import com.smartTriage.smartTriage_server.module.referral.dto.ReferralResponse;
import com.smartTriage.smartTriage_server.module.referral.dto.RespondReferralRequest;
import com.smartTriage.smartTriage_server.module.referral.entity.Referral;
import com.smartTriage.smartTriage_server.module.referral.mapper.ReferralMapper;
import com.smartTriage.smartTriage_server.module.referral.repository.ReferralRepository;
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
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Referral / consultation service. Both the request and the consultant's
 * response derive their actor from the authenticated principal — never the
 * client request body.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReferralService {

    private final ReferralRepository referralRepository;
    private final VisitService visitService;

    private static final EnumSet<ReferralStatus> RESPONSE_OUTCOMES =
            EnumSet.of(ReferralStatus.ACCEPTED, ReferralStatus.DECLINED, ReferralStatus.COMPLETED);
    private static final EnumSet<ReferralStatus> RESPONDABLE =
            EnumSet.of(ReferralStatus.REQUESTED, ReferralStatus.ACCEPTED);
    private static final EnumSet<ReferralStatus> CANCELLABLE =
            EnumSet.of(ReferralStatus.REQUESTED, ReferralStatus.ACCEPTED);

    @Transactional
    public ReferralResponse requestReferral(UUID visitId, CreateReferralRequest request) {
        Visit visit = visitService.findVisitOrThrow(visitId);
        User requester = resolveCurrentUserOrThrow();

        Referral referral = Referral.builder()
                .visit(visit)
                .referralType(request.getReferralType())
                .specialty(request.getSpecialty())
                .urgency(request.getUrgency())
                .reasonForReferral(request.getReasonForReferral())
                .clinicalQuestion(request.getClinicalQuestion())
                .targetFacility(request.getTargetFacility())
                .status(ReferralStatus.REQUESTED)
                .requestedByUserId(requester.getId())
                .requestedByName(displayNameOf(requester))
                .requestedByRole(roleOf(requester))
                .requestedAt(Instant.now())
                .notes(request.getNotes())
                .build();

        referral = referralRepository.save(referral);
        log.info("Referral requested — visit:{} specialty:{} urgency:{} by:{}",
                visit.getVisitNumber(), referral.getSpecialty(), referral.getUrgency(),
                referral.getRequestedByUserId());
        return ReferralMapper.toResponse(referral);
    }

    @Transactional
    public ReferralResponse respondToReferral(UUID referralId, RespondReferralRequest request) {
        Referral referral = findReferralOrThrow(referralId);

        if (!RESPONSE_OUTCOMES.contains(request.getOutcome())) {
            throw new ClinicalBusinessException(
                    "Response outcome must be ACCEPTED, DECLINED or COMPLETED.");
        }
        if (!RESPONDABLE.contains(referral.getStatus())) {
            throw new ClinicalBusinessException(
                    "Cannot respond to a referral in status " + referral.getStatus() + ".");
        }
        if (request.getOutcome() == ReferralStatus.DECLINED
                && (request.getDeclineReason() == null || request.getDeclineReason().isBlank())) {
            throw new ClinicalBusinessException("A reason is required when declining a referral.");
        }

        User responder = resolveCurrentUserOrThrow();
        referral.setStatus(request.getOutcome());
        referral.setRespondedByUserId(responder.getId());
        referral.setRespondedByName(displayNameOf(responder));
        referral.setRespondedByRole(roleOf(responder));
        referral.setRespondedAt(Instant.now());
        referral.setResponseNotes(request.getResponseNotes());
        if (request.getOutcome() == ReferralStatus.DECLINED) {
            referral.setDeclineReason(request.getDeclineReason());
        }

        referral = referralRepository.save(referral);
        log.info("Referral {} responded — outcome:{} by:{}",
                referral.getId(), referral.getStatus(), responder.getId());
        return ReferralMapper.toResponse(referral);
    }

    @Transactional
    public ReferralResponse cancelReferral(UUID referralId) {
        Referral referral = findReferralOrThrow(referralId);
        if (!CANCELLABLE.contains(referral.getStatus())) {
            throw new ClinicalBusinessException(
                    "Cannot cancel a referral in status " + referral.getStatus() + ".");
        }
        resolveCurrentUserOrThrow(); // require an authenticated actor
        referral.setStatus(ReferralStatus.CANCELLED);
        referral = referralRepository.save(referral);
        log.info("Referral {} cancelled", referral.getId());
        return ReferralMapper.toResponse(referral);
    }

    public List<ReferralResponse> getReferralsForVisit(UUID visitId) {
        return referralRepository.findByVisitIdAndIsActiveTrueOrderByRequestedAtDesc(visitId)
                .stream().map(ReferralMapper::toResponse).collect(Collectors.toList());
    }

    public ReferralResponse getReferral(UUID referralId) {
        return ReferralMapper.toResponse(findReferralOrThrow(referralId));
    }

    public Referral findReferralOrThrow(UUID id) {
        return referralRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Referral", "id", id));
    }

    // ── authenticated-actor resolution (mirrors ClinicalNoteService) ──

    private User resolveCurrentUserOrThrow() {
        Object principal = null;
        try {
            principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        } catch (Exception e) {
            log.warn("No authentication present for referral action");
        }
        if (principal instanceof User) {
            return (User) principal;
        }
        throw new AccessDeniedException(
                "Referral actions require an authenticated clinician; principal=" + principal);
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
