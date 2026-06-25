import React from 'react';
import { useNavigate } from 'react-router-dom';
import { AlertTriangle, AlertCircle, BellRing, X, ShieldAlert, CheckCircle2, Clock, ArrowRight } from 'lucide-react';
import { AIAlert } from '@/types';
import { formatDistanceToNow } from 'date-fns';
import { useTheme } from '@/hooks/useTheme';

interface AlertPanelProps {
  alerts: AIAlert[];
  onAcknowledge?: (alertId: string, comment?: string) => void;
  onClose?: () => void;
}

const SEV_META = {
  CRITICAL: {
    icon: ShieldAlert,
    ring: 'rgba(239,68,68,0.35)',
    glow: '0 0 0 1px rgba(239,68,68,0.25), 0 10px 30px rgba(239,68,68,0.18)',
    iconBg: 'rgba(239,68,68,0.12)',
    iconColor: '#ef4444',
    badgeBg: 'rgba(239,68,68,0.12)',
    badgeText: '#dc2626',
    accent: '#ef4444',
  },
  HIGH: {
    icon: AlertTriangle,
    ring: 'rgba(249,115,22,0.35)',
    glow: '0 0 0 1px rgba(249,115,22,0.2), 0 10px 30px rgba(249,115,22,0.12)',
    iconBg: 'rgba(249,115,22,0.12)',
    iconColor: '#f97316',
    badgeBg: 'rgba(249,115,22,0.12)',
    badgeText: '#ea580c',
    accent: '#f97316',
  },
  MEDIUM: {
    icon: AlertCircle,
    ring: 'rgba(234,179,8,0.3)',
    glow: '0 0 0 1px rgba(234,179,8,0.18), 0 10px 30px rgba(234,179,8,0.1)',
    iconBg: 'rgba(234,179,8,0.12)',
    iconColor: '#eab308',
    badgeBg: 'rgba(234,179,8,0.12)',
    badgeText: '#a16207',
    accent: '#eab308',
  },
  LOW: {
    icon: BellRing,
    ring: 'rgba(59,130,246,0.3)',
    glow: '0 0 0 1px rgba(59,130,246,0.18), 0 10px 30px rgba(59,130,246,0.1)',
    iconBg: 'rgba(59,130,246,0.12)',
    iconColor: '#3b82f6',
    badgeBg: 'rgba(59,130,246,0.12)',
    badgeText: '#2563eb',
    accent: '#3b82f6',
  },
} as const;

function getMeta(sev: AIAlert['severity']) {
  return SEV_META[sev as keyof typeof SEV_META] ?? SEV_META.LOW;
}

