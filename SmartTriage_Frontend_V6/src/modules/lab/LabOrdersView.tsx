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
import { useNavigate } from 'react-router-dom';
import {
  FlaskConical, Clock, AlertTriangle, Loader2, RefreshCw,
  Inbox, Activity, Beaker, CheckCircle2, XCircle, Phone,
  ClipboardCheck, AlertOctagon, History as HistoryIcon, Search,
  ChevronLeft, ChevronRight, Download, FileText, ExternalLink,
} from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { saveBlob } from '@/api/client';
import { labApi } from '@/api/lab';
import type { LabOrder, LabOrderStatus, LabPriority } from '@/api/lab';
import { subscribeToLabOrders } from '@/api/websocket';
import { formatDistanceToNow } from 'date-fns';
import { useTheme } from '@/hooks/useTheme';
import { PatientContextLine } from '@/components/PatientContextLine';
import { chartPath } from '@/lib/chartNav';
import { ResultEntryModal } from './ResultEntryModal';
import { RejectSpecimenModal } from './RejectSpecimenModal';
import { AcknowledgeCriticalModal } from './AcknowledgeCriticalModal';
import { VerifyResultModal, RejectVerificationModal, OverrideVerificationModal } from './VerificationModals';

type TechTab = 'inbox' | 'in-progress' | 'verification' | 'critical' | 'history';

/**
 * Workflow 2 refinement — statuses surfaced by the lab History tab.
 * Live tabs cover everything BEFORE these states; History is for
 * audit + re-look-up of completed work.
 */
const HISTORY_STATUS_OPTIONS: { value: LabOrderStatus | ''; label: string }[] = [
  { value: '',          label: 'All completed' },
  { value: 'RESULTED',  label: 'Resulted' },
  { value: 'REJECTED',  label: 'Rejected' },
  { value: 'CANCELLED', label: 'Cancelled' },
];

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
    case 'ORDERED':               return { label: 'Ordered',              className: 'bg-slate-500/15 text-slate-500' };
    case 'SPECIMEN_COLLECTED':    return { label: 'Specimen collected',   className: 'bg-blue-500/15 text-blue-500' };
    case 'RECEIVED_BY_LAB':       return { label: 'Received',             className: 'bg-indigo-500/15 text-indigo-500' };
    case 'PROCESSING':            return { label: 'Processing',           className: 'bg-violet-500/15 text-violet-500' };
    case 'AWAITING_VERIFICATION': return { label: 'Awaiting verification', className: 'bg-amber-500/15 text-amber-500' };
    case 'RESULTED':              return { label: 'Resulted',             className: 'bg-emerald-500/15 text-emerald-500' };
    case 'REJECTED':              return { label: 'Rejected',             className: 'bg-rose-500/15 text-rose-500' };
    case 'CANCELLED':             return { label: 'Cancelled',            className: 'bg-slate-500/15 text-slate-400' };
  }
}

/** Minutes since order, with target context. Negative = overdue. */
function slaInfo(order: LabOrder): { elapsed: number; target: number; overdueBy: number } {
  const elapsed = Math.floor((Date.now() - new Date(order.orderedAt).getTime()) / 60000);
  const target = PRIORITY_TARGET_MIN[order.priority];
  return { elapsed, target, overdueBy: Math.max(0, elapsed - target) };
}

