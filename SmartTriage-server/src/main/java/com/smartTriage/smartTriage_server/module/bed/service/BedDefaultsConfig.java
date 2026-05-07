package com.smartTriage.smartTriage_server.module.bed.service;

import com.smartTriage.smartTriage_server.common.enums.EdZone;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tier-keyed default bed inventory for newly-created hospitals.
 *
 * <p>Rwanda MoH / Minisante facility tiers (Public Health Facilities Service
 * Packages framework):
 * <ul>
 *   <li><b>District Hospital</b> — 1 per district (~30 nationally), catchment
 *       ~300k, 150–250 total beds. ED runs 50–150 encounters/day. Limited
 *       specialty mix.</li>
 *   <li><b>Provincial / Referral Hospital</b> — 1 per province (5
 *       nationally: Ruhengeri, Butare, Kibungo, Kibuye,
 *       Gisenyi/Nyagatare). 250–450 total beds. Mid-range specialties.</li>
 *   <li><b>National Referral / Teaching Hospital</b> — KFH (Kigali
 *       University Teaching), CHUK (CHU de Kigali), Rwanda Military
 *       Hospital. 500–1000+ total beds. Full specialty range, trauma
 *       centers.</li>
 * </ul>
 *
 * <p>The National-Teaching numbers match the V18 migration baseline, which
 * was explicitly written against KFH/CHUK/Rwanda Military Hospital. District
 * and Provincial tiers are scaled down from there based on their typical ED
 * footprint.
 *
 * <p>All seeded beds carry {@code hasMonitor=true}, mirroring V18. The
 * GENERAL and TRIAGE zones are intentionally <em>not</em> seeded — GENERAL
 * is a flat-list ambulatory zone (no bed concept) and TRIAGE is a flow zone
 * for the nurse station. This matches the V18 design.
 *
 * <p>Tier-string normalization is case-insensitive and accepts the common
 * variants seen in seed data and Hospital-create payloads. Anything
 * unrecognised (or {@code null}) falls back to <b>District</b> as the
 * smallest safe defaults — better to under-provision than to seed zero
 * beds and have the bed-suggestion engine return null on every triage.
 */
public final class BedDefaultsConfig {

    private BedDefaultsConfig() {}

    /** A single seed entry: a zone + how many beds + monitor expectation. */
    public record ZoneDefault(EdZone zone, int count, boolean hasMonitor) {}

    /** Canonical tier key — what we normalise the free-text Hospital.tier to. */
    public enum Tier {
        DISTRICT, PROVINCIAL_REFERRAL, NATIONAL_TEACHING
    }

    /**
     * Tier → ordered list of zone seeds. Order matters for deterministic
     * displayOrder assignment (R1 before R2, A1 before A2, …).
     *
     * <p>Per-zone counts approved 2026-05-06:
     * <pre>
     * Tier                  RESUS  ACUTE  PEDIATRIC  ISOLATION  OBSERVATION  Total
     * District               1      4      2          1          3            11
     * Provincial / Referral  2      6      3          2          5            18
     * National / Teaching    3      8      4          2          6            23  (= V18 baseline)
     * </pre>
     */
    private static final Map<Tier, List<ZoneDefault>> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put(Tier.DISTRICT, List.of(
                new ZoneDefault(EdZone.RESUS,        1, true),
                new ZoneDefault(EdZone.ACUTE,        4, true),
                new ZoneDefault(EdZone.PEDIATRIC,    2, true),
                new ZoneDefault(EdZone.ISOLATION,    1, true),
                new ZoneDefault(EdZone.OBSERVATION,  3, true)
        ));
        DEFAULTS.put(Tier.PROVINCIAL_REFERRAL, List.of(
                new ZoneDefault(EdZone.RESUS,        2, true),
                new ZoneDefault(EdZone.ACUTE,        6, true),
                new ZoneDefault(EdZone.PEDIATRIC,    3, true),
                new ZoneDefault(EdZone.ISOLATION,    2, true),
                new ZoneDefault(EdZone.OBSERVATION,  5, true)
        ));
        DEFAULTS.put(Tier.NATIONAL_TEACHING, List.of(
                new ZoneDefault(EdZone.RESUS,        3, true),
                new ZoneDefault(EdZone.ACUTE,        8, true),
                new ZoneDefault(EdZone.PEDIATRIC,    4, true),
                new ZoneDefault(EdZone.ISOLATION,    2, true),
                new ZoneDefault(EdZone.OBSERVATION,  6, true)
        ));
    }

    /**
     * Resolve the seed list for a hospital's tier string. Case-insensitive.
     * Unknown / null tier falls back to {@link Tier#DISTRICT} — the smallest
     * default. Admins can always run the backfill endpoint to top up after
     * tier is set correctly.
     */
    public static List<ZoneDefault> defaultsForTier(String tierString) {
        return DEFAULTS.get(normalise(tierString));
    }

    /**
     * Normalise a free-text tier label to the canonical enum. The Hospital
     * entity stores tier as a {@code String}, so we accept the common
     * spellings teams use in seed data and create payloads.
     */
    public static Tier normalise(String tierString) {
        if (tierString == null) return Tier.DISTRICT;
        String t = tierString.trim().toLowerCase();
        if (t.isEmpty()) return Tier.DISTRICT;

        // National / Teaching first — "national-referral" contains "referral",
        // so we have to disambiguate before the provincial branch.
        if (t.contains("national") || t.contains("teaching") || t.contains("tertiary")) {
            return Tier.NATIONAL_TEACHING;
        }
        if (t.contains("provincial") || t.contains("regional") || t.contains("referral")) {
            return Tier.PROVINCIAL_REFERRAL;
        }
        if (t.contains("district")) {
            return Tier.DISTRICT;
        }
        return Tier.DISTRICT; // Unknown → safest under-provisioning.
    }

    /**
     * Standard human-readable labels used when generating bed labels at
     * seed-time (e.g. "Resus Bay 1", "Acute Bed 3"). Matches V18 phrasing.
     */
    public static String labelPrefixFor(EdZone zone) {
        return switch (zone) {
            case RESUS       -> "Resus Bay";
            case ACUTE       -> "Acute Bed";
            case PEDIATRIC   -> "Pediatric Bed";
            case ISOLATION   -> "Isolation Room";
            case OBSERVATION -> "Observation Bed";
            // Defensive — none of these are seeded, but if the table ever
            // grows, give them sensible labels rather than throwing.
            case GENERAL     -> "General Bed";
            case TRIAGE      -> "Triage Bay";
        };
    }

    /**
     * Standard short-code prefix per zone, mirroring V18 (R1/R2, A1/A2, …).
     * Used to construct unique-per-hospital bed codes.
     */
    public static String codePrefixFor(EdZone zone) {
        return switch (zone) {
            case RESUS       -> "R";
            case ACUTE       -> "A";
            case PEDIATRIC   -> "P";
            case ISOLATION   -> "I";
            case OBSERVATION -> "O";
            case GENERAL     -> "G";
            case TRIAGE      -> "T";
        };
    }
}
