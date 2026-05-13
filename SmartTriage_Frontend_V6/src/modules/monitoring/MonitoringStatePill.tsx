/**
 * MonitoringStatePill — single canonical visual representation of the
 * eight monitoring lifecycle states. Replaces the binary LIVE/DEMO
 * indicator that previously couldn't distinguish "not started yet"
 * from "device disconnected" from "signal poor".
 *
 * One component, one tooltip, one color contract — used everywhere a
 * monitoring state needs to be shown so clinicians learn the colors
 * once and read them consistently across the app.
 */
import type { MonitoringState } from '@/api/types';
import { Activity, AlertTriangle, CheckCircle2, Pause, RotateCcw, Square, WifiOff } from 'lucide-react';

interface Props {
  state: MonitoringState | 'NOT_STARTED';
  /** Compact mode renders just the dot + short label. */
  compact?: boolean;
}

interface StateMeta {
  label: string;
  dotClass: string;
  textClass: string;
  bgClass: string;
  icon: React.ReactNode;
  pulse: boolean;
}

const META: Record<MonitoringState | 'NOT_STARTED', StateMeta> = {
  NOT_STARTED: {
    label: 'Awaiting Start',
    dotClass: 'bg-slate-400',
    textClass: 'text-slate-600',
    bgClass: 'bg-slate-100 border-slate-200',
    icon: <Activity className="w-3 h-3" />,
    pulse: false,
  },
  STARTING: {
    label: 'Connecting…',
    dotClass: 'bg-cyan-400',
    textClass: 'text-cyan-700',
    bgClass: 'bg-cyan-50 border-cyan-200',
    icon: <Activity className="w-3 h-3" />,
    pulse: true,
  },
  LIVE: {
    label: 'Live',
    dotClass: 'bg-emerald-500',
    textClass: 'text-emerald-700',
    bgClass: 'bg-emerald-50 border-emerald-200',
    icon: <CheckCircle2 className="w-3 h-3" />,
    pulse: true,
  },
  DEGRADED: {
    label: 'Signal poor',
    dotClass: 'bg-amber-500',
    textClass: 'text-amber-700',
    bgClass: 'bg-amber-50 border-amber-200',
    icon: <AlertTriangle className="w-3 h-3" />,
    pulse: false,
  },
  STALLED: {
    label: 'No data',
    dotClass: 'bg-amber-500',
    textClass: 'text-amber-700',
    bgClass: 'bg-amber-50 border-amber-200',
    icon: <AlertTriangle className="w-3 h-3" />,
    pulse: true,
  },
  PAUSED: {
    label: 'Paused',
    dotClass: 'bg-slate-500',
    textClass: 'text-slate-700',
    bgClass: 'bg-slate-100 border-slate-300 border-dashed',
    icon: <Pause className="w-3 h-3" />,
    pulse: false,
  },
  DISCONNECTED: {
    label: 'Device offline',
    dotClass: 'bg-red-500',
    textClass: 'text-red-700',
    bgClass: 'bg-red-50 border-red-200',
    icon: <WifiOff className="w-3 h-3" />,
    pulse: false,
  },
  ENDED: {
    label: 'Ended',
    dotClass: 'bg-slate-400',
    textClass: 'text-slate-500',
    bgClass: 'bg-slate-50 border-slate-200',
    icon: <Square className="w-3 h-3" />,
    pulse: false,
  },
};

export default function MonitoringStatePill({ state, compact = false }: Props) {
  const meta = META[state] ?? META.NOT_STARTED;
  if (compact) {
    return (
      <span className="inline-flex items-center gap-1">
        <span
          className={`w-1.5 h-1.5 rounded-full ${meta.dotClass} ${meta.pulse ? 'animate-pulse' : ''}`}
        />
        <span className={`text-[10px] font-bold uppercase tracking-wide ${meta.textClass}`}>
          {meta.label}
        </span>
      </span>
    );
  }
  return (
    <span
      className={`inline-flex items-center gap-1.5 px-2 py-1 rounded-lg border text-[11px] font-bold ${meta.bgClass} ${meta.textClass}`}
    >
      <span
        className={`w-1.5 h-1.5 rounded-full ${meta.dotClass} ${meta.pulse ? 'animate-pulse' : ''}`}
      />
      {meta.icon}
      <span className="uppercase tracking-wide">{meta.label}</span>
    </span>
  );
}
