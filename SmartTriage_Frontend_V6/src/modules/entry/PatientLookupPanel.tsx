/**
 * PatientLookupPanel — federated identity lookup for the triage registration
 * flow.
 *
 * Replaces the old "type the NID and hope the local store has it" pattern.
 * The nurse picks an identifier strategy, types what she has, and the
 * backend returns a ranked list of candidates. Picking a candidate fires
 * `onCandidatePicked` with the full {@link PatientResponse}, which the host
 * form pre-fills.
 *
 * Strategies match the backend tiers exactly (see MatchType.java):
 *   Tier 1 — NID, passport, birth-certificate (deterministic)
 *   Tier 2 — MRN
 *   Tier 3 — phone + DOB, guardian-NID + child name + DOB,
 *            guardian-phone + child name + DOB
 *   Tier 4 — first + last name + DOB
 */
import { useState } from 'react';
import {
  Search, Loader2, AlertCircle, UserCheck, CheckCircle2, Baby, ShieldCheck,
} from 'lucide-react';
import { patientApi } from '@/api/patients';
import type {
  PatientLookupCandidate,
  PatientLookupParams,
  PatientResponse,
  MatchType,
} from '@/api/types';
import { useTheme } from '@/hooks/useTheme';

type LookupStrategy =
  | 'nationalId'
  | 'passport'
  | 'birthCertificate'
  | 'mrn'
  | 'phoneDob'
  | 'guardianNid'
  | 'guardianPhone'
  | 'demographic';

const STRATEGY_LABELS: Record<LookupStrategy, string> = {
  nationalId:       'National ID',
  passport:         'Passport',
  birthCertificate: 'Birth certificate',
  mrn:              'Hospital MRN',
  phoneDob:         'Phone + DOB',
  guardianNid:      'Guardian NID + child',
  guardianPhone:    'Guardian phone + child',
  demographic:      'Name + DOB',
};

const STRATEGY_HINTS: Record<LookupStrategy, string> = {
  nationalId:       'Adult patient. 16-digit Rwanda NID.',
  passport:         'Foreign nationals.',
  birthCertificate: 'Pediatric patients with a registered birth certificate.',
  mrn:              "Hospital-issued medical record number from a previous visit.",
  phoneDob:         "Patient's own phone + date of birth.",
  guardianNid:      "Pediatric. Guardian's NID + child's first name + child DOB.",
  guardianPhone:    "Pediatric, lower confidence. Guardian's phone + child's first name + child DOB.",
  demographic:      'Last resort. First + last name + DOB exact match.',
};

const MATCH_BADGE: Record<MatchType, { label: string; cls: string }> = {
  NATIONAL_ID:          { label: 'NID',                cls: 'bg-emerald-100 text-emerald-700' },
  PASSPORT:             { label: 'Passport',           cls: 'bg-emerald-100 text-emerald-700' },
  BIRTH_CERTIFICATE:    { label: 'Birth certificate',  cls: 'bg-emerald-100 text-emerald-700' },
  MRN:                  { label: 'MRN',                cls: 'bg-blue-100 text-blue-700' },
  PHONE_AND_DOB:        { label: 'Phone + DOB',        cls: 'bg-amber-100 text-amber-700' },
  PHONE:                { label: 'Phone',              cls: 'bg-amber-100 text-amber-700' },
  GUARDIAN_NATIONAL_ID: { label: 'Guardian NID',       cls: 'bg-purple-100 text-purple-700' },
  GUARDIAN_PHONE:       { label: 'Guardian phone',     cls: 'bg-purple-100 text-purple-700' },
  DEMOGRAPHIC:          { label: 'Name + DOB',         cls: 'bg-gray-200 text-gray-700' },
};

