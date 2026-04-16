/* ── BedTile ──────────────────────────────────────────────────────────
 *
 * A single bed card inside the BedGridView. Colour-coded by status:
 *   AVAILABLE      → subtle green, ready to accept a patient
 *   OCCUPIED       → tinted by the occupant's triage category (RED/ORANGE/…)
 *   CLEANING       → slate, not actionable until housekeeping signals clean
 *   OUT_OF_SERVICE → muted gray with a wrench icon
 *
 * The tile is clickable; callers handle the click via `onClick`.
 */
import { Badge } from '@/components/ui/Badge';
import { useTheme } from '@/hooks/useTheme';
import type { BedResponse } from '@/api/types';

interface BedTileProps {
  bed: BedResponse;
  onClick?: (bed: BedResponse) => void;
  selected?: boolean;
}

export function BedTile({ bed, onClick, selected }: BedTileProps) {
  const { isDark } = useTheme();

  const statusClass = getStatusClass(bed.status, isDark, !!bed.currentTriageCategory);
  const triageBadge = bed.currentTriageCategory as 'RED' | 'ORANGE' | 'YELLOW' | 'GREEN' | 'BLUE' | undefined;

  const selectionRing = selected
    ? isDark
      ? 'ring-2 ring-cyan-400'
      : 'ring-2 ring-cyan-500'
    : '';

  return (
    <button
      type="button"
      onClick={onClick ? () => onClick(bed) : undefined}
      className={`group relative flex h-44 flex-col items-stretch justify-between rounded-xl border p-3 text-left transition ${statusClass} ${selectionRing} ${onClick ? 'cursor-pointer hover:shadow-md' : 'cursor-default'}`}
      disabled={!onClick}
      aria-label={`Bed ${bed.code} — ${bed.status}`}
    >
      <div className="flex items-start justify-between">
        <div>
          <div className="text-base font-bold leading-tight">{bed.code}</div>
          {bed.label && (
            <div className="mt-0.5 text-[11px] opacity-70 line-clamp-1">{bed.label}</div>
          )}
        </div>
        <StatusPill status={bed.status} isDark={isDark} />
      </div>

      <div className="space-y-1.5">
        {bed.status === 'OCCUPIED' && bed.currentPatientName ? (
          <>
            <div className="text-sm font-semibold leading-tight line-clamp-2">
              {bed.currentPatientName}
            </div>
            <div className="flex items-center gap-1.5">
              {triageBadge && <Badge category={triageBadge} size="sm" />}
              {bed.currentTewsScore != null && (
                <span className={`text-[11px] font-medium ${isDark ? 'text-slate-300' : 'text-slate-700'}`}>
                  TEWS {bed.currentTewsScore}
                </span>
              )}
            </div>
          </>
        ) : (
          <div className={`text-xs italic ${isDark ? 'text-slate-400' : 'text-slate-500'}`}>
            {humanStatus(bed.status)}
          </div>
        )}
      </div>

      <div className="flex items-center justify-between text-[11px]">
        <span
          className={`flex items-center gap-1 ${bed.hasMonitor ? '' : 'opacity-40'}`}
          title={bed.hasMonitor ? 'Bed has an assigned monitor' : 'No monitor assigned'}
        >
          <span>{bed.hasMonitor ? '📟' : '—'}</span>
          {bed.assignedDeviceName ? (
            <span className="line-clamp-1 max-w-[110px]">{bed.assignedDeviceName}</span>
          ) : (
            <span className="opacity-70">no monitor</span>
          )}
        </span>
        {bed.activeSessionId && (
          <span className={`rounded-full px-1.5 py-0.5 text-[10px] font-semibold ${isDark ? 'bg-emerald-500/20 text-emerald-300' : 'bg-emerald-100 text-emerald-800'}`}>
            LIVE
          </span>
        )}
      </div>
    </button>
  );
}

function StatusPill({ status, isDark }: { status: BedResponse['status']; isDark: boolean }) {
  const map: Record<BedResponse['status'], { dark: string; light: string; label: string }> = {
    AVAILABLE: { dark: 'bg-emerald-500/20 text-emerald-300', light: 'bg-emerald-100 text-emerald-800', label: 'Available' },
    OCCUPIED: { dark: 'bg-slate-500/20 text-slate-200', light: 'bg-slate-200 text-slate-800', label: 'Occupied' },
    CLEANING: { dark: 'bg-amber-500/20 text-amber-200', light: 'bg-amber-100 text-amber-800', label: 'Cleaning' },
    OUT_OF_SERVICE: { dark: 'bg-rose-500/20 text-rose-200', light: 'bg-rose-100 text-rose-800', label: 'OOS' },
  };
  const m = map[status];
  return (
    <span className={`rounded-full px-2 py-0.5 text-[10px] font-semibold ${isDark ? m.dark : m.light}`}>
      {m.label}
    </span>
  );
}

function getStatusClass(status: BedResponse['status'], isDark: boolean, hasOccupant: boolean): string {
  if (status === 'OCCUPIED' && hasOccupant) {
    return isDark
      ? 'bg-slate-800/60 border-slate-600 text-slate-100'
      : 'bg-white border-slate-300 text-slate-800';
  }
  switch (status) {
    case 'AVAILABLE':
      return isDark
        ? 'bg-emerald-900/20 border-emerald-700/40 text-emerald-100'
        : 'bg-emerald-50 border-emerald-200 text-emerald-900';
    case 'CLEANING':
      return isDark
        ? 'bg-amber-900/20 border-amber-700/40 text-amber-100'
        : 'bg-amber-50 border-amber-200 text-amber-900';
    case 'OUT_OF_SERVICE':
      return isDark
        ? 'bg-rose-900/20 border-rose-700/40 text-rose-100'
        : 'bg-rose-50 border-rose-200 text-rose-900';
    default:
      return isDark
        ? 'bg-slate-800/50 border-slate-700 text-slate-200'
        : 'bg-white border-slate-200 text-slate-800';
  }
}

function humanStatus(status: BedResponse['status']): string {
  switch (status) {
    case 'AVAILABLE': return 'Ready for placement';
    case 'OCCUPIED': return 'Occupied';
    case 'CLEANING': return 'Awaiting cleaning';
    case 'OUT_OF_SERVICE': return 'Out of service';
  }
}