export function AlertPanel({ alerts, onAcknowledge, onClose }: AlertPanelProps) {
  const { isDark, text } = useTheme();
  const navigate = useNavigate();
  const [expandedId, setExpandedId] = React.useState<string | null>(null);
  const [commentDraft, setCommentDraft] = React.useState<Record<string, string>>({});

  if (alerts.length === 0) return null;

  const sorted = [...alerts].sort((a, b) => {
    const rank = (s: AIAlert['severity']) => (s === 'CRITICAL' ? 0 : s === 'HIGH' ? 1 : s === 'MEDIUM' ? 2 : 3);
    return rank(a.severity) - rank(b.severity) ||
      new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime();
  });
  const criticalCount = alerts.filter((a) => a.severity === 'CRITICAL').length;

  const glassShell: React.CSSProperties = isDark
    ? {
        background: 'rgba(8, 47, 73, 0.92)',
        backdropFilter: 'blur(40px)',
        WebkitBackdropFilter: 'blur(40px)',
        border: '1px solid rgba(2, 132, 199, 0.28)',
        boxShadow: '0 24px 70px rgba(0,0,0,0.45), 0 10px 30px rgba(0,0,0,0.25), inset 0 1px 0 rgba(255,255,255,0.05)',
      }
    : {
        background: 'rgba(255,255,255,0.92)',
        backdropFilter: 'blur(40px)',
        WebkitBackdropFilter: 'blur(40px)',
        border: '1px solid rgba(255,255,255,0.7)',
        boxShadow: '0 24px 70px rgba(0,0,0,0.14), 0 10px 30px rgba(0,0,0,0.08), inset 0 1px 0 rgba(255,255,255,0.9)',
      };

  const rowStyle: React.CSSProperties = isDark
    ? { background: 'rgba(12,74,110,0.28)', border: '1px solid rgba(2,132,199,0.22)' }
    : { background: 'rgba(255,255,255,0.7)', border: '1px solid rgba(226,232,240,0.7)' };

  const headerBg: React.CSSProperties = isDark
    ? { background: 'rgba(8, 47, 73, 0.98)' }
    : { background: 'rgba(248,250,252,0.98)' };

  return (
    <div
      role="dialog"
      aria-label="AI Alerts"
      className="fixed right-5 top-5 w-[420px] max-h-[82vh] rounded-2xl overflow-hidden z-[9998] animate-scale-in flex flex-col"
      style={glassShell}
    >
      {/* Header */}
      <div className={`px-5 py-4 flex items-center justify-between border-b ${isDark ? 'border-white/10' : 'border-gray-200/70'}`} style={headerBg}>
        <div className="flex items-center gap-3">
          <div className="relative">
            <div className="w-9 h-9 rounded-xl flex items-center justify-center" style={{ background: criticalCount > 0 ? 'rgba(239,68,68,0.15)' : 'rgba(6,182,212,0.15)' }}>
              <BellRing className="w-[18px] h-[18px]" style={{ color: criticalCount > 0 ? '#ef4444' : '#0891b2' }} />
            </div>
            {criticalCount > 0 && (
              <span className="absolute -top-1 -right-1 w-2.5 h-2.5 bg-red-500 rounded-full ring-2 ring-white dark:ring-slate-900 animate-pulse" />
            )}
          </div>
          <div>
            <h3 className={`text-sm font-extrabold tracking-tight ${text.heading}`}>AI Alerts</h3>
            <p className={`text-[11px] font-medium ${text.body}`}>
              {alerts.length} active{criticalCount > 0 ? ` · ${criticalCount} critical` : ''}
            </p>
          </div>
        </div>
        {onClose && (
          <button
            onClick={onClose}
            className={`w-8 h-8 rounded-lg flex items-center justify-center transition-all ${isDark ? 'hover:bg-white/10 text-slate-400 hover:text-white' : 'hover:bg-gray-100 text-gray-500 hover:text-gray-800'}`}
            aria-label="Close alerts panel"
          >
            <X className="w-4 h-4" />
          </button>
        )}
      </div>

      {/* Alerts list */}
      <div className="flex-1 overflow-y-auto px-3 py-3 space-y-2.5">
        {sorted.map((alert) => {
          const meta = getMeta(alert.severity);
          const SevIcon = meta.icon;
          const isExpanded = expandedId === alert.id;
          const isCritical = alert.severity === 'CRITICAL';

          return (
            <div
              key={alert.id}
              className="rounded-xl p-3.5 transition-all duration-300 hover:-translate-y-0.5 cursor-pointer"
              style={{
                ...rowStyle,
                borderLeft: `3px solid ${meta.accent}`,
                boxShadow: isCritical ? meta.glow : undefined,
              }}
              onClick={() => setExpandedId(isExpanded ? null : alert.id)}
            >
              <div className="flex items-start gap-3">
                <div className="w-9 h-9 rounded-xl flex items-center justify-center flex-shrink-0" style={{ background: meta.iconBg }}>
                  <SevIcon className="w-4 h-4" style={{ color: meta.iconColor }} />
                </div>

                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <p className={`text-[13px] font-bold leading-snug ${isDark ? 'text-white' : 'text-slate-900'} line-clamp-2`}>
                      {alert.title || alert.message}
                    </p>
                    {alert.escalationTier && alert.escalationTier > 1 && (
                      <span
                        className="inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-red-600"
                        style={{ background: 'rgba(239,68,68,0.08)', border: '1px solid rgba(239,68,68,0.2)' }}
                      >
                        TIER {alert.escalationTier}
                      </span>
                    )}
                  </div>

                  {alert.patientName && (
                    <p className={`text-[11px] mt-0.5 truncate font-semibold ${isDark ? 'text-cyan-300' : 'text-cyan-700'}`}>
                      {alert.patientName}
                      {alert.visitNumber ? <span className={isDark ? 'text-slate-400 font-normal' : 'text-slate-500 font-normal'}> · {alert.visitNumber}</span> : null}
                    </p>
                  )}

                  <div className="flex items-center gap-2 mt-1.5 flex-wrap">
                    <span
                      className="text-[9px] font-bold px-1.5 py-0.5 rounded-lg uppercase tracking-wide"
                      style={{ background: meta.badgeBg, color: meta.badgeText }}
                    >
                      {alert.severity}
                    </span>
                    <span className={`text-[11px] font-medium ${isDark ? 'text-slate-400' : 'text-slate-500'}`}>
                      {formatDistanceToNow(new Date(alert.timestamp), { addSuffix: true })}
                    </span>
                    {alert.targetZone && (
                      <span
                        className="inline-flex items-center text-[10px] font-bold px-1.5 py-0.5 rounded-lg text-cyan-600"
                        style={{ background: 'rgba(6,182,212,0.08)', border: '1px solid rgba(6,182,212,0.2)' }}
                      >
                        {alert.targetZone}
                      </span>
                    )}
                    {alert.satsTargetMinutes != null && alert.satsTargetMinutes > 0 && (
                      <span className="inline-flex items-center gap-1 text-[10px] font-bold text-orange-600">
                        <Clock className="w-3 h-3" />
                        {alert.satsTargetMinutes}m SATS
                      </span>
                    )}
                  </div>

                  {/* Expanded detail */}
                  {isExpanded && (
                    <div className="mt-3 space-y-2.5 animate-fade-in">
                      {alert.title && alert.message && alert.title !== alert.message && (
                        <p className={`text-[12px] leading-relaxed ${isDark ? 'text-slate-300' : 'text-slate-700'}`}>
                          {alert.message}
                        </p>
                      )}

                      {alert.previousCategory && alert.recommendedCategory && (
                        <div className="flex items-center gap-2 text-[11px]">
                          <span
                            className="inline-flex items-center px-2 py-0.5 rounded-lg font-bold text-amber-600"
                            style={{ background: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.2)' }}
                          >
                            {alert.previousCategory}
                          </span>
                          <ArrowRight className="w-3 h-3 text-slate-400" />
                          <span
                            className="inline-flex items-center px-2 py-0.5 rounded-lg font-bold text-red-600"
                            style={{ background: 'rgba(239,68,68,0.08)', border: '1px solid rgba(239,68,68,0.2)' }}
                          >
                            {alert.recommendedCategory}
                          </span>
                        </div>
                      )}

                      {alert.contributingFactors && alert.contributingFactors.length > 0 && (
                        <ul className={`text-[11px] space-y-1 ${isDark ? 'text-slate-300' : 'text-slate-600'}`}>
                          {alert.contributingFactors.map((factor, i) => (
                            <li key={i} className="flex gap-1.5"><span className="text-slate-400">•</span>{factor}</li>
                          ))}
                        </ul>
                      )}

                      {!alert.acknowledged && onAcknowledge && (
                        <div className="space-y-2" onClick={(e) => e.stopPropagation()}>
                          <textarea
                            placeholder="Add a note (optional)…"
                            rows={2}
                            value={commentDraft[alert.id] || ''}
                            onChange={(e) => setCommentDraft({ ...commentDraft, [alert.id]: e.target.value })}
                            className={`w-full text-[12px] rounded-lg p-2 focus:outline-none focus:ring-2 focus:ring-cyan-500/30 resize-none ${
                              isDark
                                ? 'bg-white/5 border border-white/10 text-white placeholder-slate-500'
                                : 'bg-white border border-slate-200 text-slate-800 placeholder-slate-400'
                            }`}
                          />
                          <button
                            onClick={() => onAcknowledge(alert.id, commentDraft[alert.id])}
                            className="inline-flex items-center gap-1.5 text-[11px] font-bold text-white bg-cyan-600 hover:bg-cyan-700 px-3 py-1.5 rounded-xl transition-all shadow-sm hover:shadow"
                          >
                            <CheckCircle2 className="w-3.5 h-3.5" />
                            Acknowledge
                          </button>
                        </div>
                      )}

                      {alert.acknowledged && (
                        <div className={`text-[11px] italic flex items-center gap-1.5 ${isDark ? 'text-emerald-400' : 'text-emerald-600'}`}>
                          <CheckCircle2 className="w-3.5 h-3.5" />
                          Acknowledged{alert.acknowledgedBy ? ` by ${alert.acknowledgedBy}` : ''}
                          {alert.comment ? ` — "${alert.comment}"` : ''}
                        </div>
                      )}
                    </div>
                  )}
                </div>

                {!alert.acknowledged && onAcknowledge && !isExpanded && (
                  <button
                    onClick={(e) => { e.stopPropagation(); onAcknowledge(alert.id); }}
                    className={`text-[10px] font-extrabold flex-shrink-0 px-2 py-1 rounded-lg transition-all ${isDark ? 'text-cyan-300 bg-cyan-500/15 hover:bg-cyan-500/25' : 'text-cyan-700 bg-cyan-50 hover:bg-cyan-100'}`}
                  >
                    ACK
                  </button>
                )}
              </div>
            </div>
          );
        })}
      </div>

      {/* Footer — view all */}
      <div className={`px-5 py-3 border-t ${isDark ? 'border-white/10' : 'border-gray-200/70'}`} style={headerBg}>
        <button
          onClick={() => { onClose?.(); navigate('/alerts'); }}
          className={`w-full text-center py-2 rounded-xl text-[12px] font-bold transition-all inline-flex items-center justify-center gap-1.5 ${isDark ? 'text-cyan-300 hover:bg-white/5' : 'text-cyan-700 hover:bg-cyan-50'}`}
        >
          Open Alert Center
          <ArrowRight className="w-3.5 h-3.5" />
        </button>
      </div>
    </div>
  );
}
