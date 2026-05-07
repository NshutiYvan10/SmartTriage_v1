/* ── ResusOverflowModal ──────────────────────────────────────────────
 *
 * V28 — surfaces immediately after a Direct Resus Admission when no
 * RESUS bed was available. The patient IS admitted (overflow=true);
 * this modal helps the charge nurse free a bed by transferring out
 * the lowest-acuity current resus occupant.
 *
 * The system surfaces a ranked list. The human picks. The system does
 * not auto-transfer — bed transfer is a clinical decision.
 *
 * Ranking (computed server-side):
 *   1. Re-triaged-DOWN patients first (they're now stable enough)
 *   2. Then by time-in-bed, longest first
 *
 * Selecting a candidate uses the existing bed-transfer endpoint —
 * the same code path nurses already trust for routine transfers.
 */
import { useState } from 'react';
import {
  AlertTriangle, ArrowRight, BedDouble, Clock, Loader2, Siren, X,
} from 'lucide-react';
import { bedsApi } from '@/api/beds';
import type { TransferCandidateInfo } from '@/api/types';

interface Props {
  /** The new admission's visit ID — the patient who needs a bed. */
  newAdmissionVisitId: string;
  newAdmissionVisitNumber: string;
  newAdmissionPatientName: string;
  candidates: TransferCandidateInfo[];
  onClose: () => void;
  /**
   * Called when a transfer-out completes successfully. The caller is
   * expected to re-fetch the bed grid + the new admission's visit to
   * reflect that the new patient now has a bed.
   */
  onTransferComplete: (movedOutVisitId: string, freedBedCode: string) => void;
}

