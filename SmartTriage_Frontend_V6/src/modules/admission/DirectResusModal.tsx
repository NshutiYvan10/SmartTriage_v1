/* ── DirectResusModal ─────────────────────────────────────────────────
 *
 * Phase H (V28) — the Direct Resus Admission flow surfaced on the
 * entry/registration screen as the "Unstable patient" path.
 *
 * One-click semantics: the nurse types a one-line clinical reason
 * ("cardiac arrest", "GSW to chest"), confirms pediatric/ambulance/
 * gender flags if they apply, and submits. The patient lands in a
 * RESUS bed (or overflow, with a transfer prompt), the team is
 * alerted, and clinical care begins. Identity, vitals, full triage —
 * all back-filled later.
 *
 * On success this modal navigates the caller to the resulting visit
 * page so the resus team has the chart open immediately.
 */
import { useState } from 'react';
import {
  AlertTriangle, Truck, BedDouble, Heart, Loader2, Siren, X,
} from 'lucide-react';
import { directResusApi } from '@/api/directResus';
import type {
  ArrivalMode,
  DirectResusAdmissionRequest,
  DirectResusAdmissionResponse,
} from '@/api/types';

interface Props {
  hospitalId: string;
  /**
   * Optional pre-known patient. When set, the modal admits this
   * existing patient (their chart will simply gain a new RED visit).
   * When null, the modal creates an unidentified placeholder.
   */
  knownPatientId?: string | null;
  knownPatientName?: string | null;
  /** Initial pediatric flag (typically derived from the patient if known). */
  initialIsPediatric?: boolean;
  onClose: () => void;
  onSuccess: (response: DirectResusAdmissionResponse) => void;
}

