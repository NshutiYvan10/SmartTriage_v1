/* ── BreakTheGlassIncidents (Phase 3) ──
 *
 * Governance feed of cross-hospital break-the-glass emergency overrides performed by THIS
 * hospital's clinicians. Forensic surface (shows everything regardless of acknowledgement) with a
 * governance sign-off. Live: subscribes to /topic/governance/{hospitalId} and refetches on any
 * override, plus a 5-minute backstop. Acknowledgement is optimistic. Sits on the Override Audit
 * page beside the medication-safety overrides — same governance audience.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { ShieldAlert, Check, Loader2, RefreshCw, Clock, User, Building2 } from 'lucide-react';
import { governanceApi, type BreakTheGlassEvent, type GovernanceRange } from '@/api/crossHospital';
import { ApiError } from '@/api/client';
import { subscribeToGovernance } from '@/api/websocket';
import { useWebSocketGeneration } from '@/hooks/useWebSocket';
import { useTheme } from '@/hooks/useTheme';
import { format, formatDistanceToNow } from 'date-fns';

const RANGE_LABEL: Record<GovernanceRange, string> = {
  '24h': 'Last 24h', '7d': 'Last 7 days', '30d': 'Last 30 days', all: 'All time',
};

interface Props {
  hospitalId: string;
}

export function BreakTheGlassIncidents({ hospitalId }: Props) {
  const { glassCard, text } = useTheme();
  const wsGen = useWebSocketGeneration();
  const [events, setEvents] = useState<BreakTheGlassEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [range, setRange] = useState<GovernanceRange>('7d');
  const [ackingId, setAckingId] = useState<string | null>(null);
  const tickRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const load = useCallback(async () => {
    if (!hospitalId) return;
    setLoading(true);
    setError(null);
    try {
      const page = await governanceApi.getBreakTheGlassEvents(hospitalId, range, 0, 200);
      setEvents(page.content ?? []);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load break-the-glass events');
    } finally {
      setLoading(false);
    }
  }, [hospitalId, range]);

  useEffect(() => { load(); }, [load]);

  // Live refresh: a new break-the-glass override at this hospital. Dedicated governance topic.
  useEffect(() => {
    if (!hospitalId) return;
    const unsub = subscribeToGovernance(hospitalId, () => load());
    return () => unsub();
  }, [hospitalId, load, wsGen]);

  // 5-minute backstop (mirrors the medication overrides view).
  useEffect(() => {
    tickRef.current = setInterval(() => load(), 5 * 60 * 1000);
    return () => { if (tickRef.current) clearInterval(tickRef.current); };
  }, [load]);

  const acknowledge = async (id: string) => {
    setAckingId(id);
    const prev = events;
    // Optimistic: flip acknowledged immediately.
    setEvents((es) => es.map((e) => e.id === id ? { ...e, acknowledged: true } : e));
    try {
      const updated = await governanceApi.acknowledgeBreakTheGlassEvent(hospitalId, id);
      setEvents((es) => es.map((e) => e.id === id ? updated : e));
    } catch {
      setEvents(prev); // rollback
    } finally {
      setAckingId(null);
    }
  };

  const unacked = events.filter((e) => !e.acknowledged).length;

  return (
    <div className="rounded-3xl overflow-hidden" style={glassCard}>
      {/* Header */}
      <div className="px-5 py-4" style={{ background: 'linear-gradient(135deg, rgba(239,68,68,0.18) 0%, rgba(99,102,241,0.10) 100%)' }}>
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-red-500/20 border border-red-500/30 flex items-center justify-center">
              <ShieldAlert className="w-5 h-5 text-red-300" />
            </div>
            <div>
              <h2 className="text-base font-bold text-white tracking-wide">Break-the-Glass Incidents</h2>
              <p className="text-white/50 text-xs">
                Cross-hospital emergency record overrides by this hospital's clinicians
                {unacked > 0 ? ` · ${unacked} awaiting review` : ''} · {RANGE_LABEL[range]}
              </p>
            </div>
          </div>
          <button
            onClick={load}
            className="w-9 h-9 rounded-xl bg-white/10 flex items-center justify-center hover:bg-white/20 transition-colors"
            aria-label="Refresh break-the-glass incidents"
          >
            {loading ? <Loader2 className="w-4 h-4 text-white animate-spin" /> : <RefreshCw className="w-4 h-4 text-white" />}
          </button>
        </div>

        {/* Range tabs */}
        <div className="flex gap-1 mt-3">
          {(Object.keys(RANGE_LABEL) as GovernanceRange[]).map((r) => (
            <button
              key={r}
              onClick={() => setRange(r)}
              className={`px-3 py-1 rounded-xl text-[11px] font-bold transition-colors ${
                range === r ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md' : 'text-white/50 hover:text-white hover:bg-white/10'}`}
            >
              {RANGE_LABEL[r]}
            </button>
          ))}
        </div>
      </div>

      {/* Body */}
      <div className="p-4">
        {error ? (
          <div className="rounded-xl p-3 text-sm font-semibold text-red-700 bg-red-50 border border-red-200">{error}</div>
        ) : loading && events.length === 0 ? (
          <div className="flex items-center justify-center gap-2 py-8 text-sm text-slate-400">
            <Loader2 className="w-4 h-4 animate-spin" /> Loading…
          </div>
        ) : events.length === 0 ? (
          <div className="py-8 text-center text-sm text-slate-400">
            No break-the-glass overrides in this period.
          </div>
        ) : (
          <div className="space-y-2">
            {events.map((e) => (
              <div
                key={e.id}
                className={`rounded-xl px-4 py-3 border ${
                  e.acknowledged ? 'border-white/10 bg-white/[0.02]' : 'border-red-500/30 bg-red-500/[0.06]'}`}
              >
                <div className="flex items-start justify-between gap-3">
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 flex-wrap text-xs">
                      <span className="inline-flex items-center gap-1 font-bold text-red-300">
                        <ShieldAlert className="w-3.5 h-3.5" /> Override
                      </span>
                      <span className="inline-flex items-center gap-1 text-white/70">
                        <User className="w-3 h-3" /> {e.actorName ?? 'Unknown'}{e.actorRole ? ` (${e.actorRole})` : ''}
                      </span>
                      <span className="text-white/40">· patient {e.maskedNationalId ?? '—'}</span>
                      {e.priorConsentState && e.priorConsentState !== 'NONE' && (
                        <span className="inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg text-amber-600" style={{ background: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.2)' }}>
                          overrode {e.priorConsentState}
                        </span>
                      )}
                    </div>
                    {e.reason && <p className="text-xs text-white/70 mt-1.5">{e.reason}</p>}
                    <p className="text-[11px] text-white/40 mt-1 inline-flex items-center gap-1">
                      <Clock className="w-3 h-3" />
                      {e.accessedAt ? `${format(new Date(e.accessedAt), 'PPp')} · ${formatDistanceToNow(new Date(e.accessedAt), { addSuffix: true })}` : '—'}
                    </p>
                    {e.acknowledged && e.acknowledgedByName && (
                      <p className="text-[11px] text-emerald-300/80 mt-1 inline-flex items-center gap-1">
                        <Check className="w-3 h-3" /> Reviewed by {e.acknowledgedByName}
                        {e.acknowledgedAt ? ` · ${formatDistanceToNow(new Date(e.acknowledgedAt), { addSuffix: true })}` : ''}
                      </p>
                    )}
                  </div>
                  {!e.acknowledged && (
                    <button
                      onClick={() => acknowledge(e.id)}
                      disabled={ackingId === e.id}
                      className="flex-shrink-0 inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-[11px] font-bold text-white bg-cyan-600 hover:bg-cyan-700 disabled:opacity-50"
                    >
                      {ackingId === e.id ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Check className="w-3.5 h-3.5" />}
                      Acknowledge
                    </button>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
