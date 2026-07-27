/* ═══════════════════════════════════════════════════════════════
   AdminHome — the HOSPITAL_ADMIN dashboard.

   NOT the charge-nurse clinical board. A hospital admin runs the
   FACILITY, so this is an operations panel that synthesises the three
   things an admin actually owns — bed capacity, staffing coverage and
   the device fleet — and, above all, an "attention" strip that tells
   them what to act on (out-of-service beds, offline monitors, a zone
   carrying patients with nobody rostered to it).

   Everything here is non-PHI operational data. Patient-level clinical
   alerts (deterioration, critical labs) are a clinical need-to-know
   signal the backend deliberately withholds from admins
   (@clinicalAuthz.canReadHospitalAlerts) — branching to this home means
   those fetches / firehose subscriptions never start for an admin.
   Navigation lives in the sidebar; this page is insight, not a menu.

   Visual language mirrors the rest of SmartTriage: glassCard surfaces,
   tinted icon chips on section headers, Kpi tiles with tone-coloured
   numbers, and cell-styled rows.
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback } from 'react';
import type { ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import type { LucideIcon } from 'lucide-react';
import {
  BedDouble, Cpu, Users, Loader2, RefreshCw, ChevronRight, Gauge,
  ShieldCheck, AlertTriangle, BatteryLow, WifiOff, Wrench, UserX, DoorOpen,
} from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { useTheme } from '@/hooks/useTheme';
import { bedsApi } from '@/api/beds';
import { iotApi } from '@/api/iot';
import { shiftApi } from '@/api/shifts';
import type { BedResponse, DeviceResponse, ShiftAssignmentResponse } from '@/api/types';

const ZONE_ORDER = ['RESUS', 'ACUTE', 'GENERAL', 'TRIAGE', 'OBSERVATION', 'ISOLATION', 'PEDIATRIC'];
const ZONE_LABELS: Record<string, string> = {
  RESUS: 'Resuscitation', ACUTE: 'Acute', GENERAL: 'General', TRIAGE: 'Triage',
  OBSERVATION: 'Observation', ISOLATION: 'Isolation', PEDIATRIC: 'Pediatric',
};
const LOW_BATTERY = 20;

/** Fullness → tone. Green under 70 %, amber to 90 %, red above — the admin's cue to open beds. */
function loadTone(pct: number) {
  if (pct >= 90) return { bar: 'bg-rose-500', textCls: 'text-rose-600' };
  if (pct >= 70) return { bar: 'bg-amber-500', textCls: 'text-amber-600' };
  return { bar: 'bg-emerald-500', textCls: 'text-emerald-600' };
}

