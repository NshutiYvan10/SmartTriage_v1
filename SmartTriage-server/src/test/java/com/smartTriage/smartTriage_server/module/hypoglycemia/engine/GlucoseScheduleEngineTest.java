package com.smartTriage.smartTriage_server.module.hypoglycemia.engine;

import com.smartTriage.smartTriage_server.common.enums.AvpuScore;
import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the RATIFIED glucose measurement protocol (2026-07-12): the tier table,
 * shortest-interval-wins priority, the proportionate escalation grace, and the
 * critically-ill definition. A measurement cadence is clinical policy — it must
 * never silently drift with a refactor.
 */
class GlucoseScheduleEngineTest {

    private GlucoseScheduleEngine engine;

    @BeforeEach
    void setUp() {
        engine = new GlucoseScheduleEngine();
        // Property defaults (the engine is instantiated outside Spring here).
        ReflectionTestUtils.setField(engine, "postHypoMinutes", 60L);
        ReflectionTestUtils.setField(engine, "postHypoWindowHours", 4L);
        ReflectionTestUtils.setField(engine, "infusionMinutes", 60L);
        ReflectionTestUtils.setField(engine, "insulinMinutes", 240L);
        ReflectionTestUtils.setField(engine, "criticalMinutes", 240L);
        ReflectionTestUtils.setField(engine, "diabeticMinutes", 360L);
        ReflectionTestUtils.setField(engine, "fastGraceMinutes", 15L);
        ReflectionTestUtils.setField(engine, "graceMinutes", 30L);
    }

    @Test
    @DisplayName("Tier table: post-hypo/infusion q1h, insulin/critical q4h, diabetic q6h — shortest applicable wins")
    void tierTable() {
        var postHypo = engine.determineTier(true, true, true, true, true);
        assertEquals("POST_HYPO", postHypo.key());
        assertEquals(Duration.ofMinutes(60), postHypo.interval());

        var infusion = engine.determineTier(false, true, true, true, true);
        assertEquals("INSULIN_INFUSION", infusion.key());
        assertEquals(Duration.ofMinutes(60), infusion.interval());

        var insulin = engine.determineTier(false, false, true, true, true);
        assertEquals("INSULIN", insulin.key());
        assertEquals(Duration.ofMinutes(240), insulin.interval());

        var critical = engine.determineTier(false, false, false, true, true);
        assertEquals("CRITICAL", critical.key());
        assertEquals(Duration.ofMinutes(240), critical.interval());

        var diabetic = engine.determineTier(false, false, false, false, true);
        assertEquals("DIABETIC", diabetic.key());
        assertEquals(Duration.ofMinutes(360), diabetic.interval());
    }

    @Test
    @DisplayName("Tier 7 is deliberately NO schedule — routine patients get no serial fingersticks")
    void routinePatientsUnscheduled() {
        assertNull(engine.determineTier(false, false, false, false, false));
    }

    @Test
    @DisplayName("Escalation grace is proportionate: 15 min for q1h tiers, 30 min for slower tiers")
    void graceProportionate() {
        assertEquals(Duration.ofMinutes(15), engine.determineTier(true, false, false, false, false).grace());
        assertEquals(Duration.ofMinutes(15), engine.determineTier(false, true, false, false, false).grace());
        assertEquals(Duration.ofMinutes(30), engine.determineTier(false, false, true, false, false).grace());
        assertEquals(Duration.ofMinutes(30), engine.determineTier(false, false, false, false, true).grace());
    }

    @Test
    @DisplayName("Critically ill = triage RED, or a mandatory glucose-check trigger on the latest triage")
    void criticallyIllDefinition() {
        Visit red = Visit.builder().currentTriageCategory(TriageCategory.RED).build();
        assertTrue(engine.isCriticallyIll(red, null));

        Visit green = Visit.builder().currentTriageCategory(TriageCategory.GREEN).build();
        assertFalse(engine.isCriticallyIll(green, null));

        TriageRecord avpuPain = new TriageRecord();
        avpuPain.setAvpu(AvpuScore.PAIN);
        assertTrue(engine.isCriticallyIll(green, avpuPain));

        TriageRecord convulsing = new TriageRecord();
        convulsing.setHasConvulsions(true);
        assertTrue(engine.isCriticallyIll(green, convulsing));

        TriageRecord alertCalm = new TriageRecord();
        alertCalm.setAvpu(AvpuScore.ALERT);
        assertFalse(engine.isCriticallyIll(green, alertCalm));
        assertFalse(engine.isCriticallyIll(null, null));
    }

    @Test
    @DisplayName("Known-diabetic detection is shared with the enforcement engine (free-text conditions)")
    void diabeticDetectionShared() {
        Patient diabetic = new Patient();
        diabetic.setChronicConditions("Type 2 diabetes, hypertension");
        assertTrue(HypoglycemiaEnforcementEngine.isKnownDiabetic(diabetic));

        Patient htnOnly = new Patient();
        htnOnly.setChronicConditions("hypertension");
        assertFalse(HypoglycemiaEnforcementEngine.isKnownDiabetic(htnOnly));
        assertFalse(HypoglycemiaEnforcementEngine.isKnownDiabetic(null));
    }
}