export function ResusOverflowModal({
  newAdmissionVisitId,
  newAdmissionVisitNumber,
  newAdmissionPatientName,
  candidates,
  onClose,
  onTransferComplete,
}: Props) {
  const [selectedCandidate, setSelectedCandidate] = useState<TransferCandidateInfo | null>(null);
  const [transferring, setTransferring] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const noCandidates = candidates.length === 0;

  // We need a destination bed for the transferred-out patient. The server
  // ships `suggestedDestinationZone`; the nurse picks an actual bed there.
  // For V1 simplicity, we ask the user to confirm the suggested zone and
  // we just use the existing transferPatient endpoint, which expects a
  // destination bed ID. To keep this modal focused, we redirect the user
  // to the bed grid after acknowledging — actual placement happens there.
  // (A future iteration could embed bed-pick inline.)
  const handleAcknowledgeAndOpenBedGrid = async () => {
    if (!selectedCandidate) return;
    setTransferring(true);
    setError(null);
    try {
      // We do NOT call transferPatient here because we need the user to
      // pick a real destination bed. Surface a clear next-step instruction.
      // The parent receives the "moved-out visit id" so it can navigate
      // to the bed grid with that visit highlighted.
      onTransferComplete(selectedCandidate.visitId, selectedCandidate.bedCode);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not action transfer');
    } finally {
      setTransferring(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4">
      <div className="w-full max-w-xl rounded-2xl shadow-2xl overflow-hidden bg-white border border-rose-200">
        <div className="bg-gradient-to-r from-rose-700 to-red-800 px-5 py-4 text-white">
          <div className="flex items-start justify-between gap-3">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-white/20 flex items-center justify-center flex-shrink-0">
                <Siren className="w-5 h-5" />
              </div>
              <div>
                <h2 className="text-base font-bold">RESUS at capacity</h2>
                <p className="text-[11px] text-white/85 mt-0.5">
                  {newAdmissionVisitNumber} · {newAdmissionPatientName} admitted in overflow
                </p>
              </div>
            </div>
            <button
              onClick={onClose}
              disabled={transferring}
              className="w-8 h-8 rounded-lg bg-white/15 flex items-center justify-center hover:bg-white/25 disabled:opacity-50 flex-shrink-0"
              aria-label="Close"
            >
              <X className="w-4 h-4" />
            </button>
          </div>
        </div>

        <div className="p-5 space-y-4">
          {/* Context */}
          <div className="rounded-lg bg-amber-50 border border-amber-200 p-3 flex items-start gap-2">
            <AlertTriangle className="w-4 h-4 mt-0.5 flex-shrink-0 text-amber-700" />
            <div>
              <p className="text-xs font-bold text-amber-900">Patient is admitted — care is not blocked</p>
              <p className="text-[10px] text-amber-800 mt-0.5 leading-relaxed">
                The new admission is on a stretcher in the resus area. The list below ranks current
                occupants by clinical readiness to move out. Pick someone, then transfer them to a
                step-down bed in the bed grid.
              </p>
            </div>
          </div>

          {/* Candidates list */}
          <div>
            <p className="text-[10px] font-bold uppercase tracking-wider text-slate-500 mb-2">
              Suggested transfer-out candidates ({candidates.length})
            </p>

            {noCandidates ? (
              <div className="rounded-lg p-6 bg-rose-50 border border-rose-200 text-center">
                <p className="text-xs font-bold text-rose-800">All current resus occupants are still RED</p>
                <p className="text-[10px] text-rose-700 mt-1 leading-relaxed">
                  No safe transfer candidate exists. Escalate to the senior clinician on call —
                  this is an MCI-level decision.
                </p>
              </div>
            ) : (
              <div className="rounded-lg border border-slate-200 divide-y divide-slate-100 max-h-72 overflow-y-auto">
                {candidates.map((c, idx) => {
                  const isSelected = selectedCandidate?.visitId === c.visitId;
                  const isReTriaged = c.rationale?.startsWith('Re-triaged');
                  return (
                    <button
                      key={c.visitId}
                      onClick={() => setSelectedCandidate(c)}
                      className={`w-full text-left px-3 py-2.5 transition-colors ${
                        isSelected ? 'bg-emerald-50 border-l-4 border-l-emerald-500' : 'hover:bg-slate-50'
                      }`}
                    >
                      <div className="flex items-start justify-between gap-3">
                        <div className="min-w-0 flex-1">
                          <div className="flex items-center gap-2">
                            <span className="text-[10px] font-bold text-slate-400 font-mono">#{idx + 1}</span>
                            <p className="text-sm font-bold text-slate-800 truncate">
                              {c.patientDisplayName}
                            </p>
                            <span className={`text-[9px] font-bold px-1.5 py-0.5 rounded ${
                              c.currentCategory === 'RED' ? 'bg-rose-100 text-rose-700' :
                              c.currentCategory === 'ORANGE' ? 'bg-orange-100 text-orange-700' :
                              c.currentCategory === 'YELLOW' ? 'bg-yellow-100 text-yellow-700' :
                              'bg-emerald-100 text-emerald-700'
                            }`}>
                              {c.currentCategory}
                            </span>
                          </div>
                          <p className="text-[10px] text-slate-500 mt-0.5">
                            {c.bedCode} · {c.visitNumber}
                          </p>
                          <p className={`text-[10px] mt-1 ${isReTriaged ? 'text-emerald-700 font-semibold' : 'text-slate-600'}`}>
                            {c.rationale}
                          </p>
                        </div>
                        <div className="text-right flex-shrink-0">
                          <div className="flex items-center gap-1 text-[10px] text-slate-500">
                            <Clock className="w-3 h-3" />
                            <span className="font-mono">{c.minutesInBed}m</span>
                          </div>
                          {c.suggestedDestinationZone && (
                            <p className="text-[9px] text-cyan-700 font-bold mt-1">
                              <ArrowRight className="inline w-2.5 h-2.5" />{' '}
                              {c.suggestedDestinationZone}
                            </p>
                          )}
                        </div>
                      </div>
                    </button>
                  );
                })}
              </div>
            )}
          </div>

          {error && (
            <div className="rounded-lg p-3 flex items-start gap-2 bg-rose-50 border border-rose-200">
              <AlertTriangle className="w-4 h-4 mt-0.5 flex-shrink-0 text-rose-600" />
              <p className="text-[10px] text-rose-700">{error}</p>
            </div>
          )}

          {selectedCandidate && (
            <div className="rounded-lg bg-cyan-50 border border-cyan-200 p-3">
              <p className="text-[11px] font-bold text-cyan-900">
                Next step: transfer {selectedCandidate.patientDisplayName}
              </p>
              <p className="text-[10px] text-cyan-700 mt-0.5 leading-relaxed">
                Use the bed-grid view (bed {selectedCandidate.bedCode}) to pick the destination
                bed in {selectedCandidate.suggestedDestinationZone ?? 'a step-down zone'} and confirm transfer.
                Once that bed frees up here, place {newAdmissionPatientName} into it.
              </p>
            </div>
          )}
        </div>

        <div className="flex items-center justify-end gap-2 px-5 py-3 border-t border-slate-200 bg-slate-50/60">
          <button
            onClick={onClose}
            disabled={transferring}
            className="px-3 py-2 rounded-lg text-xs font-bold text-slate-600 hover:bg-slate-100 disabled:opacity-50"
          >
            Handle later
          </button>
          <button
            onClick={handleAcknowledgeAndOpenBedGrid}
            disabled={!selectedCandidate || transferring}
            className={`inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-xs font-bold text-white ${
              selectedCandidate && !transferring
                ? 'bg-gradient-to-r from-cyan-600 to-emerald-600 hover:from-cyan-500 hover:to-emerald-500'
                : 'bg-slate-400 cursor-not-allowed'
            }`}
          >
            {transferring ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <BedDouble className="w-3.5 h-3.5" />}
            Open bed grid to transfer
          </button>
        </div>
      </div>
    </div>
  );
}