export function LabOrdersView() {
  const navigate = useNavigate();
  const { glassCard, glassInner, isDark, text } = useTheme();
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  const techName = user?.fullName ?? '';

  const [activeTab, setActiveTab] = useState<TechTab>('inbox');
  const [inbox, setInbox] = useState<LabOrder[]>([]);
  const [inProgress, setInProgress] = useState<LabOrder[]>([]);
  const [verification, setVerification] = useState<LabOrder[]>([]);
  const [critical, setCritical] = useState<LabOrder[]>([]);

  // Workflow 2 refinement — History tab state. Paginated server-side
  // search across RESULTED / REJECTED / CANCELLED (and any other
  // state) so the tech can audit + re-look-up previously processed
  // orders without the page being limited to live work.
  const [historyRows, setHistoryRows] = useState<LabOrder[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyStatus, setHistoryStatus] = useState<LabOrderStatus | ''>('RESULTED');
  const [historyQuery, setHistoryQuery] = useState('');
  const [historyPage, setHistoryPage] = useState(0);
  const [historyTotalPages, setHistoryTotalPages] = useState(0);
  const [historyTotal, setHistoryTotal] = useState(0);
  const HISTORY_PAGE_SIZE = 25;

  // Senior tech privilege — drives the Verify / Reject buttons
  const isHeadLabTech = user?.designation === 'HEAD_LAB_TECHNICIAN';
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [reportBusy, setReportBusy] = useState(false);
  const [toast, setToast] = useState<{ type: 'ok' | 'err'; text: string } | null>(null);

  // ── Lab reporting pack (last 30 days) ──
  const reportRange = () => {
    const to = new Date().toISOString().slice(0, 10);
    const from = new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
    return { from, to };
  };
  const handleReportPdf = async () => {
    if (!hospitalId) return;
    setReportBusy(true);
    try {
      const { from, to } = reportRange();
      const { blob, filename } = await labApi.downloadReportPdf(hospitalId, from, to);
      saveBlob(blob, filename);
    } catch { /* surfaced via toast below */ setToast({ type: 'err', text: 'Report download failed' }); }
    finally { setReportBusy(false); }
  };
  const handleReportCsv = async () => {
    if (!hospitalId) return;
    setReportBusy(true);
    try {
      const { from, to } = reportRange();
      const { blob, filename } = await labApi.downloadReportCsv(hospitalId, from, to);
      saveBlob(blob, filename);
    } catch { setToast({ type: 'err', text: 'CSV download failed' }); }
    finally { setReportBusy(false); }
  };
  const flash = (type: 'ok' | 'err', text: string) => {
    setToast({ type, text });
    setTimeout(() => setToast(null), 3500);
  };

  // Modals
  const [resultTarget, setResultTarget] = useState<LabOrder | null>(null);
  const [rejectTarget, setRejectTarget] = useState<LabOrder | null>(null);
  const [ackTarget, setAckTarget] = useState<LabOrder | null>(null);
  const [verifyTarget, setVerifyTarget] = useState<LabOrder | null>(null);
  const [verifyRejectTarget, setVerifyRejectTarget] = useState<LabOrder | null>(null);
  const [overrideTarget, setOverrideTarget] = useState<LabOrder | null>(null);

  // ── Load ─────────────────────────────────────────────────────────
  const load = useCallback(async () => {
    if (!hospitalId) return;
    setLoading(true);
    try {
      const [inboxRes, ipRes, verifyRes, critRes] = await Promise.all([
        labApi.getInbox(hospitalId),
        labApi.getInProgress(hospitalId),
        labApi.getAwaitingVerification(hospitalId),
        labApi.getCritical(hospitalId),
      ]);
      setInbox(inboxRes || []);
      setInProgress(ipRes || []);
      setVerification(verifyRes || []);
      // getCritical returns CriticalValueResponse-shaped items; we treat them as LabOrder-shape
      // for the small set of fields the card touches (testName, orderNumber, resultValue, etc.).
      setCritical((critRes as unknown as LabOrder[]) || []);
    } catch (err) {
      console.error('[LabOrdersView] load failed:', err);
      flash('err', 'Failed to load lab queue');
    } finally {
      setLoading(false);
    }
  }, [hospitalId]);

  useEffect(() => { load(); }, [load]);

  // Workflow 2 refinement — history loader. Re-runs when filters
  // change OR the tab becomes active. Debounced search keeps a fast
  // typist from spamming the backend.
  const loadHistory = useCallback(async () => {
    if (!hospitalId) return;
    setHistoryLoading(true);
    try {
      const res = await labApi.getHistory(hospitalId, {
        status: historyStatus || undefined,
        q: historyQuery || undefined,
        page: historyPage,
        size: HISTORY_PAGE_SIZE,
      });
      setHistoryRows(res.content || []);
      setHistoryTotalPages(res.totalPages || 0);
      setHistoryTotal(res.totalElements || 0);
    } catch (err) {
      console.error('[LabOrdersView] history load failed:', err);
      flash('err', 'Failed to load history');
    } finally {
      setHistoryLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hospitalId, historyStatus, historyQuery, historyPage]);

  // Trigger history load when the tab becomes active OR filters change.
  useEffect(() => {
    if (activeTab !== 'history') return;
    const t = window.setTimeout(() => { void loadHistory(); }, 250);
    return () => window.clearTimeout(t);
  }, [activeTab, loadHistory]);

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

  async function handleAcknowledge(order: LabOrder) {
    if (actionLoading) return;
    setActionLoading(order.id);
    try {
      await labApi.acknowledgeOrder(order.id, techName || undefined);
      flash('ok', `Acknowledged ${order.orderNumber}`);
    } catch (err: any) {
      flash('err', err?.message || 'Acknowledge failed');
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
    { id: 'inbox',        label: 'Inbox',        icon: Inbox,          count: inbox.length },
    { id: 'in-progress',  label: 'In Progress',  icon: Activity,       count: inProgress.length },
    { id: 'verification', label: 'Verification', icon: ClipboardCheck, count: verification.length },
    { id: 'critical',     label: 'Critical',     icon: AlertOctagon,   count: critical.length },
    // Workflow 2 refinement — paginated history of completed work.
    // Count omitted (0) because the total is paginated server-side
    // and shown on the panel header. A zero count next to a tab the
    // tech KNOWS has data is worse than no count at all.
    { id: 'history',      label: 'History',      icon: HistoryIcon,    count: 0 },
  ];

  const list =
    activeTab === 'inbox'        ? inbox :
    activeTab === 'in-progress'  ? inProgress :
    activeTab === 'verification' ? verification :
    activeTab === 'critical'     ? critical :
                                   [] /* history uses a dedicated panel below */;

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-7xl mx-auto space-y-4 animate-fade-in">

        {/* Header */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-cyan-500/20 flex items-center justify-center">
                  <FlaskConical className="w-5 h-5 text-cyan-300" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">Lab — Worklist</h1>
                  <p className="text-white/50 text-xs">
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
                  onClick={handleReportPdf}
                  disabled={reportBusy}
                  className="inline-flex items-center gap-1.5 px-3 py-2 rounded-xl bg-white/15 text-white text-xs font-bold hover:bg-white/25 transition-colors disabled:opacity-50"
                  title="Lab reporting pack (PDF) — last 30 days"
                >
                  {reportBusy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <FileText className="w-3.5 h-3.5" />} Report PDF
                </button>
                <button
                  onClick={handleReportCsv}
                  disabled={reportBusy}
                  className="inline-flex items-center gap-1.5 px-3 py-2 rounded-xl bg-white/15 text-white text-xs font-bold hover:bg-white/25 transition-colors disabled:opacity-50"
                  title="Lab orders CSV — last 30 days"
                >
                  <Download className="w-3.5 h-3.5" /> CSV
                </button>
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
              className={`flex items-center gap-2 px-4 py-2 rounded-xl text-xs font-bold transition-all border ${
                activeTab === id
                  ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md border-transparent'
                  : `${text.body} hover:bg-white/5 border-transparent`
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

        {/* History panel — Workflow 2 refinement. Lives in the same
            tab strip but renders a different shape (search +
            filters + paginated table) so the tech can audit and
            re-look-up completed work without scrolling a card list. */}
        {activeTab === 'history' ? (
          <HistoryPanel
            rows={historyRows}
            loading={historyLoading}
            status={historyStatus}
            setStatus={(s) => { setHistoryStatus(s); setHistoryPage(0); }}
            query={historyQuery}
            setQuery={(q) => { setHistoryQuery(q); setHistoryPage(0); }}
            page={historyPage}
            setPage={setHistoryPage}
            totalPages={historyTotalPages}
            total={historyTotal}
            onRefresh={loadHistory}
            onOpenChart={(visitId) => visitId && navigate(chartPath(visitId))}
            glassCard={glassCard}
            glassInner={glassInner}
            text={text}
            isDark={isDark}
          />
        ) : (
        <>
        {/* List */}
        {loading && list.length === 0 ? (
          <div className="flex items-center justify-center py-12">
            <Loader2 className="w-6 h-6 animate-spin text-violet-500" />
          </div>
        ) : list.length === 0 ? (
          <div className="rounded-2xl p-8 text-center animate-fade-up" style={glassCard}>
            <Beaker className="w-10 h-10 mx-auto mb-3 text-slate-400" />
            <p className={`text-sm font-bold ${text.heading}`}>
              {activeTab === 'inbox'        && 'No orders waiting'}
              {activeTab === 'in-progress'  && 'No orders being processed'}
              {activeTab === 'verification' && 'No results awaiting verification'}
              {activeTab === 'critical'     && 'No unacknowledged critical results'}
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
                isHeadLabTech={isHeadLabTech}
                onOpenChart={() => order.visitId && navigate(chartPath(order.visitId))}
                onReceive={() => handleReceive(order)}
                onReject={() => setRejectTarget(order)}
                onAckOrder={() => handleAcknowledge(order)}
                onStartProcessing={() => handleStartProcessing(order)}
                onEnterResult={() => setResultTarget(order)}
                onAcknowledge={() => setAckTarget(order)}
                onVerify={() => setVerifyTarget(order)}
                onVerifyReject={() => setVerifyRejectTarget(order)}
                onOverride={() => setOverrideTarget(order)}
              />
            ))}
          </div>
        )}
        </>
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
      {verifyTarget && (
        <VerifyResultModal
          order={verifyTarget}
          verifiedByName={techName}
          onClose={() => setVerifyTarget(null)}
          onSaved={() => { setVerifyTarget(null); load(); }}
        />
      )}
      {verifyRejectTarget && (
        <RejectVerificationModal
          order={verifyRejectTarget}
          rejectedByName={techName}
          onClose={() => setVerifyRejectTarget(null)}
          onSaved={() => { setVerifyRejectTarget(null); load(); }}
        />
      )}
      {overrideTarget && (
        <OverrideVerificationModal
          order={overrideTarget}
          overrideByName={techName}
          onClose={() => setOverrideTarget(null)}
          onSaved={() => { setOverrideTarget(null); load(); }}
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
  isHeadLabTech: boolean;
  onOpenChart: () => void;
  onReceive: () => void;
  onReject: () => void;
  onAckOrder: () => void;
  onStartProcessing: () => void;
  onEnterResult: () => void;
  onAcknowledge: () => void;
  onVerify: () => void;
  onVerifyReject: () => void;
  onOverride: () => void;
}

function LabOrderCard({
  order, animationDelay, glassCard, glassInner, text, isLoading, isHeadLabTech,
  onOpenChart, onReceive, onReject, onAckOrder, onStartProcessing, onEnterResult, onAcknowledge,
  onVerify, onVerifyReject, onOverride,
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

      {/* Patient context FIRST — name WHO the order is for and WHERE
          they are; click to open the chart. */}
      <button
        type="button"
        onClick={onOpenChart}
        disabled={!order.visitId}
        className={`group w-full text-left mb-2 ${order.visitId ? 'cursor-pointer' : 'cursor-default'}`}
        title={order.visitId ? 'Open patient chart' : undefined}
      >
        <div className="flex items-center justify-between gap-2">
          <PatientContextLine
            patientName={order.patientName}
            zone={order.currentZone}
            bedLabel={order.currentBedLabel}
            visitNumber={order.visitNumber}
            className={`text-[11px] ${text.body}`}
          />
          {order.visitId && (
            <ExternalLink className={`w-3.5 h-3.5 flex-shrink-0 ${text.muted} opacity-0 group-hover:opacity-100 transition-opacity`} />
          )}
        </div>
      </button>

      {/* Test + indication */}
      <button
        type="button"
        onClick={onOpenChart}
        disabled={!order.visitId}
        className={`block w-full text-left mb-2 ${order.visitId ? 'cursor-pointer hover:opacity-80' : 'cursor-default'}`}
      >
        <h4 className={`text-sm font-bold ${text.heading}`}>{order.testName}</h4>
        <p className={`text-[10px] font-mono ${text.muted}`}>
          {order.orderNumber}{order.accessionNumber ? ` • ${order.accessionNumber}` : ''}
        </p>
      </button>
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
          {order.components && order.components.length > 0 ? (
            /* Per-analyte (panel) breakdown — each row independently abnormal/critical-flagged. */
            <div className="space-y-1">
              {order.components.map((c) => (
                <div key={c.analyteName} className="flex items-center justify-between text-xs">
                  <span className={text.muted}>{c.analyteName}</span>
                  <span className="flex items-center gap-1.5">
                    <span className={`font-bold ${c.isCritical ? 'text-rose-500' : c.isAbnormal ? 'text-amber-500' : text.body}`}>
                      {c.resultValue}{c.resultUnit ? ` ${c.resultUnit}` : ''}
                    </span>
                    {c.isCritical && <span className="text-[9px] font-bold px-1 rounded bg-rose-500/15 text-rose-500">CRIT</span>}
                    {!c.isCritical && c.isAbnormal && <span className="text-[9px] font-bold px-1 rounded bg-amber-500/15 text-amber-500">ABN</span>}
                    {(c.referenceLow != null || c.referenceHigh != null) && (
                      <span className={`text-[9px] ${text.muted}`}>({c.referenceLow ?? '–'}–{c.referenceHigh ?? '–'})</span>
                    )}
                  </span>
                </div>
              ))}
            </div>
          ) : (
            <>
              <div className={`text-sm font-bold ${order.isCritical ? 'text-rose-500' : order.isAbnormal ? 'text-amber-500' : text.heading}`}>
                {order.resultValue} {order.resultUnit}
              </div>
              {order.referenceRangeMin != null && order.referenceRangeMax != null && (
                <div className={`text-[10px] ${text.muted}`}>
                  Ref: {order.referenceRangeMin} – {order.referenceRangeMax} {order.resultUnit}
                </div>
              )}
            </>
          )}
        </div>
      )}

      {order.status === 'REJECTED' && (
        <div className="rounded-xl p-3 mb-3 bg-rose-500/10 ring-1 ring-rose-500/20">
          <div className="text-[10px] uppercase font-bold mb-1 text-rose-500">Rejected</div>
          <div className={`text-xs ${text.body}`}>{order.rejectionReason}{order.rejectionNotes ? ` — ${order.rejectionNotes}` : ''}</div>
        </div>
      )}

      {/* AWAITING_VERIFICATION preview — show value + timeout countdown */}
      {order.status === 'AWAITING_VERIFICATION' && (
        <div className="rounded-xl p-3 mb-3 bg-amber-500/10 ring-1 ring-amber-500/20">
          <div className="text-[10px] uppercase font-bold mb-1 text-amber-500">Pending verification</div>
          <div className={`text-sm font-bold ${order.isCritical ? 'text-rose-500' : text.heading}`}>
            {order.resultValue} {order.resultUnit}
          </div>
          <div className={`text-[10px] mt-1 ${text.muted}`}>
            Entered by {order.enteredByName ?? 'lab tech'}
            {order.verificationTimeoutAt && (
              <> • auto-release {formatDistanceToNow(new Date(order.verificationTimeoutAt), { addSuffix: true })}</>
            )}
          </div>
          {order.verificationRejectionCount > 0 && (
            <div className="text-[10px] mt-1 text-rose-500">
              Bounced back {order.verificationRejectionCount}× — last reason: {order.verificationRejectionReason}
            </div>
          )}
        </div>
      )}

      {/* Acknowledged-by-lab indicator — shows the lab has picked the order up. */}
      {order.acknowledgedByLabAt && (
        <div className={`text-[10px] mb-2 inline-flex items-center gap-1 ${text.muted}`}>
          <ClipboardCheck className="w-3 h-3 text-cyan-500" /> Acknowledged{order.acknowledgedByLabName ? ` by ${order.acknowledgedByLabName}` : ''}
        </div>
      )}

      {/* Actions */}
      <div className="flex flex-wrap gap-2">
        {(order.status === 'ORDERED' || order.status === 'SPECIMEN_COLLECTED') && (
          <>
            {!order.acknowledgedByLabAt && (
              <button
                onClick={onAckOrder}
                disabled={isLoading}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-[11px] font-bold bg-cyan-500/15 text-cyan-500 hover:bg-cyan-500/25 disabled:opacity-50"
              >
                <ClipboardCheck className="w-3 h-3" /> Acknowledge
              </button>
            )}
            <button
              onClick={onReceive}
              disabled={isLoading}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-[11px] font-bold bg-cyan-600 hover:bg-cyan-700 text-white disabled:opacity-50"
            >
              {isLoading ? <Loader2 className="w-3 h-3 animate-spin" /> : <ClipboardCheck className="w-3 h-3" />}
              Receive
            </button>
            <button
              onClick={onReject}
              disabled={isLoading}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-[11px] font-bold bg-rose-500/15 text-rose-500 hover:bg-rose-500/25 disabled:opacity-50"
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
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-[11px] font-bold bg-cyan-600 hover:bg-cyan-700 text-white disabled:opacity-50"
            >
              {isLoading ? <Loader2 className="w-3 h-3 animate-spin" /> : <Activity className="w-3 h-3" />}
              Start processing
            </button>
            <button
              onClick={onEnterResult}
              disabled={isLoading}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-[11px] font-bold bg-cyan-600 hover:bg-cyan-700 text-white disabled:opacity-50"
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
        {order.status === 'AWAITING_VERIFICATION' && (
          <>
            {isHeadLabTech && (
              <>
                <button
                  onClick={onVerify}
                  disabled={isLoading}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-[11px] font-bold bg-cyan-600 hover:bg-cyan-700 text-white disabled:opacity-50"
                >
                  <CheckCircle2 className="w-3 h-3" /> Verify & release
                </button>
                <button
                  onClick={onVerifyReject}
                  disabled={isLoading}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-[11px] font-bold bg-rose-500/15 text-rose-500 hover:bg-rose-500/25 disabled:opacity-50"
                >
                  <XCircle className="w-3 h-3" /> Reject (bounce back)
                </button>
              </>
            )}
            {!isHeadLabTech && (
              <button
                onClick={onOverride}
                disabled={isLoading}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-[11px] font-bold bg-amber-500/15 text-amber-600 hover:bg-amber-500/25 disabled:opacity-50"
                title="Emergency override — releases without senior verification"
              >
                <AlertOctagon className="w-3 h-3" /> Release without verification
              </button>
            )}
          </>
        )}
        {order.isCritical && !order.criticalValueAcknowledgedAt && order.status === 'RESULTED' && (
          <button
            onClick={onAcknowledge}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-[11px] font-bold bg-rose-500 text-white hover:bg-rose-600"
          >
            <Phone className="w-3 h-3" /> Acknowledge with read-back
          </button>
        )}
      </div>
    </div>
  );
}

/* ════════════════════════════════════════════════════════════════════
   HistoryPanel — Workflow 2 refinement.

   Paginated server-side search across completed orders (RESULTED /
   REJECTED / CANCELLED by default; "All completed" widens to every
   state). Search box hits the backend with a debounce, so typing
   doesn't spam the network. Sorted newest first.
   ════════════════════════════════════════════════════════════════════ */
function HistoryPanel({
  rows, loading, status, setStatus, query, setQuery, page, setPage,
  totalPages, total, onRefresh, onOpenChart, glassCard, glassInner, text, isDark,
}: {
  rows: LabOrder[];
  loading: boolean;
  status: LabOrderStatus | '';
  setStatus: (s: LabOrderStatus | '') => void;
  query: string;
  setQuery: (q: string) => void;
  page: number;
  setPage: (p: number) => void;
  totalPages: number;
  total: number;
  onRefresh: () => void;
  onOpenChart: (visitId: string) => void;
  glassCard: React.CSSProperties;
  glassInner: React.CSSProperties;
  text: { heading: string; muted: string; body: string; accent: string; label: string };
  isDark: boolean;
}) {
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
  return (
    <div className="space-y-3 animate-fade-up">
      {/* Filter bar */}
      <div className="rounded-2xl p-3 flex flex-col md:flex-row md:items-center gap-2" style={glassCard}>
        <div className="relative flex-1 min-w-0">
          <Search className={`w-3.5 h-3.5 absolute left-2.5 top-1/2 -translate-y-1/2 ${text.muted}`} />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search order number, test name, or accession…"
            className={`w-full pl-8 pr-3 py-2 text-sm rounded-lg focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
            style={glassInner}
          />
        </div>
        <select
          value={status}
          onChange={(e) => setStatus(e.target.value as LabOrderStatus | '')}
          className={`px-3 py-2 text-xs font-bold rounded-lg focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
          style={glassInner}
        >
          {HISTORY_STATUS_OPTIONS.map((opt) => (
            <option key={opt.value || 'all'} value={opt.value}>{opt.label}</option>
          ))}
        </select>
        <button
          onClick={onRefresh}
          disabled={loading}
          className="inline-flex items-center gap-1.5 px-3 py-2 text-xs font-bold rounded-xl bg-violet-500/15 text-violet-500 hover:bg-violet-500/25 transition-colors disabled:opacity-50"
        >
          {loading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5" />}
          Refresh
        </button>
      </div>

      {/* Result table */}
      <div className="rounded-2xl overflow-hidden" style={glassCard}>
        <div className="flex items-center justify-between px-4 py-2.5" style={{ borderBottom: borderStyle }}>
          <span className={`text-[11px] font-bold uppercase tracking-wider ${text.muted}`}>
            {total === 0 ? 'No matches' : `${total} order${total === 1 ? '' : 's'}`}
          </span>
          <span className={`text-[11px] ${text.muted}`}>
            Newest first
          </span>
        </div>
        {loading && rows.length === 0 ? (
          <div className="flex items-center justify-center py-12">
            <Loader2 className="w-6 h-6 animate-spin text-violet-500" />
          </div>
        ) : rows.length === 0 ? (
          <div className="px-4 py-10 text-center">
            <Beaker className="w-8 h-8 mx-auto mb-2 text-slate-400" />
            <p className={`text-sm font-bold ${text.heading}`}>No matching history</p>
            <p className={`text-xs ${text.muted}`}>Try widening the status filter or clearing the search box.</p>
          </div>
        ) : (
          <ul>
            {rows.map((r) => {
              const sc = statusChip(r.status);
              const pc = priorityColor(r.priority);
              return (
                <li
                  key={r.id}
                  onClick={() => r.visitId && onOpenChart(r.visitId)}
                  className={`px-4 py-3 last:border-0 hover:bg-white/5 ${r.visitId ? 'cursor-pointer' : ''}`}
                  style={{ borderBottom: borderStyle }}
                  title={r.visitId ? 'Open patient chart' : undefined}
                >
                  <div className="flex items-start gap-3 flex-wrap">
                    <div className="flex-1 min-w-0">
                      {/* Identity FIRST — who + where, before the clinical payload. */}
                      <PatientContextLine
                        patientName={r.patientName}
                        zone={r.currentZone}
                        bedLabel={r.currentBedLabel}
                        visitNumber={r.visitNumber}
                        className={`text-[11px] mb-1 ${text.body}`}
                      />
                      <div className="flex items-center gap-2 flex-wrap">
                        <span className={`font-mono text-[11px] ${text.accent}`}>{r.orderNumber}</span>
                        <span className={`text-sm font-bold ${text.heading}`}>{r.testName}</span>
                        <span className={`text-[10px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded ${pc.chip}`}>{r.priority}</span>
                        <span className={`text-[10px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded ${sc.className}`}>{sc.label}</span>
                        {r.isCritical && (
                          <span className="inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-red-600" style={{ background: 'rgba(239,68,68,0.08)', border: '1px solid rgba(239,68,68,0.2)' }}>Critical</span>
                        )}
                        {!r.isCritical && r.isAbnormal && (
                          <span className="inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-amber-600" style={{ background: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.2)' }}>Abnormal</span>
                        )}
                      </div>
                      <div className={`text-[11px] mt-0.5 ${text.muted} flex items-center gap-3 flex-wrap`}>
                        {r.accessionNumber && <span>Acc <span className={`font-mono ${text.body}`}>{r.accessionNumber}</span></span>}
                        <span>Ordered {formatDistanceToNow(new Date(r.orderedAt), { addSuffix: true })}</span>
                        {r.resultedAt && <span>Resulted {formatDistanceToNow(new Date(r.resultedAt), { addSuffix: true })}</span>}
                        {r.turnaroundMinutes != null && <span>TAT {r.turnaroundMinutes} min</span>}
                      </div>
                      {r.resultValue && (
                        <div className={`mt-1 text-[12px] ${text.body} truncate`}>
                          Result: <span className="font-medium">{r.resultValue}</span>
                          {r.resultUnit && <span className={text.muted}> {r.resultUnit}</span>}
                        </div>
                      )}
                    </div>
                  </div>
                </li>
              );
            })}
          </ul>
        )}
        {/* Pagination */}
        {totalPages > 1 && (
          <div className="flex items-center justify-between px-4 py-2" style={{ borderTop: borderStyle }}>
            <button
              onClick={() => setPage(Math.max(0, page - 1))}
              disabled={page === 0 || loading}
              className={`inline-flex items-center gap-1 px-2 py-1 text-xs font-bold rounded hover:bg-white/5 disabled:opacity-40 ${text.body}`}
            >
              <ChevronLeft className="w-3 h-3" /> Prev
            </button>
            <span className={`text-[11px] ${text.muted}`}>
              Page {page + 1} of {totalPages}
            </span>
            <button
              onClick={() => setPage(Math.min(totalPages - 1, page + 1))}
              disabled={page >= totalPages - 1 || loading}
              className={`inline-flex items-center gap-1 px-2 py-1 text-xs font-bold rounded hover:bg-white/5 disabled:opacity-40 ${text.body}`}
            >
              Next <ChevronRight className="w-3 h-3" />
            </button>
          </div>
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
