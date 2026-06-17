package com.smartTriage.smartTriage_server.module.isolation.engine;

import com.smartTriage.smartTriage_server.common.enums.InfectionRiskLevel;
import com.smartTriage.smartTriage_server.common.enums.IsolationType;
import com.smartTriage.smartTriage_server.common.enums.NotifiableDisease;
import com.smartTriage.smartTriage_server.module.isolation.dto.InfectionScreeningRequest;
import com.smartTriage.smartTriage_server.module.isolation.engine.InfectionScreeningEngine.InfectionScreeningResult;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Engine evidence — the central SAFETY invariant: a screening block can only ever
 * RAISE the precaution, never lower it. The pre-rebuild engine let a later block
 * (meningococcal / measles / TB) silently downgrade a confirmed viral-hemorrhagic-fever
 * patient from STRICT — a staff-exposure hazard. These tests lock the never-downgrade
 * behaviour plus the key classifications, afebrile cholera, and PROTECTIVE isolation.
 */
class InfectionScreeningEngineTest {

    private final InfectionScreeningEngine engine = new InfectionScreeningEngine();

    private Visit visit() {
        Visit v = new Visit();
        v.setId(UUID.randomUUID());
        return v;
    }

    private TriageRecord triage(boolean purpuric) {
        TriageRecord t = new TriageRecord();
        t.setHasPurpuricRash(purpuric);
        return t;
    }

    private InfectionScreeningRequest.InfectionScreeningRequestBuilder req() {
        return InfectionScreeningRequest.builder();
    }

    @Test
    @DisplayName("CRITICAL: confirmed VHF (fever+bleeding+contact) WITH a purpuric rash stays STRICT/EBOLA — never downgraded to DROPLET/meningococcal")
    void vhfWithPurpuricRashStaysStrict() {
        InfectionScreeningResult r = engine.screenPatient(visit(), triage(true),
                req().hasFever(true).hasBleedingSymptoms(true).hasContactWithInfectious(true).build());

        assertEquals(IsolationType.STRICT, r.isolationType(), "VHF + purpuric rash must remain STRICT");
        assertEquals(InfectionRiskLevel.CONFIRMED, r.riskLevel());
        assertEquals(NotifiableDisease.EBOLA, r.notifiableDisease(), "must not be relabelled meningococcal");
    }

    @Test
    @DisplayName("CRITICAL: high-risk VHF (fever+bleeding) WITH fever+rash stays STRICT/EBOLA — never downgraded to AIRBORNE/measles")
    void vhfWithRashStaysStrict() {
        InfectionScreeningResult r = engine.screenPatient(visit(), triage(false),
                req().hasFever(true).hasBleedingSymptoms(true).hasRash(true).build());

        assertEquals(IsolationType.STRICT, r.isolationType());
        assertEquals(NotifiableDisease.EBOLA, r.notifiableDisease());
    }

    @Test
    @DisplayName("Overlapping measles + meningococcal (fever+rash+purpura) → strictest wins = AIRBORNE, not the last-evaluated DROPLET")
    void measlesAndMeningococcalTakesAirborne() {
        InfectionScreeningResult r = engine.screenPatient(visit(), triage(true),
                req().hasFever(true).hasRash(true).build());

        assertEquals(IsolationType.AIRBORNE, r.isolationType(), "AIRBORNE (measles) must beat DROPLET (meningococcal)");
        assertTrue(r.ppeRequirements().requiresN95, "airborne PPE retained");
    }

    @Test
    @DisplayName("Afebrile purpuric rash (no fever) still triggers meningococcal DROPLET/HIGH_RISK — NOT cleared")
    void afebrilePurpuricRashTriggersMeningococcal() {
        InfectionScreeningResult r = engine.screenPatient(visit(), triage(true), req().hasFever(false).build());

        assertEquals(IsolationType.DROPLET, r.isolationType(), "a non-blanching purpuric rash must not be cleared without fever");
        assertEquals(InfectionRiskLevel.HIGH_RISK, r.riskLevel());
        assertEquals(NotifiableDisease.MENINGOCOCCAL, r.notifiableDisease());
    }

