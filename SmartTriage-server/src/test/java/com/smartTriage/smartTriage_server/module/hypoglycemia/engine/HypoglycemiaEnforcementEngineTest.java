package com.smartTriage.smartTriage_server.module.hypoglycemia.engine;

import com.smartTriage.smartTriage_server.common.enums.AvpuScore;
import com.smartTriage.smartTriage_server.common.enums.HypoglycemiaSeverity;
import com.smartTriage.smartTriage_server.module.hypoglycemia.engine.HypoglycemiaEnforcementEngine.HypoglycemiaCheckResult;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static com.smartTriage.smartTriage_server.common.enums.HypoglycemiaSeverity.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protocol-math evidence for hypoglycemia classification. Each band boundary is
 * asserted by hand against ADA/WHO mmol/L cut-offs (NORMAL ≥3.9, MILD <3.9,
 * MODERATE <3.0, SEVERE <2.2 or neuroglycopenic) plus the neonatal band (<2.6),
 * neuroglycopenia escalation, and null handling.
 */
class HypoglycemiaEnforcementEngineTest {

    private final HypoglycemiaEnforcementEngine engine = new HypoglycemiaEnforcementEngine();

    private Visit adult() {
        Visit v = new Visit();
        v.setVisitNumber("V-HG");
        v.setPatient(Patient.builder().firstName("A").lastName("B")
                .dateOfBirth(LocalDate.now().minusYears(40)).build());
        return v;
    }

    private Visit neonate() {
        Visit v = new Visit();
        v.setVisitNumber("V-NEO");
        v.setPediatric(true);
        v.setPatient(Patient.builder().firstName("Baby").lastName("X")
                .dateOfBirth(LocalDate.now().minusDays(5)).build());
        return v;
    }

    // ── Adult/child band boundaries (no neuroglycopenia) ──
    @Test
    @DisplayName("Adult band boundaries: 2.1→SEVERE, 2.2→MODERATE, 2.9→MODERATE, 3.0→MILD, 3.8→MILD, 3.9→NORMAL")
    void adultBands() {
        assertEquals(SEVERE,   engine.classify(2.1, false, false));
        assertEquals(MODERATE, engine.classify(2.2, false, false));
        assertEquals(MODERATE, engine.classify(2.9, false, false));
        assertEquals(MILD,     engine.classify(3.0, false, false));
        assertEquals(MILD,     engine.classify(3.8, false, false));
        assertEquals(NORMAL,   engine.classify(3.9, false, false));
        assertEquals(NORMAL,   engine.classify(6.0, false, false));
    }

    @Test
    @DisplayName("Neuroglycopenia escalates a clinically-significant low to SEVERE (2.5 + altered → SEVERE)")
    void neuroglycopeniaEscalates() {
        assertEquals(MODERATE, engine.classify(2.5, false, false));
        assertEquals(SEVERE,   engine.classify(2.5, false, true));
        // ...but a borderline-mild value is not forced to severe by symptoms alone.
        assertEquals(MILD,     engine.classify(3.5, false, true));
    }

    @Test
    @DisplayName("Neonatal band uses <2.6 (NORMAL ≥2.6; <2.0 or symptomatic → SEVERE)")
    void neonatalBands() {
        assertEquals(NORMAL,   engine.classify(2.6, true, false));
        assertEquals(MODERATE, engine.classify(2.5, true, false));
        assertEquals(SEVERE,   engine.classify(1.9, true, false));
        assertEquals(SEVERE,   engine.classify(2.4, true, true));
        // A glucose that would be NORMAL for an adult (3.0) is normal for a neonate too.
        assertEquals(NORMAL,   engine.classify(3.0, true, false));
    }

    @Test
    @DisplayName("Null glucose → PENDING_CHECK")
    void nullGlucose() {
        assertEquals(PENDING_CHECK, engine.classify(null, false, false));
        assertEquals(PENDING_CHECK, engine.classify(null, true, true));
    }

    // ── Triage enforcement path ──
    @Test
    @DisplayName("Triage with altered consciousness + glucose 2.0 → mandatory check, SEVERE, treatment protocol present")
    void triageSevere() {
        TriageRecord t = TriageRecord.builder().avpu(AvpuScore.PAIN).bloodGlucose(2.0).build();
        HypoglycemiaCheckResult r = engine.enforceGlucoseCheck(adult(), t);
        assertTrue(r.checkMandatory());
        assertEquals(SEVERE, r.severity());   // 2.0 < 2.2 AND neuroglycopenic
        assertTrue(r.isHypoglycemic());
        assertNotNull(r.treatmentProtocol());
    }

    @Test
    @DisplayName("Known diabetic with NO glucose on file → requiresCheck true, severity PENDING_CHECK (so it is surfaced)")
    void diabeticPendingCheck() {
        Visit v = adult();
        v.getPatient().setChronicConditions("Type 2 diabetes");
        TriageRecord t = TriageRecord.builder().avpu(AvpuScore.ALERT).build(); // no glucose
        HypoglycemiaCheckResult r = engine.enforceGlucoseCheck(v, t);
        assertTrue(r.requiresCheck());
        assertEquals(PENDING_CHECK, r.severity());
    }

    @Test
    @DisplayName("interpret() classifies a live reading from any source (3.2 → MILD, hypoglycemic)")
    void interpretLiveReading() {
        HypoglycemiaCheckResult r = engine.interpret(adult(), 3.2, false, true, true,
                java.util.List.of("manual_vitals"));
        assertEquals(MILD, r.severity());
        assertTrue(r.isHypoglycemic());
    }

    @Test
    @DisplayName("Neonate enforcement: glucose 2.4 → SEVERE-banded (and flagged neonatal)")
    void neonateSevereFlagged() {
        TriageRecord t = TriageRecord.builder().avpu(AvpuScore.ALERT).bloodGlucose(2.4).build();
        HypoglycemiaCheckResult r = engine.enforceGlucoseCheck(neonate(), t);
        assertTrue(r.neonatal());
        // 2.4 in a neonate is < 2.6 (treat) and ≥ 2.0 with no neuro → MODERATE.
        assertEquals(MODERATE, r.severity());
    }
}
