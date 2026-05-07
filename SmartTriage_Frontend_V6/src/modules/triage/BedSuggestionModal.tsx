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
  const categoryColor =
    category === 'RED' ? 'bg-red-100 text-red-700' :
    category === 'ORANGE' ? 'bg-orange-100 text-orange-700' :
    category === 'YELLOW' ? 'bg-yellow-100 text-yellow-700' :
    'bg-slate-100 text-slate-700';

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm p-4"
      onClick={placing ? undefined : onCancel}
    >
      <div
        className="w-full max-w-md rounded-2xl shadow-2xl overflow-hidden bg-white border border-slate-200"
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
              <p className="text-sm font-bold text-slate-900">Bed {bed.code}</p>
              <p className="text-[11px] text-slate-500">Zone: {bed.zone}</p>
            </div>
            <span className={`ml-auto text-[10px] font-bold px-2 py-1 rounded ${categoryColor}`}>{category}</span>
          </div>

          <div className={`rounded-lg p-3 flex items-start gap-2 ${bed.hasMonitor ? 'bg-cyan-50 border border-cyan-200' : 'bg-slate-50 border border-slate-200'}`}>
            <Monitor className={`w-3.5 h-3.5 mt-0.5 flex-shrink-0 ${bed.hasMonitor ? 'text-cyan-600' : 'text-slate-400'}`} />
            <div>
              <p className={`text-[11px] font-bold ${bed.hasMonitor ? 'text-cyan-800' : 'text-slate-600'}`}>
                {bed.hasMonitor ? 'Monitor assigned' : 'No monitor assigned'}
              </p>
              <p className={`text-[10px] mt-0.5 ${bed.hasMonitor ? 'text-cyan-700' : 'text-slate-500'}`}>
                {bed.hasMonitor
                  ? 'Vitals will start streaming to this patient automatically.'
                  : 'You can attach a monitor manually after placement.'}
              </p>
            </div>
          </div>

          {error && (
            <div className="rounded-lg p-3 flex items-start gap-2 bg-rose-50 border border-rose-200">
              <AlertTriangle className="w-3.5 h-3.5 mt-0.5 flex-shrink-0 text-rose-600" />
              <div>
                <p className="text-[11px] font-bold text-rose-800">Could not place patient</p>
                <p className="text-[10px] text-rose-700 mt-0.5">{error}</p>
              </div>
            </div>
          )}

          <p className="text-[10px] text-slate-500 leading-relaxed">
            This is a suggestion only — the patient is not yet placed. Click
            confirm to place them in this bed, or skip to handle placement
            manually from the bed grid.
          </p>
        </div>

        <div className="flex items-center justify-end gap-2 px-5 py-3 border-t border-slate-200 bg-slate-50/60">
          <button
            onClick={onCancel}
            disabled={placing}
            className="px-3 py-1.5 rounded-lg text-xs font-bold text-slate-600 hover:bg-slate-100 disabled:opacity-50"
          >
            Skip — place manually
          </button>
          <button
            onClick={onConfirm}
            disabled={placing}
            className="inline-flex items-center gap-1.5 px-4 py-1.5 rounded-lg text-xs font-bold text-white bg-gradient-to-r from-cyan-600 to-emerald-600 hover:from-cyan-500 hover:to-emerald-500 disabled:opacity-60"
          >
            {placing ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <BedDouble className="w-3.5 h-3.5" />}
            {placing ? 'Placing…' : `Place in ${bed.code}`}
          </button>
        </div>
      </div>
    </div>
  );
}
