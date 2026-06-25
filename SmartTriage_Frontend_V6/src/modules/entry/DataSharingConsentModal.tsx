/* ── DataSharingConsentModal (Phase 3) ──
 *
 * Records the patient's opt-in / refusal to share their DEEP clinical record across SmartTriage
 * hospitals (keyed on national ID). This consent gates the cross-hospital deep-record read for
 * treating clinicians; the Phase-1 safety summary is always available regardless. The obtaining
 * clinician is snapshotted server-side from the authenticated principal — never sent from here.
 */
import { useCallback, useEffect, useState } from 'react';
import { Check, FileSignature, Loader2, ShieldCheck, ShieldOff, X } from 'lucide-react';
import {
  dataSharingConsentApi,
  type ConsentGrantor,
  type DataSharingConsent,
  type DataSharingConsentStatus,
} from '@/api/crossHospital';
import { ApiError } from '@/api/client';

const GRANTORS: { value: ConsentGrantor; label: string }[] = [
  { value: 'PATIENT', label: 'Patient' },
  { value: 'PARENT_OR_GUARDIAN', label: 'Parent / Guardian' },
  { value: 'NEXT_OF_KIN', label: 'Next of kin' },
  { value: 'LEGAL_SURROGATE', label: 'Legal surrogate' },
  { value: 'COURT_ORDER', label: 'Court order' },
];

interface Props {
  nationalId: string;
  patientName?: string;
  onClose: () => void;
}

