/* ═══════════════════════════════════════════════════════════════
   Surge Prediction & Risk Analysis — Module 22
   ML-driven surge risk scoring, capacity forecasting,
   trend analysis & prediction history
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback } from 'react';
import {
  TrendingUp, TrendingDown, Minus, RefreshCw, Loader2,
  Zap, Activity, Bed, Users, AlertTriangle, Clock,
  ChevronRight, ArrowUp, ArrowDown,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { predictionApi } from '@/api/prediction';
import type { SurgePrediction } from '@/api/prediction';
import { format } from 'date-fns';

/* ── Horizon options ── */
const HORIZONS: { value: number; label: string }[] = [
  { value: 4, label: '4h' },
  { value: 8, label: '8h' },
  { value: 12, label: '12h' },
  { value: 24, label: '24h' },
];

/* ── Risk level config ── */
const RISK_CONFIG: Record<string, { color: string; bg: string; border: string; glow: string; label: string }> = {
  LOW:      { color: 'text-emerald-400', bg: 'bg-emerald-500/15', border: 'border-emerald-500/30', glow: 'shadow-emerald-500/20', label: 'LOW RISK' },
  MODERATE: { color: 'text-amber-400',   bg: 'bg-amber-500/15',   border: 'border-amber-500/30',   glow: 'shadow-amber-500/20',   label: 'MODERATE RISK' },
  HIGH:     { color: 'text-orange-400',  bg: 'bg-orange-500/15',  border: 'border-orange-500/30',  glow: 'shadow-orange-500/20',  label: 'HIGH RISK' },
  CRITICAL: { color: 'text-red-400',     bg: 'bg-red-500/15',     border: 'border-red-500/30',     glow: 'shadow-red-500/20',     label: 'CRITICAL' },
};

const getRiskConfig = (level: string) =>
  RISK_CONFIG[level] || RISK_CONFIG.LOW;

/* ── Risk score to gauge color ── */
const gaugeColor = (score: number): string => {
  if (score >= 80) return '#ef4444';
  if (score >= 60) return '#f97316';
  if (score >= 30) return '#f59e0b';
  return '#10b981';
};

const gaugeTrailColor = (isDark: boolean): string =>
  isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.06)';

/* ── Trend config ── */
const TREND_CONFIG: Record<string, { icon: typeof TrendingUp; color: string; bg: string; label: string }> = {
  INCREASING: { icon: TrendingUp, color: 'text-red-400', bg: 'bg-red-500/10', label: 'Increasing' },
  STABLE:     { icon: Minus, color: 'text-amber-400', bg: 'bg-amber-500/10', label: 'Stable' },
  DECREASING: { icon: TrendingDown, color: 'text-emerald-400', bg: 'bg-emerald-500/10', label: 'Decreasing' },
};

const getTrendConfig = (direction: string) =>
  TREND_CONFIG[direction] || TREND_CONFIG.STABLE;

/* ── Utilization bar color ── */
const utilColor = (pct: number): string => {
  if (pct >= 90) return 'bg-red-500';
  if (pct >= 75) return 'bg-amber-500';
  return 'bg-emerald-500';
};

const utilTextColor = (pct: number): string => {
  if (pct >= 90) return 'text-red-400';
  if (pct >= 75) return 'text-amber-400';
  return 'text-emerald-400';
};

/* ═══════════════════════════════════════════════════════════════ */

