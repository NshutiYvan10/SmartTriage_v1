/**
 * PrescribeSafetyDialog — hard-stop modal that surfaces ANY of:
 *   - allergy conflicts (drug matches a known patient allergy or
 *     cross-reactive family),
 *   - drug–drug interactions (drug conflicts with another medication
 *     currently active on this visit).
 *
 * Both are rendered in one dialog because two cascading modals (allergy
 * first, then interactions on override) is a worse UX than one screen
 * the prescriber reads end-to-end before deciding. The "prescribe
 * anyway" button acknowledges everything shown.
 *
 * The dialog is intentionally NOT a toast and NOT a banner — it's a
 * focus-trapping modal with two equally-prominent buttons:
 *   - Cancel order (default, primary visual weight)
 *   - "I acknowledge — prescribe anyway" (red gradient, requires
 *     deliberate click)
 *
 * Pattern: render at the page root (outside the form) so a re-render
 * of the form doesn't tear it down mid-decision.
 */
import { useMemo, useState } from 'react';
import { AlertTriangle, X, ShieldAlert, Pill, Copy, Scale, Droplet, Baby, UserMinus } from 'lucide-react';
import type { AllergyMatch } from '@/utils/allergyCheck';
import type { InteractionMatch, DuplicateMatch } from '@/utils/interactionCheck';
import type { DoseMatch } from '@/utils/pediatricDoseCheck';
import type { AdultDoseMatch } from '@/utils/adultDoseCheck';
import type { RenalMatch } from '@/utils/renalRiskCheck';
import type { TeratogenMatch } from '@/utils/teratogenCheck';
import type { GeriatricMatch } from '@/utils/geriatricCheck';
import type { RenalEgfrMatch } from '@/utils/eGfrCalc';

interface Props {
  /** Drug being prescribed — shown verbatim so the clinician can
   *  confirm "yes, that's the order I just typed". */
  drugName: string;
  /** Allergy conflicts. May be empty if only interactions fired. */
  allergyMatches: AllergyMatch[];
  /** Interaction conflicts. May be empty if only allergies fired. */
  interactionMatches: InteractionMatch[];
  /** Same-class duplicate therapy hits — different drug, same
   *  therapeutic class as another active med (e.g. two NSAIDs).
   *  Lower visual weight than interactions because duplicates are
   *  more often clinically intentional. */
  duplicateMatches: DuplicateMatch[];
  /** Paediatric weight-based dose-out-of-range hits. May be empty
   *  if patient is non-paediatric, no weight recorded, drug isn't
   *  in our rules table, or dose was within range. */
  doseMatches: DoseMatch[];
  /** Adult single-dose envelope hits (Phase 11b). Fires only on
   *  non-paediatric visits — paediatric uses the mg/kg path above.
   *  Same `[overdose]` / `[underdose]` audit-trail prefixes as the
   *  paediatric matches so the severity ladder and override-audit
   *  dashboard surface them identically. */
  adultDoseMatches: AdultDoseMatch[];
  /** Renal-risk hits — drug is renal-dangerous AND patient either
   *  has CKD on profile or vitals suggest AKI. Until we have eGFR
   *  this is a screening-level warning, not a dose-adjustment
   *  recommendation. */
  renalMatches: RenalMatch[];
  /** Teratogen hits — drug is known to harm a fetus or breastfed
   *  infant AND patient's chart explicitly records pregnancy or
   *  breastfeeding. Category X gets the most severe header copy. */
  teratogenMatches: TeratogenMatch[];
  /** Geriatric (Beers Criteria) hits — drug is on the curated Beers
   *  list AND patient is ≥ 65. Two-tier severity: 'avoid' (strong) and
   *  'caution' (use with care / dose-reduce). */
  geriatricMatches: GeriatricMatch[];
  /** Phase 12b — Cockcroft-Gault eGFR-driven dose-adjustment hits.
   *  Distinct from Phase 12a `renalMatches` (screening on chart text /
   *  AKI-pattern vitals): this requires a structured creatinine + adult
   *  weight, then runs a curated thresholds table per drug. Two-tier
   *  severity: 'avoid' (red) and 'caution' (amber-violet). */
  renalEgfrMatches: RenalEgfrMatch[];
  /** Patient's free-text allergy string, shown raw so the clinician
   *  can read it themselves rather than trusting our tokenizer.
   *  Optional — if no allergy matches fired we don't need this block. */
  rawAllergyString?: string;
  /** True while the prescribe API call is in flight after override. */
  loading: boolean;
  /** User chose to abort. Close dialog, leave form untouched. */
  onCancel: () => void;
  /** User chose to override. Caller proceeds with the prescribe call.
   *  When a SEVERE/ANAPHYLAXIS allergy match required an override
   *  reason, that reason is passed back so the caller can append it
   *  to the audit snapshot. {@code undefined} for the routine path. */
  onOverride: (overrideReason?: string) => void;
}

