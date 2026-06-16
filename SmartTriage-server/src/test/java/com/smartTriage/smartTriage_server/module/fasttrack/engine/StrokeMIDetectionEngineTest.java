package com.smartTriage.smartTriage_server.module.fasttrack.engine;

import com.smartTriage.smartTriage_server.common.enums.AvpuScore;
import com.smartTriage.smartTriage_server.common.enums.FastTrackType;
import com.smartTriage.smartTriage_server.module.fasttrack.engine.StrokeMIDetectionEngine.FastTrackRecommendation;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Evidence for the fast-track detection engine: stroke vs TIA, the dropped MI
 * chest-pain hard gate (atypical ACS in diabetics/elderly now fires), NSTEMI
 * pre-ECG labelling, pediatric caveat, and no over-firing on a lone risk factor.
 */
class StrokeMIDetectionEngineTest {

    private final StrokeMIDetectionEngine engine = new StrokeMIDetectionEngine();

    private Visit visit(String complaint, boolean pediatric, Integer ageYears, String chronic) {
        Visit v = new Visit();
        v.setVisitNumber("V-FT");
        v.setChiefComplaint(complaint);
        v.setPediatric(pediatric);
        Patient.PatientBuilder pb = Patient.builder().firstName("A").lastName("B");
        if (ageYears != null) pb.dateOfBirth(LocalDate.now().minusYears(ageYears));
        if (chronic != null) pb.chronicConditions(chronic);
        v.setPatient(pb.build());
        return v;
    }

    // ── Stroke ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Focal neurologic deficit → STROKE_SUSPECTED with confidence >= 0.7")
    void strokeFocalDeficit() {
        TriageRecord t = TriageRecord.builder().vuFocalNeurologicDeficit(true).avpu(AvpuScore.ALERT).build();
        FastTrackRecommendation r = engine.screenForStroke(visit(null, false, 60, null), t);
        assertEquals(FastTrackType.STROKE_SUSPECTED, r.type());
        assertTrue(r.confidence() >= 0.7);
    }

    @Test
    @DisplayName("Single soft indicator (AVPU not alert) → TIA_SUSPECTED (count < 3, no focal deficit)")
    void strokeSingleIndicatorIsTia() {
        TriageRecord t = TriageRecord.builder().avpu(AvpuScore.PAIN).build();
        FastTrackRecommendation r = engine.screenForStroke(visit(null, false, 60, null), t);
        assertEquals(FastTrackType.TIA_SUSPECTED, r.type());
    }

    // ── MI / ACS ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Typical chest pain → NSTEMI_SUSPECTED pre-ECG (NOT STEMI)")
    void miTypicalChestPainIsNstemiPreEcg() {
        TriageRecord t = TriageRecord.builder().vuChestPain(true).avpu(AvpuScore.ALERT).build();
        FastTrackRecommendation r = engine.screenForMI(visit(null, false, 55, null), t);
        assertEquals(FastTrackType.NSTEMI_SUSPECTED, r.type());
    }

    @Test
    @DisplayName("ATYPICAL ACS: diabetic + SOB, NO chest pain → STILL fires (the dropped-hard-gate fix)")
    void miAtypicalNoChestPainStillFires() {
        // Previously returned null because of the chest-pain hard gate — exactly
        // the diabetic/elderly population that presents without chest pain.
        TriageRecord t = TriageRecord.builder().vuShortnessOfBreath(true).avpu(AvpuScore.ALERT).build();
        FastTrackRecommendation r = engine.screenForMI(visit(null, false, 62, "Type 2 diabetes"), t);
        assertEquals(FastTrackType.NSTEMI_SUSPECTED, r.type());
        assertTrue(r.reasoning().contains("ATYPICAL"));
    }

    @Test
    @DisplayName("Lone risk factor (age + HTN, no symptoms) → does NOT fire (no over-screening)")
    void miLoneRiskFactorDoesNotFire() {
        TriageRecord t = TriageRecord.builder().avpu(AvpuScore.ALERT).build();
        FastTrackRecommendation r = engine.screenForMI(visit(null, false, 70, "hypertension"), t);
        assertNull(r);
    }

    @Test
    @DisplayName("Pediatric MI: caveat attached and the adult age>40 heuristic is not applied")
    void miPediatricCaveat() {
        TriageRecord t = TriageRecord.builder().vuChestPain(true).avpu(AvpuScore.ALERT).build();
        FastTrackRecommendation r = engine.screenForMI(visit(null, true, 10, null), t);
        assertEquals(FastTrackType.NSTEMI_SUSPECTED, r.type());
        assertTrue(r.findings().stream().anyMatch(f -> f.contains("PEDIATRIC")));
    }

    @Test
    @DisplayName("No indicators → no recommendation (both engines return null)")
    void noIndicators() {
        TriageRecord t = TriageRecord.builder().avpu(AvpuScore.ALERT).build();
        Visit v = visit(null, false, 30, null);
        assertNull(engine.screenForStroke(v, t));
        assertNull(engine.screenForMI(v, t));
    }

    @Test
    @DisplayName("Coma alone (no focal deficit) → STROKE_SUSPECTED, never TIA (hard finding forces stroke)")
    void comaAloneIsStroke() {
        TriageRecord t = TriageRecord.builder().hasComa(true).avpu(AvpuScore.ALERT).build();
        FastTrackRecommendation r = engine.screenForStroke(visit(null, false, 60, null), t);
        assertEquals(FastTrackType.STROKE_SUSPECTED, r.type());
    }

    @Test
    @DisplayName("Isolated new dyspnea (anginal equivalent), no documented risk factor → STILL fires NSTEMI/ACS")
    void isolatedDyspneaFiresMi() {
        TriageRecord t = TriageRecord.builder().vuShortnessOfBreath(true).avpu(AvpuScore.ALERT).build();
        FastTrackRecommendation r = engine.screenForMI(visit(null, false, 30, null), t);
        assertEquals(FastTrackType.NSTEMI_SUSPECTED, r.type());
    }

    @Test
    @DisplayName("Null visit/triage → null (engine robust to any caller, not just recommend())")
    void nullArgsReturnNull() {
        TriageRecord t = TriageRecord.builder().avpu(AvpuScore.ALERT).build();
        assertNull(engine.screenForStroke(null, t));
        assertNull(engine.screenForMI(visit(null, false, 40, null), null));
    }
}