    @Test
    @DisplayName("TB: chronic cough + constitutional symptom → HIGH_RISK AIRBORNE / TUBERCULOSIS")
    void tbAirborne() {
        InfectionScreeningResult r = engine.screenPatient(visit(), triage(false),
                req().hasCough(true).hasCoughDurationWeeks(3).hasNightSweats(true).build());

        assertEquals(IsolationType.AIRBORNE, r.isolationType());
        assertEquals(InfectionRiskLevel.HIGH_RISK, r.riskLevel());
        assertEquals(NotifiableDisease.TUBERCULOSIS, r.notifiableDisease());
    }

    @Test
    @DisplayName("Cholera is afebrile-aware: diarrhea + travel (no fever) flags CONTACT + CHOLERA")
    void choleraAfebrileWithTravel() {
        InfectionScreeningResult r = engine.screenPatient(visit(), triage(false),
                req().hasDiarrhea(true).hasRecentTravel(true).build());

        assertEquals(IsolationType.CONTACT, r.isolationType());
        assertEquals(NotifiableDisease.CHOLERA, r.notifiableDisease());
    }

    @Test
    @DisplayName("Plain diarrhea (no fever/travel/contact) → CONTACT precautions but NOT a cholera notification (avoids over-notifying)")
    void plainDiarrheaContactOnly() {
        InfectionScreeningResult r = engine.screenPatient(visit(), triage(false),
                req().hasDiarrhea(true).build());

        assertEquals(IsolationType.CONTACT, r.isolationType());
        assertNull(r.notifiableDisease());
    }

    @Test
    @DisplayName("Immunocompromised alone → PROTECTIVE (reverse) isolation is reachable")
    void immunocompromisedProtective() {
        InfectionScreeningResult r = engine.screenPatient(visit(), triage(false),
                req().immunocompromised(true).build());

        assertEquals(IsolationType.PROTECTIVE, r.isolationType());
    }

    @Test
    @DisplayName("Immunocompromised + TB → the infectious AIRBORNE precaution (protects others) wins the slot, not PROTECTIVE")
    void immunocompromisedPlusTbTakesAirborne() {
        InfectionScreeningResult r = engine.screenPatient(visit(), triage(false),
                req().immunocompromised(true).hasCough(true).hasCoughDurationWeeks(4).hasWeightLoss(true).build());

        assertEquals(IsolationType.AIRBORNE, r.isolationType());
    }

    @Test
    @DisplayName("No indicators → CLEARED, no isolation")
    void cleared() {
        InfectionScreeningResult r = engine.screenPatient(visit(), triage(false), req().build());
        assertEquals(InfectionRiskLevel.CLEARED, r.riskLevel());
        assertNull(r.isolationType());
    }

    @Test
    @DisplayName("strictest()/maxRisk() helpers rank correctly")
    void helpers() {
        assertEquals(IsolationType.STRICT, InfectionScreeningEngine.strictest(IsolationType.DROPLET, IsolationType.STRICT));
        assertEquals(IsolationType.AIRBORNE, InfectionScreeningEngine.strictest(IsolationType.AIRBORNE, IsolationType.CONTACT));
        assertEquals(IsolationType.CONTACT, InfectionScreeningEngine.strictest(null, IsolationType.CONTACT));
        assertEquals(InfectionRiskLevel.CONFIRMED,
                InfectionScreeningEngine.maxRisk(InfectionRiskLevel.LOW_RISK, InfectionRiskLevel.CONFIRMED));
        assertEquals(InfectionRiskLevel.HIGH_RISK,
                InfectionScreeningEngine.maxRisk(InfectionRiskLevel.HIGH_RISK, InfectionRiskLevel.MODERATE_RISK));
    }
}
