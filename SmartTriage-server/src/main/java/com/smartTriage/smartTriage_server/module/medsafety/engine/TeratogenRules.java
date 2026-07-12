package com.smartTriage.smartTriage_server.module.medsafety.engine;

import com.smartTriage.smartTriage_server.common.enums.PregnancyStatus;

import java.util.List;
import java.util.regex.Pattern;

/**
 * TeratogenRules — the AUTHORITATIVE, server-side pregnancy/lactation drug-risk
 * knowledge base. This is the Java counterpart of the frontend
 * {@code teratogenCheck.ts}: the same drug-class rules table and the same
 * pregnancy-state resolution, moved server-side so the teratogen check becomes a
 * real gate (re-derived on the prescribe path) rather than a client-only dialog
 * the server merely labels.
 *
 * <p>Two independent risk signals feed the gate:
 * <ol>
 *   <li><b>Drug-class name rules</b> (this table) — the clinically-specific list
 *       of ED-relevant teratogens with their category and the concern to show the
 *       prescriber. This is the primary signal because it carries the "why".</li>
 *   <li><b>Formulary {@code pregnancy_category}</b> (A–X) — consulted by the engine
 *       as a backstop for drugs not in this table but flagged X/D in the REML
 *       formulary.</li>
 * </ol>
 *
 * <p>Category semantics used by the gate:
 * <ul>
 *   <li>{@code X} — absolute contraindication (irreversible fetal harm) → BLOCKING.</li>
 *   <li>{@code D} — clear fetal risk → BLOCKING (override with documented reason).</li>
 *   <li>{@code D_LATE} — risk specific to late pregnancy (e.g. NSAIDs > 30 wk).
 *       Without reliable gestational age we surface it as a non-blocking WARNING,
 *       not a hard stop, to avoid false hard-blocks in the first trimester.</li>
 *   <li>{@code CAUTION} — well-evidenced concern, not formal D/X → WARNING.</li>
 * </ul>
 */
public final class TeratogenRules {

    private TeratogenRules() {}

    public enum Category { X, D, D_LATE, CAUTION }

    public enum State { PREGNANT, BREASTFEEDING }

    /** A resolved teratogen finding for a drug against a pregnancy state. */
    public record Finding(String drugClassLabel, Category category, State state,
                          String evidence, String concern) {
        /** Category X and D are hard blocks (override needs a documented reason);
         *  D-late and caution are non-blocking warnings. */
        public boolean isBlocking() {
            return category == Category.X || category == Category.D;
        }
    }

    private record Rule(List<String> keywords, String label, Category category,
                        String concern, boolean alsoBreastfeeding) {}

    // Mirrors teratogenCheck.ts TERATOGENS — keep the two in sync.
    private static final List<Rule> RULES = List.of(
            // ── Category X (absolute) ──
            new Rule(List.of("warfarin", "coumadin"), "warfarin", Category.X,
                    "Warfarin embryopathy (nasal hypoplasia, stippled epiphyses) and fetal CNS bleeding. Switch to LMWH.", false),
            new Rule(List.of("isotretinoin", "roaccutane", "accutane"), "isotretinoin", Category.X,
                    "Severe craniofacial, cardiac, and CNS malformations even at low doses. Absolutely contraindicated.", true),
            new Rule(List.of("methotrexate"), "methotrexate", Category.X,
                    "Folate antagonism — neural-tube defects, limb anomalies, embryotoxic. Avoid in pregnancy and lactation.", true),
            new Rule(List.of("misoprostol", "cytotec"), "misoprostol", Category.X,
                    "Uterotonic — pregnancy loss, Möbius sequence. Only used in pregnancy under defined obstetric protocols.", false),
            new Rule(List.of("thalidomide"), "thalidomide", Category.X,
                    "Phocomelia and limb-reduction defects from a single dose. Strict pregnancy-prevention protocol required.", false),
            new Rule(List.of("ribavirin"), "ribavirin", Category.X,
                    "Embryocidal and teratogenic at sub-therapeutic doses. Avoid.", false),
            new Rule(List.of("finasteride", "dutasteride"), "5α-reductase inhibitor", Category.X,
                    "Genital-development abnormalities in a male fetus.", false),
            new Rule(List.of("valproate", "valproic", "sodium valproate"), "valproate", Category.X,
                    "Highest teratogenicity of any anticonvulsant — neural-tube defects, dysmorphism, IQ loss.", false),
            new Rule(List.of("simvastatin", "atorvastatin", "rosuvastatin", "pravastatin", "lovastatin", "statin"),
                    "statin", Category.X,
                    "Teratogenicity from cholesterol-synthesis inhibition. Hold for the duration of pregnancy.", false),
            new Rule(List.of("leflunomide"), "leflunomide", Category.X,
                    "Animal teratogen with a very long half-life — washout protocol required.", false),

            // ── Category D (clear risk) ──
            new Rule(List.of("captopril", "enalapril", "lisinopril", "ramipril", "perindopril", "pril"),
                    "ACE inhibitor", Category.D,
                    "2nd/3rd-trimester fetopathy — oligohydramnios, renal dysgenesis, skull hypoplasia. Switch to labetalol/methyldopa.", false),
            new Rule(List.of("losartan", "valsartan", "irbesartan", "telmisartan", "candesartan", "sartan"),
                    "ARB", Category.D,
                    "Same fetopathy as ACE inhibitors — oligohydramnios, renal failure, neonatal hypotension.", false),
            new Rule(List.of("doxycycline", "tetracycline", "minocycline"), "tetracycline", Category.D,
                    "Fetal tooth discoloration and reduced bone growth after week 16. Use an alternative antibiotic.", true),
            new Rule(List.of("phenytoin"), "phenytoin", Category.D,
                    "Fetal hydantoin syndrome — craniofacial anomalies, growth restriction.", false),
            new Rule(List.of("carbamazepine", "tegretol"), "carbamazepine", Category.D,
                    "Neural-tube defects (~1%); supplement folate if continuation is required.", false),
            new Rule(List.of("lithium"), "lithium", Category.D,
                    "Ebstein anomaly (cardiac) — risk highest in 1st trimester. Discuss with psychiatry.", true),
            new Rule(List.of("fluconazole"), "fluconazole (high dose)", Category.D,
                    "High-dose chronic use — craniofacial, skeletal, cardiac defects. Single-dose 150 mg is considered safer.", false),

            // ── Category D-late (trimester-specific) ──
            new Rule(List.of("ibuprofen", "diclofenac", "naproxen", "indomethacin", "ketorolac",
                            "meloxicam", "celecoxib", "piroxicam", "mefenamic"), "NSAID", Category.D_LATE,
                    "Premature closure of the ductus arteriosus and oligohydramnios after 30 weeks. Avoid in the 3rd trimester.", false),

            // ── Caution ──
            new Rule(List.of("gentamicin", "amikacin", "tobramycin", "streptomycin"), "aminoglycoside", Category.CAUTION,
                    "Eighth-cranial-nerve toxicity — fetal sensorineural deafness reported. Use only when no alternative exists.", false),
            new Rule(List.of("trimethoprim", "cotrimoxazole", "co-trimoxazole", "septrin", "bactrim", "sulfamethoxazole"),
                    "co-trimoxazole / trimethoprim", Category.CAUTION,
                    "1st trimester: folate antagonism → neural-tube defects. 3rd trimester: neonatal kernicterus risk.", false),
            new Rule(List.of("chloramphenicol"), "chloramphenicol", Category.CAUTION,
                    "Grey-baby syndrome in a neonate exposed near delivery. Avoid in late pregnancy and lactation.", true)
    );

