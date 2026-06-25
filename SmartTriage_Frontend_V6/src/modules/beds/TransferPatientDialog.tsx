/* ── TransferPatientDialog ────────────────────────────────────────────
 *
 * Move an already-placed patient from one bed to another. Typical use
 * case: clinical deterioration pushes a patient from Acute into Resus,
 * or a ventilator becomes available in a different bed.
 *
 * Server guarantees atomicity:
 *   - Source bed: currentVisit cleared, status → CLEANING
 *   - Destination bed: currentVisit ← visit, status → OCCUPIED
 *   - Source device session closed; destination device session opened
 *     if the destination bed has an assigned monitor
 */
import { useEffect, useState } from 'react';
import { X } from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { useBedStore } from '@/store/bedStore';
import { bedsApi } from '@/api/beds';
import type { BedResponse, EdZone } from '@/api/types';
import { useTheme } from '@/hooks/useTheme';

interface TransferPatientDialogProps {
  open: boolean;
  sourceBed: BedResponse;
  onClose: () => void;
  onTransferred?: (destBed: BedResponse) => void;
}

const ZONES_FOR_TRANSFER: EdZone[] = [
  'RESUS',
  'ACUTE',
  'PEDIATRIC',
  'ISOLATION',
  'OBSERVATION',
];

