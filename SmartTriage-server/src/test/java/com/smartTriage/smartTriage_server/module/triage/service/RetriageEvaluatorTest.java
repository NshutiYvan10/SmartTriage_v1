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
    // Round 5 policy: AutoBump (was Suggest). The system guarantees
    // the floor; manual re-triage pushes higher if vitals warrant it.

    @Test
    void msatVu_belowOrange_autoBumpsToOrange() {
        RetriageDecision d = RetriageEvaluator.evaluate(
                ClinicalSignCategory.MSAT_VU,
                ClinicalSignStatus.PRESENT,
                false, false, TriageCategory.YELLOW, LBL);
        AutoBump bump = assertInstanceOf(AutoBump.class, d);
        assertEquals(TriageCategory.ORANGE, bump.targetCategory());
        assertTrue(bump.reason().contains(LBL));
    }

    @Test
    void msatVu_belowOrangeFromGreen_autoBumpsToOrange() {
        RetriageDecision d = RetriageEvaluator.evaluate(
                ClinicalSignCategory.MSAT_VU,
                ClinicalSignStatus.WORSENING,
                false, false, TriageCategory.GREEN, LBL);
        AutoBump bump = assertInstanceOf(AutoBump.class, d);
        assertEquals(TriageCategory.ORANGE, bump.targetCategory());
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
    // Round 5 policy: AutoBump (was Suggest).

    @Test
    void msatUrg_belowYellow_autoBumpsToYellow() {
        RetriageDecision d = RetriageEvaluator.evaluate(
                ClinicalSignCategory.MSAT_URG,
                ClinicalSignStatus.PRESENT,
                false, false, TriageCategory.GREEN, LBL);
        AutoBump bump = assertInstanceOf(AutoBump.class, d);
        assertEquals(TriageCategory.YELLOW, bump.targetCategory());
    }

    @Test
    void msatUrg_alreadyYellow_noAction() {
        RetriageDecision d = RetriageEvaluator.evaluate(
                ClinicalSignCategory.MSAT_URG,
                ClinicalSignStatus.PRESENT,
                false, false, TriageCategory.YELLOW, LBL);
        assertInstanceOf(NoAction.class, d);
    }

    @Test
    void msatUrg_alreadyOrange_noAction() {
        RetriageDecision d = RetriageEvaluator.evaluate(
                ClinicalSignCategory.MSAT_URG,
                ClinicalSignStatus.PRESENT,
                false, false, TriageCategory.ORANGE, LBL);
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

    // ────────────────────────────────────────────────────────────────
    // Round 4b — down-trajectory (IMPROVING / ABSENT) suggestions
    // ────────────────────────────────────────────────────────────────

    @Test
    void downBump_emergencyPresentToAbsentAtRed_suggestsMedium() {
        RetriageDecision d = RetriageEvaluator.evaluate(
                ClinicalSignCategory.EMERGENCY,
                ClinicalSignStatus.ABSENT,
                /* previous */ ClinicalSignStatus.PRESENT,
                false, false, TriageCategory.RED, LBL);
        Suggest s = assertInstanceOf(Suggest.class, d);
        assertEquals(AlertSeverity.MEDIUM, s.severity());
        assertTrue(s.message().toLowerCase().contains("resolved"));
    }

    @Test
    void downBump_msatVuWorseningToImprovingAtOrange_suggestsMedium() {
        RetriageDecision d = RetriageEvaluator.evaluate(
                ClinicalSignCategory.MSAT_VU,
                ClinicalSignStatus.IMPROVING,
                ClinicalSignStatus.WORSENING,
                false, false, TriageCategory.ORANGE, LBL);
        Suggest s = assertInstanceOf(Suggest.class, d);
        assertEquals(AlertSeverity.MEDIUM, s.severity());
        assertTrue(s.message().toLowerCase().contains("improving"));
    }

    @Test
    void downBump_msatUrgPresentToAbsentAtYellow_suggestsMedium() {
        RetriageDecision d = RetriageEvaluator.evaluate(
                ClinicalSignCategory.MSAT_URG,
                ClinicalSignStatus.ABSENT,
                ClinicalSignStatus.PRESENT,
                false, false, TriageCategory.YELLOW, LBL);
        assertInstanceOf(Suggest.class, d);
    }

    @Test
    void downBump_belowFloor_noAction() {
        // Visit is already at GREEN; the sign moving to ABSENT is fine
        // but doesn't warrant a re-triage suggestion since the sign
        // wasn't keeping the patient at a higher acuity.
        RetriageDecision d = RetriageEvaluator.evaluate(
                ClinicalSignCategory.MSAT_VU,
                ClinicalSignStatus.ABSENT,
                ClinicalSignStatus.PRESENT,
                false, false, TriageCategory.GREEN, LBL);
        assertInstanceOf(NoAction.class, d);
    }

    @Test
    void downBump_noPreviousStatus_noAction() {
        // First-ever observation of this sign at ABSENT is meaningless
        // for down-bump; the sign was never previously a driver.
        RetriageDecision d = RetriageEvaluator.evaluate(
                ClinicalSignCategory.EMERGENCY,
                ClinicalSignStatus.ABSENT,
                /* previous */ null,
                false, false, TriageCategory.RED, LBL);
        assertInstanceOf(NoAction.class, d);
    }

    @Test
    void downBump_previouslyAbsent_noAction() {
        // ABSENT → ABSENT (or UNKNOWN → ABSENT) is not a transition
        // worth surfacing; the sign was never driving.
        RetriageDecision d = RetriageEvaluator.evaluate(
                ClinicalSignCategory.EMERGENCY,
                ClinicalSignStatus.ABSENT,
                ClinicalSignStatus.ABSENT,
                false, false, TriageCategory.RED, LBL);
        assertInstanceOf(NoAction.class, d);
    }

    @Test
    void downBump_pedsEmergencyOnAdult_noAction() {
        // Defensive: peds-only sign on an adult visit shouldn't suggest
        // a down-bump either, mirroring the up-branch defensive return.
        RetriageDecision d = RetriageEvaluator.evaluate(
                ClinicalSignCategory.PEDIATRIC_EMERGENCY,
                ClinicalSignStatus.ABSENT,
                ClinicalSignStatus.PRESENT,
                false, /* peds */ false, TriageCategory.RED, LBL);
        assertInstanceOf(NoAction.class, d);
    }

    @Test
    void downBump_specialCategory_noAction() {
        RetriageDecision d = RetriageEvaluator.evaluate(
                ClinicalSignCategory.SPECIAL,
                ClinicalSignStatus.ABSENT,
                ClinicalSignStatus.PRESENT,
                false, false, TriageCategory.RED, LBL);
        assertInstanceOf(NoAction.class, d);
    }

    @Test
    void downBump_baselineEvent_noAction() {
        // Baseline events never trigger anything, including down-bumps.
        RetriageDecision d = RetriageEvaluator.evaluate(
                ClinicalSignCategory.MSAT_VU,
                ClinicalSignStatus.IMPROVING,
                ClinicalSignStatus.PRESENT,
                /* baseline */ true,
                false, TriageCategory.ORANGE, LBL);
        assertInstanceOf(NoAction.class, d);
    }

    // Backwards-compat overload (unchanged Round 3 signature) still works
    @Test
    void backwardsCompatOverload_stillRoutesUpBumps() {
        RetriageDecision d = RetriageEvaluator.evaluate(
                ClinicalSignCategory.EMERGENCY,
                ClinicalSignStatus.PRESENT,
                false, false, TriageCategory.YELLOW, LBL);
        assertInstanceOf(AutoBump.class, d);
    }
}
