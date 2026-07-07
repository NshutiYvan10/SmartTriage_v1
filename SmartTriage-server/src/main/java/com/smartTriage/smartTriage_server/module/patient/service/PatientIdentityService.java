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
    // Merge must re-point EVERYTHING keyed to the placeholder's patient id — not just
    // visits. An allergy recorded on the placeholder and left behind is invisible to
    // the prescribe-time allergy gate (it reads findActiveByPatientId on the SURVIVING
    // patient), silently defeating the hard block.
    private final com.smartTriage.smartTriage_server.module.patient.repository.PatientAllergyRepository patientAllergyRepository;
    private final com.smartTriage.smartTriage_server.module.patient.repository.PatientChronicConditionRepository patientChronicConditionRepository;
    private final com.smartTriage.smartTriage_server.module.clinicalsigns.repository.ClinicalSignEventRepository clinicalSignEventRepository;

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

        // Rename path — a real name (or a card anchor, e.g. an unconscious patient
        // identified by their card before a name is known) is required.
        boolean hasName = request.getFirstName() != null && !request.getFirstName().isBlank()
                && request.getLastName() != null && !request.getLastName().isBlank();
        boolean hasCard = request.getRfidCardId() != null && !request.getRfidCardId().isBlank();
        if (!hasName && !hasCard) {
            throw new ClinicalBusinessException(
                    "Either mergeIntoPatientId, both firstName and lastName, or an RFID card, are required");
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

        // A name may be absent when the patient is identified by CARD ALONE (e.g. an
        // unconscious patient whose card resolves before anyone can give a name) — keep
        // the placeholder display name in that case rather than blanking it.
        boolean nameCaptured = request.getFirstName() != null && !request.getFirstName().isBlank()
                && request.getLastName() != null && !request.getLastName().isBlank();
        if (request.getFirstName() != null && !request.getFirstName().isBlank()) {
            placeholder.setFirstName(request.getFirstName().trim());
        }
        if (request.getLastName() != null && !request.getLastName().isBlank()) {
            placeholder.setLastName(request.getLastName().trim());
        }
        if (request.getDateOfBirth() != null)  placeholder.setDateOfBirth(request.getDateOfBirth());
        if (request.getGender() != null)       placeholder.setGender(request.getGender());
        String nid = request.getNationalId() != null && !request.getNationalId().isBlank()
                ? request.getNationalId().trim() : null;
        String card = request.getRfidCardId() != null && !request.getRfidCardId().isBlank()
                ? request.getRfidCardId().trim() : null;
        if (nid != null) placeholder.setNationalId(nid);
        if (nid != null || card != null) {
            // A card that ALREADY belongs to another patient is that person's identity —
            // attaching this placeholder to it via findOrCreate would silently graft the
            // wrong person's cross-hospital record (allergies, deep-record reads) onto them.
            // That is a MERGE decision, not a rename: reject and route the registrar to the
            // merge path. (findOrCreate only 409s on a CONFLICTING second key, so it would
            // NOT catch a card-only resolve onto an existing owner — we guard it here.)
            if (card != null) {
                rejectIfCardBelongsToAnotherPatient(card, placeholder.getId());
            }
            // Link to the shared cross-hospital identity by whichever anchor(s) the
            // registrar supplied. Two-key resolve-or-merge: a national ID resolving to a
            // DIFFERENT identity than the card throws IdentityConflictException (409).
            placeholder.setPersonIdentity(personIdentityService.findOrCreate(nid, card));
        }
        if (request.getPhoneNumber() != null)  placeholder.setPhoneNumber(request.getPhoneNumber().trim());
        if (request.getAddress() != null)      placeholder.setAddress(request.getAddress().trim());

        // Only flip the patient to IDENTIFIED when a real human name was actually captured.
        // A card-anchor-only resolve records the card + keeps the patient findable, but the
        // patient still has no name on the chart — leave isUnidentified=true so the overdue
        // reminders + reconciliation queue keep chasing a real identity (medico-legal req).
        boolean fullyIdentified = nameCaptured
                || (placeholder.getFirstName() != null && !placeholder.getFirstName().isBlank()
                    && !"Unknown".equalsIgnoreCase(placeholder.getFirstName())
                    && placeholder.getLastName() != null && !placeholder.getLastName().isBlank());
        if (fullyIdentified) {
            placeholder.setUnidentified(false);
            placeholder.setIdentifiedAt(now);
            placeholder.setIdentifiedBy(actor);
        }
        if (request.getResolutionNote() != null) placeholder.setResolutionNote(request.getResolutionNote());

        Patient saved = patientRepository.save(placeholder);

        log.info("[identity] Resolved patient {}: '{}' → '{} {}' (identified={}) by {} at {} (note: {})",
                saved.getId(),
                oldDisplay,
                saved.getFirstName(),
                saved.getLastName(),
                fullyIdentified,
                actor != null ? formatActorName(actor) : "system",
                now,
                request.getResolutionNote() != null ? request.getResolutionNote() : "—");

        return saved;
    }

    /**
     * Guard against grafting: a card already carried by a DIFFERENT active patient must
     * not be silently attached to this placeholder's identity — that would cross-link two
     * people's cross-hospital records. Such a case is a deliberate, audited MERGE, not a
     * rename. Throws {@link com.smartTriage.smartTriage_server.common.exception.IdentityConflictException}.
     */
    private void rejectIfCardBelongsToAnotherPatient(String card, UUID placeholderId) {
        personIdentityService.findByRfidCardId(card).ifPresent(existing -> {
            boolean ownedByOther = patientRepository
                    .findByPersonIdentityIdAndIsActiveTrue(existing.getId()).stream()
                    .anyMatch(p -> !p.getId().equals(placeholderId));
            if (ownedByOther) {
                throw new com.smartTriage.smartTriage_server.common.exception.IdentityConflictException(
                        "That RFID card already belongs to a registered patient. If this is the same "
                                + "person, use 'Merge into existing patient' instead of typing an identity.");
            }
        });
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

        // Cross-hospital person identity: carry the placeholder's link (a resolved RFID
        // card / national ID) onto the target when the target has none — otherwise the
        // patient's card stops resolving the moment the placeholder is soft-deleted.
        // Two DIFFERENT identities on the two records is an identity CONFLICT: merging
        // would cross-link two people's cross-hospital records — fail closed.
        if (placeholder.getPersonIdentity() != null) {
            if (target.getPersonIdentity() == null) {
                target.setPersonIdentity(placeholder.getPersonIdentity());
            } else if (!target.getPersonIdentity().getId().equals(placeholder.getPersonIdentity().getId())) {
                throw new com.smartTriage.smartTriage_server.common.exception.IdentityConflictException(
                        "These two records are linked to DIFFERENT cross-hospital identities "
                                + "(different national ID / card). Verify you selected the right patient "
                                + "before merging — merging would cross-link two people's records.");
            }
        }

        // Free-text chronic-conditions capture (the diabetic trigger and chart summaries
        // read it) — never lose what the desk wrote on the placeholder.
        String phConditions = placeholder.getChronicConditions();
        if (phConditions != null && !phConditions.isBlank()) {
            String tgtConditions = target.getChronicConditions();
            if (tgtConditions == null || tgtConditions.isBlank()) {
                target.setChronicConditions(phConditions);
            } else if (!tgtConditions.toLowerCase().contains(phConditions.toLowerCase())) {
                target.setChronicConditions(tgtConditions + " | " + phConditions);
            }
        }

        // Re-point EVERY visit on the placeholder to the target — loop until drained
        // (the old code re-pointed only the first page of 50).
        int visitCount = 0;
        while (true) {
            List<Visit> visits = visitRepository
                    .findByPatientIdAndIsActiveTrue(placeholder.getId(), PageRequest.of(0, 50))
                    .getContent();
            if (visits.isEmpty()) break;
            for (Visit visit : visits) {
                visit.setPatient(target);
                visitRepository.save(visit);
                visitCount++;
            }
        }

        // Re-point the clinical history keyed to the patient id — INCLUDING refuted
        // allergies / resolved conditions: the history follows the person, and the
        // prescribe-time allergy gate reads the surviving patient's rows.
        int allergyCount = 0;
        for (var allergy : patientAllergyRepository.findAllByPatientIdIncludingRefuted(placeholder.getId())) {
            allergy.setPatient(target);
            patientAllergyRepository.save(allergy);
            allergyCount++;
        }
        int conditionCount = 0;
        for (var condition : patientChronicConditionRepository.findAllByPatientIdIncludingResolved(placeholder.getId())) {
            condition.setPatient(target);
            patientChronicConditionRepository.save(condition);
            conditionCount++;
        }
        int signCount = 0;
        for (var sign : clinicalSignEventRepository.findByPatientId(placeholder.getId())) {
            sign.setPatient(target);
            clinicalSignEventRepository.save(sign);
            signCount++;
        }
        patientRepository.save(target);

        // Mark the placeholder resolved + soft-deleted. Preserve
        // identified_at/by for audit even though the row is inactive.
        placeholder.setUnidentified(false);
        placeholder.setIdentifiedAt(now);
        placeholder.setIdentifiedBy(actor);
        placeholder.setResolutionNote(resolutionNote);
        placeholder.softDelete();
        patientRepository.save(placeholder);

        log.info("[identity] Merged placeholder patient {} (label={}) into existing patient {} "
                        + "({}; re-pointed {} visits, {} allergies, {} chronic conditions, {} sign events) by {} at {}",
                placeholder.getId(),
                placeholder.getPlaceholderLabel(),
                target.getId(),
                target.getFirstName() + " " + target.getLastName(),
                visitCount, allergyCount, conditionCount, signCount,
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
