/* ── PlacePatientDialog ───────────────────────────────────────────────
 *
 * Two modes, driven by props:
 *  - mode="bed-first":  a bed is pre-selected; nurse picks which triaged
 *                        patient to place.
 *  - mode="patient-first": a patient (visit) is pre-selected; nurse picks
 *                        which available bed in the patient's target zone.
 *
 * Auto-opens a DeviceSession when the selected bed has an assigned
 * monitor — the server handles that side-effect.
 */
import { useEffect, useMemo, useState } from 'react';
import { useAuthStore } from '@/store/authStore';
import { useBedStore } from '@/store/bedStore';
import { visitApi } from '@/api/visits';
import type {
  BedResponse,
  EdZone,
  VisitResponse,
  VisitStatus,
} from '@/api/types';
import { Badge } from '@/components/ui/Badge';
import { useTheme } from '@/hooks/useTheme';

type Mode =
  | { kind: 'bed-first'; bed: BedResponse }
  | { kind: 'patient-first'; visit: VisitResponse };

interface PlacePatientDialogProps {
  open: boolean;
  onClose: () => void;
  onPlaced?: (bed: BedResponse) => void;
  mode: Mode;
}

/** Triage categories that ED patients eligible for bed placement usually carry. */
const PLACEABLE_STATUSES: VisitStatus[] = [
  'TRIAGED',
  'AWAITING_ASSESSMENT',
  'UNDER_ASSESSMENT',
  'UNDER_TREATMENT',
];

