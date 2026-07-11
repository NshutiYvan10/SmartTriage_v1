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
import { useTheme } from '@/hooks/useTheme';

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
  const { glassCard, glassInner, isDark, text } = useTheme();
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
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
      <div style={glassCard} className="w-full max-w-lg rounded-2xl shadow-2xl overflow-hidden max-h-[90vh] overflow-y-auto animate-scale-in">
        <div className="bg-gradient-to-r from-cyan-700 to-emerald-700 px-5 py-3 flex items-center justify-between">
          <div className="flex items-center gap-2 text-white">
            <FileSignature className="w-5 h-5" />
            <h3 className="text-sm font-bold">Cross-hospital data-sharing consent</h3>
          </div>
          <button onClick={onClose} className="text-white/80 hover:text-white"><X className="w-4 h-4" /></button>
        </div>

        <div className="p-5 space-y-4">
          <p className={`text-xs ${text.muted}`}>
            {patientName ? <span className={`font-semibold ${text.body}`}>{patientName} — </span> : null}
            Records the patient's decision to share their deep clinical record across SmartTriage
            hospitals. The safety summary is always available regardless of this choice.
          </p>

          {/* Current effective consent */}
          {loading ? (
            <div className={`flex items-center gap-2 text-xs ${text.muted}`}>
              <Loader2 className="w-4 h-4 animate-spin" /> Loading current consent…
            </div>
          ) : effective ? (
            <div className="rounded-lg bg-emerald-500/10 border border-emerald-500/25 p-3 flex items-start gap-2">
              <ShieldCheck className="w-4 h-4 text-emerald-500 flex-shrink-0 mt-0.5" />
              <div className="flex-1 min-w-0 text-xs">
                <p className={`font-bold ${isDark ? 'text-emerald-300' : 'text-emerald-800'}`}>Sharing currently GRANTED</p>
                <p className={isDark ? 'text-emerald-300/70 mt-0.5' : 'text-emerald-700/80 mt-0.5'}>
                  By {effective.obtainedByName ?? 'clinician'}
                  {effective.obtainedAt ? ` · ${new Date(effective.obtainedAt).toLocaleString()}` : ''}
                </p>
              </div>
              <button
                type="button" disabled={submitting}
                onClick={() => withdraw(effective.id)}
                className="text-xs font-semibold text-red-500 hover:text-red-400 px-2 py-1 rounded-xl hover:bg-red-500/10 disabled:opacity-50"
              >
                Withdraw
              </button>
            </div>
          ) : (
            <div style={glassInner} className={`rounded-lg p-3 flex items-center gap-2 text-xs ${text.muted}`}>
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
                      : `${isDark ? 'bg-white/5 border-white/10 hover:bg-white/10' : 'bg-white border-slate-300 hover:bg-slate-50'} ${text.body}`}`}
                >
                  {s === 'GRANTED' ? 'Grant sharing' : 'Refuse sharing'}
                </button>
              ))}
            </div>

            <div>
              <label className={`block text-[11px] font-bold uppercase tracking-wide mb-1 ${text.label}`}>Consent given by</label>
              <select
                value={grantor} onChange={(e) => setGrantor(e.target.value as ConsentGrantor)}
                style={glassInner}
                className={`w-full px-3 py-2 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
              >
                {GRANTORS.map((g) => <option key={g.value} value={g.value}>{g.label}</option>)}
              </select>
            </div>

            {grantor !== 'PATIENT' && (
              <div>
                <label className={`block text-[11px] font-bold uppercase tracking-wide mb-1 ${text.label}`}>Grantor name</label>
                <input
                  type="text" value={grantorName} onChange={(e) => setGrantorName(e.target.value)}
                  placeholder="Name of the person giving consent"
                  style={glassInner}
                  className={`w-full px-3 py-2 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
                />
              </div>
            )}

            <div>
              <label className={`block text-[11px] font-bold uppercase tracking-wide mb-1 ${text.label}`}>Notes (optional)</label>
              <textarea
                value={notes} onChange={(e) => setNotes(e.target.value)} rows={2}
                style={glassInner}
                className={`w-full px-3 py-2 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
              />
            </div>
          </div>

          {error && (
            <div className={`rounded-lg bg-red-500/10 border border-red-500/25 p-2.5 text-xs font-semibold ${isDark ? 'text-red-300' : 'text-red-700'}`}>
              {error}
            </div>
          )}
        </div>

        <div className="flex items-center justify-end gap-2 px-5 py-3" style={{ borderTop: borderStyle }}>
          <button onClick={onClose} disabled={submitting} className={`px-4 py-1.5 rounded-xl text-xs font-bold hover:bg-white/5 ${text.body}`}>
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
