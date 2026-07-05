package com.smartTriage.smartTriage_server.module.triage.engine;

import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.module.triage.dto.PerformTriageRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for the adult Rwanda triage decision flowchart — previously untested despite being
 * the core adult acuity path. Covers the national flowchart (emergency → RED; TEWS ≥ 7 or
 * SpO2 < 92 → RED; TEWS 5–6 → ORANGE; VU → ORANGE; TEWS 3–4 → YELLOW; urgent → YELLOW; else
 * GREEN) AND the additional-signs channel (extra emergency/VU/urgent signs from the richer UI
 * drive the colour so no captured sign is inert).
 */
class RwandaTriageDecisionEngineTest {

    private final RwandaTriageDecisionEngine engine = new RwandaTriageDecisionEngine();

    private static PerformTriageRequest.PerformTriageRequestBuilder base() {
        return PerformTriageRequest.builder();
    }

    // ── National flowchart ──────────────────────────────────────────────

    @Test
    void nationalEmergencySign_forcesRed_evenAtZeroTews() {
        var r = base().hasCardiacArrest(true).build();
        assertEquals(TriageCategory.RED, engine.decide(0, 99, 0, r).category());
    }

    @Test
    void tewsSevenOrMore_isRed() {
        assertEquals(TriageCategory.RED, engine.decide(7, 99, 0, base().build()).category());
    }

    @Test
    void spo2Below92_isRed() {
        assertEquals(TriageCategory.RED, engine.decide(2, 90, 0, base().build()).category());
    }

    @Test
    void tewsFiveToSix_isOrange() {
        assertEquals(TriageCategory.ORANGE, engine.decide(5, 99, 0, base().build()).category());
    }

    @Test
    void focalNeuroDeficit_isOrange_notRed_atLowTews() {
        // The national form places focal neurologic deficit at VERY URGENT (ORANGE), not RED —
        // this is the mis-level the rebuild corrects (old UI treated it as RED).
        var r = base().vuFocalNeurologicDeficit(true).build();
        assertEquals(TriageCategory.ORANGE, engine.decide(2, 99, 0, r).category());
    }

    @Test
    void tewsThreeToFour_noSigns_isYellow() {
        assertEquals(TriageCategory.YELLOW, engine.decide(3, 99, 0, base().build()).category());
    }

    @Test
    void urgentSign_isYellow_atLowTews() {
        var r = base().urgVeryPale(true).build();
        assertEquals(TriageCategory.YELLOW, engine.decide(1, 99, 0, r).category());
    }

    @Test
    void noSigns_lowTews_isGreen() {
        assertEquals(TriageCategory.GREEN, engine.decide(1, 99, 0, base().build()).category());
    }

    // ── Additional-signs channel (richer UI, nothing inert) ─────────────

    @Test
    void additionalEmergencySign_forcesRed() {
        var r = base().additionalEmergencySigns(List.of("Tracheal deviation")).build();
        assertEquals(TriageCategory.RED, engine.decide(0, 99, 0, r).category());
    }

    @Test
    void additionalVeryUrgentSign_isOrange_atLowTews() {
        var r = base().additionalVeryUrgentSigns(List.of("Suspected pneumothorax")).build();
        assertEquals(TriageCategory.ORANGE, engine.decide(2, 99, 0, r).category());
    }

    @Test
    void additionalUrgentSign_isYellow_atLowTews() {
        var r = base().additionalUrgentSigns(List.of("Uncontrolled epistaxis")).build();
        assertEquals(TriageCategory.YELLOW, engine.decide(1, 99, 0, r).category());
    }

    @Test
    void emptyAdditionalLists_doNotAffectCategory() {
        var r = base().additionalEmergencySigns(List.of())
                .additionalVeryUrgentSigns(List.of())
                .additionalUrgentSigns(List.of())
                .build();
        assertEquals(TriageCategory.GREEN, engine.decide(1, 99, 0, r).category());
    }
}