export function SurgePredictionView() {
  const { glassCard, isDark, text } = useTheme();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';

  const [prediction, setPrediction] = useState<SurgePrediction | null>(null);
  const [history, setHistory] = useState<SurgePrediction[]>([]);
  const [horizon, setHorizon] = useState(12);
  const [loading, setLoading] = useState(true);
  const [predicting, setPredicting] = useState(false);
  const [historyPage, setHistoryPage] = useState(0);
  const [totalHistory, setTotalHistory] = useState(0);

  /* ── Load latest prediction + history ── */
  const loadData = useCallback(async () => {
    if (!hospitalId) return;
    setLoading(true);
    try {
      const [latest, hist] = await Promise.all([
        predictionApi.getLatest(hospitalId).catch(() => null),
        predictionApi.getHistory(hospitalId, historyPage),
      ]);
      setPrediction(latest);
      setHistory(hist.content);
      setTotalHistory(hist.totalElements);
    } catch (err) {
      console.error('Failed to load predictions:', err);
    } finally {
      setLoading(false);
    }
  }, [hospitalId, historyPage]);

  useEffect(() => { loadData(); }, [loadData]);

  /* ── Run prediction ── */
  const handlePredict = async () => {
    if (!hospitalId) return;
    setPredicting(true);
    try {
      const result = await predictionApi.predict(hospitalId, horizon);
      setPrediction(result);
      await loadData();
    } catch (err) {
      console.error('Failed to run prediction:', err);
    } finally {
      setPredicting(false);
    }
  };

  /* ── Helpers ── */
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';

  const riskCfg = prediction ? getRiskConfig(prediction.surgeRiskLevel) : RISK_CONFIG.LOW;
  const trendCfg = prediction ? getTrendConfig(prediction.trendDirection) : TREND_CONFIG.STABLE;
  const TrendIcon = trendCfg.icon;

  /* ── Gauge SVG dimensions ── */
  const gaugeRadius = 80;
  const gaugeStroke = 12;
  const gaugeCircumference = Math.PI * gaugeRadius; /* semicircle */
  const gaugeOffset = prediction
    ? gaugeCircumference - (prediction.surgeRiskScore / 100) * gaugeCircumference
    : gaugeCircumference;

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-7xl mx-auto space-y-4 animate-fade-in">

        {/* ── Header ── */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-rose-500/20 flex items-center justify-center">
                  <TrendingUp className="w-5 h-5 text-rose-400" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">Surge Prediction & Risk Analysis</h1>
                  <p className="text-white/50 text-xs">
                    {prediction
                      ? `Last prediction: ${format(new Date(prediction.predictedAt), 'MMM dd, yyyy HH:mm')} — ${prediction.predictionHorizonHours}h horizon`
                      : 'No prediction available'}
                  </p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                <button
                  onClick={handlePredict}
                  disabled={predicting}
                  className="flex items-center gap-2 px-4 py-2 rounded-xl bg-rose-500/20 border border-rose-500/30 text-rose-300 text-xs font-bold hover:bg-rose-500/30 transition-colors disabled:opacity-50"
                >
                  {predicting ? <Loader2 className="w-4 h-4 animate-spin" /> : <Zap className="w-4 h-4" />}
                  Run Prediction
                </button>
                <button
                  onClick={loadData}
                  className="w-9 h-9 rounded-xl bg-white/10 flex items-center justify-center hover:bg-white/20 transition-colors"
                >
                  <RefreshCw className="w-4 h-4 text-white" />
                </button>
              </div>
            </div>
          </div>

          {/* ── Horizon Selector ── */}
          <div
            className="flex gap-1 px-4 py-2"
            style={{ borderTop: borderStyle }}
          >
            <span className={`text-xs font-semibold mr-2 self-center ${text.secondary}`}>Prediction Horizon:</span>
            {HORIZONS.map(({ value, label }) => (
              <button
                key={value}
                onClick={() => setHorizon(value)}
                className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${
                  horizon === value
                    ? 'bg-rose-500/20 text-rose-400 border border-rose-500/30'
                    : `${text.secondary} hover:bg-white/5`
                }`}
              >
                {label}
              </button>
            ))}
          </div>
        </div>

        {/* ── Loading ── */}
        {loading && (
          <div className="flex items-center justify-center py-20">
            <Loader2 className="w-8 h-8 animate-spin text-rose-400" />
          </div>
        )}

        {/* ── Empty State ── */}
        {!loading && !prediction && (
          <div className="rounded-3xl overflow-hidden" style={glassCard}>
            <div className="flex flex-col items-center justify-center py-16 px-6">
              <TrendingUp className="w-12 h-12 text-slate-500 mb-4" />
              <p className={`text-sm font-semibold ${text.primary}`}>No Predictions Available</p>
              <p className={`text-xs mt-1 ${text.secondary}`}>Run a prediction to view surge risk analysis</p>
            </div>
          </div>
        )}

        {/* ── Main Content ── */}
        {!loading && prediction && (
          <div className="space-y-4">

            {/* ── Surge Risk Gauge + Trend ── */}
            <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">

              {/* Gauge */}
              <div className="lg:col-span-2 rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
                <div className="px-5 py-3" style={{ borderBottom: borderStyle }}>
                  <div className="flex items-center gap-2">
                    <AlertTriangle className={`w-4 h-4 ${riskCfg.color}`} />
                    <h2 className={`text-sm font-bold ${text.primary}`}>Surge Risk Score</h2>
                  </div>
                </div>
                <div className="flex flex-col items-center justify-center py-8 px-6">
                  {/* SVG Semicircle Gauge */}
                  <div className="relative">
                    <svg width="200" height="120" viewBox="0 0 200 120">
                      {/* Background arc */}
                      <path
                        d="M 10 110 A 80 80 0 0 1 190 110"
                        fill="none"
                        stroke={gaugeTrailColor(isDark)}
                        strokeWidth={gaugeStroke}
                        strokeLinecap="round"
                      />
                      {/* Value arc */}
                      <path
                        d="M 10 110 A 80 80 0 0 1 190 110"
                        fill="none"
                        stroke={gaugeColor(prediction.surgeRiskScore)}
                        strokeWidth={gaugeStroke}
                        strokeLinecap="round"
                        strokeDasharray={`${gaugeCircumference}`}
                        strokeDashoffset={`${gaugeOffset}`}
                        style={{ transition: 'stroke-dashoffset 0.8s ease' }}
                      />
                      {/* Score labels */}
                      <text x="10" y="118" fill={isDark ? 'rgba(255,255,255,0.3)' : 'rgba(0,0,0,0.3)'} fontSize="9" textAnchor="middle">0</text>
                      <text x="190" y="118" fill={isDark ? 'rgba(255,255,255,0.3)' : 'rgba(0,0,0,0.3)'} fontSize="9" textAnchor="middle">100</text>
                    </svg>
                    {/* Score number overlay */}
                    <div className="absolute inset-0 flex flex-col items-center justify-end pb-1">
                      <span
                        className="text-4xl font-black"
                        style={{ color: gaugeColor(prediction.surgeRiskScore) }}
                      >
                        {prediction.surgeRiskScore.toFixed(0)}
                      </span>
                    </div>
                  </div>

                  {/* Risk Level Badge */}
                  <div className={`mt-4 px-4 py-1.5 rounded-xl ${riskCfg.bg} border ${riskCfg.border} shadow-lg ${riskCfg.glow}`}>
                    <span className={`text-sm font-black tracking-wider ${riskCfg.color}`}>
                      {riskCfg.label}
                    </span>
                  </div>

                  {/* Zone Legend */}
                  <div className="flex items-center gap-4 mt-5">
                    {[
                      { label: '0-30', color: 'bg-emerald-500', name: 'Low' },
                      { label: '30-60', color: 'bg-amber-500', name: 'Moderate' },
                      { label: '60-80', color: 'bg-orange-500', name: 'High' },
                      { label: '80-100', color: 'bg-red-500', name: 'Critical' },
                    ].map(({ label, color, name }) => (
                      <div key={name} className="flex items-center gap-1.5">
                        <div className={`w-2.5 h-2.5 rounded-full ${color}`} />
                        <span className={`text-[10px] ${text.secondary}`}>{name} ({label})</span>
                      </div>
                    ))}
                  </div>

                  {/* Notes */}
                  {prediction.notes && (
                    <div className={`mt-4 px-4 py-2 rounded-lg bg-white/5 max-w-md text-center`}>
                      <p className={`text-xs ${text.secondary}`}>{prediction.notes}</p>
                    </div>
                  )}
                </div>
              </div>

              {/* Trend Direction Card */}
              <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
                <div className="px-5 py-3" style={{ borderBottom: borderStyle }}>
                  <div className="flex items-center gap-2">
                    <Activity className="w-4 h-4 text-cyan-400" />
                    <h2 className={`text-sm font-bold ${text.primary}`}>Trend & Forecast</h2>
                  </div>
                </div>
                <div className="flex flex-col items-center justify-center py-6 px-5 space-y-5">
                  {/* Trend */}
                  <div className="text-center">
                    <div className={`w-14 h-14 rounded-2xl ${trendCfg.bg} flex items-center justify-center mx-auto mb-2`}>
                      <TrendIcon className={`w-7 h-7 ${trendCfg.color}`} />
                    </div>
                    <p className={`text-sm font-bold ${trendCfg.color}`}>{trendCfg.label}</p>
                    <p className={`text-[10px] ${text.secondary}`}>Trend Direction</p>
                  </div>

                  {/* Predicted ED Admissions */}
                  <div className="w-full px-3 py-3 rounded-xl bg-white/5">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <ArrowUp className="w-3.5 h-3.5 text-blue-400" />
                        <span className={`text-xs ${text.secondary}`}>Predicted ED Admissions</span>
                      </div>
                      <span className={`text-sm font-bold ${text.primary}`}>{prediction.predictedEdAdmissions}</span>
                    </div>
                  </div>

                  {/* Predicted ICU Demand */}
                  <div className="w-full px-3 py-3 rounded-xl bg-white/5">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <Bed className="w-3.5 h-3.5 text-violet-400" />
                        <span className={`text-xs ${text.secondary}`}>Predicted ICU Demand</span>
                      </div>
                      <span className={`text-sm font-bold ${text.primary}`}>{prediction.predictedIcuDemand}</span>
                    </div>
                  </div>

                  {/* Predicted RED Patients */}
                  <div className="w-full px-3 py-3 rounded-xl bg-red-500/5 border border-red-500/10">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <AlertTriangle className="w-3.5 h-3.5 text-red-400" />
                        <span className={`text-xs ${text.secondary}`}>Predicted RED Patients</span>
                      </div>
                      <span className="text-sm font-bold text-red-400">{prediction.predictedRedPatients}</span>
                    </div>
                  </div>

                  {/* Horizon */}
                  <div className="w-full px-3 py-3 rounded-xl bg-white/5">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <Clock className="w-3.5 h-3.5 text-cyan-400" />
                        <span className={`text-xs ${text.secondary}`}>Prediction Horizon</span>
                      </div>
                      <span className={`text-sm font-bold ${text.primary}`}>{prediction.predictionHorizonHours}h</span>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            {/* ── Capacity Utilization ── */}
            <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
              <div className="px-5 py-3" style={{ borderBottom: borderStyle }}>
                <div className="flex items-center gap-2">
                  <Bed className="w-4 h-4 text-violet-400" />
                  <h2 className={`text-sm font-bold ${text.primary}`}>Capacity Utilization</h2>
                </div>
              </div>
              <div className="grid grid-cols-1 lg:grid-cols-2 gap-px">
                {/* ED Occupancy */}
                {(() => {
                  const edPct = prediction.edCapacity > 0
                    ? (prediction.currentEdOccupancy / prediction.edCapacity) * 100
                    : 0;
                  return (
                    <div className="px-5 py-4">
                      <div className="flex items-center justify-between mb-1">
                        <p className={`text-xs font-semibold ${text.secondary}`}>ED Occupancy</p>
                        <span className={`text-xs font-bold ${utilTextColor(edPct)}`}>{edPct.toFixed(0)}%</span>
                      </div>
                      <div className="w-full h-3 rounded-full bg-white/5 mb-2">
                        <div
                          className={`h-full rounded-full transition-all ${utilColor(edPct)}`}
                          style={{ width: `${Math.min(edPct, 100)}%` }}
                        />
                      </div>
                      <div className="flex items-center justify-between">
                        <div className="flex items-center gap-3">
                          <div>
                            <p className={`text-lg font-bold ${text.primary}`}>{prediction.currentEdOccupancy}</p>
                            <p className={`text-[10px] ${text.secondary}`}>Current</p>
                          </div>
                          <ChevronRight className={`w-4 h-4 ${text.secondary}`} />
                          <div>
                            <p className={`text-lg font-bold text-blue-400`}>{prediction.predictedEdAdmissions}</p>
                            <p className={`text-[10px] ${text.secondary}`}>Predicted</p>
                          </div>
                        </div>
                        <div className="text-right">
                          <p className={`text-lg font-bold ${text.primary}`}>{prediction.edCapacity}</p>
                          <p className={`text-[10px] ${text.secondary}`}>Capacity</p>
                        </div>
                      </div>
                    </div>
                  );
                })()}

                {/* ICU Occupancy */}
                {(() => {
                  const icuPct = prediction.icuCapacity > 0
                    ? (prediction.currentIcuOccupancy / prediction.icuCapacity) * 100
                    : 0;
                  return (
                    <div className="px-5 py-4">
                      <div className="flex items-center justify-between mb-1">
                        <p className={`text-xs font-semibold ${text.secondary}`}>ICU Demand</p>
                        <span className={`text-xs font-bold ${utilTextColor(icuPct)}`}>{icuPct.toFixed(0)}%</span>
                      </div>
                      <div className="w-full h-3 rounded-full bg-white/5 mb-2">
                        <div
                          className={`h-full rounded-full transition-all ${utilColor(icuPct)}`}
                          style={{ width: `${Math.min(icuPct, 100)}%` }}
                        />
                      </div>
                      <div className="flex items-center justify-between">
                        <div className="flex items-center gap-3">
                          <div>
                            <p className={`text-lg font-bold ${text.primary}`}>{prediction.currentIcuOccupancy}</p>
                            <p className={`text-[10px] ${text.secondary}`}>Current</p>
                          </div>
                          <ChevronRight className={`w-4 h-4 ${text.secondary}`} />
                          <div>
                            <p className={`text-lg font-bold text-violet-400`}>{prediction.predictedIcuDemand}</p>
                            <p className={`text-[10px] ${text.secondary}`}>Predicted</p>
                          </div>
                        </div>
                        <div className="text-right">
                          <p className={`text-lg font-bold ${text.primary}`}>{prediction.icuCapacity}</p>
                          <p className={`text-[10px] ${text.secondary}`}>Capacity</p>
                        </div>
                      </div>
                    </div>
                  );
                })()}
              </div>
            </div>

            {/* ── Prediction History ── */}
            <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
              <div className="px-5 py-3 flex items-center justify-between" style={{ borderBottom: borderStyle }}>
                <div className="flex items-center gap-2">
                  <Clock className="w-4 h-4 text-cyan-400" />
                  <h2 className={`text-sm font-bold ${text.primary}`}>Prediction History</h2>
                  <span className={`text-[10px] px-2 py-0.5 rounded-md bg-white/10 ${text.secondary}`}>
                    {totalHistory} total
                  </span>
                </div>
              </div>

              {history.length === 0 ? (
                <div className="py-10 text-center">
                  <p className={`text-xs ${text.secondary}`}>No prediction history available</p>
                </div>
              ) : (
                <div>
                  {/* Table Header */}
                  <div
                    className="grid grid-cols-7 px-5 py-2 text-[10px] font-semibold uppercase tracking-wider"
                    style={{ borderBottom: borderStyle }}
                  >
                    <span className={text.secondary}>Date/Time</span>
                    <span className={text.secondary}>Horizon</span>
                    <span className={text.secondary}>Risk Score</span>
                    <span className={text.secondary}>Level</span>
                    <span className={text.secondary}>Trend</span>
                    <span className={text.secondary}>ED Predicted</span>
                    <span className={text.secondary}>ICU Predicted</span>
                  </div>

                  {/* Rows */}
                  {history.map((h) => {
                    const hRisk = getRiskConfig(h.surgeRiskLevel);
                    const hTrend = getTrendConfig(h.trendDirection);
                    const HTrendIcon = hTrend.icon;
                    return (
                      <div
                        key={h.id}
                        className="grid grid-cols-7 px-5 py-3 items-center hover:bg-white/[0.02] transition-colors"
                        style={{ borderBottom: borderStyle }}
                      >
                        <span className={`text-xs ${text.primary}`}>
                          {format(new Date(h.predictedAt), 'MMM dd HH:mm')}
                        </span>
                        <span className={`text-xs ${text.secondary}`}>{h.predictionHorizonHours}h</span>
                        <span className={`text-xs font-bold`} style={{ color: gaugeColor(h.surgeRiskScore) }}>
                          {h.surgeRiskScore.toFixed(0)}
                        </span>
                        <span className={`text-[10px] font-bold px-2 py-0.5 rounded-md inline-block w-fit ${hRisk.bg} ${hRisk.color}`}>
                          {h.surgeRiskLevel}
                        </span>
                        <div className="flex items-center gap-1">
                          <HTrendIcon className={`w-3 h-3 ${hTrend.color}`} />
                          <span className={`text-[10px] ${hTrend.color}`}>{hTrend.label}</span>
                        </div>
                        <span className={`text-xs ${text.primary}`}>{h.predictedEdAdmissions}</span>
                        <span className={`text-xs ${text.primary}`}>{h.predictedIcuDemand}</span>
                      </div>
                    );
                  })}

                  {/* Pagination */}
                  {totalHistory > 20 && (
                    <div className="flex items-center justify-center gap-3 py-3">
                      <button
                        onClick={() => setHistoryPage((p) => Math.max(0, p - 1))}
                        disabled={historyPage === 0}
                        className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-colors ${
                          historyPage === 0
                            ? 'opacity-30 cursor-not-allowed'
                            : 'hover:bg-white/10'
                        } ${text.secondary}`}
                      >
                        Previous
                      </button>
                      <span className={`text-xs ${text.secondary}`}>
                        Page {historyPage + 1} of {Math.ceil(totalHistory / 20)}
                      </span>
                      <button
                        onClick={() => setHistoryPage((p) => p + 1)}
                        disabled={(historyPage + 1) * 20 >= totalHistory}
                        className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-colors ${
                          (historyPage + 1) * 20 >= totalHistory
                            ? 'opacity-30 cursor-not-allowed'
                            : 'hover:bg-white/10'
                        } ${text.secondary}`}
                      >
                        Next
                      </button>
                    </div>
                  )}
                </div>
              )}
            </div>

          </div>
        )}

      </div>
    </div>
  );
}
