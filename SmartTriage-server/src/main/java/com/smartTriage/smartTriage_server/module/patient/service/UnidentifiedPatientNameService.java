package com.smartTriage.smartTriage_server.module.patient.service;

import com.smartTriage.smartTriage_server.module.patient.repository.UnidentifiedPatientCounterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Generates NATO-phonetic placeholder names for unidentified patients
 * (Direct Resus Admission, V28; EMS unknown arrivals).
 *
 * <p>Why phonetic? Because in a noisy resus bay a doctor calling for
 * "Charlie's chart" cannot be confused with "Bravo's chart". Numeric
 * placeholders like "Patient 47" and "Patient 48" sound similar at a
 * distance — and that's a clinical-safety hazard. Phonetic alphabet
 * is the standard for life-or-death verbal communication in ATLS,
 * military, and aviation.
 *
 * <p>Why "lowest free among active" (NOT a daily reset)? The label must be
 * unique among the unidentified patients who are IN THE DEPARTMENT RIGHT NOW.
 * A daily counter reset broke this: an unidentified patient who lingered past
 * midnight kept "Unknown Alpha" while today's first arrival ALSO became
 * "Alpha" — two live Alphas, the exact confusion phonetic names prevent. So we
 * assign the lowest phonetic label not currently held by an active
 * unidentified patient, and reuse a name only once its holder is identified or
 * discharged. Visit IDs (UUIDs) remain globally unique regardless.
 *
 * <p>What about >26 active at once? Mass-casualty events on Rwandan roads are
 * real. Once Alpha..Zulu are all in use the service yields "Alpha-2",
 * "Bravo-2", ... still phonetically distinct — bounded by the number
 * SIMULTANEOUSLY active, so names stay short in normal operation.
 *
 * <p>Adult and pediatric admissions share the label pool — the "(child)"
 * marker comes from {@code Visit.isPediatric} at display time, not from the
 * placeholder. So an adult Alpha and a pediatric Alpha cannot co-exist, which
 * is exactly the disambiguation we want.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnidentifiedPatientNameService {

    private final UnidentifiedPatientCounterRepository counterRepository;

    /**
     * NATO/ICAO phonetic alphabet, in order. Index 0 → Alpha, 25 → Zulu.
     */
    private static final List<String> NATO_PHONETIC = List.of(
            "Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel",
            "India", "Juliet", "Kilo", "Lima", "Mike", "November", "Oscar", "Papa",
            "Quebec", "Romeo", "Sierra", "Tango", "Uniform", "Victor", "Whiskey",
            "X-Ray", "Yankee", "Zulu"
    );

    /**
     * Claim the lowest phonetic placeholder label NOT currently held by an
     * active unidentified patient at this hospital ("Alpha", then "Bravo", …,
     * "Zulu", "Alpha-2", …). The full display name ("Unknown Alpha (child)")
     * is composed at the presentation layer, since the (child) marker depends
     * on the visit's isPediatric flag, not the patient.
     *
     * <p>The repository takes a per-hospital advisory lock for the caller's
     * transaction before reading the live set, so two simultaneous unidentified
     * admissions can't both claim the same free label. Must run inside the
     * caller's transaction (both callers are {@code @Transactional}).
     */
    public PlaceholderLabel claimNext(UUID hospitalId) {
        Set<String> inUse = counterRepository.lockActivePlaceholderLabels(hospitalId);

        int index = 0;
        String label = labelForIndex(index);
        while (inUse.contains(label)) {
            index++;
            label = labelForIndex(index);
        }

        log.info("[unidentified] Claimed placeholder '{}' (index {}, {} already active) for hospital {}",
                label, index, inUse.size(), hospitalId);

        return new PlaceholderLabel(label, index);
    }

    /** Phonetic label for a 0-based slot: 0→Alpha … 25→Zulu, 26→Alpha-2, … */
    private static String labelForIndex(int index) {
        int letterIndex = index % NATO_PHONETIC.size();
        int cycle = index / NATO_PHONETIC.size();
        return cycle == 0
                ? NATO_PHONETIC.get(letterIndex)
                : NATO_PHONETIC.get(letterIndex) + "-" + (cycle + 1);
    }

    /**
     * Compose a human-readable display name from the raw placeholder label
     * and the visit's pediatric flag. Used by the frontend mapper layer
     * when the patient is unidentified.
     *
     * <pre>
     *   buildDisplayName("Alpha", false) → "Unknown Alpha"
     *   buildDisplayName("Alpha", true)  → "Unknown Alpha (child)"
     *   buildDisplayName("Bravo-2", true) → "Unknown Bravo-2 (child)"
     * </pre>
     */
    public static String buildDisplayName(String placeholderLabel, boolean isPediatric) {
        if (placeholderLabel == null || placeholderLabel.isBlank()) {
            return isPediatric ? "Unknown (child)" : "Unknown";
        }
        return isPediatric
                ? "Unknown " + placeholderLabel + " (child)"
                : "Unknown " + placeholderLabel;
    }

    /**
     * Bundle of the short label and the raw counter index that produced
     * it. The index is included for audit/debug visibility — it lets
     * a chart reviewer reconstruct ordering without parsing the label.
     */
    public record PlaceholderLabel(String label, int index) {}
}