export function DataSharingConsentModal({ nationalId, patientName, onClose }: Props) {
  const [history, setHistory] = useState<DataSharingConsent[]>([]);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [status, setStatus] = useState<DataSharingConsentStatus>('GRANTED');
  const [grantor, setGrantor] = useState<ConsentGrantor>('PATIENT');
  const [grantorName, setGrantorName] = useState('');
  const [notes, setNotes] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setHistory(await dataSharingConsentApi.history(nationalId));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not load consent history');
    } finally {
      setLoading(false);
    }
  }, [nationalId]);

  useEffect(() => { load(); }, [load]);

  const effective = history.find((c) => c.status === 'GRANTED');

  const submit = async () => {
    setSubmitting(true);
    setError(null);
    try {
      await dataSharingConsentApi.record(nationalId, {
        status,
        consentGrantor: grantor,
        grantorName: grantorName.trim() || undefined,
        notes: notes.trim() || undefined,
      });
      setGrantorName('');
      setNotes('');
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to record consent');
    } finally {
      setSubmitting(false);
    }
  };

  const withdraw = async (id: string) => {
    setSubmitting(true);
    setError(null);
    try {
      await dataSharingConsentApi.withdraw(id, 'Withdrawn by clinician at registration');
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to withdraw consent');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm"
      style={{ background: 'var(--modal-backdrop)' }}
    >
      <div className="w-full max-w-lg rounded-2xl shadow-2xl overflow-hidden bg-white animate-scale-in">
        <div className="bg-gradient-to-r from-cyan-700 to-emerald-700 px-5 py-3 flex items-center justify-between">
          <div className="flex items-center gap-2 text-white">
            <FileSignature className="w-5 h-5" />
            <h3 className="text-sm font-bold">Cross-hospital data-sharing consent</h3>
          </div>
          <button onClick={onClose} className="text-white/80 hover:text-white"><X className="w-4 h-4" /></button>
        </div>

        <div className="p-5 space-y-4">
          <p className="text-xs text-slate-500">
            {patientName ? <span className="font-semibold text-slate-700">{patientName} — </span> : null}
            Records the patient's decision to share their deep clinical record across SmartTriage
            hospitals. The safety summary is always available regardless of this choice.
          </p>

          {/* Current effective consent */}
          {loading ? (
            <div className="flex items-center gap-2 text-xs text-slate-500">
              <Loader2 className="w-4 h-4 animate-spin" /> Loading current consent…
            </div>
          ) : effective ? (
            <div className="rounded-lg bg-emerald-50 border border-emerald-200 p-3 flex items-start gap-2">
              <ShieldCheck className="w-4 h-4 text-emerald-600 flex-shrink-0 mt-0.5" />
              <div className="flex-1 min-w-0 text-xs">
                <p className="font-bold text-emerald-800">Sharing currently GRANTED</p>
                <p className="text-emerald-700/80 mt-0.5">
                  By {effective.obtainedByName ?? 'clinician'}
                  {effective.obtainedAt ? ` · ${new Date(effective.obtainedAt).toLocaleString()}` : ''}
                </p>
              </div>
              <button
                type="button" disabled={submitting}
                onClick={() => withdraw(effective.id)}
                className="text-xs font-semibold text-red-600 hover:text-red-800 px-2 py-1 rounded-xl hover:bg-red-50 disabled:opacity-50"
              >
                Withdraw
              </button>
            </div>
          ) : (
            <div className="rounded-lg bg-slate-50 border border-slate-200 p-3 flex items-center gap-2 text-xs text-slate-600">
              <ShieldOff className="w-4 h-4 text-slate-400" /> No active sharing consent on file.
            </div>
          )}

          {/* Record a new decision */}
          <div className="space-y-3">
            <div className="flex gap-2">
              {(['GRANTED', 'DENIED'] as DataSharingConsentStatus[]).map((s) => (
                <button
                  key={s} type="button" onClick={() => setStatus(s)}
                  className={`flex-1 px-3 py-2 rounded-xl text-xs font-bold border transition-colors ${
                    status === s
                      ? (s === 'GRANTED' ? 'bg-emerald-600 text-white border-emerald-600' : 'bg-red-600 text-white border-red-600')
                      : 'bg-white text-slate-600 border-slate-300 hover:bg-slate-50'}`}
                >
                  {s === 'GRANTED' ? 'Grant sharing' : 'Refuse sharing'}
                </button>
              ))}
            </div>

            <div>
              <label className="block text-[11px] font-bold uppercase tracking-wide text-slate-600 mb-1">Consent given by</label>
              <select
                value={grantor} onChange={(e) => setGrantor(e.target.value as ConsentGrantor)}
                className="w-full px-3 py-2 border border-slate-300 rounded-lg text-sm"
              >
                {GRANTORS.map((g) => <option key={g.value} value={g.value}>{g.label}</option>)}
              </select>
            </div>

            {grantor !== 'PATIENT' && (
              <div>
                <label className="block text-[11px] font-bold uppercase tracking-wide text-slate-600 mb-1">Grantor name</label>
                <input
                  type="text" value={grantorName} onChange={(e) => setGrantorName(e.target.value)}
                  placeholder="Name of the person giving consent"
                  className="w-full px-3 py-2 border border-slate-300 rounded-lg text-sm"
                />
              </div>
            )}

            <div>
              <label className="block text-[11px] font-bold uppercase tracking-wide text-slate-600 mb-1">Notes (optional)</label>
              <textarea
                value={notes} onChange={(e) => setNotes(e.target.value)} rows={2}
                className="w-full px-3 py-2 border border-slate-300 rounded-lg text-sm"
              />
            </div>
          </div>

          {error && (
            <div className="rounded-lg bg-red-50 border border-red-200 p-2.5 text-xs font-semibold text-red-700">
              {error}
            </div>
          )}
        </div>

        <div className="flex items-center justify-end gap-2 px-5 py-3 border-t border-slate-100">
          <button onClick={onClose} disabled={submitting} className="px-4 py-1.5 rounded-xl text-xs font-bold text-slate-600 hover:bg-slate-100">
            Close
          </button>
          <button
            onClick={submit} disabled={submitting}
            className="inline-flex items-center gap-1.5 px-4 py-1.5 rounded-xl text-xs font-bold text-white bg-cyan-600 hover:bg-cyan-700 disabled:opacity-50"
          >
            {submitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Check className="w-3.5 h-3.5" />}
            Record {status === 'GRANTED' ? 'consent' : 'refusal'}
          </button>
        </div>
      </div>
    </div>
  );
}