    private static final List<String> PREGNANCY_TOKENS = List.of(
            "pregnant", "pregnancy", "gestation", "gestational", "gravid", "trimester",
            "antenatal", "expecting", "in utero", "fetus", "foetus");
    private static final List<String> BREASTFEEDING_TOKENS = List.of(
            "breastfeeding", "breast-feeding", "breast feeding", "lactating", "lactation", "nursing mother");
    private static final List<String> NEGATIONS = List.of(
            "not pregnant", "no pregnancy", "pregnancy test negative", "pregnancy ruled out",
            "denies pregnancy", "pregnancy: no", "pregnancy - no");
    private static final Pattern GP_NOTATION = Pattern.compile("\\bg\\s*\\d+\\s*p\\s*\\d+", Pattern.CASE_INSENSITIVE);

    /**
     * Resolve the effective pregnancy/lactation state for the gate.
     *
     * @return the resolved {@link State}, or {@code null} when the check should stay
     *         quiet. {@code suppress} distinguishes "explicitly ruled out" (never scan
     *         free text) from "no signal"; callers pass {@code freeText == null} to skip
     *         the fallback.
     */
    public static State resolveState(PregnancyStatus status, String freeText) {
        if (status != null) {
            switch (status) {
                case PREGNANT, POSSIBLY_PREGNANT -> { return State.PREGNANT; }
                case BREASTFEEDING -> { return State.BREASTFEEDING; }
                case NOT_PREGNANT, NOT_APPLICABLE -> { return null; } // explicitly suppressed
                case UNKNOWN -> { /* fall through to free-text scan */ }
            }
        }
        // UNKNOWN or null structured status → legacy free-text scan.
        if (freeText == null || freeText.isBlank()) return null;
        String s = freeText.toLowerCase();
        for (String neg : NEGATIONS) if (s.contains(neg)) return null;
        if (GP_NOTATION.matcher(s).find()) return State.PREGNANT;
        for (String tok : PREGNANCY_TOKENS) if (s.contains(tok)) return State.PREGNANT;
        for (String tok : BREASTFEEDING_TOKENS) if (s.contains(tok)) return State.BREASTFEEDING;
        return null;
    }

    /** True when the structured status explicitly rules pregnancy out (gate must stay silent). */
    public static boolean isExplicitlySuppressed(PregnancyStatus status) {
        return status == PregnancyStatus.NOT_PREGNANT || status == PregnancyStatus.NOT_APPLICABLE;
    }

    /**
     * Classify a drug against a resolved pregnancy state via the name-rules table.
     * Returns null when the drug isn't a known teratogen, or when the state is
     * breastfeeding and the drug isn't flagged as a lactation concern.
     */
    public static Finding classify(String drugName, State state) {
        if (drugName == null || state == null) return null;
        String n = drugName.toLowerCase();
        for (Rule rule : RULES) {
            for (String kw : rule.keywords()) {
                if (n.contains(kw)) {
                    if (state == State.BREASTFEEDING && !rule.alsoBreastfeeding()) return null;
                    String evidence = state == State.PREGNANT ? "recorded as pregnant" : "recorded as breastfeeding";
                    return new Finding(rule.label(), rule.category(), state, evidence, rule.concern());
                }
            }
        }
        return null;
    }

    /** Map a formulary pregnancy_category letter (A–X) to a gate Category, or null for A/B/C. */
    public static Category fromFormularyCategory(String pregnancyCategory) {
        if (pregnancyCategory == null) return null;
        return switch (pregnancyCategory.trim().toUpperCase()) {
            case "X" -> Category.X;
            case "D" -> Category.D;
            default -> null; // A / B / C are not gated
        };
    }
}