export function AdminHome() {
  const navigate = useNavigate();
  const { glassCard, isDark, text } = useTheme();
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
  const trackBg = isDark ? 'rgba(255,255,255,0.08)' : 'rgba(15,23,42,0.06)';
  const cell = isDark ? 'bg-white/[0.04] border-white/10' : 'bg-white/60 border-slate-200/60';
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  const firstName = user?.fullName?.split(' ')?.[0] || 'there';

  const [beds, setBeds] = useState<BedResponse[]>([]);
  const [devices, setDevices] = useState<DeviceResponse[]>([]);
  const [onDuty, setOnDuty] = useState<ShiftAssignmentResponse[]>([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    if (!hospitalId) { setLoading(false); return; }
    setLoading(true);
    const [bedsR, devicesR, shiftR] = await Promise.allSettled([
      bedsApi.getBedsForHospital(hospitalId),
      iotApi.getDevicesByHospital(hospitalId, 0, 200),
      shiftApi.getCurrentShift(hospitalId),
    ]);
    if (bedsR.status === 'fulfilled') setBeds(bedsR.value ?? []);
    if (devicesR.status === 'fulfilled') setDevices(devicesR.value?.content ?? []);
    if (shiftR.status === 'fulfilled') setOnDuty((shiftR.value ?? []).filter((a) => a.active));
    setLoading(false);
  }, [hospitalId]);

  useEffect(() => { void load(); }, [load]);

  // ── Derived, non-PHI operational state ──
  const bedsOccupied = beds.filter((b) => b.status === 'OCCUPIED').length;
  const bedsOOS = beds.filter((b) => b.status === 'OUT_OF_SERVICE').length;
  const usable = beds.filter((b) => b.status !== 'OUT_OF_SERVICE').length;
  const bedsOpen = usable - bedsOccupied;
  const occupancyPct = usable > 0 ? Math.round((bedsOccupied / usable) * 100) : 0;

  const staffByZone: Record<string, ShiftAssignmentResponse[]> = {};
  for (const a of onDuty) (staffByZone[a.zone] ??= []).push(a);

  const zones = Array.from(new Set<string>([
    ...ZONE_ORDER, ...beds.map((b) => b.zone as string), ...Object.keys(staffByZone),
  ])).filter((z) => beds.some((b) => b.zone === z) || staffByZone[z]?.length);
  zones.sort((a, b) => (ZONE_ORDER.indexOf(a) + 100) % 100 - (ZONE_ORDER.indexOf(b) + 100) % 100);

  const zoneRows = zones.map((zone) => {
    const zb = beds.filter((b) => b.zone === zone);
    const total = zb.filter((b) => b.status !== 'OUT_OF_SERVICE').length;
    const occ = zb.filter((b) => b.status === 'OCCUPIED').length;
    const staffList = staffByZone[zone] ?? [];
    const pct = total > 0 ? Math.round((occ / total) * 100) : 0;
    return { zone, total, occ, staff: staffList.length, lead: staffList.some((a) => a.isShiftLead), staffList, pct, uncovered: occ > 0 && staffList.length === 0 };
  });

  const devOnline = devices.filter((d) => d.status === 'ONLINE' || d.status === 'MONITORING').length;
  const devOffline = devices.filter((d) => (d.status === 'OFFLINE' || d.status === 'ERROR') && d.inService);
  const devLowBattery = devices.filter((d) => d.inService && d.batteryLevel != null && d.batteryLevel < LOW_BATTERY);

  const uncoveredZones = zoneRows.filter((z) => z.uncovered);
  const attention = [
    uncoveredZones.length > 0 && {
      key: 'coverage', icon: UserX, tone: 'rose' as const,
      text: `${uncoveredZones.map((z) => ZONE_LABELS[z.zone] ?? z.zone).join(', ')} — patients in bed, no staff rostered`,
      to: '/shift-planner',
    },
    devOffline.length > 0 && {
      key: 'offline', icon: WifiOff, tone: 'amber' as const,
      text: `${devOffline.length} in-service ${devOffline.length === 1 ? 'monitor is' : 'monitors are'} offline`,
      to: '/iot-devices',
    },
    devLowBattery.length > 0 && {
      key: 'battery', icon: BatteryLow, tone: 'amber' as const,
      text: `${devLowBattery.length} ${devLowBattery.length === 1 ? 'device' : 'devices'} low on battery (below ${LOW_BATTERY}%)`,
      to: '/iot-devices',
    },
    bedsOOS > 0 && {
      key: 'oos', icon: Wrench, tone: 'slate' as const,
      text: `${bedsOOS} ${bedsOOS === 1 ? 'bed is' : 'beds are'} out of service`,
      to: '/admin/beds',
    },
  ].filter(Boolean) as { key: string; icon: LucideIcon; tone: 'rose' | 'amber' | 'slate'; text: string; to: string }[];

  const attnTone: Record<string, string> = {
    rose: isDark ? 'bg-rose-500/10 border-rose-500/30 text-rose-300' : 'bg-rose-50 border-rose-200 text-rose-700',
    amber: isDark ? 'bg-amber-500/10 border-amber-500/30 text-amber-300' : 'bg-amber-50 border-amber-200 text-amber-700',
    slate: isDark ? 'bg-slate-500/10 border-slate-500/25 text-slate-300' : 'bg-slate-50 border-slate-200 text-slate-600',
  };

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-7xl mx-auto space-y-4 animate-fade-in">
        {/* Header */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5 flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-indigo-500/20 flex items-center justify-center">
              <ShieldCheck className="w-5 h-5 text-indigo-300" />
            </div>
            <div className="flex-1">
              <h1 className="text-lg font-bold text-white">Facility operations — Welcome, {firstName}</h1>
              <p className="text-sm text-white/50">Capacity, staffing and device fleet across the department, right now.</p>
            </div>
            <button onClick={load} disabled={loading}
              className="inline-flex items-center gap-2 px-3 py-2 text-xs font-bold rounded-xl bg-white/10 hover:bg-white/20 text-white transition-colors">
              {loading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5" />}
              Refresh
            </button>
          </div>
        </div>

        {/* KPI strip */}
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 animate-fade-up">
          <Kpi icon={Gauge} label="Bed occupancy" value={loading ? '—' : `${occupancyPct}%`} tone={loadTone(occupancyPct).textCls} isDark={isDark} />
          <Kpi icon={DoorOpen} label="Open beds" value={loading ? '—' : bedsOpen} tone="text-emerald-600" isDark={isDark} />
          <Kpi icon={Cpu} label="Devices online" value={loading ? '—' : `${devOnline}/${devices.length}`} tone={devOffline.length > 0 ? 'text-amber-600' : 'text-cyan-600'} isDark={isDark} />
          <Kpi icon={Users} label="Staff on duty" value={loading ? '—' : onDuty.length} tone="text-indigo-600" isDark={isDark} />
        </div>

        {/* Needs attention */}
        {!loading && attention.length > 0 && (
          <div className="space-y-2 animate-fade-up">
            {attention.map((a) => {
              const Icon = a.icon;
              return (
                <button key={a.key} type="button" onClick={() => navigate(a.to)}
                  className={`w-full flex items-center gap-2.5 rounded-xl px-3.5 py-2.5 text-left border transition-all hover:-translate-y-px ${attnTone[a.tone]}`}>
                  <Icon className="w-4 h-4 flex-shrink-0" />
                  <span className="text-xs font-semibold flex-1">{a.text}</span>
                  <ChevronRight className="w-4 h-4 opacity-60" />
                </button>
              );
            })}
          </div>
        )}
        {!loading && attention.length === 0 && (
          <div className={`rounded-xl px-3.5 py-2.5 text-xs font-semibold flex items-center gap-2.5 border ${isDark ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-300' : 'bg-emerald-50 border-emerald-200 text-emerald-700'}`}>
            <ShieldCheck className="w-4 h-4" /> All clear — beds, staffing and the device fleet are nominal.
          </div>
        )}

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
          {/* Bed capacity — spans 2 */}
          <div className="lg:col-span-2 rounded-2xl p-5" style={glassCard}>
            <SectionHeader icon={BedDouble} tint="16,185,129" iconCls="text-emerald-500" title="Bed capacity"
              right={<span className={`text-[11px] ${text.muted}`}>{bedsOccupied} occupied · {bedsOpen} open{bedsOOS ? ` · ${bedsOOS} OOS` : ''}</span>} />
            <div className="flex items-end gap-2.5 mt-4 mb-2">
              <span className={`text-4xl font-extrabold tabular-nums leading-none ${loadTone(occupancyPct).textCls}`}>{loading ? '—' : `${occupancyPct}%`}</span>
              <span className={`text-[11px] font-semibold mb-1 ${text.muted}`}>occupancy · {usable} usable beds</span>
            </div>
            <div className="h-2 rounded-full overflow-hidden mb-4" style={{ background: trackBg }}>
              <div className={`h-full rounded-full ${loadTone(occupancyPct).bar} transition-all`} style={{ width: `${occupancyPct}%` }} />
            </div>
            <div className="space-y-1.5">
              {zoneRows.length === 0 ? (
                <p className={`text-sm py-4 text-center ${text.muted}`}>No beds configured yet.</p>
              ) : zoneRows.map((z) => {
                const tone = loadTone(z.pct);
                return (
                  <button key={z.zone} type="button" onClick={() => navigate('/admin/beds')}
                    className={`w-full flex items-center gap-3 rounded-lg border p-2.5 transition-colors ${cell} hover:brightness-105`}>
                    <span className={`w-24 flex-shrink-0 text-left text-xs font-bold ${text.heading}`}>{ZONE_LABELS[z.zone] ?? z.zone}</span>
                    <div className="flex-1 h-2 rounded-full overflow-hidden" style={{ background: trackBg }}>
                      <div className={`h-full rounded-full ${tone.bar}`} style={{ width: `${z.pct}%` }} />
                    </div>
                    <span className={`w-12 text-right text-[11px] font-bold tabular-nums ${tone.textCls}`}>{z.occ}/{z.total}</span>
                  </button>
                );
              })}
            </div>
          </div>

          {/* Device fleet */}
          <div className="rounded-2xl p-5" style={glassCard}>
            <SectionHeader icon={Cpu} tint="6,182,212" iconCls="text-cyan-500" title="Device fleet"
              right={<span className={`text-[11px] ${text.muted}`}>{devices.filter((d) => d.inService).length} in service</span>} />
            <div className="flex items-end gap-2 mt-4 mb-4">
              <span className={`text-4xl font-extrabold tabular-nums leading-none ${devOffline.length > 0 ? 'text-amber-600' : 'text-cyan-600'}`}>{loading ? '—' : devOnline}</span>
              <span className={`text-[11px] font-semibold mb-1 ${text.muted}`}>online now</span>
            </div>
            <div className="space-y-2">
              <FleetRow label="Offline" value={devOffline.length} bad={devOffline.length > 0} cell={cell} isDark={isDark} textBody={text.body} textMuted={text.muted} />
              <FleetRow label="Low battery" value={devLowBattery.length} bad={devLowBattery.length > 0} cell={cell} isDark={isDark} textBody={text.body} textMuted={text.muted} />
              <FleetRow label="Total registered" value={devices.length} bad={false} cell={cell} isDark={isDark} textBody={text.body} textMuted={text.muted} />
            </div>
            {devLowBattery.length > 0 && (
              <div className="mt-3 pt-3 space-y-1" style={{ borderTop: borderStyle }}>
                {devLowBattery.slice(0, 3).map((d) => (
                  <div key={d.id} className="flex items-center gap-2 py-0.5">
                    <BatteryLow className="w-3.5 h-3.5 text-amber-500 flex-shrink-0" />
                    <span className={`text-[11px] font-semibold truncate flex-1 ${text.body}`}>{d.deviceName}</span>
                    <span className="text-[11px] font-bold text-amber-600 tabular-nums">{d.batteryLevel}%</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>

        {/* Staffing coverage */}
        <div className="rounded-2xl p-5" style={glassCard}>
          <SectionHeader icon={Users} tint="99,102,241" iconCls="text-indigo-500" title="Staffing coverage"
            right={
              <button onClick={() => navigate('/shift-planner')}
                className="text-[11px] font-bold inline-flex items-center gap-1 text-indigo-500 hover:text-indigo-400">
                Shift planner <ChevronRight className="w-3.5 h-3.5" />
              </button>
            }
            sub={`${onDuty.length} on duty this shift`} />
          {loading && onDuty.length === 0 ? (
            <div className={`text-center py-10 ${text.muted}`}><Loader2 className="w-6 h-6 animate-spin mx-auto opacity-50" /></div>
          ) : zoneRows.length === 0 ? (
            <p className={`py-8 text-center text-sm ${text.muted}`}>No zones staffed or configured yet.</p>
          ) : (
            <div className="space-y-2 mt-4">
              {zoneRows.map((z) => (
                <div key={z.zone} className={`rounded-lg border p-3 ${z.uncovered ? (isDark ? 'bg-rose-500/10 border-rose-500/30' : 'bg-rose-50 border-rose-200') : cell}`}>
                  <div className="flex items-center gap-2">
                    <span className={`text-xs font-bold ${text.heading}`}>{ZONE_LABELS[z.zone] ?? z.zone}</span>
                    {z.uncovered ? (
                      <span className={`inline-flex items-center gap-1 text-[11px] font-bold ${isDark ? 'text-rose-300' : 'text-rose-600'}`}>
                        <AlertTriangle className="w-3.5 h-3.5" /> {z.occ} in bed · no staff rostered
                      </span>
                    ) : (
                      <span className={`text-[11px] font-semibold ${text.muted}`}>
                        {z.staff} {z.staff === 1 ? 'staff' : 'staff'} · {z.occ}/{z.total} beds
                      </span>
                    )}
                    {z.lead && <span className="ml-auto text-[9px] font-bold px-1.5 py-0.5 rounded bg-amber-500/15 text-amber-600">SHIFT LEAD</span>}
                  </div>
                  {z.staffList.length > 0 && (
                    <div className="flex flex-wrap gap-1.5 mt-2">
                      {z.staffList.map((a) => (
                        <span key={a.id}
                          className={`inline-flex items-center gap-1 text-[11px] font-semibold px-2 py-0.5 rounded-md ${isDark ? 'bg-white/5 text-slate-200' : 'bg-white/70 border border-slate-200/60 text-slate-700'}`}
                          title={a.userDesignationLabel ?? a.userRole}>
                          {a.userName}
                        </span>
                      ))}
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

/** Section header — tinted icon chip + title (+ optional sub / right slot), matching the app pattern. */
function SectionHeader({ icon: Icon, tint, iconCls, title, right, sub }: {
  icon: LucideIcon; tint: string; iconCls: string; title: string; right?: ReactNode; sub?: string;
}) {
  const { text } = useTheme();
  return (
    <div className="flex items-center gap-3">
      <div className="w-9 h-9 rounded-xl flex items-center justify-center flex-shrink-0" style={{ backgroundColor: `rgba(${tint},0.12)` }}>
        <Icon className={`w-[18px] h-[18px] ${iconCls}`} />
      </div>
      <div className="min-w-0">
        <h3 className={`text-base font-extrabold tracking-tight ${text.heading}`}>{title}</h3>
        {sub && <p className={`text-[11px] font-medium ${text.muted}`}>{sub}</p>}
      </div>
      {right && <div className="ml-auto">{right}</div>}
    </div>
  );
}

/** KPI tile — icon + tone-coloured number + label, matching ShiftSummaryCard's Kpi. */
function Kpi({ icon: Icon, label, value, tone, isDark }: {
  icon: LucideIcon; label: string; value: string | number; tone: string; isDark: boolean;
}) {
  return (
    <div className="flex items-center gap-3 p-3.5 rounded-2xl" style={{
      background: isDark ? 'rgba(12,74,110,0.18)' : 'rgba(255,255,255,0.6)',
      border: isDark ? '1px solid rgba(2,132,199,0.22)' : '1px solid rgba(203,213,225,0.4)',
      boxShadow: isDark ? '0 2px 8px rgba(0,0,0,0.15)' : '0 2px 8px rgba(99,102,241,0.05)',
    }}>
      <div className="w-9 h-9 rounded-xl flex items-center justify-center flex-shrink-0" style={{ background: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(15,23,42,0.04)' }}>
        <Icon className={`w-[18px] h-[18px] ${tone}`} />
      </div>
      <div className="min-w-0">
        <div className={`text-2xl font-extrabold leading-none tabular-nums ${tone}`}>{value}</div>
        <div className={`text-[11px] font-medium mt-1 ${isDark ? 'text-slate-400' : 'text-slate-500'}`}>{label}</div>
      </div>
    </div>
  );
}

/** Fleet stat row inside a subtle cell. */
function FleetRow({ label, value, bad, cell, isDark, textBody, textMuted }: {
  label: string; value: number; bad: boolean; cell: string; isDark: boolean; textBody: string; textMuted: string;
}) {
  return (
    <div className={`flex items-center justify-between rounded-lg border px-3 py-2 ${cell}`}>
      <span className={`text-[12px] font-semibold ${textMuted}`}>{label}</span>
      <span className={`text-[13px] font-bold tabular-nums ${bad ? (isDark ? 'text-amber-300' : 'text-amber-600') : textBody}`}>{value}</span>
    </div>
  );
}