export function DirectResusModal({
  hospitalId,
  knownPatientId,
  knownPatientName,
  initialIsPediatric = false,
  onClose,
  onSuccess,
}: Props) {
  const [reason, setReason] = useState('');
  const [isPediatric, setIsPediatric] = useState(initialIsPediatric);
  const [ambulancePreArrival, setTruckPreArrival] = useState(false);
  const [arrivalMode, setArrivalMode] = useState<ArrivalMode | ''>('');
  const [estimatedGender, setEstimatedGender] = useState<'MALE' | 'FEMALE' | ''>('');
  const [preArrivalNotes, setPreArrivalNotes] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const isUnidentified = !knownPatientId;
  const canSubmit = reason.trim().length >= 2 && !submitting;

  const handleSubmit = async () => {
    if (!canSubmit) return;
    setSubmitting(true);
    setError(null);
    try {
      const payload: DirectResusAdmissionRequest = {
        patientId: knownPatientId ?? null,
        hospitalId: knownPatientId ? null : hospitalId,
        reason: reason.trim(),
        isPediatric,
        ambulancePreArrival,
        arrivalMode: arrivalMode || (ambulancePreArrival ? 'AMBULANCE' : 'WALK_IN'),
        preArrivalNotes: preArrivalNotes.trim() || null,
        estimatedGender: isUnidentified && estimatedGender ? estimatedGender : null,
      };
      const response = await directResusApi.admit(payload);
      onSuccess(response);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Direct Resus admission failed. Please try again.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4"
      onClick={submitting ? undefined : onClose}
    >
      <div
        className="w-full max-w-lg rounded-2xl shadow-2xl overflow-hidden bg-white border border-rose-200"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header — emergency red, signals seriousness */}
        <div className="bg-gradient-to-r from-rose-600 to-red-700 px-5 py-4 text-white">
          <div className="flex items-start justify-between gap-3">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-white/20 flex items-center justify-center flex-shrink-0">
                <Siren className="w-5 h-5" />
              </div>
              <div>
                <h2 className="text-base font-bold">Direct Resus Admission</h2>
                <p className="text-[11px] text-white/80">
                  {isUnidentified
                    ? 'Unidentified patient — system will assign a phonetic placeholder.'
                    : `Patient: ${knownPatientName ?? 'existing record'}`}
                </p>
              </div>
            </div>
            <button
              onClick={onClose}
              disabled={submitting}
              className="w-8 h-8 rounded-lg bg-white/15 flex items-center justify-center hover:bg-white/25 disabled:opacity-50 flex-shrink-0"
              aria-label="Close"
            >
              <X className="w-4 h-4" />
            </button>
          </div>
        </div>

        {/* Body */}
        <div className="p-5 space-y-4">
          {/* Reason — required, short clinical phrase */}
          <div>
            <label className="block text-[11px] font-bold uppercase tracking-wider text-slate-500 mb-1.5">
              Clinical reason <span className="text-rose-600">*</span>
            </label>
            <input
              autoFocus
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="e.g. cardiac arrest, GSW to chest, severe airway compromise"
              maxLength={500}
              className="w-full px-3 py-2.5 rounded-lg text-sm outline-none border border-slate-300 bg-slate-50 focus:bg-white focus:border-rose-500 focus:ring-2 focus:ring-rose-100"
            />
            <p className="text-[10px] text-slate-500 mt-1">
              One short clinical phrase. This becomes the admission's audit anchor.
            </p>
          </div>

          {/* Toggles — pediatric + ambulance pre-arrival */}
          <div className="grid grid-cols-2 gap-3">
            <label className={`flex items-center gap-2 p-3 rounded-lg border cursor-pointer transition-colors ${
              isPediatric ? 'bg-cyan-50 border-cyan-300' : 'bg-slate-50 border-slate-200 hover:border-slate-300'
            }`}>
              <input
                type="checkbox"
                checked={isPediatric}
                onChange={(e) => setIsPediatric(e.target.checked)}
                className="w-4 h-4 accent-cyan-600"
              />
              <div>
                <div className="flex items-center gap-1.5 text-xs font-bold text-slate-800">
                  <Heart className="w-3 h-3 text-cyan-600" /> Pediatric (under 13)
                </div>
                <p className="text-[10px] text-slate-500 leading-tight mt-0.5">
                  Triggers pediatric kit prep
                </p>
              </div>
            </label>

            <label className={`flex items-center gap-2 p-3 rounded-lg border cursor-pointer transition-colors ${
              ambulancePreArrival ? 'bg-amber-50 border-amber-300' : 'bg-slate-50 border-slate-200 hover:border-slate-300'
            }`}>
              <input
                type="checkbox"
                checked={ambulancePreArrival}
                onChange={(e) => setTruckPreArrival(e.target.checked)}
                className="w-4 h-4 accent-amber-600"
              />
              <div>
                <div className="flex items-center gap-1.5 text-xs font-bold text-slate-800">
                  <Truck className="w-3 h-3 text-amber-600" /> Truck call-ahead
                </div>
                <p className="text-[10px] text-slate-500 leading-tight mt-0.5">
                  Patient inbound, not yet arrived
                </p>
              </div>
            </label>
          </div>

          {/* Estimated gender — only relevant for unidentified */}
          {isUnidentified && (
            <div>
              <label className="block text-[11px] font-bold uppercase tracking-wider text-slate-500 mb-1.5">
                Apparent gender <span className="text-slate-400 font-normal">(optional, for med-dosing)</span>
              </label>
              <div className="grid grid-cols-2 gap-2">
                {(['MALE', 'FEMALE'] as const).map((g) => (
                  <button
                    key={g}
                    type="button"
                    onClick={() => setEstimatedGender(estimatedGender === g ? '' : g)}
                    className={`px-3 py-2 rounded-lg text-xs font-bold border transition-colors ${
                      estimatedGender === g
                        ? 'bg-slate-800 text-white border-slate-800'
                        : 'bg-white text-slate-600 border-slate-200 hover:border-slate-400'
                    }`}
                  >
                    {g}
                  </button>
                ))}
              </div>
            </div>
          )}

          {/* Pre-arrival notes — only if ambulance call-ahead */}
          {ambulancePreArrival && (
            <div>
              <label className="block text-[11px] font-bold uppercase tracking-wider text-slate-500 mb-1.5">
                Pre-hospital notes <span className="text-slate-400 font-normal">(optional)</span>
              </label>
              <textarea
                value={preArrivalNotes}
                onChange={(e) => setPreArrivalNotes(e.target.value)}
                rows={2}
                maxLength={2000}
                placeholder="e.g. ETA 5 min, intubated en route, adrenaline x1"
                className="w-full px-3 py-2 rounded-lg text-sm outline-none border border-slate-300 bg-slate-50 focus:bg-white focus:border-amber-500 focus:ring-2 focus:ring-amber-100"
              />
            </div>
          )}

          {/* What happens next — set expectations */}
          <div className="rounded-lg bg-slate-50 border border-slate-200 p-3">
            <p className="text-[11px] font-bold text-slate-700 mb-1">What happens on submit</p>
            <ul className="text-[10px] text-slate-600 space-y-0.5 leading-relaxed">
              <li>• Patient placed in RESUS bed with monitor (if available)</li>
              <li>• Auto-RED triage record created — back-fill clinical detail later</li>
              <li>• Resus team alerted on /alerts/{`{hospital}/RESUS`} (CRITICAL)</li>
              {isUnidentified && (
                <li>• A phonetic placeholder name is assigned ("Unknown Alpha"); resolve identity from the chart</li>
              )}
              {ambulancePreArrival && (
                <li>• Door clock does <em>not</em> start — confirm arrival when patient walks in</li>
              )}
            </ul>
          </div>

          {/* Error */}
          {error && (
            <div className="rounded-lg p-3 flex items-start gap-2 bg-rose-50 border border-rose-200">
              <AlertTriangle className="w-4 h-4 mt-0.5 flex-shrink-0 text-rose-600" />
              <div>
                <p className="text-xs font-bold text-rose-800">Admission failed</p>
                <p className="text-[10px] text-rose-700 mt-0.5">{error}</p>
              </div>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-end gap-2 px-5 py-3 border-t border-slate-200 bg-slate-50/60">
          <button
            onClick={onClose}
            disabled={submitting}
            className="px-3 py-2 rounded-lg text-xs font-bold text-slate-600 hover:bg-slate-100 disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            onClick={handleSubmit}
            disabled={!canSubmit}
            className={`inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-xs font-bold text-white shadow-md
              ${canSubmit ? 'bg-gradient-to-r from-rose-600 to-red-700 hover:from-rose-500 hover:to-red-600' : 'bg-slate-400 cursor-not-allowed'}
            `}
          >
            {submitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <BedDouble className="w-3.5 h-3.5" />}
            {submitting
              ? 'Admitting…'
              : ambulancePreArrival
                ? 'Reserve resus & alert team'
                : 'Admit to Resus now'}
          </button>
        </div>
      </div>
    </div>
  );
}
