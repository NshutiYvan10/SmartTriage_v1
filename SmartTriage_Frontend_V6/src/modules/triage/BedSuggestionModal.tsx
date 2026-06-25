/* ── BedSuggestionModal ───────────────────────────────────────────────
 *
 * Phase G #2 — confirm modal that surfaces the backend's bed
 * suggestion after a triage submit. Used by both AdultTriageForm and
 * PediatricTriageForm. The nurse keeps the final say: clicking confirm
 * places the patient via bedsApi.placePatient; skip dismisses without
 * action and lets the nurse use the bed grid manually.
 */
import { AlertTriangle, BedDouble, Loader2, Monitor, X } from 'lucide-react';
import type { EdZone } from '@/api/types';
import { useTheme } from '@/hooks/useTheme';

export interface BedSuggestion {
  id: string;
  code: string;
  zone: EdZone;
  hasMonitor: boolean;
}

interface Props {
  bed: BedSuggestion;
  category: string;             // RED / ORANGE / YELLOW / GREEN / BLUE
  placing: boolean;
  error: string | null;
  onConfirm: () => void;
  onCancel: () => void;
}

export function BedSuggestionModal({ bed, category, placing, error, onConfirm, onCancel }: Props) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
  const categoryColor =
    category === 'RED' ? 'bg-red-500/20 text-red-300 border border-red-500/30' :
    category === 'ORANGE' ? 'bg-orange-500/20 text-orange-300 border border-orange-500/30' :
    category === 'YELLOW' ? 'bg-yellow-500/20 text-yellow-300 border border-yellow-500/30' :
    'bg-slate-500/20 text-slate-300 border border-slate-500/30';

  return (
    <div
      className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm"
      style={{ background: 'rgba(2,6,23,0.65)' }}
      onClick={placing ? undefined : onCancel}
    >
      <div
        style={glassCard}
        className="w-full max-w-md rounded-2xl overflow-hidden shadow-2xl animate-scale-in"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="bg-gradient-to-r from-cyan-600 to-emerald-600 px-5 py-3 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <BedDouble className="w-4 h-4 text-white" />
            <h3 className="text-sm font-bold text-white">Place patient in suggested bed?</h3>
          </div>
          <button
            onClick={onCancel}
            disabled={placing}
            className="w-7 h-7 rounded-lg bg-white/15 flex items-center justify-center hover:bg-white/25 disabled:opacity-50"
          >
            <X className="w-3.5 h-3.5 text-white" />
          </button>
        </div>

        <div className="p-5 space-y-3">
          <div className="flex items-center gap-3">
            <div className="w-12 h-12 rounded-xl bg-gradient-to-br from-emerald-500 to-cyan-600 flex items-center justify-center shadow-sm">
              <span className="text-xs font-extrabold text-white leading-none">{bed.code}</span>
            </div>
            <div className="min-w-0">
              <p className={`text-sm font-bold ${text.heading}`}>Bed {bed.code}</p>
              <p className={`text-[11px] ${text.muted}`}>Zone: {bed.zone}</p>
            </div>
            <span className={`ml-auto text-[10px] font-bold px-2 py-1 rounded ${categoryColor}`}>{category}</span>
          </div>

          <div
            className={`rounded-lg p-3 flex items-start gap-2 ${bed.hasMonitor ? 'bg-cyan-500/20 border border-cyan-500/30' : ''}`}
            style={bed.hasMonitor ? undefined : glassInner}
          >
            <Monitor className={`w-3.5 h-3.5 mt-0.5 flex-shrink-0 ${bed.hasMonitor ? 'text-cyan-300' : text.muted}`} />
            <div>
              <p className={`text-[11px] font-bold ${bed.hasMonitor ? 'text-cyan-300' : text.body}`}>
                {bed.hasMonitor ? 'Monitor assigned' : 'No monitor assigned'}
              </p>
              <p className={`text-[10px] mt-0.5 ${bed.hasMonitor ? 'text-cyan-200' : text.muted}`}>
                {bed.hasMonitor
                  ? 'Vitals will start streaming to this patient automatically.'
                  : 'You can attach a monitor manually after placement.'}
              </p>
            </div>
          </div>

          {error && (
            <div className="rounded-lg p-3 flex items-start gap-2 bg-rose-500/20 border border-rose-500/30">
              <AlertTriangle className="w-3.5 h-3.5 mt-0.5 flex-shrink-0 text-rose-400" />
              <div>
                <p className="text-[11px] font-bold text-rose-300">Could not place patient</p>
                <p className="text-[10px] text-rose-200 mt-0.5">{error}</p>
              </div>
            </div>
          )}

          <p className={`text-[10px] leading-relaxed ${text.muted}`}>
            This is a suggestion only — the patient is not yet placed. Click
            confirm to place them in this bed, or skip to handle placement
            manually from the bed grid.
          </p>
        </div>

        <div className="flex items-center justify-end gap-2 px-5 py-3" style={{ borderTop: borderStyle }}>
          <button
            onClick={onCancel}
            disabled={placing}
            className={`px-3 py-1.5 rounded-xl text-xs font-bold hover:bg-white/5 disabled:opacity-50 ${text.body}`}
          >
            Skip — place manually
          </button>
          <button
            onClick={onConfirm}
            disabled={placing}
            className="inline-flex items-center gap-1.5 px-4 py-1.5 rounded-xl text-xs font-bold text-white bg-cyan-600 hover:bg-cyan-700 disabled:opacity-60"
          >
            {placing ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <BedDouble className="w-3.5 h-3.5" />}
            {placing ? 'Placing…' : `Place in ${bed.code}`}
          </button>
        </div>
      </div>
    </div>
  );
}
