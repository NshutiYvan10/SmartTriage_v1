/* ── BedActionSheet ───────────────────────────────────────────────────
 *
 * A contextual popover of the actions available for a single bed. What
 * appears depends on the bed's current status:
 *
 *   AVAILABLE      → "Place patient" (opens PlacePatientDialog bed-first)
 *   OCCUPIED       → "Transfer patient", "Discharge from bed"
 *   CLEANING       → "Mark clean" (housekeeping signal)
 *   OUT_OF_SERVICE → "Return to service"
 *
 * Admins additionally get an "Edit bed" entry that jumps to the admin page.
 */
import { useState } from 'react';
import { useAuthStore } from '@/store/authStore';
import { useBedStore } from '@/store/bedStore';
import type { BedResponse } from '@/api/types';
import { useTheme } from '@/hooks/useTheme';
import { Badge } from '@/components/ui/Badge';
import { PlacePatientDialog } from './PlacePatientDialog';
import { TransferPatientDialog } from './TransferPatientDialog';

interface BedActionSheetProps {
  bed: BedResponse;
  onClose: () => void;
  onActionComplete: () => void;
}

export function BedActionSheet({ bed, onClose, onActionComplete }: BedActionSheetProps) {
  const { isDark } = useTheme();
  const user = useAuthStore((s) => s.user);
  const isAdmin = user?.role === 'HOSPITAL_ADMIN' || user?.role === 'SUPER_ADMIN';

  const { dischargePatient, markCleaned, markAvailable } = useBedStore();

  const [showPlace, setShowPlace] = useState(false);
  const [showTransfer, setShowTransfer] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function run(fn: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    try {
      await fn();
      onActionComplete();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Action failed');
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="fixed inset-0 z-40 flex items-end justify-center bg-black/40 p-4 sm:items-center">
        <div
          className={`w-full max-w-md rounded-xl border shadow-2xl ${
            isDark ? 'border-slate-700 bg-slate-900' : 'border-slate-200 bg-white'
          }`}
        >
          {/* Header */}
          <div className={`flex items-start justify-between border-b px-5 py-3 ${isDark ? 'border-slate-700' : 'border-slate-200'}`}>
            <div>
              <div className={`text-lg font-bold ${isDark ? 'text-slate-100' : 'text-slate-900'}`}>
                {bed.code}
                <span className={`ml-2 text-sm font-normal ${isDark ? 'text-slate-400' : 'text-slate-500'}`}>
                  {bed.zone}
                </span>
              </div>
              {bed.label && (
                <div className={`text-xs ${isDark ? 'text-slate-400' : 'text-slate-500'}`}>{bed.label}</div>
              )}
            </div>
            <button
              onClick={onClose}
              className={`text-xl leading-none ${isDark ? 'text-slate-400 hover:text-slate-100' : 'text-slate-500 hover:text-slate-900'}`}
              aria-label="Close"
            >
              ×
            </button>
          </div>

          {/* Body */}
          <div className="space-y-3 px-5 py-4">
            {error && (
              <div className={`rounded-md border px-3 py-2 text-sm ${isDark ? 'border-rose-700/50 bg-rose-900/30 text-rose-200' : 'border-rose-300 bg-rose-50 text-rose-800'}`}>
                {error}
              </div>
            )}

            {bed.status === 'OCCUPIED' && (
              <div className={`rounded-md border p-3 ${isDark ? 'border-slate-700 bg-slate-800/60' : 'border-slate-200 bg-slate-50'}`}>
                <div className={`text-xs uppercase tracking-wide ${isDark ? 'text-slate-500' : 'text-slate-500'}`}>
                  Current patient
                </div>
                <div className={`mt-1 text-sm font-semibold ${isDark ? 'text-slate-100' : 'text-slate-900'}`}>
                  {bed.currentPatientName || '—'}
                </div>
                <div className="mt-1 flex items-center gap-2">
                  {bed.currentVisitNumber && (
                    <span className={`text-xs ${isDark ? 'text-slate-400' : 'text-slate-500'}`}>
                      {bed.currentVisitNumber}
                    </span>
                  )}
                  {bed.currentTriageCategory && (
                    <Badge category={bed.currentTriageCategory as any} size="sm" />
                  )}
                  {bed.currentTewsScore != null && (
                    <span className={`text-xs font-medium ${isDark ? 'text-slate-300' : 'text-slate-700'}`}>
                      TEWS {bed.currentTewsScore}
                    </span>
                  )}
                </div>
              </div>
            )}

            <div className={`rounded-md border p-3 ${isDark ? 'border-slate-700 bg-slate-800/40' : 'border-slate-200 bg-white'}`}>
              <div className={`text-xs uppercase tracking-wide ${isDark ? 'text-slate-500' : 'text-slate-500'}`}>
                Monitor
              </div>
              {bed.assignedDeviceId ? (
                <div className="mt-1 flex items-center gap-2 text-sm">
                  <span className={isDark ? 'text-slate-100' : 'text-slate-900'}>
                    {bed.assignedDeviceName}
                  </span>
                  {bed.assignedDeviceStatus && (
                    <span className={`rounded-full px-2 py-0.5 text-[10px] font-semibold ${
                      bed.assignedDeviceStatus === 'MONITORING'
                        ? isDark ? 'bg-emerald-900/40 text-emerald-300' : 'bg-emerald-100 text-emerald-800'
                        : bed.assignedDeviceStatus === 'ONLINE'
                          ? isDark ? 'bg-slate-700 text-slate-200' : 'bg-slate-200 text-slate-700'
                          : isDark ? 'bg-rose-900/40 text-rose-300' : 'bg-rose-100 text-rose-800'
                    }`}>
                      {bed.assignedDeviceStatus}
                    </span>
                  )}
                </div>
              ) : (
                <div className={`mt-1 text-xs italic ${isDark ? 'text-slate-400' : 'text-slate-500'}`}>
                  No monitor assigned — manual pairing required.
                </div>
              )}
            </div>

            {/* Actions */}
            <div className="grid gap-2">
              {bed.status === 'AVAILABLE' && (
                <PrimaryButton isDark={isDark} onClick={() => setShowPlace(true)} disabled={busy}>
                  Place patient here
                </PrimaryButton>
              )}

              {bed.status === 'OCCUPIED' && (
                <>
                  <PrimaryButton isDark={isDark} onClick={() => setShowTransfer(true)} disabled={busy}>
                    Transfer patient
                  </PrimaryButton>
                  <DangerButton
                    isDark={isDark}
                    disabled={busy}
                    onClick={() => {
                      if (confirm(`Discharge ${bed.currentPatientName || 'this patient'} from bed ${bed.code}?\n\nThe bed will move to CLEANING.`)) {
                        run(() => dischargePatient(bed.id));
                      }
                    }}
                  >
                    Discharge from bed
                  </DangerButton>
                </>
              )}

              {bed.status === 'CLEANING' && (
                <PrimaryButton isDark={isDark} onClick={() => run(() => markCleaned(bed.id))} disabled={busy}>
                  Mark bed clean (Available)
                </PrimaryButton>
              )}

              {bed.status === 'OUT_OF_SERVICE' && (
                <PrimaryButton isDark={isDark} onClick={() => run(() => markAvailable(bed.id))} disabled={busy}>
                  Return bed to service
                </PrimaryButton>
              )}

              {isAdmin && (
                <a
                  href="/admin/beds"
                  className={`rounded-md border px-3 py-1.5 text-center text-sm font-medium ${isDark ? 'border-slate-700 text-slate-300 hover:bg-slate-800' : 'border-slate-200 text-slate-600 hover:bg-slate-50'}`}
                >
                  Manage bed configuration →
                </a>
              )}
            </div>
          </div>
        </div>
      </div>

      <PlacePatientDialog
        open={showPlace}
        onClose={() => setShowPlace(false)}
        onPlaced={() => {
          setShowPlace(false);
          onActionComplete();
        }}
        mode={{ kind: 'bed-first', bed }}
      />

      {bed.status === 'OCCUPIED' && (
        <TransferPatientDialog
          open={showTransfer}
          sourceBed={bed}
          onClose={() => setShowTransfer(false)}
          onTransferred={() => {
            setShowTransfer(false);
            onActionComplete();
          }}
        />
      )}
    </>
  );
}

function PrimaryButton({
  children,
  onClick,
  disabled,
  isDark,
}: {
  children: React.ReactNode;
  onClick: () => void;
  disabled?: boolean;
  isDark: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={`rounded-md px-3 py-2 text-sm font-semibold text-white transition ${
        disabled
          ? 'cursor-not-allowed bg-slate-400'
          : isDark ? 'bg-cyan-500 hover:bg-cyan-400' : 'bg-cyan-600 hover:bg-cyan-500'
      }`}
    >
      {children}
    </button>
  );
}

function DangerButton({
  children,
  onClick,
  disabled,
  isDark,
}: {
  children: React.ReactNode;
  onClick: () => void;
  disabled?: boolean;
  isDark: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={`rounded-md border px-3 py-2 text-sm font-semibold transition ${
        disabled
          ? 'cursor-not-allowed opacity-50'
          : isDark ? 'border-rose-700 text-rose-300 hover:bg-rose-900/30' : 'border-rose-300 text-rose-700 hover:bg-rose-50'
      }`}
    >
      {children}
    </button>
  );
}
