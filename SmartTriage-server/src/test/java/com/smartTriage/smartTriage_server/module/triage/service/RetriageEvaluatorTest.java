package com.smartTriage.smartTriage_server.module.triage.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.module.clinicalsigns.entity.ClinicalSignCategory;
import com.smartTriage.smartTriage_server.module.clinicalsigns.entity.ClinicalSignStatus;
import com.smartTriage.smartTriage_server.module.triage.service.RetriageEvaluator.AutoBump;
import com.smartTriage.smartTriage_server.module.triage.service.RetriageEvaluator.NoAction;
import com.smartTriage.smartTriage_server.module.triage.service.RetriageEvaluator.RetriageDecision;
import com.smartTriage.smartTriage_server.module.triage.service.RetriageEvaluator.Suggest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Table-driven unit test for {@link RetriageEvaluator}. Each test case
 * mirrors a row of the decision table documented in the Round 3 plan;
 * keep this in sync when the rules change.
 */
class RetriageEvaluatorTest {

    private static final String LBL = "Cardiac arrest";

    // ── EMERGENCY rows ────────────────────────────────────────────

    @Test
    void emergencyPresent_belowRed_autoBumpsToRed() {
        RetriageDecision d = RetriageEvaluator.evaluate(
                ClinicalSignCategory.EMERGENCY,
                ClinicalSignStatus.PRESENT,
                /* baseline */ false,
                /* peds */ false,
                TriageCategory.YELLOW,
                LBL);
        AutoBump bump = assertInstanceOf(AutoBump.class, d);
        assertEquals(TriageCategory.RED, bump.targetCategory());
        assertTrue(bump.reason().contains(LBL));
    }

    @Test
    void emergencyWorsening_belowRed_autoBumpsToRed() {
        RetriageDecision d = RetriageEvaluator.evaluate(
                ClinicalSignCategory.EMERGENCY,
                ClinicalSignStatus.WORSENING,
                false, false, TriageCategory.GREEN, LBL);
        assertInstanceOf(AutoBump.class, d);
    }

    @Test
    void emergencyPresent_alreadyRed_noAction() {
        RetriageDecision d = RetriageEvaluator.evaluate(
                ClinicalSignCategory.EMERGENCY,
                ClinicalSignStatus.PRESENT,
                false, false, TriageCategory.RED, LBL);
        assertInstanceOf(NoAction.class, d);
    }

    // ── PEDIATRIC_EMERGENCY rows ──────────────────────────────────

    @Test
    void pedsEmergency_pediatricVisit_autoBumpsToRed() {
        RetriageDecision d = RetriageEvaluator.evaluate(
                ClinicalSignCategory.PEDIATRIC_EMERGENCY,
                ClinicalSignStatus.PRESENT,
                false, /* peds */ true, TriageCategory.YELLOW, LBL);
        AutoBump bump = assertInstanceOf(AutoBump.class, d);
        assertEquals(TriageCategory.RED, bump.targetCategory());
    }

    @Test
    void pedsEmergency_adultVisit_noAction() {
        // Defensive: a peds-only sign on an adult visit shouldn't escalate.
        RetriageDecision d = RetriageEvaluator.evaluate(
                ClinicalSignCategory.PEDIATRIC_EMERGENCY,
                ClinicalSignStatus.PRESENT,
                false, /* peds */ false, TriageCategory.GREEN, LBL);
        assertInstanceOf(NoAction.class, d);
    }

    @Test
    void pedsEmergency_pediatricVisitAlreadyRed_noAction() {
        RetriageDecision d = RetriageEvaluator.evaluate(
                ClinicalSignCategory.PEDIATRIC_EMERGENCY,
                ClinicalSignStatus.WORSENING,
                false, true, TriageCategory.RED, LBL);
        assertInstanceOf(NoAction.class, d);
    }

    // ── MSAT_VU rows ──────────────────────────────────────────────

    @Test
    void msatVu_belowOrange_suggestsHigh() {
        RetriageDecision d = RetriageEvaluator.evaluate(
                ClinicalSignCategory.MSAT_VU,
                ClinicalSignStatus.PRESENT,
                false, false, TriageCategory.YELLOW, LBL);
        Suggest s = assertInstanceOf(Suggest.class, d);
        assertEquals(AlertSeverity.HIGH, s.severity());
        assertTrue(s.message().contains(LBL));
    }

