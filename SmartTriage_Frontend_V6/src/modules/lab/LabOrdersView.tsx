/* ═══════════════════════════════════════════════════════════════
   Laboratory Orders — Module 14: Lab Integration
   Full lifecycle: Order → Collect → Receive → Result → Acknowledge
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback } from 'react';
import {
  FlaskConical, Clock, CheckCircle, AlertTriangle, Search,
  Loader2, RefreshCw, Beaker, TestTube, ArrowRight,
  X, MessageSquare, ChevronRight, Zap, Eye,
  FileText, XCircle,
} from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { labApi } from '@/api/lab';
import type { LabOrder, RecordLabResultRequest } from '@/api/lab';
import { format, formatDistanceToNow } from 'date-fns';
import { useTheme } from '@/hooks/useTheme';

// ── Status pipeline ──
const STATUS_STEPS = ['ORDERED', 'SPECIMEN_COLLECTED', 'RECEIVED_BY_LAB', 'RESULTED'] as const;

const STATUS_LABELS: Record<string, string> = {
  ORDERED: 'Ordered',
  SPECIMEN_COLLECTED: 'Collected',
  RECEIVED_BY_LAB: 'Received',
  RESULTED: 'Resulted',
  CANCELLED: 'Cancelled',
};

// ── Priority styling ──
const PRIORITY_STYLE: Record<string, { bg: string; border: string; text: string }> = {
  STAT: {
    bg: 'rgba(239,68,68,0.08)',
    border: '1px solid rgba(239,68,68,0.25)',
    text: 'text-red-600',
  },
  URGENT: {
    bg: 'rgba(245,158,11,0.08)',
    border: '1px solid rgba(245,158,11,0.25)',
    text: 'text-amber-600',
  },
  ROUTINE: {
    bg: 'rgba(100,116,139,0.08)',
    border: '1px solid rgba(100,116,139,0.2)',
    text: 'text-slate-600',
  },
};

function getPriorityStyle(priority: string) {
  return PRIORITY_STYLE[priority] || PRIORITY_STYLE.ROUTINE;
}

function getStatusIndex(status: string): number {
  const idx = STATUS_STEPS.indexOf(status as typeof STATUS_STEPS[number]);
  return idx >= 0 ? idx : -1;
}

type FilterTab = 'pending' | 'stat' | 'critical' | 'visit';

export function LabOrdersView() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';

  // ── Data state ──
  const [pendingOrders, setPendingOrders] = useState<LabOrder[]>([]);
  const [statOrders, setStatOrders] = useState<LabOrder[]>([]);
  const [criticalOrders, setCriticalOrders] = useState<LabOrder[]>([]);
  const [visitOrders, setVisitOrders] = useState<LabOrder[]>([]);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState<string | null>(null);

  // ── Filter / Search ──
  const [activeTab, setActiveTab] = useState<FilterTab>('pending');
  const [searchQuery, setSearchQuery] = useState('');
  const [visitIdInput, setVisitIdInput] = useState('');
  const [visitSearched, setVisitSearched] = useState(false);

  // ── Result dialog ──
  const [resultDialogOpen, setResultDialogOpen] = useState(false);
  const [resultOrderId, setResultOrderId] = useState<string | null>(null);
  const [resultForm, setResultForm] = useState<RecordLabResultRequest>({
    resultValue: '',
    resultUnit: '',
    resultNumeric: undefined,
    referenceRangeMin: undefined,
    referenceRangeMax: undefined,
    isAbnormal: false,
  });
  const [resultSubmitting, setResultSubmitting] = useState(false);

  // ── Cancel dialog ──
  const [cancelDialogOpen, setCancelDialogOpen] = useState(false);
  const [cancelOrderId, setCancelOrderId] = useState<string | null>(null);
  const [cancelReason, setCancelReason] = useState('');
  const [cancelSubmitting, setCancelSubmitting] = useState(false);

  // ── Data loading ──
  const loadData = useCallback(async () => {
    if (!hospitalId) return;
    setLoading(true);
    try {
      const [pendingRes, statRes, criticalRes] = await Promise.all([
        labApi.getPending(hospitalId, 0),
        labApi.getStat(hospitalId),
        labApi.getCritical(hospitalId),
      ]);
      setPendingOrders(pendingRes.content || []);
      setStatOrders(statRes || []);
      setCriticalOrders(criticalRes || []);
    } catch (err) {
      console.error('[LabOrdersView] Failed to load data:', err);
    } finally {
      setLoading(false);
    }
  }, [hospitalId]);

  useEffect(() => { loadData(); }, [loadData]);

  // Auto-refresh every 30s
  useEffect(() => {
    const iv = setInterval(loadData, 30000);
    return () => clearInterval(iv);
  }, [loadData]);

  // ── Visit search ──
  const searchByVisit = useCallback(async () => {
    if (!visitIdInput.trim()) return;
    setLoading(true);
    try {
      const res = await labApi.getForVisit(visitIdInput.trim(), 0);
      setVisitOrders(res.content || []);
      setVisitSearched(true);
    } catch (err) {
      console.error('[LabOrdersView] Visit search failed:', err);
      setVisitOrders([]);
      setVisitSearched(true);
    } finally {
      setLoading(false);
    }
  }, [visitIdInput]);

  // ── Get current list based on tab ──
  const currentOrders = (() => {
    let orders: LabOrder[];
    switch (activeTab) {
      case 'stat': orders = statOrders; break;
      case 'critical': orders = criticalOrders; break;
      case 'visit': orders = visitOrders; break;
      default: orders = pendingOrders;
    }
    if (!searchQuery.trim()) return orders;
    const q = searchQuery.toLowerCase();
    return orders.filter(
      (o) =>
        o.testName.toLowerCase().includes(q) ||
        o.orderNumber.toLowerCase().includes(q) ||
        o.orderedByName?.toLowerCase().includes(q)
    );
  })();

  // ── Summary counts ──
  const statCount = statOrders.length;
  const criticalCount = criticalOrders.length;
  const pendingCount = pendingOrders.length;

  // ── Actions ──
  const handleCollectSpecimen = async (orderId: string) => {
    setActionLoading(orderId);
    try {
      await labApi.collectSpecimen(orderId);
      loadData();
    } catch (err) { console.error(err); }
    finally { setActionLoading(null); }
  };

  const handleReceiveInLab = async (orderId: string) => {
    setActionLoading(orderId);
    try {
      await labApi.receiveInLab(orderId);
      loadData();
    } catch (err) { console.error(err); }
    finally { setActionLoading(null); }
  };

  const handleAcknowledgeCritical = async (orderId: string) => {
    setActionLoading(orderId);
    try {
      await labApi.acknowledgeCritical(orderId);
      loadData();
    } catch (err) { console.error(err); }
    finally { setActionLoading(null); }
  };

  const openResultDialog = (orderId: string) => {
    setResultOrderId(orderId);
    setResultForm({ resultValue: '', resultUnit: '', resultNumeric: undefined, referenceRangeMin: undefined, referenceRangeMax: undefined, isAbnormal: false });
    setResultDialogOpen(true);
  };

  const submitResult = async () => {
    if (!resultOrderId || !resultForm.resultValue.trim()) return;
    setResultSubmitting(true);
    try {
      await labApi.recordResult(resultOrderId, resultForm);
      setResultDialogOpen(false);
      loadData();
    } catch (err) { console.error(err); }
    finally { setResultSubmitting(false); }
  };

  const openCancelDialog = (orderId: string) => {
    setCancelOrderId(orderId);
    setCancelReason('');
    setCancelDialogOpen(true);
  };

  const submitCancel = async () => {
    if (!cancelOrderId || !cancelReason.trim()) return;
    setCancelSubmitting(true);
    try {
      await labApi.cancel(cancelOrderId, cancelReason);
      setCancelDialogOpen(false);
      loadData();
    } catch (err) { console.error(err); }
    finally { setCancelSubmitting(false); }
  };

  // ── Render status pipeline ──
  const renderStatusPipeline = (order: LabOrder) => {
    const currentIdx = order.status === 'CANCELLED' ? -1 : getStatusIndex(order.status);
    return (
      <div className="flex items-center gap-1 mt-3">
        {STATUS_STEPS.map((step, i) => {
          const isComplete = i <= currentIdx;
          const isCurrent = i === currentIdx;
          return (
            <div key={step} className="flex items-center gap-1">
              <div className="flex flex-col items-center">
                <div
                  className={`w-6 h-6 rounded-full flex items-center justify-center text-[9px] font-bold transition-all ${
                    isComplete
                      ? 'bg-emerald-500 text-white shadow-md shadow-emerald-500/30'
                      : isCurrent
                        ? 'bg-cyan-500 text-white shadow-md shadow-cyan-500/30 animate-pulse'
                        : isDark
                          ? 'bg-white/10 text-slate-500'
                          : 'bg-slate-100 text-slate-400'
                  }`}
                >
                  {isComplete ? <CheckCircle className="w-3.5 h-3.5" /> : i + 1}
                </div>
                <span className={`text-[8px] font-bold mt-1 uppercase tracking-wider ${isComplete ? 'text-emerald-500' : text.muted}`}>
                  {STATUS_LABELS[step]}
                </span>
              </div>
              {i < STATUS_STEPS.length - 1 && (
                <div className={`w-6 h-0.5 mb-4 rounded-full ${isComplete ? 'bg-emerald-500' : isDark ? 'bg-white/10' : 'bg-slate-200'}`} />
              )}
            </div>
          );
        })}
        {order.status === 'CANCELLED' && (
          <div className="ml-2 flex items-center gap-1">
            <XCircle className="w-4 h-4 text-red-500" />
            <span className="text-[10px] font-bold text-red-500">Cancelled</span>
          </div>
        )}
      </div>
    );
  };

  // ── Render action buttons for an order ──
  const renderActions = (order: LabOrder) => {
    const isLoading = actionLoading === order.id;
    if (order.status === 'CANCELLED' || order.status === 'RESULTED') return null;

    return (
      <div className="flex flex-wrap gap-2 mt-3">
        {order.status === 'ORDERED' && (
          <>
            <button
              onClick={() => handleCollectSpecimen(order.id)}
              disabled={isLoading}
              className="inline-flex items-center gap-1.5 px-3.5 py-2 text-[11px] font-bold text-white bg-gradient-to-r from-cyan-600 to-cyan-500 hover:shadow-lg hover:-translate-y-0.5 rounded-xl transition-all shadow-md shadow-cyan-600/15 disabled:opacity-50"
            >
              {isLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <TestTube className="w-3.5 h-3.5" />}
              Collect Specimen
            </button>
            <button
              onClick={() => openCancelDialog(order.id)}
              className={`inline-flex items-center gap-1.5 px-3.5 py-2 text-[11px] font-bold rounded-xl transition-all ${
                isDark ? 'text-slate-400 bg-white/5 hover:bg-white/10 border border-white/10' : 'text-slate-500 bg-white/60 hover:bg-white/80 border border-slate-200/60'
              }`}
            >
              <XCircle className="w-3.5 h-3.5" /> Cancel
            </button>
          </>
        )}
        {order.status === 'SPECIMEN_COLLECTED' && (
          <button
            onClick={() => handleReceiveInLab(order.id)}
            disabled={isLoading}
            className="inline-flex items-center gap-1.5 px-3.5 py-2 text-[11px] font-bold text-white bg-gradient-to-r from-indigo-600 to-indigo-500 hover:shadow-lg hover:-translate-y-0.5 rounded-xl transition-all shadow-md shadow-indigo-600/15 disabled:opacity-50"
          >
            {isLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Beaker className="w-3.5 h-3.5" />}
            Receive in Lab
          </button>
        )}
        {order.status === 'RECEIVED_BY_LAB' && (
          <button
            onClick={() => openResultDialog(order.id)}
            disabled={isLoading}
            className="inline-flex items-center gap-1.5 px-3.5 py-2 text-[11px] font-bold text-white bg-gradient-to-r from-emerald-600 to-emerald-500 hover:shadow-lg hover:-translate-y-0.5 rounded-xl transition-all shadow-md shadow-emerald-600/15 disabled:opacity-50"
          >
            {isLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <FileText className="w-3.5 h-3.5" />}
            Record Result
          </button>
        )}
        {order.isCritical && !order.criticalValueAcknowledgedAt && order.status === 'RESULTED' && (
          <button
            onClick={() => handleAcknowledgeCritical(order.id)}
            disabled={isLoading}
            className="inline-flex items-center gap-1.5 px-3.5 py-2 text-[11px] font-bold text-white bg-gradient-to-r from-red-600 to-red-500 hover:shadow-lg hover:-translate-y-0.5 rounded-xl transition-all shadow-md shadow-red-600/15 disabled:opacity-50 animate-pulse"
          >
            {isLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <AlertTriangle className="w-3.5 h-3.5" />}
            Acknowledge Critical
          </button>
        )}
      </div>
    );
  };

  // ── Render result display ──
  const renderResult = (order: LabOrder) => {
    if (!order.resultValue) return null;
    const hasRange = order.referenceRangeMin != null || order.referenceRangeMax != null;
    return (
      <div
        className={`mt-3 px-4 py-3 rounded-xl ${order.isAbnormal ? '' : ''}`}
        style={{
          background: order.isAbnormal
            ? order.isCritical ? 'rgba(239,68,68,0.06)' : 'rgba(245,158,11,0.06)'
            : 'rgba(34,197,94,0.06)',
          border: order.isAbnormal
            ? order.isCritical ? '1px solid rgba(239,68,68,0.2)' : '1px solid rgba(245,158,11,0.2)'
            : '1px solid rgba(34,197,94,0.15)',
        }}
      >
        <div className="flex items-center gap-3">
          <div className="flex items-baseline gap-1.5">
            <span className={`text-lg font-black ${order.isAbnormal ? (order.isCritical ? 'text-red-500' : 'text-amber-500') : 'text-emerald-500'}`}>
              {order.resultValue}
            </span>
            {order.resultUnit && (
              <span className={`text-xs font-medium ${text.muted}`}>{order.resultUnit}</span>
            )}
          </div>
          {order.isAbnormal && (
            <span
              className={`inline-flex items-center gap-1 px-2 py-0.5 text-[9px] font-bold uppercase tracking-wider rounded-lg ${
                order.isCritical
                  ? 'bg-red-500/10 text-red-600 border border-red-500/20'
                  : 'bg-amber-500/10 text-amber-600 border border-amber-500/20'
              }`}
            >
              <AlertTriangle className="w-3 h-3" />
              {order.isCritical ? 'Critical' : 'Abnormal'}
            </span>
          )}
          {!order.isAbnormal && (
            <span className="inline-flex items-center gap-1 px-2 py-0.5 text-[9px] font-bold uppercase tracking-wider rounded-lg bg-emerald-500/10 text-emerald-600 border border-emerald-500/20">
              <CheckCircle className="w-3 h-3" /> Normal
            </span>
          )}
        </div>
        {hasRange && (
          <p className={`text-[10px] font-medium mt-1.5 ${text.muted}`}>
            Reference: {order.referenceRangeMin ?? '—'} – {order.referenceRangeMax ?? '—'} {order.resultUnit || ''}
          </p>
        )}
        {order.resultedAt && (
          <p className={`text-[10px] font-medium mt-1 ${text.muted}`}>
            Resulted {formatDistanceToNow(new Date(order.resultedAt), { addSuffix: true })}
          </p>
        )}
      </div>
    );
  };

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-6xl mx-auto space-y-4 animate-fade-in">

        {/* ── Header Banner ── */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-4">
                <div className="w-12 h-12 bg-white/20 rounded-2xl flex items-center justify-center shadow-lg">
                  <FlaskConical className="w-6 h-6 text-white" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">Laboratory Orders</h1>
                  <p className="text-white/70 text-xs font-medium">Order tracking, specimen collection & result management</p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                <div className="bg-white/15 backdrop-blur rounded-xl px-3 py-1.5 flex items-center gap-2">
                  <div className="w-2 h-2 bg-emerald-400 rounded-full animate-pulse" />
                  <span className="text-xs font-semibold text-white/90">Live</span>
                </div>
                <button
                  onClick={loadData}
                  className="w-9 h-9 rounded-xl bg-white/10 flex items-center justify-center hover:bg-white/20 transition-colors"
                >
                  <RefreshCw className="w-4 h-4 text-white" />
                </button>
              </div>
            </div>
          </div>
        </div>

        {/* ── Summary Cards ── */}
        <div className="grid grid-cols-3 gap-3">
          {[
            { label: 'STAT Orders', value: statCount, icon: Zap, color: 'text-red-500', bg: 'rgba(239,68,68,0.1)', pulse: statCount > 0 },
            { label: 'Critical Results', value: criticalCount, icon: AlertTriangle, color: 'text-red-500', bg: 'rgba(239,68,68,0.1)', pulse: criticalCount > 0 },
            { label: 'Pending', value: pendingCount, icon: Clock, color: 'text-amber-500', bg: 'rgba(245,158,11,0.1)', pulse: false },
          ].map((s) => {
            const Icon = s.icon;
            return (
              <div key={s.label} className="rounded-2xl p-4 animate-fade-up" style={glassCard}>
                <div className="flex items-center gap-3">
                  <div
                    className={`w-10 h-10 rounded-xl flex items-center justify-center ${s.pulse ? 'animate-pulse' : ''}`}
                    style={{ backgroundColor: s.bg }}
                  >
                    <Icon className={`w-5 h-5 ${s.color}`} />
                  </div>
                  <div>
                    <p className={`text-2xl font-black ${s.color}`}>{s.value}</p>
                    <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>{s.label}</p>
                  </div>
                </div>
              </div>
            );
          })}
        </div>

        {/* ── Filter Tabs & Search ── */}
        <div className="rounded-2xl p-4 animate-fade-up" style={glassCard}>
          <div className="flex flex-col sm:flex-row sm:items-center gap-3">
            <div className="relative flex-1">
              <Search className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search by test name, order number, or clinician..."
                className={`w-full pl-10 pr-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all ${
                  isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                }`}
                style={glassInner}
              />
            </div>
            <div className="flex items-center gap-2">
              {([
                { key: 'pending' as FilterTab, label: 'All Pending', icon: Clock },
                { key: 'stat' as FilterTab, label: 'STAT Orders', icon: Zap },
                { key: 'critical' as FilterTab, label: 'Critical Results', icon: AlertTriangle },
                { key: 'visit' as FilterTab, label: 'By Visit', icon: Eye },
              ]).map((tab) => {
                const Icon = tab.icon;
                return (
                  <button
                    key={tab.key}
                    onClick={() => setActiveTab(tab.key)}
                    className={`inline-flex items-center gap-1.5 px-3.5 py-2 text-[11px] font-bold rounded-lg transition-all whitespace-nowrap ${
                      activeTab === tab.key
                        ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md'
                        : isDark ? 'text-slate-400 hover:text-white hover:bg-white/5' : 'text-slate-500 hover:text-slate-800 hover:bg-white/60'
                    }`}
                  >
                    <Icon className="w-3 h-3" />
                    {tab.label}
                  </button>
                );
              })}
            </div>
          </div>

          {/* Visit search input */}
          {activeTab === 'visit' && (
            <div className="mt-3 flex gap-2">
              <input
                type="text"
                value={visitIdInput}
                onChange={(e) => setVisitIdInput(e.target.value)}
                placeholder="Enter Visit ID..."
                onKeyDown={(e) => e.key === 'Enter' && searchByVisit()}
                className={`flex-1 px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all ${
                  isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                }`}
                style={glassInner}
              />
              <button
                onClick={searchByVisit}
                className="inline-flex items-center gap-1.5 px-4 py-2.5 text-xs font-bold text-white bg-gradient-to-r from-slate-800 to-slate-700 hover:shadow-lg rounded-xl transition-all shadow-md"
              >
                <Search className="w-3.5 h-3.5" /> Search
              </button>
            </div>
          )}
        </div>

        {/* ── Orders List ── */}
        {loading ? (
          <div className="flex items-center justify-center py-16">
            <Loader2 className="w-7 h-7 animate-spin text-cyan-500" />
          </div>
        ) : currentOrders.length === 0 ? (
          <div className="rounded-2xl p-12 text-center animate-fade-up" style={glassCard}>
            <div className="w-16 h-16 rounded-2xl flex items-center justify-center mx-auto mb-4" style={{ backgroundColor: 'rgba(34,197,94,0.1)' }}>
              <CheckCircle className="w-8 h-8 text-emerald-400" />
            </div>
            <p className={`text-sm font-bold ${text.heading}`}>
              {activeTab === 'visit' && !visitSearched ? 'Enter a Visit ID to search' : 'No lab orders found'}
            </p>
            <p className={`text-xs font-medium mt-1 ${text.muted}`}>
              {activeTab === 'pending' && 'All pending lab orders have been processed'}
              {activeTab === 'stat' && 'No STAT priority orders at this time'}
              {activeTab === 'critical' && 'No critical results requiring attention'}
              {activeTab === 'visit' && visitSearched && 'No orders found for this visit'}
            </p>
          </div>
        ) : (
          <div className="space-y-3">
            {currentOrders.map((order, idx) => {
              const pStyle = getPriorityStyle(order.priority);
              const isCriticalOrder = order.isCritical && !order.criticalValueAcknowledgedAt;

              return (
                <div
                  key={order.id}
                  className={`rounded-2xl p-5 transition-all animate-fade-up hover:-translate-y-0.5 ${isCriticalOrder ? 'ring-1 ring-red-500/40' : ''}`}
                  style={{
                    ...glassCard,
                    ...(isCriticalOrder ? { border: '1px solid rgba(239,68,68,0.35)' } : {}),
                    animationDelay: `${0.05 + idx * 0.03}s`,
                  } as React.CSSProperties}
                >
                  <div className="flex items-start gap-4">
                    {/* Test icon */}
                    <div
                      className={`w-11 h-11 rounded-xl flex items-center justify-center flex-shrink-0 ${isCriticalOrder ? 'animate-pulse' : ''}`}
                      style={{
                        backgroundColor: isCriticalOrder ? 'rgba(239,68,68,0.1)' : 'rgba(6,182,212,0.1)',
                      }}
                    >
                      <FlaskConical className={`w-5 h-5 ${isCriticalOrder ? 'text-red-500' : 'text-cyan-500'}`} />
                    </div>

                    {/* Content */}
                    <div className="flex-1 min-w-0">
                      {/* Title row */}
                      <div className="flex items-center gap-2.5 mb-1 flex-wrap">
                        <h3 className={`text-sm font-extrabold tracking-tight ${text.heading}`}>
                          {order.testName}
                        </h3>
                        <span
                          className={`inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider ${pStyle.text}`}
                          style={{ background: pStyle.bg, border: pStyle.border }}
                        >
                          {order.priority}
                        </span>
                        {isCriticalOrder && (
                          <span className="inline-flex items-center gap-1 px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-red-600 bg-red-500/10 border border-red-500/20 animate-pulse">
                            <AlertTriangle className="w-3 h-3" /> Critical
                          </span>
                        )}
                      </div>

                      {/* Meta row */}
                      <div className="flex items-center gap-3 flex-wrap">
                        <span className={`text-[11px] font-medium ${text.muted}`}>
                          #{order.orderNumber}
                        </span>
                        <span className={`text-[11px] font-medium ${text.muted}`}>
                          by {order.orderedByName}
                        </span>
                        {order.specimenType && (
                          <span className={`text-[11px] font-medium flex items-center gap-1 ${text.accent}`}>
                            <TestTube className="w-3 h-3" /> {order.specimenType}
                          </span>
                        )}
                        <span className={`text-[11px] font-medium flex items-center gap-1 ${text.muted}`}>
                          <Clock className="w-3 h-3" />
                          {format(new Date(order.orderedAt), 'dd MMM yyyy HH:mm')}
                        </span>
                        {order.turnaroundMinutes != null && (
                          <span className={`text-[11px] font-bold flex items-center gap-1 ${order.turnaroundMinutes > 120 ? 'text-amber-500' : 'text-emerald-500'}`}>
                            <ArrowRight className="w-3 h-3" />
                            TAT: {order.turnaroundMinutes}m
                          </span>
                        )}
                      </div>

                      {/* Status pipeline */}
                      {renderStatusPipeline(order)}

                      {/* Result display */}
                      {renderResult(order)}

                      {/* Critical acknowledgement info */}
                      {order.isCritical && order.criticalValueAcknowledgedAt && (
                        <div
                          className="flex items-center gap-2 mt-3 px-3 py-2.5 rounded-xl"
                          style={{ background: 'rgba(34,197,94,0.06)', border: '1px solid rgba(34,197,94,0.15)' }}
                        >
                          <CheckCircle className="w-3.5 h-3.5 text-emerald-500 flex-shrink-0" />
                          <span className={`text-[11px] font-medium ${isDark ? 'text-emerald-400' : 'text-emerald-700'}`}>
                            Critical value acknowledged {formatDistanceToNow(new Date(order.criticalValueAcknowledgedAt), { addSuffix: true })}
                          </span>
                        </div>
                      )}

                      {/* Notes */}
                      {order.notes && (
                        <p className={`text-[11px] font-medium mt-2 flex items-start gap-1.5 ${text.muted}`}>
                          <MessageSquare className="w-3 h-3 mt-0.5 flex-shrink-0" /> {order.notes}
                        </p>
                      )}

                      {/* Actions */}
                      {renderActions(order)}
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* ═══════════════════════════════════════════════════════════════
         Record Result Dialog
         ═══════════════════════════════════════════════════════════════ */}
      {resultDialogOpen && (
        <div className="fixed inset-0 z-[100] flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/50 backdrop-blur-sm"
            onClick={() => !resultSubmitting && setResultDialogOpen(false)}
          />
          <div className="relative w-full max-w-lg mx-4 rounded-2xl p-6 shadow-2xl animate-scale-in" style={glassCard}>
            {/* Header */}
            <div className="flex items-center justify-between mb-5">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl flex items-center justify-center bg-emerald-500/10">
                  <FileText className="w-5 h-5 text-emerald-500" />
                </div>
                <div>
                  <h3 className={`text-sm font-bold ${text.heading}`}>Record Lab Result</h3>
                  <p className={`text-[11px] ${text.muted}`}>Enter the test result values</p>
                </div>
              </div>
              <button
                onClick={() => !resultSubmitting && setResultDialogOpen(false)}
                className={`w-8 h-8 rounded-lg flex items-center justify-center transition-colors ${
                  isDark ? 'hover:bg-white/10 text-slate-400' : 'hover:bg-slate-100 text-slate-500'
                }`}
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            {/* Form */}
            <div className="space-y-4 mb-5">
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className={`block text-[11px] font-bold uppercase tracking-wider mb-2 ${text.muted}`}>Result Value *</label>
                  <input
                    type="text"
                    value={resultForm.resultValue}
                    onChange={(e) => setResultForm((f) => ({ ...f, resultValue: e.target.value }))}
                    placeholder="e.g. 12.5"
                    autoFocus
                    className={`w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/30 transition-all ${
                      isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                    }`}
                    style={glassInner}
                  />
                </div>
                <div>
                  <label className={`block text-[11px] font-bold uppercase tracking-wider mb-2 ${text.muted}`}>Unit</label>
                  <input
                    type="text"
                    value={resultForm.resultUnit || ''}
                    onChange={(e) => setResultForm((f) => ({ ...f, resultUnit: e.target.value }))}
                    placeholder="e.g. mg/dL"
                    className={`w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/30 transition-all ${
                      isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                    }`}
                    style={glassInner}
                  />
                </div>
              </div>
              <div>
                <label className={`block text-[11px] font-bold uppercase tracking-wider mb-2 ${text.muted}`}>Numeric Value</label>
                <input
                  type="number"
                  step="any"
                  value={resultForm.resultNumeric ?? ''}
                  onChange={(e) => setResultForm((f) => ({ ...f, resultNumeric: e.target.value ? Number(e.target.value) : undefined }))}
                  placeholder="Numeric equivalent"
                  className={`w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/30 transition-all ${
                    isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                  }`}
                  style={glassInner}
                />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className={`block text-[11px] font-bold uppercase tracking-wider mb-2 ${text.muted}`}>Ref Range Min</label>
                  <input
                    type="number"
                    step="any"
                    value={resultForm.referenceRangeMin ?? ''}
                    onChange={(e) => setResultForm((f) => ({ ...f, referenceRangeMin: e.target.value ? Number(e.target.value) : undefined }))}
                    placeholder="Min"
                    className={`w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/30 transition-all ${
                      isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                    }`}
                    style={glassInner}
                  />
                </div>
                <div>
                  <label className={`block text-[11px] font-bold uppercase tracking-wider mb-2 ${text.muted}`}>Ref Range Max</label>
                  <input
                    type="number"
                    step="any"
                    value={resultForm.referenceRangeMax ?? ''}
                    onChange={(e) => setResultForm((f) => ({ ...f, referenceRangeMax: e.target.value ? Number(e.target.value) : undefined }))}
                    placeholder="Max"
                    className={`w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/30 transition-all ${
                      isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                    }`}
                    style={glassInner}
                  />
                </div>
              </div>
              <div className="flex items-center gap-3">
                <button
                  onClick={() => setResultForm((f) => ({ ...f, isAbnormal: !f.isAbnormal }))}
                  className={`inline-flex items-center gap-2 px-4 py-2.5 text-[11px] font-bold rounded-xl transition-all ${
                    resultForm.isAbnormal
                      ? 'bg-red-500/10 text-red-500 border border-red-500/20'
                      : isDark ? 'bg-white/5 text-slate-400 border border-white/10' : 'bg-white/60 text-slate-500 border border-slate-200/60'
                  }`}
                >
                  <AlertTriangle className="w-3.5 h-3.5" />
                  {resultForm.isAbnormal ? 'Marked Abnormal' : 'Mark as Abnormal'}
                </button>
              </div>
            </div>

            {/* Actions */}
            <div className="flex items-center justify-end gap-3">
              <button
                onClick={() => !resultSubmitting && setResultDialogOpen(false)}
                disabled={resultSubmitting}
                className={`px-4 py-2.5 text-xs font-bold rounded-xl transition-all ${
                  isDark ? 'text-slate-400 hover:bg-white/5' : 'text-slate-500 hover:bg-slate-50'
                }`}
              >
                Cancel
              </button>
              <button
                onClick={submitResult}
                disabled={resultSubmitting || !resultForm.resultValue.trim()}
                className="inline-flex items-center gap-2 px-5 py-2.5 text-xs font-bold text-white rounded-xl shadow-lg transition-all disabled:opacity-50 disabled:cursor-not-allowed bg-gradient-to-r from-emerald-500 to-emerald-600 shadow-emerald-500/20 hover:shadow-emerald-500/30 hover:-translate-y-0.5"
              >
                {resultSubmitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <CheckCircle className="w-3.5 h-3.5" />}
                {resultSubmitting ? 'Saving...' : 'Save Result'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ═══════════════════════════════════════════════════════════════
         Cancel Dialog
         ═══════════════════════════════════════════════════════════════ */}
      {cancelDialogOpen && (
        <div className="fixed inset-0 z-[100] flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/50 backdrop-blur-sm"
            onClick={() => !cancelSubmitting && setCancelDialogOpen(false)}
          />
          <div className="relative w-full max-w-md mx-4 rounded-2xl p-6 shadow-2xl animate-scale-in" style={glassCard}>
            <div className="flex items-center justify-between mb-5">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl flex items-center justify-center bg-red-500/10">
                  <XCircle className="w-5 h-5 text-red-500" />
                </div>
                <div>
                  <h3 className={`text-sm font-bold ${text.heading}`}>Cancel Lab Order</h3>
                  <p className={`text-[11px] ${text.muted}`}>Provide a reason for cancellation</p>
                </div>
              </div>
              <button
                onClick={() => !cancelSubmitting && setCancelDialogOpen(false)}
                className={`w-8 h-8 rounded-lg flex items-center justify-center transition-colors ${
                  isDark ? 'hover:bg-white/10 text-slate-400' : 'hover:bg-slate-100 text-slate-500'
                }`}
              >
                <X className="w-4 h-4" />
              </button>
            </div>
            <div className="mb-5">
              <label className={`block text-[11px] font-bold uppercase tracking-wider mb-2 ${text.muted}`}>
                <MessageSquare className="w-3 h-3 inline mr-1" /> Reason *
              </label>
              <textarea
                value={cancelReason}
                onChange={(e) => setCancelReason(e.target.value)}
                placeholder="Enter reason for cancellation..."
                rows={3}
                autoFocus
                className={`w-full px-4 py-3 rounded-xl text-sm resize-none focus:outline-none focus:ring-2 focus:ring-cyan-500/30 transition-all ${
                  isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                }`}
                style={glassInner}
              />
            </div>
            <div className="flex items-center justify-end gap-3">
              <button
                onClick={() => !cancelSubmitting && setCancelDialogOpen(false)}
                disabled={cancelSubmitting}
                className={`px-4 py-2.5 text-xs font-bold rounded-xl transition-all ${
                  isDark ? 'text-slate-400 hover:bg-white/5' : 'text-slate-500 hover:bg-slate-50'
                }`}
              >
                Cancel
              </button>
              <button
                onClick={submitCancel}
                disabled={cancelSubmitting || !cancelReason.trim()}
                className="inline-flex items-center gap-2 px-5 py-2.5 text-xs font-bold text-white rounded-xl shadow-lg transition-all disabled:opacity-50 disabled:cursor-not-allowed bg-gradient-to-r from-red-500 to-red-600 shadow-red-500/20 hover:shadow-red-500/30 hover:-translate-y-0.5"
              >
                {cancelSubmitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <XCircle className="w-3.5 h-3.5" />}
                {cancelSubmitting ? 'Cancelling...' : 'Cancel Order'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
