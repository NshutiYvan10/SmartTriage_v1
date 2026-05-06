/* ═══════════════════════════════════════════════════════════════
   Clinical-sign diff helper.

   Reduces a flat event-log into per-sign trajectories so the UI can
   answer two distinct questions in one pass:

     1. "What's the per-sign timeline?" — full chronological list of
        events for each signCode. Drives the sparkline next to each
        sign card on Current State.

     2. "What's changed since triage?" — per-sign baseline vs. latest
        comparison, classified into four buckets the doctor cares about
        on a ward round:
          • NEW       — sign wasn't in baseline, has been observed since
          • WORSENING — latest status is WORSENING, or trajectory has
                        moved from a "good" state (ABSENT/IMPROVING) to
                        a "bad" state (PRESENT/WORSENING)
          • IMPROVING — latest status is IMPROVING, or trajectory moved
                        from "bad" to "improving"
          • RESOLVED  — baseline had a presence (PRESENT/WORSENING), and
                        latest is ABSENT
          • UNCHANGED — baseline status equals latest status (filtered out
                        of the diff card by default; we only surface the
                        first four)

   Notes on intent:
     - UNKNOWN is preserved as a real value, never collapsed into
       "absent" or "no data". A sign that moved from PRESENT to UNKNOWN
       is classified as UNCHANGED for diff purposes (the doctor needs to
       reassess, but no new clinical information has been added).
     - Numeric value transitions (e.g. glucose dropping further) are NOT
       surfaced in the diff at this layer — the sparkline shows the dot
       sequence, the diff card shows status changes only. Numeric is in
       Round 3 scope (re-triage engine).
   ═══════════════════════════════════════════════════════════════ */

import type { ClinicalSignEventResponse, ClinicalSignStatus } from '@/api/clinicalSigns';

/** Per-sign chronological event list. */
export type SignTimeline = Map<string, ClinicalSignEventResponse[]>;

export type ChangeBucket = 'NEW' | 'WORSENING' | 'IMPROVING' | 'RESOLVED' | 'UNCHANGED';

export interface SignChange {
  signCode: string;
  baseline: ClinicalSignEventResponse | null;
  latest: ClinicalSignEventResponse;
  bucket: ChangeBucket;
  /** True when there are ≥2 events for this sign on this visit. */
  hasMultipleEvents: boolean;
}

/**
 * Build the per-sign timeline map. Events are sorted ascending by
 * recorded_at so the sparkline reads left-to-right oldest-to-newest
 * and the latest is the rightmost dot.
 */
export function buildSignTimelines(history: ClinicalSignEventResponse[]): SignTimeline {
  const map: SignTimeline = new Map();
  for (const e of history) {
    const list = map.get(e.signCode);
    if (list) list.push(e);
    else map.set(e.signCode, [e]);
  }
  for (const list of map.values()) {
    list.sort((a, b) => new Date(a.recordedAt).getTime() - new Date(b.recordedAt).getTime());
  }
  return map;
}

/**
 * "Good" vs "bad" classification used to detect crossings. UNKNOWN is
 * neutral — an UNKNOWN → PRESENT crossing is still WORSENING (going
 * from "we didn't know" to "we know it's there"); PRESENT → UNKNOWN is
 * UNCHANGED (no new clinical info, just lost track).
 */
function tone(status: ClinicalSignStatus): 'good' | 'bad' | 'neutral' {
  switch (status) {
    case 'ABSENT':
    case 'IMPROVING':
      return 'good';
    case 'PRESENT':
    case 'WORSENING':
      return 'bad';
    case 'UNKNOWN':
    default:
      return 'neutral';
  }
}

/**
 * Classify a single sign's baseline-to-latest trajectory.
 *
 * Decision table:
 *   no baseline AND latest is bad/neutral PRESENT → NEW
 *   baseline=bad,  latest=ABSENT          → RESOLVED
 *   latest=WORSENING                      → WORSENING
 *   latest=IMPROVING                      → IMPROVING
 *   baseline=good, latest=bad             → WORSENING (uncategorised
 *                                                       crossing)
 *   baseline=bad,  latest=good (not ABSENT) → IMPROVING (e.g. PRESENT
 *                                                       → IMPROVING)
 *   otherwise                              → UNCHANGED
 */
function classify(
  baseline: ClinicalSignEventResponse | null,
  latest: ClinicalSignEventResponse,
): ChangeBucket {
  // No baseline → only counts as NEW if the latest is something the
  // clinician would care about — a baseline UNKNOWN that flips to
  // PRESENT/WORSENING is genuinely new info; an UNKNOWN→ABSENT or
  // straight UNKNOWN/ABSENT shouldn't clutter the diff card.
  if (!baseline) {
    if (latest.status === 'PRESENT' || latest.status === 'WORSENING') return 'NEW';
    if (latest.status === 'IMPROVING') return 'IMPROVING';
    return 'UNCHANGED';
  }

  if (baseline.status === latest.status) return 'UNCHANGED';

  const baseTone = tone(baseline.status);
  const latestTone = tone(latest.status);

  if (baseTone === 'bad' && latest.status === 'ABSENT') return 'RESOLVED';
  if (latest.status === 'WORSENING') return 'WORSENING';
  if (latest.status === 'IMPROVING') return 'IMPROVING';
  if (baseTone === 'good' && latestTone === 'bad') return 'WORSENING';
  if (baseTone === 'bad' && latestTone === 'good') return 'IMPROVING';

  return 'UNCHANGED';
}

/**
 * Reduce the per-sign timelines into a flat list of SignChange records
 * — one per signCode that has at least one event. Caller filters by
 * bucket for the diff card.
 *
 * The "baseline" event is the first chronological event flagged
 * isBaseline. If multiple baseline events exist for the same sign
 * (re-triage), we use the earliest.
 */
export function classifyChanges(timelines: SignTimeline): SignChange[] {
  const out: SignChange[] = [];
  for (const [signCode, list] of timelines.entries()) {
    if (list.length === 0) continue;
    const baseline = list.find((e) => e.isBaseline) ?? null;
    const latest = list[list.length - 1];
    out.push({
      signCode,
      baseline,
      latest,
      bucket: classify(baseline, latest),
      hasMultipleEvents: list.length >= 2,
    });
  }
  return out;
}

/** Convenience — group changes by bucket for the diff card. */
export function groupChangesByBucket(changes: SignChange[]): Record<ChangeBucket, SignChange[]> {
  const grouped: Record<ChangeBucket, SignChange[]> = {
    NEW: [], WORSENING: [], IMPROVING: [], RESOLVED: [], UNCHANGED: [],
  };
  for (const c of changes) grouped[c.bucket].push(c);
  return grouped;
}
