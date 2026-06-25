/* ── IdentityResolutionModal ─────────────────────────────────────────
 *
 * V28 — Set Patient Identity. Surfaces on the visit chart of an
 * unidentified Direct Resus patient. Two paths:
 *
 *   (a) Type the real identity. The placeholder Patient is updated
 *       in place — UUID preserved, all references (visit, triage
 *       record, bed placement, alerts) remain valid.
 *
 *   (b) Merge into an existing patient (returning visitor whose
 *       MPI record we already have). Visits are re-pointed; the
 *       placeholder is soft-deleted with audit metadata preserved.
 *
 * Path (b) is intentionally less prominent — simple rename is the
 * common case. MPI search lives behind a toggle so the interface
 * doesn't overwhelm a nurse who just wants to type "Marie Uwimana".
 */
import { useState, type ReactNode } from 'react';
import {
  AlertTriangle, Check, Link2, Loader2, Search, UserCheck, X,
} from 'lucide-react';
import { directResusApi } from '@/api/directResus';
import { patientApi } from '@/api/patients';
import type { Gender, PatientResponse, ResolveIdentityRequest } from '@/api/types';
import { useTheme } from '@/hooks/useTheme';
import { formatPatientDisplayName } from './displayName';

interface Props {
  patient: PatientResponse;
  hospitalId: string;
  onClose: () => void;
  onResolved: (resolved: PatientResponse) => void;
}

export function IdentityResolutionModal({ patient, hospitalId, onClose, onResolved }: Props) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
  const [mode, setMode] = useState<'rename' | 'merge'>('rename');

  // Rename-mode state
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [dateOfBirth, setDateOfBirth] = useState('');
  const [gender, setGender] = useState<Gender | ''>('');
  const [nationalId, setNationalId] = useState('');
  const [phoneNumber, setPhoneNumber] = useState('');
  const [resolutionNote, setResolutionNote] = useState('');

  // Merge-mode state
  const [searchQuery, setSearchQuery] = useState('');
  const [searchResults, setSearchResults] = useState<PatientResponse[]>([]);
  const [searching, setSearching] = useState(false);
  const [selectedTarget, setSelectedTarget] = useState<PatientResponse | null>(null);

  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const placeholderName = formatPatientDisplayName(patient);

  const renameValid =
    mode === 'rename' && firstName.trim().length >= 1 && lastName.trim().length >= 1;
  const mergeValid =
    mode === 'merge' && selectedTarget != null && selectedTarget.id !== patient.id;
  const canSubmit = (renameValid || mergeValid) && !submitting;

  const runSearch = async () => {
    if (searchQuery.trim().length < 2) {
      setSearchResults([]);
      return;
    }
    setSearching(true);
    setError(null);
    try {
      const page = await patientApi.search(hospitalId, searchQuery.trim(), 0, 10);
      const results = page.content.filter(
        (p: PatientResponse) => p.id !== patient.id && !p.isUnidentified,
      );
      setSearchResults(results);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Search failed');
    } finally {
      setSearching(false);
    }
  };

  const handleSubmit = async () => {
    if (!canSubmit) return;
    setSubmitting(true);
    setError(null);
    try {
      const body: ResolveIdentityRequest =
        mode === 'rename'
          ? {
              firstName: firstName.trim(),
              lastName: lastName.trim(),
              dateOfBirth: dateOfBirth || undefined,
              gender: gender || undefined,
              nationalId: nationalId.trim() || undefined,
              phoneNumber: phoneNumber.trim() || undefined,
              resolutionNote: resolutionNote.trim() || undefined,
            }
          : {
              mergeIntoPatientId: selectedTarget!.id,
              resolutionNote: resolutionNote.trim() || undefined,
            };
      const resolved = await directResusApi.resolveIdentity(patient.id, body);
      onResolved(resolved);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Identity resolution failed');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm"
      style={{ background: 'var(--modal-backdrop)' }}
      onClick={submitting ? undefined : onClose}
    >
      <div
        style={glassCard}
        className="w-full max-w-lg rounded-2xl shadow-2xl overflow-hidden animate-scale-in"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-5 py-3 text-white flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 rounded-lg bg-cyan-500/20 flex items-center justify-center">
              <UserCheck className="w-4 h-4 text-cyan-300" />
            </div>
            <h3 className="text-sm font-bold text-white">Resolve patient identity</h3>
          </div>
          <button
            onClick={onClose}
            disabled={submitting}
            className="w-7 h-7 rounded-lg bg-white/15 flex items-center justify-center hover:bg-white/25 disabled:opacity-50"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="p-5 space-y-4">
          {/* Current placeholder summary */}
          <div style={glassInner} className="rounded-lg px-3 py-2.5">
            <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Currently registered as</p>
            <p className={`text-sm font-bold mt-0.5 italic ${text.heading}`}>{placeholderName}</p>
            {patient.placeholderAssignedAt && (
              <p className={`text-[10px] mt-0.5 ${text.muted}`}>
                Placeholder assigned at {new Date(patient.placeholderAssignedAt).toLocaleString()}
              </p>
            )}
          </div>

          {/* Mode toggle */}
          <div className="flex rounded-lg overflow-hidden text-xs font-bold" style={{ border: borderStyle }}>
            <button
              onClick={() => setMode('rename')}
              disabled={submitting}
              className={`flex-1 py-2 rounded-xl transition-colors ${
                mode === 'rename'
                  ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md'
                  : `${text.body} hover:bg-white/5 border border-transparent`
              }`}
            >
              Type real identity
            </button>
            <button
              onClick={() => setMode('merge')}
              disabled={submitting}
              className={`flex-1 py-2 rounded-xl transition-colors flex items-center justify-center gap-1 ${
                mode === 'merge'
                  ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md'
                  : `${text.body} hover:bg-white/5 border border-transparent`
              }`}
            >
              <Link2 className="w-3 h-3" />
              Merge into existing patient
            </button>
          </div>

          {/* Body */}
          {mode === 'rename' ? (
            <RenameForm
              firstName={firstName} setFirstName={setFirstName}
              lastName={lastName} setLastName={setLastName}
              dateOfBirth={dateOfBirth} setDateOfBirth={setDateOfBirth}
              gender={gender} setGender={setGender}
              nationalId={nationalId} setNationalId={setNationalId}
              phoneNumber={phoneNumber} setPhoneNumber={setPhoneNumber}
              resolutionNote={resolutionNote} setResolutionNote={setResolutionNote}
            />
          ) : (
            <MergeSearch
              searchQuery={searchQuery} setSearchQuery={setSearchQuery}
              onRunSearch={runSearch}
              searching={searching}
              results={searchResults}
              selectedTarget={selectedTarget}
              setSelectedTarget={setSelectedTarget}
              resolutionNote={resolutionNote} setResolutionNote={setResolutionNote}
            />
          )}

          {error && (
            <div className="rounded-lg p-3 flex items-start gap-2 bg-rose-500/20 border border-rose-500/30">
              <AlertTriangle className="w-4 h-4 mt-0.5 flex-shrink-0 text-rose-400" />
              <div>
                <p className="text-xs font-bold text-rose-300">Could not resolve</p>
                <p className="text-[10px] text-rose-400 mt-0.5">{error}</p>
              </div>
            </div>
          )}
        </div>

        <div className="flex items-center justify-end gap-2 px-5 py-3" style={{ borderTop: borderStyle }}>
          <button
            onClick={onClose}
            disabled={submitting}
            className={`px-3 py-1.5 rounded-xl text-xs font-bold hover:bg-white/5 disabled:opacity-50 ${text.body}`}
          >
            Cancel
          </button>
          <button
            onClick={handleSubmit}
            disabled={!canSubmit}
            className={`inline-flex items-center gap-1.5 px-4 py-1.5 rounded-xl text-xs font-bold text-white
              ${canSubmit
                ? 'bg-cyan-600 hover:bg-cyan-700'
                : 'bg-slate-400 cursor-not-allowed'}
            `}
          >
            {submitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Check className="w-3.5 h-3.5" />}
            {submitting ? 'Resolving…' : mode === 'rename' ? 'Save real identity' : 'Merge into selected patient'}
          </button>
        </div>
      </div>
    </div>
  );
}

