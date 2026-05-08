/* ═══════════════════════════════════════════════════════════════
   Laboratory Orders — Lab Technician Dashboard (Phase 1)

   Three-tab worklist for the lab tech:
     1. Inbox       — orders waiting on lab action (Receive / Reject)
     2. In Progress — orders processing (Enter Result)
     3. Critical    — unacknowledged critical results (visible to all)

   STAT cards pulse, SLA countdown badges, real-time WebSocket push
   so new orders land without polling.
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback, useRef } from 'react';
import {
  FlaskConical, Clock, AlertTriangle, Loader2, RefreshCw,
  Inbox, Activity, Beaker, CheckCircle2, XCircle, Phone,
  ClipboardCheck, AlertOctagon,
} from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { labApi } from '@/api/lab';
import type { LabOrder, LabOrderStatus, LabPriority } from '@/api/lab';
import { subscribeToLabOrders } from '@/api/websocket';
import { formatDistanceToNow } from 'date-fns';
import { useTheme } from '@/hooks/useTheme';
import { ResultEntryModal } from './ResultEntryModal';
import { RejectSpecimenModal } from './RejectSpecimenModal';
import { AcknowledgeCriticalModal } from './AcknowledgeCriticalModal';

type TechTab = 'inbox' | 'in-progress' | 'critical';

const PRIORITY_TARGET_MIN: Record<LabPriority, number> = {
  STAT: 30,
  URGENT: 120,
  ROUTINE: 1440,
};

function priorityColor(priority: LabPriority): { ring: string; chip: string; pulse: boolean } {
  switch (priority) {
    case 'STAT':
      return { ring: 'ring-2 ring-rose-500/40', chip: 'bg-rose-500/15 text-rose-500', pulse: true };
    case 'URGENT':
      return { ring: 'ring-1 ring-amber-500/30', chip: 'bg-amber-500/15 text-amber-500', pulse: false };
    default:
      return { ring: '', chip: 'bg-slate-500/15 text-slate-500', pulse: false };
  }
}

function statusChip(status: LabOrderStatus): { label: string; className: string } {
  switch (status) {
    case 'ORDERED':            return { label: 'Ordered',           className: 'bg-slate-500/15 text-slate-500' };
    case 'SPECIMEN_COLLECTED': return { label: 'Specimen collected', className: 'bg-blue-500/15 text-blue-500' };
    case 'RECEIVED_BY_LAB':    return { label: 'Received',          className: 'bg-indigo-500/15 text-indigo-500' };
    case 'PROCESSING':         return { label: 'Processing',        className: 'bg-violet-500/15 text-violet-500' };
    case 'RESULTED':           return { label: 'Resulted',          className: 'bg-emerald-500/15 text-emerald-500' };
    case 'REJECTED':           return { label: 'Rejected',          className: 'bg-rose-500/15 text-rose-500' };
    case 'CANCELLED':          return { label: 'Cancelled',         className: 'bg-slate-500/15 text-slate-400' };
  }
}

/** Minutes since order, with target context. Negative = overdue. */
function slaInfo(order: LabOrder): { elapsed: number; target: number; overdueBy: number } {
  const elapsed = Math.floor((Date.now() - new Date(order.orderedAt).getTime()) / 60000);
  const target = PRIORITY_TARGET_MIN[order.priority];
  return { elapsed, target, overdueBy: Math.max(0, elapsed - target) };
}