    @Test
    void msatVu_alreadyOrange_noAction() {
        RetriageDecision d = RetriageEvaluator.evaluate(
                ClinicalSignCategory.MSAT_VU,
                ClinicalSignStatus.PRESENT,
                false, false, TriageCategory.ORANGE, LBL);
        assertInstanceOf(NoAction.class, d);
    }

    @Test
    void msatVu_alreadyRed_noAction() {
        RetriageDecision d = RetriageEvaluator.evaluate(
                ClinicalSignCategory.MSAT_VU,
                ClinicalSignStatus.WORSENING,
                false, false, TriageCategory.RED, LBL);
        assertInstanceOf(NoAction.class, d);
    }

    // ── MSAT_URG rows ─────────────────────────────────────────────

    @Test
    void msatUrg_belowYellow_suggestsHigh() {
        RetriageDecision d = RetriageEvaluator.evaluate(
                ClinicalSignCategory.MSAT_URG,
                ClinicalSignStatus.PRESENT,
                false, false, TriageCategory.GREEN, LBL);
        assertInstanceOf(Suggest.class, d);
    }

    @Test
    void msatUrg_alreadyYellow_noAction() {
        RetriageDecision d = RetriageEvaluator.evaluate(
                ClinicalSignCategory.MSAT_URG,
                ClinicalSignStatus.PRESENT,
                false, false, TriageCategory.YELLOW, LBL);
        assertInstanceOf(NoAction.class, d);
    }

    // ── Down-trajectory + UNKNOWN rows ────────────────────────────

    @Test
    void improvingStatus_neverActions() {
        for (ClinicalSignCategory cat : ClinicalSignCategory.values()) {
            RetriageDecision d = RetriageEvaluator.evaluate(
                    cat, ClinicalSignStatus.IMPROVING,
                    false, true, TriageCategory.GREEN, LBL);
            assertInstanceOf(NoAction.class, d, "category " + cat + " should NoAction on IMPROVING");
        }
    }

    @Test
    void absentStatus_neverActions() {
        for (ClinicalSignCategory cat : ClinicalSignCategory.values()) {
            RetriageDecision d = RetriageEvaluator.evaluate(
                    cat, ClinicalSignStatus.ABSENT,
                    false, true, TriageCategory.GREEN, LBL);
            assertInstanceOf(NoAction.class, d, "category " + cat + " should NoAction on ABSENT");
        }
    }

    @Test
    void unknownStatus_neverActions() {
        for (ClinicalSignCategory cat : ClinicalSignCategory.values()) {
            RetriageDecision d = RetriageEvaluator.evaluate(
                    cat, ClinicalSignStatus.UNKNOWN,
                    false, true, TriageCategory.GREEN, LBL);
            assertInstanceOf(NoAction.class, d, "category " + cat + " should NoAction on UNKNOWN");
        }
    }

    // ── Baseline events never trigger ─────────────────────────────

    @Test
    void baselineEvent_neverActions() {
        // Even an EMERGENCY PRESENT baseline event shouldn't trigger,
        // because the triage form already saw it at form-fill time.
        RetriageDecision d = RetriageEvaluator.evaluate(
                ClinicalSignCategory.EMERGENCY,
                ClinicalSignStatus.PRESENT,
                /* baseline */ true,
                false, TriageCategory.GREEN, LBL);
        assertInstanceOf(NoAction.class, d);
    }

    // ── SPECIAL category never triggers ───────────────────────────

    @Test
    void specialCategory_neverActions() {
        RetriageDecision d = RetriageEvaluator.evaluate(
                ClinicalSignCategory.SPECIAL,
                ClinicalSignStatus.PRESENT,
                false, false, TriageCategory.GREEN, LBL);
        assertInstanceOf(NoAction.class, d);
    }

    // ── Null current category (not yet triaged edge case) ──────────

    @Test
    void emergencyWithNullCurrentCategory_autoBumps() {
        // currentCategory==null is treated as lowest severity, so any
        // EMERGENCY presence should still bump.
        RetriageDecision d = RetriageEvaluator.evaluate(
                ClinicalSignCategory.EMERGENCY,
                ClinicalSignStatus.PRESENT,
                false, false, /* not yet triaged */ null, LBL);
        assertInstanceOf(AutoBump.class, d);
    }
}
