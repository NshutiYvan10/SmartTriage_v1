/**
 * V56 — Doctor-on-Duty picker. Used by the triage form's Notified Doctor
 * and Attending Doctor fields. Fetches the on-duty roster for the
 * patient's destination zone (derived by the parent from the triage
 * category) and presents the right UX for 0/1/N candidates.
 *
 * ### States
 *
 *   - **No zone yet** — disabled with a hint: "Pick a category to see
 *     on-duty doctors." This is what the nurse sees before triage
 *     produces a category.
 *
 *   - **Loading** — small spinner; previous selection (if any) is
 *     preserved.
 *
 *   - **Zero doctors** — yellow warning panel listing the consequence
 *     (no immediate notify target; hospital-wide escalation kicks in
 *     after 2 min) plus a free-text input so the nurse can capture a
 *     locum / off-shift doctor coming in to help.
 *
 *   - **One doctor** — pre-selected with a green confirmation strip.
 *     One click on the dropdown lets them override or pick "Other…".
 *
 *   - **Two or more doctors** — dropdown is left unselected. The nurse
 *     must make an explicit choice (avoids the alphabetical-bias trap
 *     of auto-selecting whoever's first).
 *
 *   - **Other / free-text** — bottom option in the dropdown toggles a
 *     plain text input. The parent receives the name without a userId
 *     (locum / unscheduled doctor path).
 */
import { useEffect, useRef, useState } from 'react';
import {
  AlertTriangle, CheckCircle, ChevronDown, Loader2, Pencil, Stethoscope,
} from 'lucide-react';
import type { DoctorOnDutyResponse, EdZone } from '@/api/types';
import { shiftApi } from '@/api/shifts';
import { useTheme } from '@/hooks/useTheme';

interface Props {
  hospitalId: string | undefined;
  /** Destination zone for the patient, derived from triage category. Null disables the picker. */
  zone: EdZone | null;
  /** Current free-text value (always kept in sync; the source of truth in the form). */
  name: string;
  /** Selected user-id when picked from the dropdown; null on free-text / locum path. */
  userId: string | null;
  /** Called when the nurse picks or types — emits both name and (optional) userId. */
  onChange: (name: string, userId: string | null) => void;
  label: string;
  /** Label suffix shown in green when a value is set. Defaults to "selected". */
  selectedHint?: string;
  /** Extra placeholder text for the free-text fallback. */
  freeTextPlaceholder?: string;
}

const CACHE_MAX_AGE_MS = 30_000;