// ── Sub-components ─────────────────────────────────────────────────────

interface RenameFormProps {
  firstName: string;
  setFirstName: (v: string) => void;
  lastName: string;
  setLastName: (v: string) => void;
  dateOfBirth: string;
  setDateOfBirth: (v: string) => void;
  gender: Gender | '';
  setGender: (v: Gender | '') => void;
  nationalId: string;
  setNationalId: (v: string) => void;
  phoneNumber: string;
  setPhoneNumber: (v: string) => void;
  resolutionNote: string;
  setResolutionNote: (v: string) => void;
}

function RenameForm(p: RenameFormProps) {
  const { glassInner, text } = useTheme();
  const inputCls = `${inputBaseCls} ${text.body}`;
  return (
    <>
      <div className="grid grid-cols-2 gap-3">
        <Field label="First name *">
          <input
            value={p.firstName}
            onChange={(e) => p.setFirstName(e.target.value)}
            maxLength={100}
            autoFocus
            placeholder="Marie"
            style={glassInner}
            className={inputCls}
          />
        </Field>
        <Field label="Last name *">
          <input
            value={p.lastName}
            onChange={(e) => p.setLastName(e.target.value)}
            maxLength={100}
            placeholder="Uwimana"
            style={glassInner}
            className={inputCls}
          />
        </Field>
      </div>

      <div className="grid grid-cols-2 gap-3">
        <Field label="Date of birth">
          <input
            type="date"
            value={p.dateOfBirth}
            onChange={(e) => p.setDateOfBirth(e.target.value)}
            style={glassInner}
            className={inputCls}
          />
        </Field>
        <Field label="Gender">
          <select
            value={p.gender}
            onChange={(e) => p.setGender(e.target.value as Gender | '')}
            style={glassInner}
            className={inputCls}
          >
            <option value="">—</option>
            <option value="MALE">Male</option>
            <option value="FEMALE">Female</option>
          </select>
        </Field>
      </div>

      <div className="grid grid-cols-2 gap-3">
        <Field label="National ID">
          <input
            value={p.nationalId}
            onChange={(e) => p.setNationalId(e.target.value)}
            maxLength={30}
            style={glassInner}
            className={inputCls}
          />
        </Field>
        <Field label="Phone">
          <input
            value={p.phoneNumber}
            onChange={(e) => p.setPhoneNumber(e.target.value)}
            maxLength={20}
            style={glassInner}
            className={inputCls}
          />
        </Field>
      </div>

      <Field label="Resolution note (optional)">
        <input
          value={p.resolutionNote}
          onChange={(e) => p.setResolutionNote(e.target.value)}
          placeholder="e.g. Family arrived with ID; patient woke up and gave name"
          maxLength={500}
          style={glassInner}
          className={inputCls}
        />
      </Field>
    </>
  );
}

