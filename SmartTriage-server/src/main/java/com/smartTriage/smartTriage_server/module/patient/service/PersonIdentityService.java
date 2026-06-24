package com.smartTriage.smartTriage_server.module.patient.service;

import com.smartTriage.smartTriage_server.common.exception.IdentityConflictException;
import com.smartTriage.smartTriage_server.module.patient.entity.PersonIdentity;
import com.smartTriage.smartTriage_server.module.patient.repository.PersonIdentityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the SHARED cross-hospital {@link PersonIdentity} from two co-equal anchors — national ID
 * and RFID card UID (V95). Matching on each key is deterministic-exact (a wrong probabilistic merge
 * of two people would be a patient-safety incident). An identity may be anchored by either or both.
 *
 * <p>Resolve-or-merge rules (both keys normalized; blanks → null):
 * <ul>
 *   <li>both null → no identity (caller keeps the patient purely local — unchanged behaviour).</li>
 *   <li>neither key resolves → create a new identity anchored by whatever key(s) are present.</li>
 *   <li>exactly one resolves, the other newly provided and unused → ATTACH it (a card issued to a
 *       known person; or a national ID added to a card-anchored placeholder = identification).</li>
 *   <li>both resolve to the SAME identity → return it.</li>
 *   <li>both resolve to DIFFERENT identities, OR a different value is already on file for the
 *       resolved identity → {@link IdentityConflictException} (reject, never auto-merge / overwrite —
 *       reassignment must be an explicit, audited action).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonIdentityService {

    private final PersonIdentityRepository personIdentityRepository;

    /**
     * National-ID-only convenience overload (no card anchor). Used by callers that never carry a
     * card — consent capture, placeholder-identity resolution. Delegates to the two-key resolver.
     */
    @Transactional
    public PersonIdentity findOrCreate(String nationalId) {
        return findOrCreate(nationalId, null);
    }

    /**
     * Resolve (and if needed create / extend) the shared identity for the given anchors.
     * Returns null when neither anchor is present. See class javadoc for the full rule set.
     */
    @Transactional
    public PersonIdentity findOrCreate(String nationalId, String rfidCardId) {
        String nid = normalize(nationalId);
        String card = normalize(rfidCardId);
        if (nid == null && card == null) {
            return null; // no anchor — patient stays purely local
        }

        PersonIdentity byNid = nid == null ? null
                : personIdentityRepository.findByNationalIdAndIsActiveTrue(nid).orElse(null);
        PersonIdentity byCard = card == null ? null
                : personIdentityRepository.findByRfidCardIdAndIsActiveTrue(card).orElse(null);

        // Both anchors resolve.
        if (byNid != null && byCard != null) {
            if (!byNid.getId().equals(byCard.getId())) {
                throw new IdentityConflictException(
                        "National ID and RFID card already belong to different patients — reconcile manually.");
            }
            return byNid; // same identity, both keys already set
        }

        // Only the national-ID anchor resolves: attach the card if a new, unused one was provided.
        if (byNid != null) {
            return attachIfProvided(byNid, byNid.getRfidCardId(), card,
                    byNid::setRfidCardId,
                    "Patient already has a different RFID card on file — reassignment requires an explicit action.");
        }

        // Only the card anchor resolves: attach the national ID if a new, unused one was provided
        // (the common "unidentified placeholder is now identified" upgrade).
        if (byCard != null) {
            return attachIfProvided(byCard, byCard.getNationalId(), nid,
                    byCard::setNationalId,
                    "RFID card is already linked to a patient with a different national ID — reconcile manually.");
        }

        // Neither resolves — create a fresh identity anchored by whatever is present.
        return createOrReread(nid, card);
    }

    /** Attach a newly-provided key to an existing identity, or reject a conflicting different value. */
    private PersonIdentity attachIfProvided(PersonIdentity identity, String existing, String provided,
                                            java.util.function.Consumer<String> setter, String conflictMessage) {
        if (provided == null || provided.equals(existing)) {
            return identity; // nothing new to attach (or already equal)
        }
        if (existing != null) {
            throw new IdentityConflictException(conflictMessage);
        }
        setter.accept(provided);
        try {
            return personIdentityRepository.saveAndFlush(identity);
        } catch (DataIntegrityViolationException race) {
            // The key was taken by another transaction between our read and write.
            throw new IdentityConflictException(
                    "That identifier was just assigned to another patient — please retry.");
        }
    }

    private PersonIdentity createOrReread(String nid, String card) {
        try {
            return personIdentityRepository.saveAndFlush(
                    PersonIdentity.builder().nationalId(nid).rfidCardId(card).build());
        } catch (DataIntegrityViolationException race) {
            // Another transaction created one of these anchors concurrently — adopt the existing row.
            PersonIdentity existing = null;
            if (nid != null) {
                existing = personIdentityRepository.findByNationalIdAndIsActiveTrue(nid).orElse(null);
            }
            if (existing == null && card != null) {
                existing = personIdentityRepository.findByRfidCardIdAndIsActiveTrue(card).orElse(null);
            }
            if (existing == null) {
                throw race;
            }
            return existing;
        }
    }

    private static String normalize(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