export function TransferPatientDialog({
  open,
  sourceBed,
  onClose,
  onTransferred,
}: TransferPatientDialogProps) {
  const { isDark, glassCard } = useTheme();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  const { transferPatient } = useBedStore();

  const [targetZone, setTargetZone] = useState<EdZone>(sourceBed.zone);
  const [availableBeds, setAvailableBeds] = useState<BedResponse[]>([]);
  const [selectedBedId, setSelectedBedId] = useState<string | null>(null);
  const [reason, setReason] = useState('');
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setError(null);
    setSubmitting(false);
    setSelectedBedId(null);
    setReason('');
    setTargetZone(sourceBed.zone);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  useEffect(() => {
    if (!open || !hospitalId) return;
    setLoading(true);
    bedsApi.getAvailableInZone(hospitalId, targetZone)
      .then((beds) => {
        // Exclude the source bed itself (it's not AVAILABLE anyway, but defensive)
        setAvailableBeds(beds.filter((b) => b.id !== sourceBed.id));
      })
      .catch((e) => {
        console.error('[TransferPatientDialog] load available', e);
        setError('Failed to load destination beds.');
      })
      .finally(() => setLoading(false));
  }, [open, hospitalId, targetZone, sourceBed.id]);

  const canSubmit = !!selectedBedId && !submitting;

  async function handleSubmit() {
    if (!selectedBedId) return;
    setSubmitting(true);
    setError(null);
    try {
      const dest = await transferPatient(sourceBed.id, {
        destinationBedId: selectedBedId,
        reason: reason.trim() || undefined,
      });
      onTransferred?.(dest);
      onClose();
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'Transfer failed';
      setError(msg);
    } finally {
      setSubmitting(false);
    }
  }

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm"
      style={{ background: 'rgba(2,6,23,0.65)' }}
    >
      <div
        className="w-full max-w-lg rounded-2xl overflow-hidden shadow-2xl animate-scale-in"
        style={glassCard}
      >
        <div className={`flex items-start justify-between border-b px-5 py-3 ${isDark ? 'border-slate-700' : 'border-slate-200'}`}>
          <div>
            <h3 className={`text-base font-semibold ${isDark ? 'text-slate-100' : 'text-slate-900'}`}>
              Transfer patient from {sourceBed.code}
            </h3>
            {sourceBed.currentPatientName && (
              <p className={`text-xs ${isDark ? 'text-slate-400' : 'text-slate-500'}`}>
                {sourceBed.currentPatientName}
                {sourceBed.currentVisitNumber ? ` · ${sourceBed.currentVisitNumber}` : ''}
              </p>
            )}
          </div>
          <button
            onClick={onClose}
            className={`leading-none ${isDark ? 'text-slate-400 hover:text-slate-100' : 'text-slate-500 hover:text-slate-900'}`}
            aria-label="Close"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="space-y-4 px-5 py-4">
          {error && (
            <div className={`rounded-md border px-3 py-2 text-sm ${isDark ? 'border-rose-700/50 bg-rose-900/30 text-rose-200' : 'border-rose-300 bg-rose-50 text-rose-800'}`}>
              {error}
            </div>
          )}

          <div>
            <label className={`mb-1 block text-xs font-medium uppercase tracking-wide ${isDark ? 'text-slate-400' : 'text-slate-500'}`}>
              Target zone
            </label>
            <div className="flex flex-wrap gap-1.5">
              {ZONES_FOR_TRANSFER.map((z) => (
                <button
                  key={z}
                  type="button"
                  onClick={() => setTargetZone(z)}
                  className={`rounded-full border px-2.5 py-1 text-xs font-medium transition ${
                    z === targetZone
                      ? isDark ? 'border-cyan-400 bg-cyan-900/40 text-cyan-100' : 'border-cyan-500 bg-cyan-50 text-cyan-700'
                      : isDark ? 'border-slate-700 text-slate-300 hover:border-slate-500' : 'border-slate-300 text-slate-700 hover:border-slate-400'
                  }`}
                >
                  {z}
                </button>
              ))}
            </div>
          </div>

          <div>
            <label className={`mb-1 block text-xs font-medium uppercase tracking-wide ${isDark ? 'text-slate-400' : 'text-slate-500'}`}>
              Destination bed
            </label>
            {loading ? (
              <div className={`rounded-md border px-3 py-6 text-center text-sm ${isDark ? 'border-slate-700 text-slate-400' : 'border-slate-200 text-slate-500'}`}>
                Loading available beds…
              </div>
            ) : availableBeds.length === 0 ? (
              <div className={`rounded-md border border-dashed px-3 py-6 text-center text-sm ${isDark ? 'border-slate-700 text-slate-400' : 'border-slate-200 text-slate-500'}`}>
                No available beds in {targetZone}.
              </div>
            ) : (
              <ul className={`grid max-h-52 grid-cols-2 gap-2 overflow-y-auto rounded-md border p-2 sm:grid-cols-3 ${isDark ? 'border-slate-700' : 'border-slate-200'}`}>
                {availableBeds.map((b) => {
                  const isSelected = b.id === selectedBedId;
                  return (
                    <li key={b.id}>
                      <button
                        type="button"
                        onClick={() => setSelectedBedId(b.id)}
                        className={`flex w-full flex-col items-start gap-0.5 rounded-md border px-3 py-2 text-left ${
                          isSelected
                            ? isDark ? 'border-cyan-400 bg-cyan-900/40' : 'border-cyan-500 bg-cyan-50'
                            : isDark ? 'border-slate-700 hover:border-slate-500' : 'border-slate-200 hover:border-slate-300'
                        }`}
                      >
                        <span className={`text-sm font-bold ${isDark ? 'text-slate-100' : 'text-slate-900'}`}>
                          {b.code}
                        </span>
                        {b.label && (
                          <span className={`text-[11px] line-clamp-1 ${isDark ? 'text-slate-400' : 'text-slate-500'}`}>
                            {b.label}
                          </span>
                        )}
                        <span className={`text-[10px] ${b.hasMonitor ? (isDark ? 'text-emerald-300' : 'text-emerald-600') : (isDark ? 'text-slate-400' : 'text-slate-400')}`}>
                          {b.hasMonitor ? '📟 monitor' : 'no monitor'}
                        </span>
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>

          <div>
            <label className={`mb-1 block text-xs font-medium uppercase tracking-wide ${isDark ? 'text-slate-400' : 'text-slate-500'}`}>
              Reason (optional)
            </label>
            <textarea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              rows={2}
              placeholder="e.g. TEWS escalation → Resus"
              className={`w-full rounded-md border px-3 py-2 text-sm outline-none transition ${
                isDark
                  ? 'border-slate-700 bg-slate-800 text-slate-100 placeholder:text-slate-500 focus:border-cyan-500'
                  : 'border-slate-300 bg-white text-slate-900 placeholder:text-slate-400 focus:border-cyan-500'
              }`}
            />
          </div>
        </div>

        <div className={`flex justify-end gap-2 border-t px-5 py-3 ${isDark ? 'border-slate-700' : 'border-slate-200'}`}>
          <button
            className={`rounded-xl px-3 py-1.5 text-sm font-medium ${isDark ? 'bg-slate-700 text-slate-200 hover:bg-slate-600' : 'bg-slate-100 text-slate-700 hover:bg-slate-200'}`}
            onClick={onClose}
            disabled={submitting}
          >
            Cancel
          </button>
          <button
            className={`rounded-xl px-3 py-1.5 text-sm font-semibold text-white ${
              canSubmit ? 'bg-cyan-600 hover:bg-cyan-700' : 'cursor-not-allowed bg-slate-400'
            }`}
            onClick={handleSubmit}
            disabled={!canSubmit}
          >
            {submitting ? 'Transferring…' : 'Transfer patient'}
          </button>
        </div>
      </div>
    </div>
  );
}
