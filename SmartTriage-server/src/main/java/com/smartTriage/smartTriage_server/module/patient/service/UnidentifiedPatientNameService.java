package com.smartTriage.smartTriage_server.module.patient.service;

import com.smartTriage.smartTriage_server.module.patient.repository.UnidentifiedPatientCounterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Generates per-hospital, per-day NATO-phonetic placeholder names for
 * unidentified patients (Direct Resus Admission, V28).
 *
 * <p>Why phonetic? Because in a noisy resus bay a doctor calling for
 * "Charlie's chart" cannot be confused with "Bravo's chart". Numeric
 * placeholders like "Patient 47" and "Patient 48" sound similar at a
 * distance — and that's a clinical-safety hazard. Phonetic alphabet
 * is the standard for life-or-death verbal communication in ATLS,
 * military, and aviation.
 *
 * <p>Why daily reset? Charts file per day. Tomorrow's first unidentified
 * patient is Alpha again. Visit IDs (UUIDs) are still globally unique,
 * so no record collision occurs.
 *
 * <p>What about >26 in one day? Mass-casualty events on Rwandan roads
 * are real. After Zulu the service yields "Alpha-2", "Bravo-2", ...
 * still phonetically distinct from "Alpha"/"Bravo".
 *
 * <p>Adult and pediatric admissions share the counter — the "(child)"
 * marker comes from {@code Visit.isPediatric} at display time, not
 * from the placeholder. So an adult Alpha and a pediatric Alpha cannot
 * co-exist on the same day, which is exactly the disambiguation we want.
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
     * Atomically claim the next placeholder label for the hospital today.
     * The returned label is the short form ("Alpha", "Bravo-2"). The
     * full display name ("Unknown Alpha (child)") is composed at the
     * presentation layer, since the (child) marker depends on the
     * visit's isPediatric flag, not the patient.
     *
     * <p>Must run inside the caller's transaction.
     */
    public PlaceholderLabel claimNext(UUID hospitalId) {
        LocalDate today = LocalDate.now();
        int index = counterRepository.claimNextIndex(hospitalId, today);

        int letterIndex = index % NATO_PHONETIC.size();
        int cycle = index / NATO_PHONETIC.size();   // 0 first time round, 1 second, ...

        String label = cycle == 0
                ? NATO_PHONETIC.get(letterIndex)
                : NATO_PHONETIC.get(letterIndex) + "-" + (cycle + 1);

        log.info("[unidentified] Claimed placeholder '{}' (index {}) for hospital {} on {}",
                label, index, hospitalId, today);

        return new PlaceholderLabel(label, index);
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
