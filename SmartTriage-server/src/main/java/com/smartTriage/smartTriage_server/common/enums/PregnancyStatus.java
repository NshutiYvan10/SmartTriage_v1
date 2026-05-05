package com.smartTriage.smartTriage_server.common.enums;

/**
 * Structured pregnancy / lactation status for a patient.
 *
 * Why this enum exists (Phase 13b):
 *   The teratogen safety check (Phase 13) was originally driven by
 *   substring scans of the free-text `chronic_conditions` field —
 *   "pregnant", "pregnancy", "lactating", "breastfeeding". That worked
 *   for the smoke-test phase but has known failure modes:
 *     • Misspellings ("pregnent") fail to trigger.
 *     • Negations ("not pregnant", "ruled out pregnancy") false-trigger
 *       the warning, training prescribers to dismiss it.
 *     • Translations / mixed-language notes ("hamile", "enceinte")
 *       silently miss.
 *     • Stale data lingers — a chart still says "pregnant" six months
 *       after delivery because nobody edited the free text.
 *
 *   This enum gives the triage / registration UI a discrete dropdown
 *   that can be set, cleared, and timestamped. The teratogen check
 *   reads this field first and falls back to the free-text scan only
 *   when this field is null (so existing patients without structured
 *   data don't lose coverage during rollout).
 *
 * Why not a boolean:
 *   "Is this patient pregnant?" is not a yes/no question in the ED.
 *   We need to distinguish:
 *     • PREGNANT — known active pregnancy (any trimester).
 *     • BREASTFEEDING — postpartum lactation, drug crosses into milk.
 *     • POSSIBLY_PREGNANT — childbearing-age + missed period / unsure;
 *       triage flags this so the prescriber thinks twice on Category-X
 *       drugs even before a beta-hCG comes back.
 *     • NOT_PREGNANT — explicitly ruled out (negative test, post-
 *       hysterectomy, etc.). Stops the safety check from firing.
 *     • UNKNOWN — no information yet; teratogen check falls back to
 *       free-text scan (legacy behaviour).
 *     • NOT_APPLICABLE — male / pre-menarche / post-menopausal.
 *       Suppresses the check entirely.
 *
 *   Triage staff can record any of these without owning the question
 *   "should we prescribe X?" — that's still the prescriber's call,
 *   informed by the dialog.
 *
 * Wire format note:
 *   teratogenCheck.ts on the frontend keys off this enum directly when
 *   present. The free-text fallback path is preserved for legacy /
 *   imported records. We do NOT silently overwrite chronicConditions
 *   when this enum is set — operators may have additional context
 *   (gestational age, complications) in the free text that's worth
 *   keeping visible.
 */
public enum PregnancyStatus {
    PREGNANT,
    BREASTFEEDING,
    POSSIBLY_PREGNANT,
    NOT_PREGNANT,
    NOT_APPLICABLE,
    UNKNOWN
}
