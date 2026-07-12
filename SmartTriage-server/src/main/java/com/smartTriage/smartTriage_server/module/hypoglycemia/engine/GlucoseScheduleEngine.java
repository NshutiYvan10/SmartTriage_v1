package com.smartTriage.smartTriage_server.module.hypoglycemia.engine;

import com.smartTriage.smartTriage_server.common.enums.AvpuScore;
import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * GlucoseScheduleEngine — decides HOW OFTEN a patient's blood glucose must be
 * measured. Glucose is the one vital the bedside monitor cannot capture: it is
 * entered by nursing staff, so without a schedule the system only ever REACTS
 * to readings that happen to arrive and can never notice a missing one.
 *
 * Tiers (shortest applicable interval wins) — RATIFIED as clinical policy by
 * the product owner (2026-07-12):
 * <ol>
 *   <li><b>Unresolved hypoglycemia event</b> — NOT handled here: the mandatory
 *       15-minute recheck clock ({@code HypoglycemiaRecheckMonitorService})
 *       stays authoritative, and this engine must never double-page it.</li>
 *   <li><b>Post-hypoglycemia observation</b> — event resolved &lt; 4 h ago →
 *       q1h (recurrence risk is highest in the first hours after correction).</li>
 *   <li><b>Insulin infusion</b> — active CONTINUOUS insulin order → q1h
 *       (universal infusion / DKA protocol standard).</li>
 *   <li><b>Other insulin exposure</b> — any other live insulin order this
 *       visit → q4h (ADA inpatient POC cadence, ED-adapted).</li>
 *   <li><b>Critically ill</b> — current triage RED, or a mandatory
 *       glucose-check trigger on the latest triage (AVPU≠alert, convulsions,
 *       coma, altered mental status) → q4h.</li>
 *   <li><b>Known diabetic</b> (no insulin) → q6h.</li>
 *   <li><b>Everyone else — deliberately NO schedule.</b> Routine ED patients
 *       do not get serial fingersticks; a blanket schedule breeds alarm
 *       fatigue and needless painful tests. Do not add a catch-all tier
 *       without clinical sign-off.</li>
 * </ol>
 *
 * Escalation grace (due → overdue): 15 min for q1h tiers, 30 min otherwise —
 * fast tiers escalate fast. All values are properties so a hospital can tune
 * them without a release.
 */
@Component
public class GlucoseScheduleEngine {

    /** One measurement-frequency tier. {@code interval} = time between readings;
     *  {@code grace} = how long past due before the zone doctor + charge nurse are paged. */
    public record Tier(String key, String label, Duration interval, Duration grace) {}

    @Value("${smarttriage.glucose.schedule.post-hypo-minutes:60}")
    private long postHypoMinutes;
    @Value("${smarttriage.glucose.schedule.post-hypo-window-hours:4}")
    private long postHypoWindowHours;
    @Value("${smarttriage.glucose.schedule.infusion-minutes:60}")
    private long infusionMinutes;
    @Value("${smarttriage.glucose.schedule.insulin-minutes:240}")
    private long insulinMinutes;
    @Value("${smarttriage.glucose.schedule.critical-minutes:240}")
    private long criticalMinutes;
    @Value("${smarttriage.glucose.schedule.diabetic-minutes:360}")
    private long diabeticMinutes;
    @Value("${smarttriage.glucose.schedule.fast-grace-minutes:15}")
    private long fastGraceMinutes;
    @Value("${smarttriage.glucose.schedule.grace-minutes:30}")
    private long graceMinutes;

    /** How long after an event resolves the q1h observation tier applies. */
    public Duration postHypoWindow() {
        return Duration.ofHours(postHypoWindowHours);
    }

    /**
     * Pick the measurement tier for a patient's CURRENT state, or {@code null}
     * when no serial glucose is clinically indicated (tier 7). Inputs are
     * derived live by the caller on every evaluation — a patient moves between
     * tiers mid-visit (insulin started, re-triaged RED, event resolved).
     */
    public Tier determineTier(boolean recentHypoResolved,
                              boolean insulinInfusion,
                              boolean otherInsulin,
                              boolean criticallyIll,
                              boolean knownDiabetic) {
        if (recentHypoResolved) return tier("POST_HYPO", "Post-hypoglycemia observation", postHypoMinutes);
        if (insulinInfusion)    return tier("INSULIN_INFUSION", "Insulin infusion", infusionMinutes);
        if (otherInsulin)       return tier("INSULIN", "On insulin", insulinMinutes);
        if (criticallyIll)      return tier("CRITICAL", "Critically ill", criticalMinutes);
        if (knownDiabetic)      return tier("DIABETIC", "Known diabetic", diabeticMinutes);
        return null;
    }

    /**
     * "Critically ill" for glucose-surveillance purposes: current triage RED, or
     * the latest triage carries a mandatory glucose-check trigger. The trigger
     * set MUST stay in sync with
     * {@link HypoglycemiaEnforcementEngine#enforceGlucoseCheck} (AVPU≠alert,
     * convulsions, coma, altered mental status).
     */
    public boolean isCriticallyIll(Visit visit, TriageRecord latestTriage) {
        if (visit != null && visit.getCurrentTriageCategory() == TriageCategory.RED) return true;
        if (latestTriage == null) return false;
        return (latestTriage.getAvpu() != null && latestTriage.getAvpu() != AvpuScore.ALERT)
                || latestTriage.isHasConvulsions()
                || latestTriage.isHasComa()
                || latestTriage.isVuAlteredMentalStatus();
    }

    private Tier tier(String key, String label, long minutes) {
        Duration interval = Duration.ofMinutes(minutes);
        Duration grace = Duration.ofMinutes(minutes <= 60 ? fastGraceMinutes : graceMinutes);
        return new Tier(key, label, interval, grace);
    }
}