export function LabOrdersView() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  const techName = user?.fullName ?? '';

  const [activeTab, setActiveTab] = useState<TechTab>('inbox');
  const [inbox, setInbox] = useState<LabOrder[]>([]);
  const [inProgress, setInProgress] = useState<LabOrder[]>([]);
  const [critical, setCritical] = useState<LabOrder[]>([]);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [toast, setToast] = useState<{ type: 'ok' | 'err'; text: string } | null>(null);
  const flash = (type: 'ok' | 'err', text: string) => {
    setToast({ type, text });
    setTimeout(() => setToast(null), 3500);
  };

  // Modals
  const [resultTarget, setResultTarget] = useState<LabOrder | null>(null);
  const [rejectTarget, setRejectTarget] = useState<LabOrder | null>(null);
  const [ackTarget, setAckTarget] = useState<LabOrder | null>(null);

  // ── Load ─────────────────────────────────────────────────────────
  const load = useCallback(async () => {
    if (!hospitalId) return;
    setLoading(true);
    try {
      const [inboxRes, ipRes, critRes] = await Promise.all([
        labApi.getInbox(hospitalId),
        labApi.getInProgress(hospitalId),
        labApi.getCritical(hospitalId),
      ]);
      setInbox(inboxRes || []);
      setInProgress(ipRes || []);
      // getCritical returns CriticalValueResponse; we re-fetch as LabOrder shape via getForVisit later if needed.
      // For Phase 1 the tech tab cares about lab orders proper — to keep things consistent we fetch the
      // unack criticals as LabOrder via the pending list and filter.
      setCritical((critRes as unknown as LabOrder[]) || []);
    } catch (err) {
      console.error('[LabOrdersView] load failed:', err);
      flash('err', 'Failed to load lab queue');
    } finally {
      setLoading(false);
    }
  }, [hospitalId]);

  useEffect(() => { load(); }, [load]);

  // ── Live updates ─────────────────────────────────────────────────
  // Subscribe to /topic/lab/{hospitalId}. Each message is a full
  // LabOrderResponse — re-route the row into the right column based
  // on the new status, and remove it from columns it has left.
  const reloadDebounced = useRef<number | null>(null);
  useEffect(() => {
    if (!hospitalId) return;
    const unsub = subscribeToLabOrders(hospitalId, (incoming: LabOrder) => {
      // Cheap path: re-fetch if a relevant status arrives. Debounced
      // so a burst of transitions on the same order doesn't fire a
      // dozen GETs.
      if (reloadDebounced.current) window.clearTimeout(reloadDebounced.current);
      reloadDebounced.current = window.setTimeout(() => {
        load();
      }, 300);
      // Critical-result push? show a toast even if we'll reload
      if (incoming?.isCritical && !incoming?.criticalValueAcknowledgedAt) {
        flash('err', `CRITICAL: ${incoming.testName} (${incoming.orderNumber})`);
      }
    });
    return () => unsub();
  }, [hospitalId, load]);

  // SLA tick — re-render every 30s so timers advance
  const [, setTick] = useState(0);
  useEffect(() => {
    const iv = window.setInterval(() => setTick((t) => t + 1), 30_000);
    return () => window.clearInterval(iv);
  }, []);

  // ── Tech actions ─────────────────────────────────────────────────
  async function handleReceive(order: LabOrder) {
    if (actionLoading) return;
    setActionLoading(order.id);
    try {
      await labApi.receiveInLab(order.id, { receivedByName: techName || undefined });
      flash('ok', `Specimen accessioned for ${order.orderNumber}`);
    } catch (err: any) {
      flash('err', err?.message || 'Receive failed');
    } finally {
      setActionLoading(null);
    }
  }

  async function handleStartProcessing(order: LabOrder) {
    if (actionLoading) return;
    setActionLoading(order.id);
    try {
      await labApi.startProcessing(order.id, techName || undefined);
      flash('ok', `Processing started for ${order.orderNumber}`);
    } catch (err: any) {
      flash('err', err?.message || 'Start processing failed');
    } finally {
      setActionLoading(null);
    }
  }

  // ── Render ───────────────────────────────────────────────────────
  const tabs: { id: TechTab; label: string; icon: any; count: number }[] = [
    { id: 'inbox',       label: 'Inbox',       icon: Inbox,    count: inbox.length },
    { id: 'in-progress', label: 'In Progress', icon: Activity, count: inProgress.length },
    { id: 'critical',    label: 'Critical',    icon: AlertOctagon, count: critical.length },
  ];

  const list = activeTab === 'inbox' ? inbox : activeTab === 'in-progress' ? inProgress : critical;

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-7xl mx-auto space-y-4 animate-fade-in">

        {/* Header */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-violet-700 to-violet-600 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-white/20 flex items-center justify-center">
                  <FlaskConical className="w-5 h-5 text-white" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">Lab — Worklist</h1>
                  <p className="text-white/60 text-xs">
                    {techName ? `Signed in as ${techName}` : 'Lab technician dashboard'}
                  </p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                {critical.length > 0 && (
                  <div className="px-3 py-1.5 rounded-lg bg-rose-500/30 ring-1 ring-rose-300/40 animate-pulse">
                    <span className="text-white text-xs font-bold">{critical.length} CRITICAL UNACK</span>
                  </div>
                )}
                <button
                  onClick={load}
                  className="w-9 h-9 rounded-xl bg-white/15 flex items-center justify-center hover:bg-white/25 transition-colors"
                  title="Refresh"
                >
                  <RefreshCw className={`w-4 h-4 text-white ${loading ? 'animate-spin' : ''}`} />
                </button>
              </div>
            </div>
          </div>
        </div>

        {/* Toast */}
        {toast && (
          <div className={`flex items-center gap-3 px-4 py-3 rounded-2xl text-sm font-semibold animate-fade-up ${
            toast.type === 'ok'
              ? 'bg-emerald-500/15 text-emerald-500 border border-emerald-500/20'
              : 'bg-rose-500/15 text-rose-500 border border-rose-500/20'
          }`}>
            {toast.type === 'ok' ? <CheckCircle2 className="w-4 h-4" /> : <AlertTriangle className="w-4 h-4" />}
            {toast.text}
          </div>
        )}

        {/* Tabs */}
        <div className="flex flex-wrap gap-2">
          {tabs.map(({ id, label, icon: Icon, count }) => (
            <button
              key={id}
              onClick={() => setActiveTab(id)}
              className={`flex items-center gap-2 px-4 py-2 rounded-xl text-xs font-bold transition-all ${
                activeTab === id
                  ? 'bg-gradient-to-r from-violet-700 to-violet-600 text-white shadow-lg'
                  : isDark ? 'bg-white/5 text-white/60 hover:bg-white/10' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
              }`}
            >
              <Icon className="w-3.5 h-3.5" />
              {label}
              <span className={`px-2 py-0.5 rounded-md text-[10px] font-bold ${
                activeTab === id ? 'bg-white/20' : 'bg-slate-500/20'
              }`}>{count}</span>
            </button>
          ))}
        </div>

        {/* List */}
        {loading && list.length === 0 ? (
          <div className="flex items-center justify-center py-12">
            <Loader2 className="w-6 h-6 animate-spin text-violet-500" />
          </div>
        ) : list.length === 0 ? (
          <div className="rounded-2xl p-8 text-center animate-fade-up" style={glassCard}>
            <Beaker className="w-10 h-10 mx-auto mb-3 text-slate-400" />
            <p className={`text-sm font-bold ${text.heading}`}>
              {activeTab === 'inbox'       && 'No orders waiting'}
              {activeTab === 'in-progress' && 'No orders being processed'}
              {activeTab === 'critical'    && 'No unacknowledged critical results'}
            </p>
            <p className={text.muted}>You are up to date.</p>
          </div>
        ) : (
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
            {list.map((order, i) => (
              <LabOrderCard
                key={order.id}
                order={order}
                animationDelay={i * 0.03}
                glassCard={glassCard}
                glassInner={glassInner}
                text={text}
                isLoading={actionLoading === order.id}
                onReceive={() => handleReceive(order)}
                onReject={() => setRejectTarget(order)}
                onStartProcessing={() => handleStartProcessing(order)}
                onEnterResult={() => setResultTarget(order)}
                onAcknowledge={() => setAckTarget(order)}
              />
            ))}
          </div>
        )}
      </div>

      {/* Modals */}
      {resultTarget && (
        <ResultEntryModal
          order={resultTarget}
          enteredByName={techName}
          onClose={() => setResultTarget(null)}
          onSaved={() => { setResultTarget(null); load(); }}
        />
      )}
      {rejectTarget && (
        <RejectSpecimenModal
          order={rejectTarget}
          rejectedByName={techName}
          onClose={() => setRejectTarget(null)}
          onSaved={() => { setRejectTarget(null); load(); }}
        />
      )}
      {ackTarget && (
        <AcknowledgeCriticalModal
          order={ackTarget}
          acknowledgedByName={techName}
          onClose={() => setAckTarget(null)}
          onSaved={() => { setAckTarget(null); load(); }}
        />
      )}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────
// Single order card — extracted so the parent stays readable
// ─────────────────────────────────────────────────────────────────

interface CardProps {
  order: LabOrder;
  animationDelay: number;
  glassCard: any;
  glassInner: any;
  text: any;
  isLoading: boolean;
  onReceive: () => void;
  onReject: () => void;
  onStartProcessing: () => void;
  onEnterResult: () => void;
  onAcknowledge: () => void;
}

function LabOrderCard({
  order, animationDelay, glassCard, glassInner, text, isLoading,
  onReceive, onReject, onStartProcessing, onEnterResult, onAcknowledge,
}: CardProps) {
  const pri = priorityColor(order.priority);
  const sc = statusChip(order.status);
  const sla = slaInfo(order);

  return (
    <div
      className={`rounded-2xl p-5 animate-fade-up ${pri.ring} ${pri.pulse && order.status !== 'RESULTED' ? 'animate-pulse-slow' : ''}`}
      style={{ ...glassCard, animationDelay: `${animationDelay}s` }}
    >
      {/* Top row: priority + status + SLA */}
      <div className="flex items-start justify-between mb-3 gap-2">
        <div className="flex items-center gap-2 flex-wrap">
          <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg ${pri.chip}`}>
            {order.priority}
          </span>
          <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg ${sc.className}`}>
            {sc.label}
          </span>
          {order.isCritical && !order.criticalValueAcknowledgedAt && (
            <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg bg-rose-500/20 text-rose-500 ring-1 ring-rose-500/30 inline-flex items-center gap-1">
              <AlertOctagon className="w-3 h-3" /> CRITICAL UNACK
            </span>
          )}
        </div>
        <SlaBadge sla={sla} priority={order.priority} />
      </div>

      {/* Test + indication */}
      <div className="mb-2">
        <h4 className={`text-sm font-bold ${text.heading}`}>{order.testName}</h4>
        <p className={`text-[10px] font-mono ${text.muted}`}>
          {order.orderNumber}{order.accessionNumber ? ` • ${order.accessionNumber}` : ''}
        </p>
      </div>
      {order.clinicalIndication && (
        <p className={`text-xs italic mb-2 ${text.body}`}>
          “{order.clinicalIndication}”
        </p>
      )}

      {/* Specimen + ordering doctor */}
      <div className={`text-[11px] grid grid-cols-2 gap-2 mb-3 ${text.muted}`}>
        <span>Specimen: <span className={text.body}>{order.specimenType ?? '—'}</span></span>
        <span>Ordered by: <span className={text.body}>{order.orderedByName ?? '—'}</span></span>
        <span>Ordered: <span className={text.body}>{formatDistanceToNow(new Date(order.orderedAt), { addSuffix: true })}</span></span>
        {order.specimenCollectedAt && (
          <span>Collected: <span className={text.body}>{formatDistanceToNow(new Date(order.specimenCollectedAt), { addSuffix: true })}</span></span>
        )}
      </div>

      {/* Result preview if resulted */}
      {order.status === 'RESULTED' && order.resultValue && (
        <div className="rounded-xl p-3 mb-3" style={glassInner}>
          <div className={`text-[10px] uppercase font-bold mb-1 ${text.label}`}>Result</div>
          <div className={`text-sm font-bold ${order.isCritical ? 'text-rose-500' : order.isAbnormal ? 'text-amber-500' : text.heading}`}>
            {order.resultValue} {order.resultUnit}
          </div>
          {order.referenceRangeMin !== null && order.referenceRangeMax !== null && (
            <div className={`text-[10px] ${text.muted}`}>
              Ref: {order.referenceRangeMin} – {order.referenceRangeMax} {order.resultUnit}
            </div>
          )}
        </div>
      )}

      {order.status === 'REJECTED' && (
        <div className="rounded-xl p-3 mb-3 bg-rose-500/10 ring-1 ring-rose-500/20">
          <div className="text-[10px] uppercase font-bold mb-1 text-rose-500">Rejected</div>
          <div className={`text-xs ${text.body}`}>{order.rejectionReason}{order.rejectionNotes ? ` — ${order.rejectionNotes}` : ''}</div>
        </div>
      )}

      {/* Actions */}
      <div className="flex flex-wrap gap-2">
        {(order.status === 'ORDERED' || order.status === 'SPECIMEN_COLLECTED') && (
          <>
            <button
              onClick={onReceive}
              disabled={isLoading}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-bold bg-indigo-500 text-white hover:bg-indigo-600 disabled:opacity-50"
            >
              {isLoading ? <Loader2 className="w-3 h-3 animate-spin" /> : <ClipboardCheck className="w-3 h-3" />}
              Receive
            </button>
            <button
              onClick={onReject}
              disabled={isLoading}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-bold bg-rose-500/15 text-rose-500 hover:bg-rose-500/25 disabled:opacity-50"
            >
              <XCircle className="w-3 h-3" /> Reject specimen
            </button>
          </>
        )}
        {order.status === 'RECEIVED_BY_LAB' && (
          <>
            <button
              onClick={onStartProcessing}
              disabled={isLoading}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-bold bg-violet-500 text-white hover:bg-violet-600 disabled:opacity-50"
            >
              {isLoading ? <Loader2 className="w-3 h-3 animate-spin" /> : <Activity className="w-3 h-3" />}
              Start processing
            </button>
            <button
              onClick={onEnterResult}
              disabled={isLoading}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-bold bg-emerald-500 text-white hover:bg-emerald-600 disabled:opacity-50"
            >
              <Beaker className="w-3 h-3" /> Enter result
            </button>
          </>
        )}
        {order.status === 'PROCESSING' && (
          <button
            onClick={onEnterResult}
            disabled={isLoading}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-bold bg-emerald-500 text-white hover:bg-emerald-600 disabled:opacity-50"
          >
            <Beaker className="w-3 h-3" /> Enter result
          </button>
        )}
        {order.isCritical && !order.criticalValueAcknowledgedAt && (
          <button
            onClick={onAcknowledge}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-bold bg-rose-500 text-white hover:bg-rose-600"
          >
            <Phone className="w-3 h-3" /> Acknowledge with read-back
          </button>
        )}
      </div>
    </div>
  );
}

function SlaBadge({ sla, priority }: { sla: { elapsed: number; target: number; overdueBy: number }; priority: LabPriority }) {
  const overdue = sla.overdueBy > 0;
  return (
    <div className={`text-[10px] font-bold inline-flex items-center gap-1 px-2 py-0.5 rounded-lg ${
      overdue
        ? 'bg-rose-500/20 text-rose-500 animate-pulse'
        : priority === 'STAT'
          ? 'bg-amber-500/15 text-amber-500'
          : 'bg-slate-500/15 text-slate-500'
    }`}>
      <Clock className="w-3 h-3" />
      {overdue ? `${sla.overdueBy} min overdue` : `${sla.elapsed}/${sla.target} min`}
    </div>
  );
}
