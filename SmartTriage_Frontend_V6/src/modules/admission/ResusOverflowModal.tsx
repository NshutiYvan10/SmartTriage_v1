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
import { useTheme } from '@/hooks/useTheme';
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
  const { glassCard, glassInner, isDark, text } = useTheme();
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';

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
    <div
      className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm"
      style={{ background: 'rgba(2,6,23,0.65)' }}
      onClick={onClose}
    >
      <div
        style={glassCard}
        className="w-full max-w-xl rounded-2xl overflow-hidden shadow-2xl animate-scale-in"
        onClick={(e) => e.stopPropagation()}
      >
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
          <div className="rounded-lg bg-amber-500/20 border border-amber-500/30 p-3 flex items-start gap-2">
            <AlertTriangle className="w-4 h-4 mt-0.5 flex-shrink-0 text-amber-400" />
            <div>
              <p className="text-xs font-bold text-amber-300">Patient is admitted — care is not blocked</p>
              <p className="text-[10px] text-amber-300/90 mt-0.5 leading-relaxed">
                The new admission is on a stretcher in the resus area. The list below ranks current
                occupants by clinical readiness to move out. Pick someone, then transfer them to a
                step-down bed in the bed grid.
              </p>
            </div>
          </div>

          {/* Candidates list */}
          <div>
            <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted} mb-2`}>
              Suggested transfer-out candidates ({candidates.length})
            </p>

            {noCandidates ? (
              <div className="rounded-lg p-6 bg-rose-500/20 border border-rose-500/30 text-center">
                <p className="text-xs font-bold text-rose-300">All current resus occupants are still RED</p>
                <p className="text-[10px] text-rose-300/90 mt-1 leading-relaxed">
                  No safe transfer candidate exists. Escalate to the senior clinician on call —
                  this is an MCI-level decision.
                </p>
              </div>
            ) : (
              <div className="rounded-lg overflow-hidden max-h-72 overflow-y-auto" style={glassInner}>
                {candidates.map((c, idx) => {
                  const isSelected = selectedCandidate?.visitId === c.visitId;
                  const isReTriaged = c.rationale?.startsWith('Re-triaged');
                  return (
                    <button
                      key={c.visitId}
                      onClick={() => setSelectedCandidate(c)}
                      style={{ borderTop: idx === 0 ? undefined : borderStyle }}
                      className={`w-full text-left px-3 py-2.5 transition-colors ${
                        isSelected ? 'bg-emerald-500/20 border-l-4 border-l-emerald-500' : 'hover:bg-white/5'
                      }`}
                    >
                      <div className="flex items-start justify-between gap-3">
                        <div className="min-w-0 flex-1">
                          <div className="flex items-center gap-2">
                            <span className={`text-[10px] font-bold ${text.muted} font-mono`}>#{idx + 1}</span>
                            <p className={`text-sm font-bold ${text.heading} truncate`}>
                              {c.patientDisplayName}
                            </p>
                            <span className={`text-[9px] font-bold px-1.5 py-0.5 rounded border ${
                              c.currentCategory === 'RED' ? 'bg-rose-500/20 text-rose-300 border-rose-500/30' :
                              c.currentCategory === 'ORANGE' ? 'bg-orange-500/20 text-orange-300 border-orange-500/30' :
                              c.currentCategory === 'YELLOW' ? 'bg-yellow-500/20 text-yellow-300 border-yellow-500/30' :
                              'bg-emerald-500/20 text-emerald-300 border-emerald-500/30'
                            }`}>
                              {c.currentCategory}
                            </span>
                          </div>
                          <p className={`text-[10px] ${text.muted} mt-0.5`}>
                            {c.bedCode} · {c.visitNumber}
                          </p>
                          <p className={`text-[10px] mt-1 ${isReTriaged ? 'text-emerald-400 font-semibold' : text.body}`}>
                            {c.rationale}
                          </p>
                        </div>
                        <div className="text-right flex-shrink-0">
                          <div className={`flex items-center gap-1 text-[10px] ${text.muted}`}>
                            <Clock className="w-3 h-3" />
                            <span className="font-mono">{c.minutesInBed}m</span>
                          </div>
                          {c.suggestedDestinationZone && (
                            <p className="text-[9px] text-cyan-400 font-bold mt-1">
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
            <div className="rounded-lg p-3 flex items-start gap-2 bg-rose-500/20 border border-rose-500/30">
              <AlertTriangle className="w-4 h-4 mt-0.5 flex-shrink-0 text-rose-400" />
              <p className="text-[10px] text-rose-300">{error}</p>
            </div>
          )}

          {selectedCandidate && (
            <div className="rounded-lg bg-cyan-500/20 border border-cyan-500/30 p-3">
              <p className="text-[11px] font-bold text-cyan-300">
                Next step: transfer {selectedCandidate.patientDisplayName}
              </p>
              <p className="text-[10px] text-cyan-300/90 mt-0.5 leading-relaxed">
                Use the bed-grid view (bed {selectedCandidate.bedCode}) to pick the destination
                bed in {selectedCandidate.suggestedDestinationZone ?? 'a step-down zone'} and confirm transfer.
                Once that bed frees up here, place {newAdmissionPatientName} into it.
              </p>
            </div>
          )}
        </div>

        <div className="flex items-center justify-end gap-2 px-5 py-3" style={{ borderTop: borderStyle }}>
          <button
            onClick={onClose}
            disabled={transferring}
            className={`px-3 py-2 rounded-xl text-xs font-bold ${text.body} hover:bg-white/5 disabled:opacity-50`}
          >
            Handle later
          </button>
          <button
            onClick={handleAcknowledgeAndOpenBedGrid}
            disabled={!selectedCandidate || transferring}
            className={`inline-flex items-center gap-1.5 px-4 py-2 rounded-xl text-xs font-bold text-white ${
              selectedCandidate && !transferring
                ? 'bg-cyan-600 hover:bg-cyan-700'
                : 'bg-slate-500/40 cursor-not-allowed'
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