function formatLastVisit(iso: string | null): string {
  if (!iso) return 'No prior visits';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return 'Last visit: ?';
  const now = Date.now();
  const ageMs = now - d.getTime();
  const days = Math.floor(ageMs / (1000 * 60 * 60 * 24));
  if (days < 1) return 'Last visit: today';
  if (days === 1) return 'Last visit: yesterday';
  if (days < 30) return `Last visit: ${days}d ago`;
  if (days < 365) return `Last visit: ${Math.floor(days / 30)}mo ago`;
  return `Last visit: ${Math.floor(days / 365)}y ago`;
}

interface Props {
  /** Hospital scope. Required — server will not infer it from the request body. */
  hospitalId: string;
  /**
   * Fired when the nurse confirms a candidate. Receives the full
   * PatientResponse (the panel fetches it internally) so the host form can
   * pre-fill all identity + contact fields in one step.
   */
  onCandidatePicked: (patient: PatientResponse) => void;
  /** Optional — fired when nurse clicks "register new" to bypass lookup. */
  onRegisterNew?: () => void;
}

export function PatientLookupPanel({ hospitalId, onCandidatePicked, onRegisterNew }: Props) {
  const { isDark, glassCard } = useTheme();

  const [strategy, setStrategy] = useState<LookupStrategy>('nationalId');
  const [primary, setPrimary]   = useState('');
  const [dob, setDob]           = useState('');
  const [firstName, setFirstName] = useState('');
  const [lastName,  setLastName]  = useState('');

  const [candidates, setCandidates] = useState<PatientLookupCandidate[] | null>(null);
  const [searching, setSearching] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [pickingId, setPickingId] = useState<string | null>(null);

  // Reset secondary fields when strategy changes so values don't leak between
  // strategies (e.g. an old DOB attaching to a guardian-NID query).
  const handleStrategyChange = (next: LookupStrategy) => {
    setStrategy(next);
    setCandidates(null);
    setError(null);
  };

  const buildParams = (): PatientLookupParams | null => {
    const p = primary.trim();
    switch (strategy) {
      case 'nationalId':       return p ? { nationalId: p } : null;
      case 'passport':         return p ? { passport: p } : null;
      case 'birthCertificate': return p ? { birthCertificate: p } : null;
      case 'mrn':              return p ? { mrn: p } : null;
      case 'phoneDob':
        return p && dob ? { phone: p, dob } : null;
      case 'guardianNid':
        return p && firstName.trim() && dob
          ? { guardianNationalId: p, firstName: firstName.trim(), dob }
          : null;
      case 'guardianPhone':
        return p && firstName.trim() && dob
          ? { guardianPhone: p, firstName: firstName.trim(), dob }
          : null;
      case 'demographic':
        return firstName.trim() && lastName.trim() && dob
          ? { firstName: firstName.trim(), lastName: lastName.trim(), dob }
          : null;
    }
  };

  const handleSearch = async (e: React.FormEvent) => {
    e.preventDefault();
    const params = buildParams();
    if (!params) {
      setError('Please fill all fields required for this lookup strategy.');
      return;
    }
    setSearching(true);
    setError(null);
    setCandidates(null);
    try {
      const results = await patientApi.lookup(hospitalId, params);
      setCandidates(results);
    } catch (err: any) {
      setError(err?.message ?? 'Lookup failed');
    } finally {
      setSearching(false);
    }
  };

  const handlePick = async (c: PatientLookupCandidate) => {
    setPickingId(c.patientId);
    setError(null);
    try {
      const full = await patientApi.getById(c.patientId);
      onCandidatePicked(full);
    } catch (err: any) {
      setError(err?.message ?? 'Failed to load patient details');
    } finally {
      setPickingId(null);
    }
  };

  // ── Styling helpers ──
  const cardCls = `rounded-xl shadow-md p-4 ${
    isDark ? glassCard + ' border border-white/10' : 'bg-white border border-gray-200'
  }`;
  const headerTextCls = isDark ? 'text-white' : 'text-gray-900';
  const subtleTextCls = isDark ? 'text-slate-400' : 'text-gray-500';
  const inputCls = `w-full px-3 py-2 text-sm rounded-lg border ${
    isDark
      ? 'bg-white/5 border-white/10 text-white placeholder:text-slate-500'
      : 'bg-white border-gray-200 text-gray-900 placeholder:text-gray-400'
  } focus:outline-none focus:ring-2 focus:ring-blue-500/40`;

  // What input fields to render for the chosen strategy
  const showPrimary = strategy !== 'demographic';
  const primaryLabel: Record<LookupStrategy, string> = {
    nationalId:       'National ID',
    passport:         'Passport number',
    birthCertificate: 'Birth certificate number',
    mrn:              'Medical record number',
    phoneDob:         "Patient's phone",
    guardianNid:      "Guardian's national ID",
    guardianPhone:    "Guardian's phone",
    demographic:      '',
  };
  const showDob = ['phoneDob', 'guardianNid', 'guardianPhone', 'demographic'].includes(strategy);
  const showFirstName = ['guardianNid', 'guardianPhone', 'demographic'].includes(strategy);
  const showLastName  = strategy === 'demographic';

  return (
    <div className={cardCls}>
      <div className="flex items-center justify-between mb-3">
        <h3 className={`text-sm font-bold flex items-center gap-2 ${headerTextCls}`}>
          <Search className="w-4 h-4" />
          Find existing patient
        </h3>
        {onRegisterNew && (
          <button
            type="button"
            onClick={onRegisterNew}
            className={`text-xs px-2.5 py-1 rounded-md ${
              isDark ? 'text-slate-300 hover:bg-white/5' : 'text-gray-600 hover:bg-gray-100'
            }`}
          >
            Skip — register new
          </button>
        )}
      </div>

      <p className={`text-[11px] mb-3 ${subtleTextCls}`}>
        Search before registering to avoid duplicate records and pull up the patient's history.
      </p>

      {/* ── Strategy picker ──────────────────────────────────────────── */}
      <form onSubmit={handleSearch} className="space-y-2">
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-1">
          {(Object.keys(STRATEGY_LABELS) as LookupStrategy[]).map((key) => {
            const active = strategy === key;
            return (
              <button
                key={key}
                type="button"
                onClick={() => handleStrategyChange(key)}
                className={`text-[11px] px-2 py-1.5 rounded-md border transition-colors ${
                  active
                    ? 'bg-blue-600 border-blue-600 text-white'
                    : isDark
                      ? 'border-white/10 text-slate-300 hover:bg-white/5'
                      : 'border-gray-200 text-gray-700 hover:bg-gray-50'
                }`}
              >
                {STRATEGY_LABELS[key]}
              </button>
            );
          })}
        </div>
        <p className={`text-[11px] flex items-center gap-1 ${subtleTextCls}`}>
          {strategy === 'guardianNid' || strategy === 'guardianPhone' ? (
            <Baby className="w-3 h-3" />
          ) : strategy === 'nationalId' || strategy === 'passport' || strategy === 'birthCertificate' ? (
            <ShieldCheck className="w-3 h-3" />
          ) : null}
          {STRATEGY_HINTS[strategy]}
        </p>

        {/* ── Strategy-specific inputs ──────────────────────────────── */}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
          {showPrimary && (
            <input
              type="text"
              className={inputCls}
              placeholder={primaryLabel[strategy]}
              value={primary}
              onChange={(e) => setPrimary(e.target.value)}
              disabled={searching}
            />
          )}
          {showFirstName && (
            <input
              type="text"
              className={inputCls}
              placeholder={strategy === 'demographic' ? 'First name' : "Child's first name"}
              value={firstName}
              onChange={(e) => setFirstName(e.target.value)}
              disabled={searching}
            />
          )}
          {showLastName && (
            <input
              type="text"
              className={inputCls}
              placeholder="Last name"
              value={lastName}
              onChange={(e) => setLastName(e.target.value)}
              disabled={searching}
            />
          )}
          {showDob && (
            <input
              type="date"
              className={inputCls}
              value={dob}
              onChange={(e) => setDob(e.target.value)}
              disabled={searching}
              max={new Date().toISOString().slice(0, 10)}
            />
          )}
        </div>

        {error && (
          <div className="flex items-center gap-1.5 text-xs text-red-500">
            <AlertCircle className="w-3.5 h-3.5" />
            {error}
          </div>
        )}

        <div className="flex justify-end">
          <button
            type="submit"
            disabled={searching}
            className={`inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg transition-colors ${
              searching
                ? 'bg-gray-300 text-gray-500 cursor-not-allowed'
                : 'bg-blue-600 hover:bg-blue-700 text-white'
            }`}
          >
            {searching ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Search className="w-3.5 h-3.5" />}
            {searching ? 'Searching…' : 'Search'}
          </button>
        </div>
      </form>

      {/* ── Results ──────────────────────────────────────────────────── */}
      {candidates !== null && (
        <div className="mt-4">
          {candidates.length === 0 ? (
            <div className={`flex items-center gap-2 text-sm py-3 ${subtleTextCls}`}>
              <UserCheck className="w-4 h-4" />
              No matching patient found. You can register a new one below.
            </div>
          ) : (
            <>
              <p className={`text-[11px] mb-1.5 ${subtleTextCls}`}>
                {candidates.length} candidate{candidates.length === 1 ? '' : 's'} — highest confidence first.
              </p>
              <div className="space-y-1.5 max-h-[360px] overflow-y-auto pr-1">
                {candidates.map((c) => {
                  const badge = MATCH_BADGE[c.matchType];
                  const picking = pickingId === c.patientId;
                  return (
                    <div
                      key={c.patientId}
                      className={`rounded-lg border p-2.5 flex items-center gap-3 ${
                        isDark ? 'bg-white/[0.04] border-white/10' : 'bg-gray-50 border-gray-200'
                      }`}
                    >
                      <div className="flex-1 min-w-0">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className={`text-sm font-semibold ${headerTextCls}`}>
                            {c.firstName} {c.lastName}
                          </span>
                          <span className={`text-[10px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded ${badge.cls}`}>
                            {badge.label}
                          </span>
                          <span className={`text-[10px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded ${
                            c.confidence >= 0.95 ? 'bg-emerald-100 text-emerald-700'
                              : c.confidence >= 0.80 ? 'bg-amber-100 text-amber-700'
                              : 'bg-gray-200 text-gray-700'
                          }`}>
                            {Math.round(c.confidence * 100)}%
                          </span>
                          {c.isPediatric && (
                            <span className="text-[10px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded bg-pink-100 text-pink-700 inline-flex items-center gap-1">
                              <Baby className="w-3 h-3" />
                              Pediatric
                            </span>
                          )}
                        </div>
                        <div className={`text-[11px] mt-0.5 ${subtleTextCls}`}>
                          MRN {c.medicalRecordNumber || '—'}
                          {c.dateOfBirth && <> · DOB {c.dateOfBirth}</>}
                          {c.ageInYears !== null && <> · {c.ageInYears}y</>}
                          {c.gender && <> · {c.gender}</>}
                          {c.nationalIdLast4 && <> · NID …{c.nationalIdLast4}</>}
                        </div>
                        <div className={`text-[11px] ${subtleTextCls}`}>
                          {formatLastVisit(c.lastVisitAt)}
                        </div>
                      </div>
                      <button
                        type="button"
                        onClick={() => handlePick(c)}
                        disabled={picking || pickingId !== null}
                        className={`inline-flex items-center gap-1 px-2.5 py-1 text-xs font-semibold rounded-md whitespace-nowrap ${
                          picking
                            ? 'bg-gray-300 text-gray-500 cursor-not-allowed'
                            : 'bg-emerald-600 hover:bg-emerald-700 text-white'
                        }`}
                      >
                        {picking ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <CheckCircle2 className="w-3.5 h-3.5" />}
                        {picking ? 'Loading…' : 'Use this record'}
                      </button>
                    </div>
                  );
                })}
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
}

export default PatientLookupPanel;