interface MergeSearchProps {
  searchQuery: string;
  setSearchQuery: (v: string) => void;
  onRunSearch: () => void;
  searching: boolean;
  results: PatientResponse[];
  selectedTarget: PatientResponse | null;
  setSelectedTarget: (p: PatientResponse | null) => void;
  resolutionNote: string;
  setResolutionNote: (v: string) => void;
}

function MergeSearch(p: MergeSearchProps) {
  const { glassInner, isDark, text } = useTheme();
  const inputCls = `${inputBaseCls} ${text.body}`;
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
  return (
    <>
      <Field label="Search MPI by name, national ID, or MRN">
        <div className="flex gap-2">
          <input
            value={p.searchQuery}
            onChange={(e) => p.setSearchQuery(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') p.onRunSearch(); }}
            placeholder="e.g. Uwimana, 1198..."
            style={glassInner}
            className={inputCls}
          />
          <button
            type="button"
            onClick={p.onRunSearch}
            disabled={p.searching || p.searchQuery.trim().length < 2}
            className="inline-flex items-center gap-1 px-3 py-2 rounded-xl text-xs font-bold bg-cyan-600 text-white hover:bg-cyan-700 disabled:opacity-50"
          >
            {p.searching ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Search className="w-3.5 h-3.5" />}
            Search
          </button>
        </div>
      </Field>

      <div style={glassInner} className="max-h-56 overflow-y-auto rounded-lg">
        {p.results.length === 0 ? (
          <div className="px-3 py-6 text-center">
            <p className={`text-xs ${text.muted}`}>
              {p.searching ? 'Searching…' : 'No results yet — search by name or ID above.'}
            </p>
          </div>
        ) : (
          p.results.map((r) => (
            <button
              key={r.id}
              onClick={() => p.setSelectedTarget(r)}
              style={{ borderBottom: borderStyle }}
              className={`w-full text-left px-3 py-2 transition-colors ${
                p.selectedTarget?.id === r.id ? 'bg-emerald-500/20' : 'hover:bg-white/5'
              }`}
            >
              <p className={`text-sm font-bold ${text.heading}`}>
                {r.firstName} {r.lastName}
                {p.selectedTarget?.id === r.id && (
                  <Check className="inline w-3.5 h-3.5 text-emerald-400 ml-2" />
                )}
              </p>
              <p className={`text-[10px] ${text.muted}`}>
                {r.nationalId ? `ID: ${r.nationalId} · ` : ''}
                {r.medicalRecordNumber ? `MRN: ${r.medicalRecordNumber} · ` : ''}
                {r.dateOfBirth ? `DOB: ${r.dateOfBirth}` : ''}
              </p>
            </button>
          ))
        )}
      </div>

      <Field label="Resolution note (optional)">
        <input
          value={p.resolutionNote}
          onChange={(e) => p.setResolutionNote(e.target.value)}
          placeholder="e.g. Returning visitor — same patient as last admission"
          maxLength={500}
          style={glassInner}
          className={inputCls}
        />
      </Field>

      {p.selectedTarget && (
        <div className="rounded-lg p-3 bg-amber-500/20 border border-amber-500/30">
          <p className="text-[11px] font-bold text-amber-300">Merge effect</p>
          <p className="text-[10px] text-amber-400 mt-0.5 leading-relaxed">
            All visits on the placeholder will be re-pointed at <span className="font-bold">{p.selectedTarget.firstName} {p.selectedTarget.lastName}</span>.
            The placeholder row is preserved (soft-deleted) so the audit trail of "admitted as Unknown … at 14:32" remains intact.
          </p>
        </div>
      )}
    </>
  );
}

const inputBaseCls =
  'w-full px-3 py-2 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20';

function Field({ label, children }: { label: string; children: ReactNode }) {
  const { text } = useTheme();
  return (
    <div>
      <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1 ${text.label}`}>{label}</label>
      {children}
    </div>
  );
}