export default function DoctorOnDutyPicker({
  hospitalId, zone, name, userId, onChange, label,
  selectedHint = 'selected',
  freeTextPlaceholder = 'Dr. name',
}: Props) {
  const { glassInner, isDark, text } = useTheme();
  const [doctors, setDoctors] = useState<DoctorOnDutyResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** True when the nurse explicitly picked "Other…" — locks the free-text path. */
  const [freeText, setFreeText] = useState(false);
  /** Timestamp of the last successful fetch — drives the refetch-on-focus check. */
  const lastFetchRef = useRef<number>(0);
  /** Reference to the latest fetch promise so a slow request from a stale zone can't overwrite a fresh one. */
  const fetchSeqRef = useRef(0);

  const fetchRoster = (currentZone: EdZone) => {
    if (!hospitalId) return;
    const seq = ++fetchSeqRef.current;
    setLoading(true);
    setError(null);
    shiftApi.getDoctorsOnDuty(hospitalId, currentZone)
      .then((rows) => {
        if (seq !== fetchSeqRef.current) return; // stale
        setDoctors(rows);
        lastFetchRef.current = Date.now();
        // Auto-select the single doctor on duty. Don't auto-clobber an
        // existing valid selection (e.g. nurse already picked someone).
        if (rows.length === 1 && !userId && !freeText) {
          const only = rows[0];
          onChange(only.fullName, only.userId);
        } else if (rows.length === 0 && !freeText) {
          // No doctors on duty — force free-text fallback. Keep any
          // value the parent already had (the nurse may have started
          // typing).
          setFreeText(true);
        }
      })
      .catch((e: any) => {
        if (seq !== fetchSeqRef.current) return;
        setError(e?.message ?? 'Could not load on-duty doctors.');
        // Don't lock into free-text on error — the nurse may want to
        // retry, and the existing selection (if any) stays valid.
      })
      .finally(() => {
        if (seq === fetchSeqRef.current) setLoading(false);
      });
  };

  // Re-fetch whenever the destination zone changes (e.g. triage
  // category bumped from ORANGE → RED moves them from Acute to Resus).
  useEffect(() => {
    setDoctors([]);
    setFreeText(false);
    lastFetchRef.current = 0;
    if (!zone || !hospitalId) return;
    fetchRoster(zone);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [zone, hospitalId]);

  const refetchIfStale = () => {
    if (!zone) return;
    if (Date.now() - lastFetchRef.current > CACHE_MAX_AGE_MS) {
      fetchRoster(zone);
    }
  };

  // ── Render branches ─────────────────────────────────────────────

  // No zone yet — show disabled hint.
  if (!zone) {
    return (
      <div>
        <label className={`block text-[10px] font-bold ${text.muted} uppercase tracking-wider mb-1`}>
          {label}
        </label>
        <div style={glassInner} className={`px-2.5 py-1.5 rounded-lg text-xs ${text.muted} italic`}>
          Pick a triage category to see doctors on duty
        </div>
      </div>
    );
  }

  return (
    <div>
      <label className={`block text-[10px] font-bold ${text.body} uppercase tracking-wider mb-1 flex items-center gap-1.5`}>
        <Stethoscope className={`w-3 h-3 ${text.muted}`} />
        {label}
        {(userId || (name && freeText)) && (
          <span className={`${isDark ? 'text-green-400' : 'text-green-600'} text-[8px] normal-case font-bold tracking-normal`}>
            ✓ {selectedHint}
          </span>
        )}
      </label>

      {/* Free-text / locum mode — always available, takes precedence */}
      {freeText ? (
        <div className="flex items-center gap-1">
          <input
            type="text"
            value={name}
            onChange={(e) => onChange(e.target.value, null)}
            placeholder={freeTextPlaceholder}
            className={`flex-1 px-2.5 py-1.5 rounded-lg text-xs focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${isDark ? 'bg-amber-500/10 border border-amber-500/30 text-amber-200' : 'bg-amber-50/50 border border-amber-300 text-slate-800'}`}
            onFocus={refetchIfStale}
          />
          <button
            type="button"
            onClick={() => { setFreeText(false); onChange('', null); }}
            style={glassInner}
            className={`px-2 py-1.5 text-[10px] font-bold rounded-lg ${text.body} hover:bg-white/5`}
            title="Back to on-duty doctor list"
          >
            ← List
          </button>
        </div>
      ) : loading ? (
        <div style={glassInner} className={`flex items-center gap-2 px-2.5 py-1.5 rounded-lg text-xs ${text.body}`}>
          <Loader2 className="w-3.5 h-3.5 animate-spin text-cyan-500" />
          Loading doctors on duty in {zone}…
        </div>
      ) : doctors.length === 0 ? (
        // Reached only on error (no-doctors triggers the freeText fallback above).
        <div className="space-y-1.5">
          <div className={`flex items-start gap-1.5 px-2.5 py-1.5 rounded-lg ${isDark ? 'bg-amber-500/20 border border-amber-500/30' : 'bg-amber-50 border border-amber-200'}`}>
            <AlertTriangle className={`w-3.5 h-3.5 ${isDark ? 'text-amber-300' : 'text-amber-500'} flex-shrink-0 mt-0.5`} />
            <p className={`text-[10px] ${isDark ? 'text-amber-300' : 'text-amber-700'}`}>
              {error || `No doctor on duty in ${zone}.`}{' '}
              Hospital-wide alert will fire on Tier 2 escalation after 2 min.
              Enter the name of any doctor stepping in:
            </p>
          </div>
          <input
            type="text"
            value={name}
            onChange={(e) => onChange(e.target.value, null)}
            placeholder={freeTextPlaceholder}
            style={glassInner}
            className={`w-full px-2.5 py-1.5 rounded-lg text-xs focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
          />
        </div>
      ) : (
        <div className="relative">
          <select
            value={userId ?? ''}
            onChange={(e) => {
              const v = e.target.value;
              if (v === '__other__') {
                setFreeText(true);
                onChange('', null);
                return;
              }
              if (v === '') {
                onChange('', null);
                return;
              }
              const d = doctors.find((x) => x.userId === v);
              if (d) onChange(d.fullName, d.userId);
            }}
            onFocus={refetchIfStale}
            style={glassInner}
            className={`w-full px-2.5 py-1.5 pr-7 rounded-lg text-xs appearance-none cursor-pointer focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
          >
            {doctors.length > 1 && <option value="">— Select doctor —</option>}
            {doctors.map((d) => (
              <option key={d.userId} value={d.userId}>
                {d.fullName} · {fnLabel(d.shiftFunction)}
                {d.shiftLead ? ' · Lead' : ''}
                {' '}({d.zonePatientCount} in zone)
              </option>
            ))}
            <option value="__other__">Other / locum…</option>
          </select>
          <ChevronDown className={`w-3.5 h-3.5 ${text.muted} absolute right-2 top-1/2 -translate-y-1/2 pointer-events-none`} />
          {/* Selected confirmation for the single-doctor pre-select case */}
          {doctors.length === 1 && userId && (
            <p className={`mt-1 flex items-center gap-1 text-[10px] ${isDark ? 'text-emerald-400' : 'text-emerald-600'}`}>
              <CheckCircle className="w-3 h-3" />
              Only doctor on duty in {zone} — auto-selected. Tap to change.
            </p>
          )}
          {/* Hint for multi-doctor case — until they pick someone */}
          {doctors.length > 1 && !userId && (
            <p className={`mt-1 flex items-center gap-1 text-[10px] ${text.body}`}>
              <Pencil className="w-3 h-3" />
              {doctors.length} doctors on duty — pick one to notify
            </p>
          )}
        </div>
      )}
    </div>
  );
}

function fnLabel(fn: DoctorOnDutyResponse['shiftFunction']): string {
  switch (fn) {
    case 'PRIMARY_DOCTOR':     return 'Primary';
    case 'SUPERVISING_DOCTOR': return 'Supervising';
    case 'RESIDENT':           return 'Resident';
    default:                   return fn;
  }
}
