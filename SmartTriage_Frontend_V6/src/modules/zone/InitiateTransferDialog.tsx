/**
 * InitiateTransferDialog — the charge-nurse "open a zone transfer by hand"
 * step (#4 of the zone-routing workflow).
 *
 * The automatic path opens transfers on acuity change / deterioration; this
 * is the human entry point for the two cases that path can't see:
 *   • an operational move (overcrowding, isolation need, staffing), and
 *   • a clinical step-down of a stabilised patient to a lower-acuity zone.
 *
 * Pick a patient from the hospital's active visits, choose a target zone
 * (the patient's current zone is excluded), give a reason, submit. The
 * backend takes the target literally (no auto-upgrade) and refuses a
 * step-down that would bury a still-pending clinical escalation — that
 * error is surfaced inline.
 */
import { useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import { ArrowRight, Loader2, Search, X, Send } from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { visitApi } from '@/api/visits';
import { zoneTransferApi } from '@/api/zoneTransfers';
import type { EdZone, VisitResponse } from '@/api/types';

interface Props {
  hospitalId: string;
  onCreated: () => Promise<void> | void;
  onClose: () => void;
}

/** Zones a patient can be moved to by hand. Triage/Neonatal are excluded —
 *  those are entry/specialist zones, not manual-move destinations. */
const TARGET_ZONES: EdZone[] = [
  'RESUS', 'ACUTE', 'GENERAL', 'OBSERVATION', 'ISOLATION', 'PEDIATRIC', 'AMBULATORY',
];

export default function InitiateTransferDialog({ hospitalId, onCreated, onClose }: Props) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';

  const [visits, setVisits] = useState<VisitResponse[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const [selected, setSelected] = useState<VisitResponse | null>(null);
  const [toZone, setToZone] = useState<EdZone | ''>('');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const page = await visitApi.getActiveForCallerByHospital(hospitalId, 0, 200);
        if (!cancelled) setVisits(page?.content ?? []);
      } catch (e: any) {
        if (!cancelled) setLoadError(e?.message ?? 'Failed to load patients');
      }
    })();
    return () => { cancelled = true; };
  }, [hospitalId]);

  const filtered = useMemo(() => {
    const list = visits ?? [];
    const q = query.trim().toLowerCase();
    if (!q) return list.slice(0, 40);
    return list.filter((v) =>
      (v.patientName ?? '').toLowerCase().includes(q)
      || (v.visitNumber ?? '').toLowerCase().includes(q),
    ).slice(0, 40);
  }, [visits, query]);

  // Can't move a patient to the zone they're already in.
  const zoneOptions = useMemo(
    () => TARGET_ZONES.filter((z) => z !== selected?.currentEdZone),
    [selected],
  );

  const canSubmit = !!selected && !!toZone && !busy;

  const submit = async () => {
    if (!selected || !toZone) return;
    setBusy(true);
    setError(null);
    try {
      await zoneTransferApi.initiate(selected.id, toZone, reason.trim() || undefined);
      await onCreated();
      onClose();
    } catch (e: any) {
      // Surfaces the backend's step-down-vs-pending-escalation guard verbatim.
      setError(e?.message ?? 'Could not initiate the transfer');
    } finally {
      setBusy(false);
    }
  };

  // Portal to <body>: rendered from inside the dashboard, whose ancestors
  // carry backdrop-filter/transform (glass shell) that would otherwise make
  // position:fixed resolve to that box and clip the dialog to a top strip.
  return createPortal(
    <div
      className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm"
      style={{ background: 'var(--modal-backdrop)' }}
    >
      <div
        style={glassCard}
        className="w-full max-w-lg rounded-2xl shadow-2xl overflow-hidden mx-4 animate-scale-in flex flex-col max-h-[90vh]"
      >
        {/* Header */}
        <div className="px-5 py-3 flex items-center justify-between" style={{ borderBottom: borderStyle }}>
          <h3 className={`text-sm font-bold ${text.heading}`}>Initiate zone transfer</h3>
          <button onClick={onClose} className={`${text.muted} hover:opacity-70`} aria-label="Close">
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="p-5 space-y-4 overflow-y-auto">
          {/* Patient picker */}
          <div>
            <label className={`block text-[11px] font-bold uppercase tracking-wide mb-1.5 ${text.label}`}>
              Patient
            </label>
            <div className="relative mb-2">
              <Search className={`absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 ${text.muted}`} />
              <input
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="Search by name or visit number…"
                style={glassInner}
                className={`w-full pl-8 pr-3 py-2 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
              />
            </div>
            {loadError ? (
              <p className="text-xs text-rose-500">{loadError}</p>
            ) : visits === null ? (
              <div className={`py-6 text-center ${text.muted}`}><Loader2 className="w-5 h-5 animate-spin mx-auto" /></div>
            ) : filtered.length === 0 ? (
              <p className={`py-4 text-center text-xs ${text.muted}`}>No active patients match.</p>
            ) : (
              <div className="rounded-lg border max-h-48 overflow-y-auto" style={{ borderColor: isDark ? 'rgba(2,132,199,0.2)' : 'rgba(203,213,225,0.5)' }}>
                {filtered.map((v) => {
                  const active = selected?.id === v.id;
                  return (
                    <button
                      key={v.id}
                      type="button"
                      onClick={() => { setSelected(v); setToZone(''); }}
                      className={`w-full text-left px-3 py-2 flex items-center gap-2 border-b last:border-0 transition-colors ${
                        active
                          ? (isDark ? 'bg-cyan-500/15' : 'bg-cyan-50')
                          : (isDark ? 'hover:bg-white/5 border-white/5' : 'hover:bg-slate-50 border-slate-100')
                      }`}
                    >
                      <div className="flex-1 min-w-0">
                        <span className={`text-sm font-semibold ${text.heading}`}>{v.patientName || 'Unidentified patient'}</span>
                        <span className={`ml-2 font-mono text-[10px] ${text.muted}`}>{v.visitNumber}</span>
                      </div>
                      <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded ${isDark ? 'bg-white/10 text-slate-200' : 'bg-slate-100 text-slate-600'}`}>
                        {v.currentEdZone ?? 'unplaced'}
                      </span>
                    </button>
                  );
                })}
              </div>
            )}
          </div>

          {/* Move to zone */}
          {selected && (
            <div className="animate-fade-in">
              <label className={`block text-[11px] font-bold uppercase tracking-wide mb-1.5 ${text.label}`}>
                Move to
              </label>
              <div className={`flex items-center gap-2 mb-2 text-xs ${text.body}`}>
                <span className={`font-semibold ${text.label}`}>{selected.currentEdZone ?? 'unplaced'}</span>
                <ArrowRight className={`w-3.5 h-3.5 ${text.muted}`} />
                <span className="font-bold text-cyan-600">{toZone || '—'}</span>
              </div>
              <div className="flex flex-wrap gap-1.5">
                {zoneOptions.map((z) => (
                  <button
                    key={z}
                    type="button"
                    onClick={() => setToZone(z)}
                    className={`px-2.5 py-1 text-[11px] font-bold rounded-lg border transition-colors ${
                      toZone === z
                        ? 'bg-cyan-600 text-white border-cyan-600'
                        : (isDark ? 'border-slate-600 text-slate-200 hover:bg-white/5' : 'border-slate-300 text-slate-700 hover:bg-slate-100')
                    }`}
                  >
                    {z}
                  </button>
                ))}
              </div>

              <label className={`block text-[11px] font-bold uppercase tracking-wide mt-3 mb-1.5 ${text.label}`}>
                Reason <span className={`font-normal ${text.muted}`}>(recommended)</span>
              </label>
              <textarea
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                rows={2}
                placeholder="e.g. Bed needed for incoming resus; patient stabilised, stepping down."
                style={glassInner}
                className={`w-full px-3 py-2 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
              />
            </div>
          )}

          {error && (
            <div className={`rounded-lg px-3 py-2 text-xs font-semibold border ${isDark ? 'bg-rose-500/10 border-rose-500/30 text-rose-300' : 'bg-rose-50 border-rose-200 text-rose-700'}`}>
              {error}
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-end gap-2 px-5 py-3" style={{ borderTop: borderStyle }}>
          <button onClick={onClose} disabled={busy} className={`px-4 py-1.5 rounded-xl text-xs font-bold hover:bg-white/5 ${text.body}`}>
            Cancel
          </button>
          <button
            onClick={submit}
            disabled={!canSubmit}
            className={`inline-flex items-center gap-1.5 px-4 py-1.5 rounded-xl text-xs font-bold text-white ${
              canSubmit ? 'bg-cyan-600 hover:bg-cyan-700' : 'bg-slate-400 cursor-not-allowed'}`}
          >
            {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Send className="w-3.5 h-3.5" />}
            Initiate transfer
          </button>
        </div>
      </div>
    </div>,
    document.body,
  );
}
