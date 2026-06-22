package com.smartTriage.smartTriage_server.module.patient.service;

import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.patient.dto.ResolveIdentityRequest;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PatientIdentityService — resolves the identity of a patient who was
 * admitted as an unidentified placeholder via Direct Resus (V28).
 *
 * <p>Two resolution paths:
 * <ul>
 *   <li><b>Type real identity</b>: caller supplies firstName + lastName
 *       (and optional DOB, gender, ID). The placeholder Patient row is
 *       updated <em>in place</em>: its UUID is preserved, so all
 *       downstream references (visit, triage record, bed placement,
 *       alerts, audit log) remain valid. {@code is_unidentified} flips
 *       to FALSE; {@code placeholder_label} stays as the audit anchor.</li>
 *   <li><b>Merge into existing patient</b>: caller supplies
 *       {@code mergeIntoPatientId}. All visits attached to the placeholder
 *       are re-pointed at the existing patient and the placeholder is
 *       soft-deleted. Used when MPI search finds the patient was already
 *       registered from a previous visit.</li>
 * </ul>
 *
 * <p>Both paths log the resolution for audit. The "Marie Uwimana was
 * admitted as Unknown Alpha at 14:32, identified at 15:18 by Nurse
 * Marie" trace must be reconstructible from the patient row's
 * {@code placeholder_label}, {@code identified_at}, and
 * {@code identified_by} fields.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PatientIdentityService {

    private final PatientRepository patientRepository;
    private final VisitRepository visitRepository;
    /** Phase 1 — link a resolved placeholder to the shared cross-hospital identity once it gains a national ID. */
    private final PersonIdentityService personIdentityService;

    /**
     * Resolve a placeholder patient's identity. Returns the resulting
     * Patient — for the rename path that's the same entity (UUID
     * preserved); for the merge path that's the existing target patient.
     */
    @Transactional
    public Patient resolveIdentity(UUID placeholderPatientId, ResolveIdentityRequest request) {
        Patient placeholder = patientRepository.findByIdAndIsActiveTrue(placeholderPatientId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient", "id", placeholderPatientId));

        if (!placeholder.isUnidentified()) {
            throw new ClinicalBusinessException(
                    "Patient " + placeholderPatientId + " is already identified — nothing to resolve");
        }

        User actor = resolveAuthenticatedUser().orElse(null);
        Instant now = Instant.now();

        if (request.getMergeIntoPatientId() != null) {
            return mergeIntoExistingPatient(placeholder, request.getMergeIntoPatientId(), actor, now,
                    request.getResolutionNote());
        }

        // Rename path
        if (request.getFirstName() == null || request.getFirstName().isBlank()
                || request.getLastName() == null || request.getLastName().isBlank()) {
            throw new ClinicalBusinessException(
                    "Either mergeIntoPatientId, or both firstName and lastName, are required");
        }

        return renamePlaceholderInPlace(placeholder, request, actor, now);
    }

    /**
     * Update the placeholder Patient with the real identity. UUID
     * preserved — every existing reference is automatically valid.
     */
    private Patient renamePlaceholderInPlace(Patient placeholder,
                                             ResolveIdentityRequest request,
                                             User actor,
                                             Instant now) {
        String oldDisplay = "Unknown " + (placeholder.getPlaceholderLabel() != null
                ? placeholder.getPlaceholderLabel() : placeholder.getLastName());

        placeholder.setFirstName(request.getFirstName().trim());
        placeholder.setLastName(request.getLastName().trim());
        if (request.getDateOfBirth() != null)  placeholder.setDateOfBirth(request.getDateOfBirth());
        if (request.getGender() != null)       placeholder.setGender(request.getGender());
        if (request.getNationalId() != null) {
            placeholder.setNationalId(request.getNationalId().trim());
            // Now that the patient has a national ID, link them to the shared cross-hospital identity.
            placeholder.setPersonIdentity(personIdentityService.findOrCreate(placeholder.getNationalId()));
        }
        if (request.getPhoneNumber() != null)  placeholder.setPhoneNumber(request.getPhoneNumber().trim());
        if (request.getAddress() != null)      placeholder.setAddress(request.getAddress().trim());

        placeholder.setUnidentified(false);
        placeholder.setIdentifiedAt(now);
        placeholder.setIdentifiedBy(actor);
        placeholder.setResolutionNote(request.getResolutionNote());

        Patient saved = patientRepository.save(placeholder);

        log.info("[identity] Resolved patient {}: '{}' → '{} {}' by {} at {} (note: {})",
                saved.getId(),
                oldDisplay,
                saved.getFirstName(),
                saved.getLastName(),
                actor != null ? formatActorName(actor) : "system",
                now,
                request.getResolutionNote() != null ? request.getResolutionNote() : "—");

        return saved;
    }

    /**
     * Merge the placeholder's visits into an existing patient. The
     * placeholder row is preserved (soft-deleted) so the audit trail
     * isn't broken — its {@code placeholder_label}, {@code identified_at},
     * and {@code identified_by} stay intact, with {@code is_active=false}.
     */
    private Patient mergeIntoExistingPatient(Patient placeholder,
                                             UUID targetPatientId,
                                             User actor,
                                             Instant now,
                                             String resolutionNote) {
        if (placeholder.getId().equals(targetPatientId)) {
            throw new ClinicalBusinessException("Cannot merge a patient into itself");
        }

        Patient target = patientRepository.findByIdAndIsActiveTrue(targetPatientId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient", "id", targetPatientId));

        if (!target.getHospital().getId().equals(placeholder.getHospital().getId())) {
            throw new ClinicalBusinessException(
                    "Cannot merge across hospitals (placeholder=" + placeholder.getHospital().getHospitalCode()
                            + ", target=" + target.getHospital().getHospitalCode() + ")");
        }

        // Re-point every active visit on the placeholder to the target.
        // Page through to avoid loading huge result sets in one shot, but
        // for unidentified arrivals we expect 1 visit ~always.
        var page = visitRepository.findByPatientIdAndIsActiveTrue(placeholder.getId(),
                PageRequest.of(0, 50));
        List<Visit> visits = page.getContent();
        for (Visit visit : visits) {
            visit.setPatient(target);
            visitRepository.save(visit);
        }

        // Mark the placeholder resolved + soft-deleted. Preserve
        // identified_at/by for audit even though the row is inactive.
        placeholder.setUnidentified(false);
        placeholder.setIdentifiedAt(now);
        placeholder.setIdentifiedBy(actor);
        placeholder.setResolutionNote(resolutionNote);
        placeholder.softDelete();
        patientRepository.save(placeholder);

        log.info("[identity] Merged placeholder patient {} (label={}) into existing patient {} "
                        + "({}, {} visits re-pointed) by {} at {}",
                placeholder.getId(),
                placeholder.getPlaceholderLabel(),
                target.getId(),
                target.getFirstName() + " " + target.getLastName(),
                visits.size(),
                actor != null ? formatActorName(actor) : "system",
                now);

        return target;
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private Optional<User> resolveAuthenticatedUser() {
        try {
            Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            if (principal instanceof User user) return Optional.of(user);
        } catch (Exception ignored) {
            // SecurityContext may be empty (background jobs, tests)
        }
        return Optional.empty();
    }

    private String formatActorName(User user) {
        String full = ((user.getFirstName() != null ? user.getFirstName() : "") + " "
                + (user.getLastName() != null ? user.getLastName() : "")).trim();
        return full.isEmpty() ? user.getUsername() : full;
    }
}