export function PlacePatientDialog({ open, onClose, onPlaced, mode }: PlacePatientDialogProps) {
  const { isDark } = useTheme();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  const { loadZone, placePatient } = useBedStore();

  const [patients, setPatients] = useState<VisitResponse[]>([]);
  const [beds, setBeds] = useState<BedResponse[]>([]);
  const [selectedVisitId, setSelectedVisitId] = useState<string | null>(null);
  const [selectedBedId, setSelectedBedId] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // ── Initial prefill when dialog opens ──
  useEffect(() => {
    if (!open) return;
    setError(null);
    setSubmitting(false);

    if (mode.kind === 'bed-first') {
      setSelectedBedId(mode.bed.id);
      setSelectedVisitId(null);
      loadPlaceablePatients();
    } else {
      setSelectedVisitId(mode.visit.id);
      setSelectedBedId(null);
      const zone = resolveZoneForVisit(mode.visit);
      if (zone) loadAvailableBedsInZone(zone);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, mode.kind]);

  async function loadPlaceablePatients() {
    if (!hospitalId) return;
    setLoading(true);
    try {
      // Pull the first few pages of active visits, then filter to placeable
      // statuses. For a small ED this covers everyone in the queue.
      const page = await visitApi.getActiveByHospital(hospitalId, 0, 200);
      setPatients(
        page.content.filter((v) => PLACEABLE_STATUSES.includes(v.status))
      );
    } catch (e) {
      console.error('[PlacePatientDialog] loadPlaceablePatients', e);
      setError('Failed to load patients.');
    } finally {
      setLoading(false);
    }
  }

  async function loadAvailableBedsInZone(zone: EdZone) {
    if (!hospitalId) return;
    setLoading(true);
    try {
      const snap = await loadZone(hospitalId, zone);
      setBeds((snap?.beds || []).filter((b) => b.status === 'AVAILABLE'));
    } catch (e) {
      console.error('[PlacePatientDialog] loadAvailableBedsInZone', e);
      setError('Failed to load beds.');
    } finally {
      setLoading(false);
    }
  }

  const title = mode.kind === 'bed-first'
    ? `Place a patient in ${mode.bed.code}`
    : `Place ${mode.visit.patientName || 'patient'} in a bed`;

  const zoneHint = mode.kind === 'bed-first'
    ? `${mode.bed.zone} zone`
    : resolveZoneForVisit(mode.visit);

  const canSubmit = selectedBedId && selectedVisitId && !submitting;

  async function handleSubmit() {
    if (!selectedBedId || !selectedVisitId) return;
    setSubmitting(true);
    setError(null);
    try {
      const bed = await placePatient(selectedBedId, { visitId: selectedVisitId });
      onPlaced?.(bed);
      onClose();
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'Placement failed';
      setError(msg);
    } finally {
      setSubmitting(false);
    }
  }

  if (!open) return null;

  return (
    <DialogShell isDark={isDark} onClose={onClose} title={title} subtitle={zoneHint || undefined}>
      {error && (
        <div className={`mb-3 rounded-md border px-3 py-2 text-sm ${isDark ? 'border-rose-700/50 bg-rose-900/30 text-rose-200' : 'border-rose-300 bg-rose-50 text-rose-800'}`}>
          {error}
        </div>
      )}

      {mode.kind === 'bed-first' ? (
        <PatientPickerList
          patients={patients}
          loading={loading}
          selectedId={selectedVisitId}
          onSelect={setSelectedVisitId}
          isDark={isDark}
        />
      ) : (
        <BedPickerList
          beds={beds}
          loading={loading}
          selectedId={selectedBedId}
          onSelect={setSelectedBedId}
          isDark={isDark}
        />
      )}

      <div className="mt-4 flex justify-end gap-2">
        <button
          className={`rounded-md px-3 py-1.5 text-sm font-medium ${isDark ? 'bg-slate-700 text-slate-200 hover:bg-slate-600' : 'bg-slate-100 text-slate-700 hover:bg-slate-200'}`}
          onClick={onClose}
          disabled={submitting}
        >
          Cancel
        </button>
        <button
          className={`rounded-md px-3 py-1.5 text-sm font-semibold text-white ${canSubmit ? 'bg-cyan-600 hover:bg-cyan-500' : 'cursor-not-allowed bg-slate-400'}`}
          onClick={handleSubmit}
          disabled={!canSubmit}
        >
          {submitting ? 'Placing…' : 'Place patient'}
        </button>
      </div>
    </DialogShell>
  );
}

// ──────────────────────────────────────────────────────────────────────
// Supporting presentational components
// ──────────────────────────────────────────────────────────────────────

function PatientPickerList({
  patients,
  loading,
  selectedId,
  onSelect,
  isDark,
}: {
  patients: VisitResponse[];
  loading: boolean;
  selectedId: string | null;
  onSelect: (id: string) => void;
  isDark: boolean;
}) {
  const sorted = useMemo(
    () => [...patients].sort((a, b) => triageWeight(b) - triageWeight(a)),
    [patients]
  );

  if (loading) return <ListLoading isDark={isDark} label="Loading patients…" />;
  if (patients.length === 0) {
    return (
      <EmptyState
        isDark={isDark}
        title="No placeable patients"
        body="No patients in the triage queue are ready for placement right now."
      />
    );
  }

  return (
    <ul className={`max-h-80 space-y-1 overflow-y-auto rounded-md border ${isDark ? 'border-slate-700' : 'border-slate-200'}`}>
      {sorted.map((v) => {
        const isSelected = v.id === selectedId;
        return (
          <li key={v.id}>
            <button
              type="button"
              onClick={() => onSelect(v.id)}
              className={`flex w-full items-center justify-between gap-3 px-3 py-2 text-left ${
                isSelected
                  ? isDark ? 'bg-cyan-900/40' : 'bg-cyan-50'
                  : isDark ? 'hover:bg-slate-800' : 'hover:bg-slate-50'
              }`}
            >
              <div>
                <div className={`text-sm font-medium ${isDark ? 'text-slate-100' : 'text-slate-900'}`}>
                  {v.patientName}
                  <span className={`ml-2 text-xs font-normal ${isDark ? 'text-slate-400' : 'text-slate-500'}`}>
                    {v.visitNumber}
                  </span>
                </div>
                <div className={`text-xs ${isDark ? 'text-slate-400' : 'text-slate-500'}`}>
                  {v.status.replace(/_/g, ' ')}
                  {v.chiefComplaint ? ` · ${v.chiefComplaint}` : ''}
                </div>
              </div>
              <div className="flex items-center gap-2">
                {v.currentTriageCategory && (
                  <Badge category={v.currentTriageCategory as any} size="sm" />
                )}
                {v.currentTewsScore != null && (
                  <span className={`text-[11px] font-medium ${isDark ? 'text-slate-300' : 'text-slate-700'}`}>
                    TEWS {v.currentTewsScore}
                  </span>
                )}
              </div>
            </button>
          </li>
        );
      })}
    </ul>
  );
}

function BedPickerList({
  beds,
  loading,
  selectedId,
  onSelect,
  isDark,
}: {
  beds: BedResponse[];
  loading: boolean;
  selectedId: string | null;
  onSelect: (id: string) => void;
  isDark: boolean;
}) {
  if (loading) return <ListLoading isDark={isDark} label="Loading beds…" />;
  if (beds.length === 0) {
    return (
      <EmptyState
        isDark={isDark}
        title="No available beds"
        body="Every bed in this zone is occupied, cleaning, or out of service. Consider a transfer or wait for a bed to free up."
      />
    );
  }
  return (
    <ul className={`grid max-h-80 grid-cols-2 gap-2 overflow-y-auto rounded-md border p-2 sm:grid-cols-3 ${isDark ? 'border-slate-700' : 'border-slate-200'}`}>
      {beds.map((b) => {
        const isSelected = b.id === selectedId;
        return (
          <li key={b.id}>
            <button
              type="button"
              onClick={() => onSelect(b.id)}
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
  );
}

function DialogShell({
  isDark,
  onClose,
  title,
  subtitle,
  children,
}: {
  isDark: boolean;
  onClose: () => void;
  title: string;
  subtitle?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <div
        className={`w-full max-w-lg rounded-xl border shadow-2xl ${isDark ? 'border-slate-700 bg-slate-900' : 'border-slate-200 bg-white'}`}
      >
        <div className={`flex items-start justify-between border-b px-5 py-3 ${isDark ? 'border-slate-700' : 'border-slate-200'}`}>
          <div>
            <h3 className={`text-base font-semibold ${isDark ? 'text-slate-100' : 'text-slate-900'}`}>
              {title}
            </h3>
            {subtitle && (
              <p className={`text-xs ${isDark ? 'text-slate-400' : 'text-slate-500'}`}>{subtitle}</p>
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
        <div className="px-5 py-4">{children}</div>
      </div>
    </div>
  );
}

function ListLoading({ isDark, label }: { isDark: boolean; label: string }) {
  return (
    <div className={`rounded-md border px-3 py-6 text-center text-sm ${isDark ? 'border-slate-700 text-slate-400' : 'border-slate-200 text-slate-500'}`}>
      {label}
    </div>
  );
}

function EmptyState({ isDark, title, body }: { isDark: boolean; title: string; body: string }) {
  return (
    <div className={`rounded-md border border-dashed px-3 py-6 text-center text-sm ${isDark ? 'border-slate-700 text-slate-400' : 'border-slate-200 text-slate-500'}`}>
      <div className="font-medium">{title}</div>
      <div className={`mt-1 text-xs ${isDark ? 'text-slate-500' : 'text-slate-400'}`}>{body}</div>
    </div>
  );
}

// ── Helpers ──────────────────────────────────────────────────────────

/** Map a triaged visit to the preferred ED zone for placement. */
function resolveZoneForVisit(v: VisitResponse): EdZone | null {
  switch (v.currentTriageCategory) {
    case 'RED': return 'RESUS';
    case 'ORANGE': return 'ACUTE';
    case 'YELLOW':
    case 'GREEN':
    case 'BLUE':
      return 'GENERAL';
    default:
      return null;
  }
}

/** Sort weight so the most critical triaged patients bubble to the top. */
function triageWeight(v: VisitResponse): number {
  switch (v.currentTriageCategory) {
    case 'RED': return 5;
    case 'ORANGE': return 4;
    case 'YELLOW': return 3;
    case 'GREEN': return 2;
    case 'BLUE': return 1;
    default: return 0;
  }
}