export function PrescribeSafetyDialog({
  drugName,
  allergyMatches,
  interactionMatches,
  duplicateMatches,
  doseMatches,
  adultDoseMatches,
  renalMatches,
  teratogenMatches,
  geriatricMatches,
  renalEgfrMatches,
  rawAllergyString,
  loading,
  onCancel,
  onOverride,
}: Props) {
  // Workflow 2 — capture an override reason when the highest matched
  // allergy is SEVERE or ANAPHYLAXIS. For MILD / MODERATE / UNKNOWN
  // (or any legacy free-text match without a structured severity)
  // the existing single-click override flow stays.
  const highestAllergySev = useMemo<string | null>(() => {
    const rank: Record<string, number> = {
      MILD: 1, MODERATE: 2, UNKNOWN: 2, SEVERE: 3, ANAPHYLAXIS: 4,
    };
    let best: string | null = null;
    for (const m of allergyMatches) {
      if (!m.severity) continue;
      if (best === null || rank[m.severity] > rank[best]) best = m.severity;
    }
    return best;
  }, [allergyMatches]);

  const requiresOverrideReason =
    highestAllergySev === 'SEVERE' || highestAllergySev === 'ANAPHYLAXIS';

  const [overrideReason, setOverrideReason] = useState('');
  const overrideReasonOk =
    !requiresOverrideReason || overrideReason.trim().length >= 10;

  const hasAllergy = allergyMatches.length > 0;
  const hasInteraction = interactionMatches.length > 0;
  const hasDuplicate = duplicateMatches.length > 0;
  const hasDose = doseMatches.length > 0;
  const hasAdultDose = adultDoseMatches.length > 0;
  // hasOverdose covers BOTH dose paths so the header / chip / footer
  // logic doesn't have to know whether the patient is a child or an
  // adult — a 4× max overdose is "Overdose" either way.
  const hasOverdose =
    doseMatches.some((m) => m.severity === 'overdose') ||
    adultDoseMatches.some((m) => m.severity === 'overdose');
  const hasRenal = renalMatches.length > 0;
  const hasCkd = renalMatches.some((m) => m.trigger === 'ckd');
  const hasTeratogen = teratogenMatches.length > 0;
  const hasCategoryX = teratogenMatches.some((m) => m.category === 'X');
  const isBreastfeeding = teratogenMatches.every((m) => m.state === 'breastfeeding');
  const hasGeriatric = geriatricMatches.length > 0;
  const hasGeriatricAvoid = geriatricMatches.some((m) => m.severity === 'avoid');
  const hasRenalEgfr = renalEgfrMatches.length > 0;
  const hasRenalEgfrAvoid = renalEgfrMatches.some((m) => m.severity === 'avoid');

  // Header label adapts to what fired. Multi-cause → vaguer "Safety
  // conflict" rather than naming one cause and hiding the others.
  // Lying-by-omission ("Allergy conflict" when an interaction also
  // fired) would be worse than a vaguer header.
  const causeCount =
    (hasAllergy ? 1 : 0) +
    (hasInteraction ? 1 : 0) +
    (hasDuplicate ? 1 : 0) +
    (hasDose ? 1 : 0) +
    (hasAdultDose ? 1 : 0) +
    (hasRenal ? 1 : 0) +
    (hasTeratogen ? 1 : 0) +
    (hasGeriatric ? 1 : 0) +
    (hasRenalEgfr ? 1 : 0);
  const headerLabel = causeCount > 1
    ? 'Safety conflict'
    : hasAllergy
      ? 'Allergy conflict'
      : hasTeratogen
        ? isBreastfeeding ? 'Lactation precaution' : 'Pregnancy precaution'
        : hasInteraction
          ? 'Drug interaction'
          : hasDose
            ? hasOverdose ? 'Dose out of range' : 'Subtherapeutic dose'
            : hasRenalEgfr
              ? hasRenalEgfrAvoid ? 'Renal eGFR precaution (avoid)' : 'Renal eGFR precaution'
              : hasRenal
                ? hasCkd ? 'Renal precaution' : 'Renal precaution (AKI risk)'
                : hasGeriatric
                  ? hasGeriatricAvoid ? 'Geriatric precaution (avoid)' : 'Geriatric precaution'
                  : 'Duplicate therapy';

  const headerSubtitle = causeCount > 1
    ? 'Multiple conflicts detected for this prescription.'
    : hasAllergy
      ? 'The prescribed drug matches a known patient allergy.'
      : hasTeratogen
        ? isBreastfeeding
          ? "Drug poses risk to a breastfed infant — patient's chart records breastfeeding."
          : "Drug poses risk to the fetus — patient's chart records pregnancy."
        : hasInteraction
          ? 'The prescribed drug interacts with an active medication.'
          : hasDose
            ? hasOverdose
              ? 'The prescribed dose exceeds the paediatric weight-based maximum.'
              : 'The prescribed dose is below the paediatric weight-based minimum.'
            : hasAdultDose
              ? hasOverdose
                ? 'The prescribed dose exceeds the adult single-dose maximum.'
                : 'The prescribed dose is below the adult single-dose minimum.'
              : hasRenalEgfr
                ? hasRenalEgfrAvoid
                  ? "Patient's calculated eGFR is below the threshold for safe use of this drug."
                  : "Patient's calculated eGFR requires dose adjustment for this drug."
                : hasRenal
                ? hasCkd
                  ? 'Drug requires caution in chronic kidney disease.'
                  : 'Vitals suggest possible acute kidney injury — drug requires caution.'
                : hasGeriatric
                  ? hasGeriatricAvoid
                    ? "Drug is on the Beers Criteria avoid list for patients ≥ 65."
                    : "Drug is on the Beers Criteria caution list for patients ≥ 65."
                  : 'The prescribed drug duplicates an active medication in the same class.';

  // Promote contraindicated severity into the header copy so a
  // distracted prescriber sees "contraindicated" before reading the
  // body. An overdose is escalated alongside contraindicated — both
  // are "stop and think" magnitude.
  const hasContraindicated = interactionMatches.some(
    (m) => m.severity === 'contraindicated',
  );
  const hasSevereDose =
    doseMatches.some(
      (m) => m.severity === 'overdose' && (m.foldOverMax ?? 0) >= 2,
    ) ||
    adultDoseMatches.some(
      (m) => m.severity === 'overdose' && (m.foldOverMax ?? 0) >= 2,
    );

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm animate-fade-in"
      role="dialog"
      aria-modal="true"
      aria-labelledby="safety-dialog-title"
    >
      <div className="w-full max-w-lg rounded-2xl bg-white shadow-2xl overflow-hidden animate-fade-up max-h-[90vh] flex flex-col">
        {/* ── Header (red, unmissable) ── */}
        <div className="bg-gradient-to-r from-red-600 to-red-500 px-5 py-4 flex items-center gap-3 flex-shrink-0">
          <div className="w-10 h-10 rounded-xl bg-white/20 flex items-center justify-center flex-shrink-0">
            <ShieldAlert className="w-6 h-6 text-white" />
          </div>
          <div className="flex-1 min-w-0">
            <h2 id="safety-dialog-title" className="text-base font-extrabold text-white tracking-tight">
              {headerLabel}
              {hasContraindicated && (
                <span className="ml-2 text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded bg-white text-red-700 align-middle">
                  Contraindicated
                </span>
              )}
              {hasSevereDose && (
                <span className="ml-2 text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded bg-white text-red-700 align-middle">
                  Overdose
                </span>
              )}
              {hasCategoryX && (
                <span className="ml-2 text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded bg-white text-red-700 align-middle">
                  Category X
                </span>
              )}
            </h2>
            <p className="text-xs text-red-50/90 mt-0.5">{headerSubtitle}</p>
          </div>
          <button
            type="button"
            onClick={onCancel}
            disabled={loading}
            aria-label="Cancel"
            className="w-8 h-8 rounded-lg bg-white/10 hover:bg-white/20 flex items-center justify-center transition-colors disabled:opacity-50"
          >
            <X className="w-4 h-4 text-white" />
          </button>
        </div>

        {/* ── Body (scrollable when content is long) ── */}
        <div className="p-5 space-y-4 overflow-y-auto">
          {/* Drug being prescribed */}
          <div className="rounded-xl border border-slate-200 bg-slate-50 p-3">
            <div className="text-[10px] font-bold uppercase tracking-wider text-slate-500">
              Prescribing
            </div>
            <div className="text-base font-bold text-slate-900 break-words">
              {drugName}
            </div>
          </div>

          {/* ── Allergy conflicts (severity-aware — Workflow 2) ── */}
          {hasAllergy && (() => {
            // Highest-severity rank across all matches drives the
            // visual flavour. Free-text fallback matches arrive
            // without a severity — render in the legacy amber tone.
            const sevRank: Record<string, number> = {
              MILD: 1, MODERATE: 2, UNKNOWN: 2, SEVERE: 3, ANAPHYLAXIS: 4,
            };
            let topSev: string | undefined;
            for (const m of allergyMatches) {
              if (!m.severity) continue;
              if (!topSev || sevRank[m.severity] > sevRank[topSev]) {
                topSev = m.severity;
              }
            }
            // Visual flavour per severity:
            //   ANAPHYLAXIS → dark red (hardest stop)
            //   SEVERE      → red
            //   MODERATE/UNKNOWN → orange/amber (hard warning)
            //   MILD        → yellow (soft warning)
            //   no severity → amber (legacy fallback)
            const flavour =
              topSev === 'ANAPHYLAXIS' ? {
                box: 'border-red-500 bg-red-100',
                icon: 'text-red-800',
                title: 'text-red-900',
                body: 'text-red-900',
                subtle: 'text-red-800/80',
                label: 'ANAPHYLAXIS — life-threatening reaction on record',
              } : topSev === 'SEVERE' ? {
                box: 'border-red-400 bg-red-50',
                icon: 'text-red-700',
                title: 'text-red-800',
                body: 'text-red-900',
                subtle: 'text-red-800/80',
                label: 'SEVERE allergy on record',
              } : topSev === 'MODERATE' || topSev === 'UNKNOWN' ? {
                box: 'border-orange-300 bg-orange-50',
                icon: 'text-orange-700',
                title: 'text-orange-800',
                body: 'text-orange-900',
                subtle: 'text-orange-800/80',
                label: topSev === 'UNKNOWN'
                  ? 'UNKNOWN reaction — treated as moderate'
                  : 'MODERATE allergy on record',
              } : topSev === 'MILD' ? {
                box: 'border-yellow-300 bg-yellow-50',
                icon: 'text-yellow-700',
                title: 'text-yellow-800',
                body: 'text-yellow-900',
                subtle: 'text-yellow-800/80',
                label: 'MILD allergy on record',
              } : {
                box: 'border-amber-300 bg-amber-50',
                icon: 'text-amber-700',
                title: 'text-amber-800',
                body: 'text-amber-900',
                subtle: 'text-amber-800/80',
                label: 'Allergy match (severity not recorded)',
              };
            return (
              <div className={`rounded-xl border p-3 ${flavour.box}`}>
                <div className="flex items-start gap-2">
                  <AlertTriangle className={`w-4 h-4 ${flavour.icon} flex-shrink-0 mt-0.5`} />
                  <div className="flex-1 min-w-0">
                    <div className={`flex items-center gap-2 flex-wrap`}>
                      <span className={`text-[11px] font-bold uppercase tracking-wider ${flavour.title}`}>
                        Allergy conflict
                      </span>
                      <span className={`text-[10px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded bg-white border border-current ${flavour.title}`}>
                        {flavour.label}
                      </span>
                    </div>
                    <ul className="mt-1.5 space-y-1.5">
                      {allergyMatches.map((m, i) => (
                        <li key={i} className={`text-sm ${flavour.body}`}>
                          <div>
                            <span className="font-semibold">{m.patientAllergen}</span>
                            {m.matchType === 'cross' && m.family && (
                              <span className={flavour.subtle}>
                                {' '}— same family as prescribed drug ({m.family})
                              </span>
                            )}
                            {m.matchType === 'direct' && (
                              <span className={flavour.subtle}>
                                {' '}— direct match in drug name
                              </span>
                            )}
                            {m.severity && (
                              <span className={`ml-2 text-[10px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded bg-white/70 ${flavour.title}`}>
                                {m.severity}
                              </span>
                            )}
                          </div>
                          {m.reaction && (
                            <div className={`text-[11px] mt-0.5 ${flavour.subtle}`}>
                              Prior reaction: <span className="font-medium">{m.reaction}</span>
                            </div>
                          )}
                        </li>
                      ))}
                    </ul>
                  </div>
                </div>
              </div>
            );
          })()}

          {/* ── Pregnancy / lactation precaution ── */}
          {hasTeratogen && (
            <div
              className={`rounded-xl border p-3 ${
                hasCategoryX ? 'border-red-300 bg-red-50' : 'border-pink-300 bg-pink-50'
              }`}
            >
              <div className="flex items-start gap-2">
                <Baby
                  className={`w-4 h-4 flex-shrink-0 mt-0.5 ${
                    hasCategoryX ? 'text-red-700' : 'text-pink-700'
                  }`}
                />
                <div className="flex-1 min-w-0">
                  <div
                    className={`text-[11px] font-bold uppercase tracking-wider ${
                      hasCategoryX ? 'text-red-800' : 'text-pink-800'
                    }`}
                  >
                    {isBreastfeeding ? 'Lactation precaution' : 'Pregnancy precaution'}
                  </div>
                  <p
                    className={`text-[11px] mt-0.5 mb-1.5 ${
                      hasCategoryX ? 'text-red-800/80' : 'text-pink-800/80'
                    }`}
                  >
                    {hasCategoryX
                      ? 'Category X drug — fetal harm is well-evidenced and effectively unavoidable. Almost never an acceptable trade-off; an alternative agent is the right answer.'
                      : isBreastfeeding
                        ? 'Drug appears in breast milk at concentrations that may harm the infant. Confirm benefit outweighs risk and consider monitoring.'
                        : 'Drug carries a documented risk of fetal harm. Confirm pregnancy status and consider an alternative agent.'}
                  </p>
                  <ul className="space-y-1.5">
                    {teratogenMatches.map((m, i) => (
                      <li
                        key={i}
                        className={`text-sm ${hasCategoryX ? 'text-red-900' : 'text-pink-900'}`}
                      >
                        <div>
                          <span className="font-semibold">{m.drugClassLabel}</span>
                          <span className={hasCategoryX ? 'text-red-800' : 'text-pink-800'}>
                            {' '}— chart says: <span className="italic">"{m.evidence}"</span>
                          </span>
                          <span
                            className={`ml-1.5 text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded ${
                              m.category === 'X'
                                ? 'bg-red-600 text-white'
                                : m.category === 'D'
                                  ? 'bg-pink-700 text-white'
                                  : m.category === 'D-late'
                                    ? 'bg-pink-600 text-white'
                                    : 'bg-amber-600 text-white'
                            }`}
                          >
                            {m.category === 'D-late'
                              ? 'D · 3rd trimester'
                              : m.category === 'caution'
                                ? 'Caution'
                                : `Category ${m.category}`}
                          </span>
                        </div>
                        <div
                          className={`text-xs mt-0.5 ml-0.5 ${
                            hasCategoryX ? 'text-red-800' : 'text-pink-800'
                          }`}
                        >
                          → {m.concern}
                        </div>
                      </li>
                    ))}
                  </ul>
                </div>
              </div>
            </div>
          )}

          {/* ── Patient's recorded allergies (raw text) ── */}
          {hasAllergy && rawAllergyString && (
            <div className="rounded-xl border border-red-200 bg-red-50 p-3">
              <div className="text-[10px] font-bold uppercase tracking-wider text-red-700">
                Patient's recorded allergies
              </div>
              <div className="text-sm text-red-900 mt-1 break-words font-medium">
                {rawAllergyString}
              </div>
            </div>
          )}

          {/* ── Duplicate therapy ── */}
          {hasDuplicate && (
            <div className="rounded-xl border border-yellow-300 bg-yellow-50 p-3">
              <div className="flex items-start gap-2">
                <Copy className="w-4 h-4 text-yellow-700 flex-shrink-0 mt-0.5" />
                <div className="flex-1 min-w-0">
                  <div className="text-[11px] font-bold uppercase tracking-wider text-yellow-800">
                    Duplicate therapy
                  </div>
                  <p className="text-[11px] text-yellow-800/80 mt-0.5 mb-1.5">
                    Already on a drug in the same class. Confirm if intentional
                    (PRN + scheduled, loading + maintenance, IV-to-PO bridge).
                  </p>
                  <ul className="space-y-1">
                    {duplicateMatches.map((m, i) => (
                      <li key={i} className="text-sm text-yellow-900">
                        <span className="font-semibold">{m.otherDrugName}</span>
                        <span className="text-yellow-800">
                          {' '}— same class as prescribed drug ({m.sharedClassLabel})
                        </span>
                      </li>
                    ))}
                  </ul>
                </div>
              </div>
            </div>
          )}

          {/* ── Paediatric dose out of range ── */}
          {hasDose && (
            <div
              className={`rounded-xl border p-3 ${
                hasOverdose
                  ? 'border-red-300 bg-red-50'
                  : 'border-blue-300 bg-blue-50'
              }`}
            >
              <div className="flex items-start gap-2">
                <Scale
                  className={`w-4 h-4 flex-shrink-0 mt-0.5 ${
                    hasOverdose ? 'text-red-700' : 'text-blue-700'
                  }`}
                />
                <div className="flex-1 min-w-0">
                  <div
                    className={`text-[11px] font-bold uppercase tracking-wider ${
                      hasOverdose ? 'text-red-800' : 'text-blue-800'
                    }`}
                  >
                    {hasOverdose ? 'Dose exceeds paediatric maximum' : 'Dose below paediatric minimum'}
                  </div>
                  <p
                    className={`text-[11px] mt-0.5 mb-1.5 ${
                      hasOverdose ? 'text-red-800/80' : 'text-blue-800/80'
                    }`}
                  >
                    Calculated against the patient's recorded weight. Confirm
                    weight is correct before overriding — a wrong weight is the
                    most common cause of a false dose alert.
                  </p>
                  <ul className="space-y-1.5">
                    {doseMatches.map((m, i) => (
                      <li
                        key={i}
                        className={`text-sm ${hasOverdose ? 'text-red-900' : 'text-blue-900'}`}
                      >
                        <div>
                          <span className="font-semibold">{m.ruleLabel}</span>
                          <span className={hasOverdose ? 'text-red-800' : 'text-blue-800'}>
                            {' '}— {m.doseString} @ {m.weightKg} kg ={' '}
                            <span className="font-bold">
                              {formatMgPerKg(m.prescribedMgPerKg)} mg/kg
                            </span>
                          </span>
                          {m.severity === 'overdose' && (m.foldOverMax ?? 0) >= 2 && (
                            <span className="ml-1.5 text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded bg-red-600 text-white">
                              {formatFold(m.foldOverMax!)}× max
                            </span>
                          )}
                        </div>
                        <div
                          className={`text-xs mt-0.5 ml-0.5 ${
                            hasOverdose ? 'text-red-800' : 'text-blue-800'
                          }`}
                        >
                          → recommended {m.minMgPerKg}–{m.maxMgPerKg} mg/kg/dose
                        </div>
                      </li>
                    ))}
                  </ul>
                </div>
              </div>
            </div>
          )}

          {/* ── Adult single-dose envelope (Phase 11b) ── */}
          {hasAdultDose && (
            <div
              className={`rounded-xl border p-3 ${
                adultDoseMatches.some((m) => m.severity === 'overdose')
                  ? 'border-red-300 bg-red-50'
                  : 'border-blue-300 bg-blue-50'
              }`}
            >
              <div className="flex items-start gap-2">
                <Scale
                  className={`w-4 h-4 flex-shrink-0 mt-0.5 ${
                    adultDoseMatches.some((m) => m.severity === 'overdose')
                      ? 'text-red-700' : 'text-blue-700'
                  }`}
                />
                <div className="flex-1 min-w-0">
                  <div
                    className={`text-[11px] font-bold uppercase tracking-wider ${
                      adultDoseMatches.some((m) => m.severity === 'overdose')
                        ? 'text-red-800' : 'text-blue-800'
                    }`}
                  >
                    {adultDoseMatches.some((m) => m.severity === 'overdose')
                      ? 'Dose exceeds adult single-dose maximum'
                      : 'Dose below adult single-dose minimum'}
                  </div>
                  <p
                    className={`text-[11px] mt-0.5 mb-1.5 ${
                      adultDoseMatches.some((m) => m.severity === 'overdose')
                        ? 'text-red-800/80' : 'text-blue-800/80'
                    }`}
                  >
                    Compared against the common adult single-dose envelope
                    (PO + IV combined). Loading doses and route-specific
                    extremes may legitimately fall outside — confirm before
                    overriding.
                  </p>
                  <ul className="space-y-1.5">
                    {adultDoseMatches.map((m, i) => {
                      const isOver = m.severity === 'overdose';
                      return (
                        <li
                          key={i}
                          className={`text-sm ${isOver ? 'text-red-900' : 'text-blue-900'}`}
                        >
                          <div>
                            <span className="font-semibold">{m.ruleLabel}</span>
                            <span className={isOver ? 'text-red-800' : 'text-blue-800'}>
                              {' '}— {m.doseString} ={' '}
                              <span className="font-bold">
                                {m.prescribedMg} mg
                              </span>
                            </span>
                            {isOver && (m.foldOverMax ?? 0) >= 2 && (
                              <span className="ml-1.5 text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded bg-red-600 text-white">
                                {formatFold(m.foldOverMax!)}× max
                              </span>
                            )}
                          </div>
                          <div
                            className={`text-xs mt-0.5 ml-0.5 ${
                              isOver ? 'text-red-800' : 'text-blue-800'
                            }`}
                          >
                            → adult single-dose range {m.minMg}–{m.maxMg} mg
                          </div>
                        </li>
                      );
                    })}
                  </ul>
                </div>
              </div>
            </div>
          )}

          {/* ── Renal precaution ── */}
          {hasRenal && (
            <div className="rounded-xl border border-violet-300 bg-violet-50 p-3">
              <div className="flex items-start gap-2">
                <Droplet className="w-4 h-4 text-violet-700 flex-shrink-0 mt-0.5" />
                <div className="flex-1 min-w-0">
                  <div className="text-[11px] font-bold uppercase tracking-wider text-violet-800">
                    {hasCkd ? 'Renal precaution — CKD' : 'Renal precaution — possible AKI'}
                  </div>
                  <p className="text-[11px] text-violet-800/80 mt-0.5 mb-1.5">
                    {hasCkd
                      ? "Patient's chart records chronic kidney disease. Confirm renal function before prescribing — dose adjustment may be needed."
                      : 'Vitals suggest haemodynamic instability that can cause pre-renal AKI. Hold or dose-adjust if renal function unconfirmed.'}
                    {' '}This warning is screening-only — it does not replace a creatinine / eGFR check.
                  </p>
                  <ul className="space-y-1.5">
                    {renalMatches.map((m, i) => (
                      <li key={i} className="text-sm text-violet-900">
                        <div>
                          <span className="font-semibold">{m.drugClassLabel}</span>
                          {m.trigger === 'ckd' && m.conditionEvidence && (
                            <span className="text-violet-800">
                              {' '}— chart says: <span className="italic">"{m.conditionEvidence}"</span>
                            </span>
                          )}
                          {m.trigger === 'aki_likely' && m.vitalEvidence && (
                            <span className="text-violet-800">
                              {' '}— vitals: {m.vitalEvidence}
                            </span>
                          )}
                        </div>
                        <div className="text-xs text-violet-800 mt-0.5 ml-0.5">
                          → {m.concern}
                        </div>
                      </li>
                    ))}
                  </ul>
                </div>
              </div>
            </div>
          )}

          {/* ── Renal eGFR precaution (Phase 12b — Cockcroft-Gault) ── */}
          {hasRenalEgfr && (
            <div
              className={`rounded-xl border p-3 ${
                hasRenalEgfrAvoid
                  ? 'border-red-300 bg-red-50'
                  : 'border-violet-300 bg-violet-50'
              }`}
            >
              <div className="flex items-start gap-2">
                <Droplet
                  className={`w-4 h-4 flex-shrink-0 mt-0.5 ${
                    hasRenalEgfrAvoid ? 'text-red-700' : 'text-violet-700'
                  }`}
                />
                <div className="flex-1 min-w-0">
                  <div
                    className={`text-[11px] font-bold uppercase tracking-wider ${
                      hasRenalEgfrAvoid ? 'text-red-800' : 'text-violet-800'
                    }`}
                  >
                    {hasRenalEgfrAvoid
                      ? 'Renal eGFR — avoid'
                      : 'Renal eGFR — dose-adjust / caution'}
                  </div>
                  <p
                    className={`text-[11px] mt-0.5 mb-1.5 ${
                      hasRenalEgfrAvoid ? 'text-red-800/80' : 'text-violet-800/80'
                    }`}
                  >
                    Calculated by Cockcroft-Gault from the most recent serum
                    creatinine and recorded weight. Confirm both values are
                    current — a stale lab is the most common cause of a false
                    eGFR alert.
                  </p>
                  <ul className="space-y-1.5">
                    {renalEgfrMatches.map((m, i) => {
                      const isAvoid = m.severity === 'avoid';
                      return (
                        <li
                          key={i}
                          className={`text-sm ${isAvoid ? 'text-red-900' : 'text-violet-900'}`}
                        >
                          <div>
                            <span className="font-semibold">{m.drugClassLabel}</span>
                            <span className={isAvoid ? 'text-red-800' : 'text-violet-800'}>
                              {' '}— eGFR{' '}
                              <span className="font-bold">
                                {m.patientEgfr.toFixed(0)}
                              </span>{' '}
                              mL/min (threshold {m.thresholdEgfr})
                            </span>
                            <span
                              className={`ml-1.5 text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded text-white ${
                                isAvoid ? 'bg-red-600' : 'bg-violet-600'
                              }`}
                            >
                              {isAvoid ? 'Avoid' : 'Caution'}
                            </span>
                          </div>
                          <div
                            className={`text-xs mt-0.5 ml-0.5 ${
                              isAvoid ? 'text-red-800' : 'text-violet-800'
                            }`}
                          >
                            → {m.concern}
                          </div>
                        </li>
                      );
                    })}
                  </ul>
                </div>
              </div>
            </div>
          )}

          {/* ── Geriatric (Beers Criteria) precaution (Phase 16) ── */}
          {hasGeriatric && (
            <div
              className={`rounded-xl border p-3 ${
                hasGeriatricAvoid
                  ? 'border-red-300 bg-red-50'
                  : 'border-amber-300 bg-amber-50'
              }`}
            >
              <div className="flex items-start gap-2">
                <UserMinus
                  className={`w-4 h-4 flex-shrink-0 mt-0.5 ${
                    hasGeriatricAvoid ? 'text-red-700' : 'text-amber-700'
                  }`}
                />
                <div className="flex-1 min-w-0">
                  <div
                    className={`text-[11px] font-bold uppercase tracking-wider ${
                      hasGeriatricAvoid ? 'text-red-800' : 'text-amber-800'
                    }`}
                  >
                    {hasGeriatricAvoid
                      ? 'Beers Criteria — avoid in elderly'
                      : 'Beers Criteria — use with caution in elderly'}
                  </div>
                  <p
                    className={`text-[11px] mt-0.5 mb-1.5 ${
                      hasGeriatricAvoid ? 'text-red-800/80' : 'text-amber-800/80'
                    }`}
                  >
                    The American Geriatrics Society Beers Criteria flags this
                    drug for patients ≥ 65 due to elderly-specific harm. Confirm
                    benefit outweighs risk before overriding.
                  </p>
                  <ul className="space-y-1.5">
                    {geriatricMatches.map((m, i) => {
                      const isAvoid = m.severity === 'avoid';
                      return (
                        <li
                          key={i}
                          className={`text-sm ${isAvoid ? 'text-red-900' : 'text-amber-900'}`}
                        >
                          <div>
                            <span className="font-semibold">{m.drugClassLabel}</span>
                            <span className={isAvoid ? 'text-red-800' : 'text-amber-800'}>
                              {' '}— age {m.ageYears}
                            </span>
                            <span
                              className={`ml-1.5 text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded text-white ${
                                isAvoid ? 'bg-red-600' : 'bg-amber-600'
                              }`}
                            >
                              {isAvoid ? 'Avoid' : 'Caution'}
                            </span>
                          </div>
                          <div
                            className={`text-xs mt-0.5 ml-0.5 ${
                              isAvoid ? 'text-red-800' : 'text-amber-800'
                            }`}
                          >
                            → {m.concern}
                          </div>
                        </li>
                      );
                    })}
                  </ul>
                </div>
              </div>
            </div>
          )}

          {/* ── Interaction conflicts ── */}
          {hasInteraction && (
            <div className="rounded-xl border border-orange-300 bg-orange-50 p-3">
              <div className="flex items-start gap-2">
                <Pill className="w-4 h-4 text-orange-700 flex-shrink-0 mt-0.5" />
                <div className="flex-1 min-w-0">
                  <div className="text-[11px] font-bold uppercase tracking-wider text-orange-800">
                    Drug interactions
                  </div>
                  <ul className="mt-1.5 space-y-1.5">
                    {interactionMatches.map((m, i) => (
                      <li key={i} className="text-sm text-orange-900">
                        <div>
                          <span className="font-semibold">{m.otherDrugName}</span>
                          <span className="text-orange-800">
                            {' '}— {m.prescribedClassLabel} + {m.otherClassLabel}
                          </span>
                          {m.severity === 'contraindicated' && (
                            <span className="ml-1.5 text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded bg-red-600 text-white">
                              Contraindicated
                            </span>
                          )}
                          {m.severity === 'major' && (
                            <span className="ml-1.5 text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded bg-orange-600 text-white">
                              Major
                            </span>
                          )}
                        </div>
                        <div className="text-xs text-orange-800 mt-0.5 ml-0.5">
                          → {m.mechanism}
                        </div>
                      </li>
                    ))}
                  </ul>
                </div>
              </div>
            </div>
          )}

          <p className="text-xs text-slate-600 leading-relaxed">
            If the order is clinically necessary despite the conflict
            (e.g. desensitization protocol, monitored co-administration,
            alternative is unavailable), you may override. The override
            will be recorded in the audit trail.
          </p>
        </div>

        {/* ── Override reason (Workflow 2 — SEVERE/ANAPHYLAXIS only) ── */}
        {requiresOverrideReason && (
          <div className="px-5 pb-3 border-t border-slate-200">
            <label className="block mt-3 text-[11px] font-bold uppercase tracking-wider text-red-700">
              Override reason <span className="text-red-600">*</span>
            </label>
            <p className="text-[11px] text-slate-600 mt-0.5 mb-2">
              {highestAllergySev === 'ANAPHYLAXIS'
                ? 'Anaphylaxis is on this patient\'s record. Document why this prescription is clinically justified — your reason is permanent and visible department-wide.'
                : 'A severe allergy is on this patient\'s record. Document why this prescription is clinically justified — your reason is permanent and visible department-wide.'}
            </p>
            <textarea
              rows={3}
              value={overrideReason}
              onChange={(e) => setOverrideReason(e.target.value)}
              placeholder="e.g. Desensitisation protocol started 2 hours ago, monitored in resus, no alternative available."
              className="w-full px-3 py-2 text-sm rounded-lg border border-red-300 outline-none focus:border-red-500"
              disabled={loading}
            />
            <p className="text-[10px] text-slate-500 mt-1">
              {overrideReason.trim().length < 10
                ? `Need at least 10 characters (${overrideReason.trim().length}/10).`
                : `${overrideReason.trim().length} characters.`}
            </p>
          </div>
        )}

        {/* ── Actions ── */}
        <div className="px-5 py-4 bg-slate-50 border-t border-slate-200 flex items-center justify-end gap-2 flex-shrink-0">
          <button
            type="button"
            onClick={onCancel}
            disabled={loading}
            className="px-4 py-2.5 text-sm font-bold rounded-xl bg-white border border-slate-300 text-slate-800 hover:bg-slate-100 transition-colors disabled:opacity-50"
          >
            Cancel order
          </button>
          <button
            type="button"
            onClick={() => onOverride(requiresOverrideReason ? overrideReason.trim() : undefined)}
            disabled={loading || !overrideReasonOk}
            className="px-4 py-2.5 text-sm font-bold rounded-xl bg-gradient-to-r from-red-600 to-red-500 text-white shadow-md hover:-translate-y-0.5 transition-all disabled:opacity-50 disabled:cursor-not-allowed inline-flex items-center gap-2"
            title={!overrideReasonOk ? 'Override reason required (at least 10 characters)' : undefined}
          >
            <AlertTriangle className="w-4 h-4" />
            {loading ? 'Prescribing…' : 'I acknowledge — prescribe anyway'}
          </button>
        </div>
      </div>
    </div>
  );
}

export default PrescribeSafetyDialog;

// Display-only helpers for the dose section. We round to a precision
// that's readable at-a-glance — the wire-format snapshot keeps the
// exact value via `formatDoseMatches`, this is just for the dialog UI.
function formatMgPerKg(n: number): string {
  if (n >= 10) return n.toFixed(0);
  if (n >= 1) return n.toFixed(1);
  return n.toFixed(2);
}

function formatFold(n: number): string {
  if (n >= 10) return n.toFixed(0);
  return n.toFixed(1);
}
