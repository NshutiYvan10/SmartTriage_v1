package com.smartTriage.smartTriage_server.module.medsafety.engine;

import com.smartTriage.smartTriage_server.common.enums.PregnancyStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Pins the server-side teratogen knowledge base. A clinical rules table is exactly
 * the kind of thing that must never silently drift, so the load-bearing
 * classifications + the state-resolution logic are locked here.
 */
class TeratogenRulesTest {

    // ── state resolution ──

    @Test
    @DisplayName("PREGNANT / POSSIBLY_PREGNANT resolve to the pregnant state; explicit-negatives suppress")
    void stateResolution() {
        assertEquals(TeratogenRules.State.PREGNANT, TeratogenRules.resolveState(PregnancyStatus.PREGNANT, null));
        assertEquals(TeratogenRules.State.PREGNANT, TeratogenRules.resolveState(PregnancyStatus.POSSIBLY_PREGNANT, null));
        assertEquals(TeratogenRules.State.BREASTFEEDING, TeratogenRules.resolveState(PregnancyStatus.BREASTFEEDING, null));
        assertNull(TeratogenRules.resolveState(PregnancyStatus.NOT_PREGNANT, "20 weeks pregnant"));
        assertNull(TeratogenRules.resolveState(PregnancyStatus.NOT_APPLICABLE, null));
        assertTrue(TeratogenRules.isExplicitlySuppressed(PregnancyStatus.NOT_PREGNANT));
        assertTrue(TeratogenRules.isExplicitlySuppressed(PregnancyStatus.NOT_APPLICABLE));
        assertFalse(TeratogenRules.isExplicitlySuppressed(PregnancyStatus.PREGNANT));
    }

    @Test
    @DisplayName("UNKNOWN / null fall back to a free-text scan, with negation guards")
    void freeTextFallback() {
        assertEquals(TeratogenRules.State.PREGNANT, TeratogenRules.resolveState(PregnancyStatus.UNKNOWN, "G2P1, 30/40"));
        assertEquals(TeratogenRules.State.PREGNANT, TeratogenRules.resolveState(null, "patient is 20 weeks pregnant"));
        assertEquals(TeratogenRules.State.BREASTFEEDING, TeratogenRules.resolveState(null, "currently lactating"));
        assertNull(TeratogenRules.resolveState(null, "pregnancy test negative"));
        assertNull(TeratogenRules.resolveState(null, "not pregnant"));
        assertNull(TeratogenRules.resolveState(null, "hypertension, diabetes")); // no signal
    }

    // ── drug classification ──

    @Test
    @DisplayName("Category X absolutes block; a name rule works with no formulary entry")
    void categoryXBlocks() {
        var f = TeratogenRules.classify("Warfarin", TeratogenRules.State.PREGNANT);
        assertNotNull(f);
        assertEquals(TeratogenRules.Category.X, f.category());
        assertTrue(f.isBlocking());

        assertEquals(TeratogenRules.Category.X, TeratogenRules.classify("methotrexate", TeratogenRules.State.PREGNANT).category());
        assertEquals(TeratogenRules.Category.X, TeratogenRules.classify("valproate", TeratogenRules.State.PREGNANT).category());
    }

    @Test
    @DisplayName("ACE inhibitors are D (blocking); NSAIDs are D-late (non-blocking by default)")
    void categoryDAndDLate() {
        var ace = TeratogenRules.classify("lisinopril", TeratogenRules.State.PREGNANT);
        assertEquals(TeratogenRules.Category.D, ace.category());
        assertTrue(ace.isBlocking());

        var nsaid = TeratogenRules.classify("ibuprofen", TeratogenRules.State.PREGNANT);
        assertEquals(TeratogenRules.Category.D_LATE, nsaid.category());
        assertFalse(nsaid.isBlocking()); // trimester-gated escalation happens in the engine
    }

    @Test
    @DisplayName("A non-teratogen returns no finding; a non-lactation drug is silent while breastfeeding")
    void quietCases() {
        assertNull(TeratogenRules.classify("amoxicillin", TeratogenRules.State.PREGNANT));
        // warfarin is not flagged for lactation → silent when only breastfeeding
        assertNull(TeratogenRules.classify("warfarin", TeratogenRules.State.BREASTFEEDING));
        // methotrexate IS flagged for lactation → fires
        assertNotNull(TeratogenRules.classify("methotrexate", TeratogenRules.State.BREASTFEEDING));
    }

    @Test
    @DisplayName("Formulary category maps X/D to gate categories; A/B/C are not gated")
    void formularyBackstop() {
        assertEquals(TeratogenRules.Category.X, TeratogenRules.fromFormularyCategory("X"));
        assertEquals(TeratogenRules.Category.D, TeratogenRules.fromFormularyCategory("d"));
        assertNull(TeratogenRules.fromFormularyCategory("C"));
        assertNull(TeratogenRules.fromFormularyCategory("A"));
        assertNull(TeratogenRules.fromFormularyCategory(null));
    }
}
