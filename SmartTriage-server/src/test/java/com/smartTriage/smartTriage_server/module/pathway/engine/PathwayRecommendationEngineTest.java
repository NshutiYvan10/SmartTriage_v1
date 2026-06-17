package com.smartTriage.smartTriage_server.module.pathway.engine;

import com.smartTriage.smartTriage_server.module.pathway.dto.PathwayRecommendation;
import com.smartTriage.smartTriage_server.module.pathway.entity.ClinicalPathway;
import com.smartTriage.smartTriage_server.module.pathway.repository.ClinicalPathwayRepository;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Recommendation-engine evidence — the trigger-accuracy fixes from the pathways
 * audit: malaria is severity-gated (bare "fever" is only a low-confidence screen,
 * not a HIGH severe-malaria activation); ACS/sepsis triggers steer to the dedicated
 * Fast Track / Sepsis tools; obstetric no longer false-fires on "laboratory"; a
 * free-text head-injury recommends even without a triage trauma flag; null triage is safe.
 */
class PathwayRecommendationEngineTest {

    private ClinicalPathwayRepository pathwayRepository;
    private PathwayRecommendationEngine engine;

    @BeforeEach
    void setUp() {
        pathwayRepository = mock(ClinicalPathwayRepository.class);
        // Any referenced code resolves to a pathway so addRecommendation emits it.
        when(pathwayRepository.findByPathwayCodeAndIsActiveTrue(anyString())).thenAnswer(inv -> {
            String code = inv.getArgument(0);
            ClinicalPathway p = new ClinicalPathway();
            p.setId(UUID.randomUUID());
            p.setPathwayCode(code);
            p.setPathwayName(code);
            return Optional.of(p);
        });
        engine = new PathwayRecommendationEngine(pathwayRepository);
    }

    private Visit visit(String chiefComplaint) {
        Visit v = new Visit();
        v.setId(UUID.randomUUID());
        v.setVisitNumber("V-1");
        v.setChiefComplaint(chiefComplaint);
        return v;
    }

    private PathwayRecommendation rec(List<PathwayRecommendation> recs, String code) {
        return recs.stream().filter(r -> code.equals(r.getPathwayCode())).findFirst().orElse(null);
    }

    @Test
    @DisplayName("Bare 'fever' → MAL-SEV only as a low-confidence screen (MEDIUM), not a HIGH severe-malaria activation")
    void bareFeverIsLowConfidenceMalaria() {
        List<PathwayRecommendation> recs = engine.recommendPathways(visit("high fever and chills"), null);
        PathwayRecommendation mal = rec(recs, "MAL-SEV");
        assertTrue(mal != null, "fever should still prompt a malaria screen");
        assertEquals("MEDIUM", mal.getUrgency());
        assertTrue(mal.getConfidence() < 0.6, "bare fever must be low-confidence");
    }

    @Test
    @DisplayName("Malaria-specific signal ('malaria') → HIGH severe-malaria pathway")
    void malariaKeywordIsHigh() {
        PathwayRecommendation mal = rec(engine.recommendPathways(visit("known malaria, rigors"), null), "MAL-SEV");
        assertTrue(mal != null);
        assertEquals("HIGH", mal.getUrgency());
    }

    @Test
    @DisplayName("Chest pain (vu sign) → CARD-ACS steers to the Fast Track tool")
    void chestPainSteersToFastTrack() {
        TriageRecord t = new TriageRecord();
        t.setVuChestPain(true);
        PathwayRecommendation acs = rec(engine.recommendPathways(visit("chest pain"), t), "CARD-ACS");
        assertTrue(acs != null);
        assertTrue(acs.getReason().toUpperCase().contains("FAST TRACK"), "must steer to Fast Track");
    }

    @Test
    @DisplayName("'sepsis' → INF-SEPSIS steers to the dedicated Sepsis tool")
    void sepsisSteersToSepsisTool() {
        PathwayRecommendation sep = rec(engine.recommendPathways(visit("query sepsis"), null), "INF-SEPSIS");
        assertTrue(sep != null);
        assertTrue(sep.getReason().toUpperCase().contains("SEPSIS"));
    }

    @Test
    @DisplayName("'laboratory results' does NOT false-fire the obstetric pathway (dropped 'labor' substring)")
    void laboratoryDoesNotTriggerObstetric() {
        List<PathwayRecommendation> recs = engine.recommendPathways(visit("review laboratory results"), null);
        assertFalse(recs.stream().anyMatch(r -> "OBS-EMERG".equals(r.getPathwayCode())));
    }

    @Test
    @DisplayName("Free-text head injury (no trauma flag) still recommends TRA-HEAD")
    void freeTextHeadInjuryRecommendsTrauma() {
        PathwayRecommendation head = rec(engine.recommendPathways(visit("motorcycle accident, head injury"), null), "TRA-HEAD");
        assertTrue(head != null, "a free-text head injury must recommend the head-trauma pathway even without a trauma flag");
    }

    @Test
    @DisplayName("Null triage is handled safely (no NPE)")
    void nullTriageSafe() {
        List<PathwayRecommendation> recs = engine.recommendPathways(visit("abdominal pain"), null);
        assertTrue(recs != null);
    }
}
