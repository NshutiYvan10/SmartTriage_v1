/* ═══════════════════════════════════════════════════════════════
   Global Patient Registry — REGISTRAR-only, SYSTEM-WIDE search.

   The registration desk's cross-hospital lookup: find a patient first
   registered at ANY SmartTriage hospital (by name / national ID / phone /
   MRN / RFID card) and start a visit HERE without re-registering them.
   The deliberate exception to hospital scoping — clinical staff keep the
   hospital-scoped Patients list. Each row shows its originating hospital;
   rows are flagged when the patient already has an open visit here.
   ═══════════════════════════════════════════════════════════════ */

import { useState, useCallback, useRef, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Globe, Search, Loader2, UserPlus, ChevronDown, ChevronRight,
  Building2, CreditCard, ScanLine, AlertTriangle, CheckCircle2, ArrowRight,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { patientApi } from '@/api/patients';
import { ApiError } from '@/api/client';
import type { GlobalPatientRow } from '@/api/types';
import { CrossHospitalSafetyBanner } from '@/modules/entry/CrossHospitalSafetyBanner';

function ageFromDob(dob: string | null): string {
  if (!dob) return '—';
  const d = new Date(dob);
  if (Number.isNaN(d.getTime())) return '—';
  const now = new Date();
  let years = now.getFullYear() - d.getFullYear();
  const m = now.getMonth() - d.getMonth();
  if (m < 0 || (m === 0 && now.getDate() < d.getDate())) years--;
  if (years < 0) return '—';
  if (years === 0) {
    const months = Math.max(0, m + (now.getDate() < d.getDate() ? -1 : 0) + (m < 0 ? 12 : 0));
    return `${months}mo`;
  }
  return `${years}y`;
}

export function GlobalRegistryView() {
  const { glassCard, isDark, text } = useTheme();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const myHospitalId = user?.hospitalId || undefined;

  const [query, setQuery] = useState('');
  const [rows, setRows] = useState<GlobalPatientRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [searched, setSearched] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [openingId, setOpeningId] = useState<string | null>(null);
  const [rowError, setRowError] = useState<Record<string, string>>({});

  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.4)';

  // Monotonic request token: only the LATEST query may write state. Without this a
  // slower earlier request could resolve last and overwrite the newer query's results
  // with a stale patient list — a silently-wrong list in a dedup workflow is dangerous.
  const seqRef = useRef(0);

  const runSearch = useCallback(async (q: string) => {
    const trimmed = q.trim();
    if (!trimmed) { setRows([]); setSearched(false); return; }
    const mySeq = ++seqRef.current;
    setLoading(true);
    setError(null);
    try {
      const page = await patientApi.registrySearch(trimmed, myHospitalId, 0, 30);
      if (mySeq !== seqRef.current) return; // a newer query superseded this one — drop it
      setRows(page.content);
      setSearched(true);
    } catch (err) {
      if (mySeq !== seqRef.current) return;
      setError(err instanceof ApiError ? err.message : 'Search failed');
      setRows([]);
    } finally {
      if (mySeq === seqRef.current) setLoading(false);
    }
  }, [myHospitalId]);

  // Debounce typing → search (min 2 chars; exact card/NID also works).
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    if (query.trim().length < 2) { setRows([]); setSearched(false); return; }
    debounceRef.current = setTimeout(() => runSearch(query), 350);
    return () => { if (debounceRef.current) clearTimeout(debounceRef.current); };
  }, [query, runSearch]);

  const startVisitHere = async (row: GlobalPatientRow) => {
    if (!myHospitalId) return;
    setOpeningId(row.patientId);
    setRowError((e) => ({ ...e, [row.patientId]: '' }));
    try {
      const res = await patientApi.openVisitHere(row.patientId, {
        hospitalId: myHospitalId,
        arrivalMode: 'WALK_IN',
      });
      // Land on the patient record (the visit now shows in their history + triage queue).
      // Route to /patients/:id — NOT /visit/:id, which is triage-gated and a registrar
      // cannot reach (it would silently bounce to /dashboard, hiding the created visit).
      const pid = res?.patient?.id || row.patientId;
      navigate(`/patients/${pid}`);
    } catch (err) {
      setRowError((e) => ({
        ...e,
        [row.patientId]: err instanceof ApiError ? err.message : 'Could not open the visit',
      }));
    } finally {
      setOpeningId(null);
    }
  };

  return (
    <div className="max-w-5xl mx-auto p-4 sm:p-6 space-y-5">
      {/* Header */}
      <div>
        <div className="flex items-center gap-2.5">
          <div className="w-10 h-10 rounded-xl bg-cyan-500/15 flex items-center justify-center">
            <Globe className="w-5 h-5 text-cyan-600" />
          </div>
          <div>
            <h1 className={`text-xl font-bold ${text}`}>Patient Registry</h1>
            <p className="text-sm text-slate-500">
              System-wide search across every SmartTriage hospital — find a returning patient and
              start a visit here without re-registering them.
            </p>
          </div>
        </div>
      </div>

      {/* Search */}
      <div className="rounded-2xl p-4" style={{ ...glassCard, border: borderStyle }}>
        <div className="relative">
          <Search className="w-4 h-4 text-slate-400 absolute left-3.5 top-1/2 -translate-y-1/2" />
          <input
            autoFocus
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search by name, national ID, phone, MRN, or tap/scan an RFID card…"
            className={`w-full pl-10 pr-4 py-2.5 rounded-xl text-sm outline-none focus:ring-2 focus:ring-cyan-500/40 ${
              isDark ? 'bg-slate-800/60 text-slate-100 placeholder-slate-500' : 'bg-white text-slate-800 placeholder-slate-400 border border-slate-200'
            }`}
          />
          {loading && <Loader2 className="w-4 h-4 text-cyan-500 animate-spin absolute right-3.5 top-1/2 -translate-y-1/2" />}
        </div>
        {error && (
          <p className="text-xs font-semibold text-red-600 mt-2 flex items-center gap-1.5">
            <AlertTriangle className="w-3.5 h-3.5" /> {error}
          </p>
        )}
        {query.trim().length > 0 && query.trim().length < 2 && (
          <p className="text-xs text-slate-400 mt-2">Type at least 2 characters.</p>
        )}
      </div>

      {/* Results */}
      {searched && !loading && rows.length === 0 && (
        <div className="rounded-2xl p-8 text-center" style={{ ...glassCard, border: borderStyle }}>
          <p className={`text-sm font-semibold ${text}`}>No patient found anywhere in SmartTriage</p>
          <p className="text-xs text-slate-500 mt-1">
            This is likely a first-time patient. Register them from the Registration page.
          </p>
          <button
            onClick={() => navigate('/entry')}
            className="inline-flex items-center gap-1.5 mt-3 text-xs font-bold text-white bg-cyan-600 hover:bg-cyan-700 px-3.5 py-1.5 rounded-xl transition-colors"
          >
            <UserPlus className="w-3.5 h-3.5" /> Register new patient
          </button>
        </div>
      )}

      {rows.length > 0 && (
        <div className="space-y-2.5">
          {rows.map((row) => {
            const isOpen = expanded === row.patientId;
            const name = `${row.firstName ?? ''} ${row.lastName ?? ''}`.trim() || 'Unidentified patient';
            return (
              <div key={row.patientId} className="rounded-2xl overflow-hidden" style={{ ...glassCard, border: borderStyle }}>
                <div className="p-4 flex items-start gap-3">
                  {/* Identity + context */}
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 flex-wrap">
                      <span className={`text-sm font-bold ${text}`}>{name}</span>
                      <span className="text-xs text-slate-400">{ageFromDob(row.dateOfBirth)} · {row.gender ?? '—'}</span>
                      {row.unidentified && (
                        <span className="text-[10px] font-bold uppercase tracking-wide px-1.5 py-0.5 rounded-md bg-amber-500/15 text-amber-600">
                          Unidentified
                        </span>
                      )}
                      {row.localToMyHospital ? (
                        <span className="text-[10px] font-bold uppercase tracking-wide px-1.5 py-0.5 rounded-md bg-emerald-500/15 text-emerald-600">
                          Local
                        </span>
                      ) : (
                        <span className="inline-flex items-center gap-1 text-[10px] font-semibold px-1.5 py-0.5 rounded-md bg-sky-500/10 text-sky-600">
                          <Building2 className="w-3 h-3" /> {row.hospitalName ?? row.hospitalCode ?? 'Other hospital'}
                        </span>
                      )}
                      {row.hasRfidCard && <ScanLine className="w-3.5 h-3.5 text-slate-400" aria-label="Has RFID card" />}
                    </div>
                    <div className="flex items-center gap-3 mt-1 text-xs text-slate-500">
                      {row.nationalId && <span className="inline-flex items-center gap-1"><CreditCard className="w-3 h-3" /> {row.nationalId}</span>}
                      {row.phoneNumber && <span>{row.phoneNumber}</span>}
                      {row.medicalRecordNumber && <span className="font-mono">{row.medicalRecordNumber}</span>}
                    </div>
                    {rowError[row.patientId] && (
                      <p className="text-xs font-semibold text-red-600 mt-2 flex items-center gap-1.5">
                        <AlertTriangle className="w-3.5 h-3.5" /> {rowError[row.patientId]}
                      </p>
                    )}
                  </div>

                  {/* Actions */}
                  <div className="flex flex-col items-end gap-1.5 flex-shrink-0">
                    {row.hasOpenVisitAtMyHospital ? (
                      <button
                        onClick={() => navigate(`/patients/${row.patientId}`)}
                        className="inline-flex items-center gap-1.5 text-xs font-semibold text-emerald-700 bg-emerald-500/10 hover:bg-emerald-500/20 px-3 py-1.5 rounded-xl transition-colors"
                      >
                        <CheckCircle2 className="w-3.5 h-3.5" /> Open visit active — go to it
                      </button>
                    ) : (
                      <button
                        onClick={() => startVisitHere(row)}
                        disabled={openingId === row.patientId}
                        className="inline-flex items-center gap-1.5 text-xs font-bold text-white bg-cyan-600 hover:bg-cyan-700 px-3.5 py-1.5 rounded-xl transition-colors disabled:opacity-50"
                      >
                        {openingId === row.patientId ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ArrowRight className="w-3.5 h-3.5" />}
                        Start visit here
                      </button>
                    )}
                    <button
                      onClick={() => setExpanded(isOpen ? null : row.patientId)}
                      className="inline-flex items-center gap-1 text-xs font-medium text-slate-500 hover:text-slate-700 px-2 py-1 rounded-lg transition-colors"
                    >
                      {isOpen ? <ChevronDown className="w-3.5 h-3.5" /> : <ChevronRight className="w-3.5 h-3.5" />}
                      Prior history
                    </button>
                  </div>
                </div>

                {/* Cross-hospital safety floor (allergies / conditions / meds by hospital). */}
                {isOpen && (
                  <div className="px-4 pb-4">
                    <CrossHospitalSafetyBanner
                      nationalId={row.nationalId || undefined}
                      patientName={name}
                    />
                    {!row.nationalId && (
                      <p className="text-xs text-slate-400 mt-2">
                        Prior cross-hospital history is available for patients with a national ID on file.
                      </p>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}

      {!searched && !loading && (
        <div className="rounded-2xl p-8 text-center" style={{ ...glassCard, border: borderStyle }}>
          <Globe className="w-8 h-8 text-slate-300 mx-auto mb-2" />
          <p className="text-sm text-slate-500">
            Start typing to search every SmartTriage hospital. Rows from other hospitals show their
            source; a patient with an open visit here is flagged so you never start a duplicate.
          </p>
        </div>
      )}
    </div>
  );
}
