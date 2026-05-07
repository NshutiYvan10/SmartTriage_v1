/* ── UnidentifiedBadge ───────────────────────────────────────────────
 *
 * Visual treatment for an unidentified Direct Resus patient. Drops in
 * next to the patient name anywhere a patient is rendered (bed grid,
 * visit list, alerts dashboard, chart header).
 *
 * Renders three things, depending on how much context the surface has
 * room for:
 *   - A "?" icon  (always — the at-a-glance cue)
 *   - "Unidentified" label (when `showLabel`)
 *   - A live "12 min" age pill (when `showAge` and the placeholder
 *     has a placeholderAssignedAt timestamp)
 *
 * Color tone escalates with elapsed time:
 *   - <30 min  → slate (informational)
 *   - 30-119 m → amber (soft prompt — match the 30-min UI cue)
 *   - >=120 m  → rose  (hard alert — matches the IDENTITY_UNRESOLVED
 *                       backend alert that fires here)
 */
import { HelpCircle } from 'lucide-react';
import { useEffect, useState } from 'react';
import {
  identityOverdueTier,
  minutesSincePlaceholderAssigned,
  type IdentityOverdueTier,
} from './displayName';
import type { PatientResponse } from '@/api/types';

interface Props {
  patient: Pick<PatientResponse, 'isUnidentified' | 'placeholderAssignedAt'> | null | undefined;
  showLabel?: boolean;
  showAge?: boolean;
  /** Override the auto-tick clock — useful for storybook / static screenshots. */
  freezeMinutesOverride?: number | null;
  size?: 'xs' | 'sm';
}

export function UnidentifiedBadge({
  patient,
  showLabel = false,
  showAge = false,
  freezeMinutesOverride,
  size = 'sm',
}: Props) {
  // Live tick: re-compute the elapsed minutes once a minute so the
  // "12 min" ages on screen without a refetch.
  const [, setTick] = useState(0);
  useEffect(() => {
    if (!patient?.isUnidentified || !showAge) return;
    const id = setInterval(() => setTick((n) => n + 1), 60_000);
    return () => clearInterval(id);
  }, [patient?.isUnidentified, showAge]);

  if (!patient?.isUnidentified) return null;

  const minutes = freezeMinutesOverride ?? minutesSincePlaceholderAssigned(patient);
  const tier = identityOverdueTier(minutes);
  const styles = TIER_STYLES[tier];
  const iconSize = size === 'xs' ? 'w-3 h-3' : 'w-3.5 h-3.5';

  return (
    <span
      className={`inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-bold ${styles.bg} ${styles.text} ${styles.border}`}
      title={
        minutes != null
          ? `Unidentified — placeholder assigned ${minutes} min ago. Resolve identity from the patient chart.`
          : 'Unidentified patient. Resolve identity from the patient chart.'
      }
    >
      <HelpCircle className={iconSize} />
      {showLabel && <span className="uppercase tracking-wider">Unidentified</span>}
      {showAge && minutes != null && (
        <span className="font-mono">{formatMinutes(minutes)}</span>
      )}
    </span>
  );
}

function formatMinutes(m: number): string {
  if (m < 60) return `${m}m`;
  const hours = Math.floor(m / 60);
  const remaining = m % 60;
  return remaining === 0 ? `${hours}h` : `${hours}h ${remaining}m`;
}

const TIER_STYLES: Record<IdentityOverdueTier, { bg: string; text: string; border: string }> = {
  none: { bg: 'bg-slate-100',  text: 'text-slate-600', border: 'border border-slate-200' },
  soft: { bg: 'bg-amber-100',  text: 'text-amber-800', border: 'border border-amber-300' },
  hard: { bg: 'bg-rose-100',   text: 'text-rose-800',  border: 'border border-rose-300' },
};
