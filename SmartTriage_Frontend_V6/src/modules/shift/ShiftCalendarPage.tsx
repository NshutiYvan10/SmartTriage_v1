/* ════════════════════════════════════════════════════════════════════
   Shift Calendar — month-grid view of the roster, leave overlay,
   and per-day coverage map.

   What it shows:
     • A 7-column month grid (Mon-first), with each day cell carrying
       three small signals:
         — number of staff scheduled (DAY + NIGHT combined)
         — number of staff on approved leave that day
         — a coverage pill: GOOD / THIN / GAP (heuristic, see below)
     • Click a day → right-hand panel renders the full roster for that
       date (split DAY / NIGHT), the leave list, and a per-zone gap
       summary that the Charge Nurse uses to spot holes before they
       become real coverage failures.

   Heuristic for coverage pill:
     GAP   — any of {RESUS, ACUTE, TRIAGE} has zero staff on either shift
     THIN  — any of those zones has only one staff member on a shift
     GOOD  — otherwise

   The thresholds are intentionally minimal-viable; a future iteration
   should pull per-hospital target staffing from a config table.

   Data sources (one network call per panel, batched):
     • shiftApi.getByDate     — roster for a single day
     • leaveApi.listOverlapping — every approved leave overlapping the
                                  visible month, keyed by date locally
   ════════════════════════════════════════════════════════════════════ */

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ChevronLeft, ChevronRight, Calendar, AlertTriangle, UserMinus, Users, Sun, Moon,
  Plus, Trash2, Copy, FileText, X, Loader2,
} from 'lucide-react';
import { shiftApi, leaveApi, userApi, shiftTemplateApi } from '@/api';
import type {
  ApplyTemplateRequest,
  BulkPlanResult,
  CopyWeekRequest,
  CreateShiftAssignmentRequest,
  EdZone,
  LeaveType,
  ShiftAssignmentResponse,
  ShiftFunction,
  ShiftPeriod,
  ShiftTemplateResponse,
  StaffLeaveResponse,
  UserResponse,
} from '@/api/types';
import { useAuthStore } from '@/store/authStore';

const ALL_ZONES: EdZone[] = [
  'RESUS', 'ACUTE', 'GENERAL', 'AMBULATORY', 'TRIAGE',
  'OBSERVATION', 'ISOLATION', 'PEDIATRIC', 'NEONATAL',
];
const ALL_FUNCTIONS: ShiftFunction[] = [
  'CHARGE_NURSE', 'TRIAGE_NURSE', 'ZONE_NURSE',
  'PRIMARY_DOCTOR', 'SUPERVISING_DOCTOR', 'RESIDENT',
];

/** Monday of the ISO week containing `d` (local time). */
function mondayOf(d: Date): Date {
  const dow = monDow(d);
  const m = new Date(d);
  m.setDate(d.getDate() - dow);
  m.setHours(0, 0, 0, 0);
  return m;
}

/* ─── Date utilities (local-time, not UTC) ─── */

function fmtIso(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function startOfMonth(d: Date) { return new Date(d.getFullYear(), d.getMonth(), 1); }
function endOfMonth(d: Date)   { return new Date(d.getFullYear(), d.getMonth() + 1, 0); }
function addMonths(d: Date, n: number) { return new Date(d.getFullYear(), d.getMonth() + n, 1); }

/* Mon-first day-of-week index: Mon=0 ... Sun=6 */
function monDow(d: Date) { return (d.getDay() + 6) % 7; }

function* daysBetween(from: Date, to: Date): Generator<Date> {
  const cur = new Date(from);
  while (cur <= to) {
    yield new Date(cur);
    cur.setDate(cur.getDate() + 1);
  }
}

/* ─── Tier-1 zones we hold to the staffing heuristic. */
const TIER_1_ZONES: EdZone[] = ['RESUS', 'ACUTE', 'TRIAGE'];

type CoverageLevel = 'GOOD' | 'THIN' | 'GAP';

interface DaySignals {
  date: string;
  scheduledCount: number;
  leaveCount: number;
  coverage: CoverageLevel | null;  // null when not yet loaded
}

/* ─── Component ─── */

export function ShiftCalendarPage() {
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  const isChargeNurse = user?.designation === 'CHARGE_NURSE';
  const isHospitalAdmin = user?.role === 'HOSPITAL_ADMIN';
  const canEdit = isChargeNurse || isHospitalAdmin || user?.role === 'SUPER_ADMIN';

  const [cursor, setCursor] = useState<Date>(() => startOfMonth(new Date()));
  const [selectedDate, setSelectedDate] = useState<string>(() => fmtIso(new Date()));
  const [signals, setSignals] = useState<Record<string, DaySignals>>({});
  const [monthLeaves, setMonthLeaves] = useState<StaffLeaveResponse[]>([]);
  const [loading, setLoading] = useState(false);

  // Modals & toasts. `reloadKey` is a coarse "data changed → re-fetch
  // everything visible" trigger after any planning op. Cheap to bump,
  // and matches the operational expectation that bulk ops update the
  // whole month.
  const [showCopyWeek, setShowCopyWeek] = useState(false);
  const [showApplyTemplate, setShowApplyTemplate] = useState(false);
  const [reloadKey, setReloadKey] = useState(0);
  const [toast, setToast] = useState<string | null>(null);
  const showToast = useCallback((msg: string) => {
    setToast(msg);
    window.setTimeout(() => setToast(null), 5000);
  }, []);
  const onPlanningChange = useCallback(() => setReloadKey((k) => k + 1), []);

  /* Load every day's roster for the visible month + leave overlay. */
  useEffect(() => {
    if (!hospitalId) return;
    let cancelled = false;

    const monthStart = startOfMonth(cursor);
    const monthEnd   = endOfMonth(cursor);

    const allDates: string[] = [];
    for (const d of daysBetween(monthStart, monthEnd)) allDates.push(fmtIso(d));

    setLoading(true);

    (async () => {
      try {
        const [rosters, leaves] = await Promise.all([
          Promise.all(allDates.map((iso) => shiftApi.getByDate(hospitalId, iso))),
          leaveApi.listOverlapping(hospitalId, fmtIso(monthStart), fmtIso(monthEnd)),
        ]);
        if (cancelled) return;

        // Index leaves by date by expanding [startsOn, endsOn] for each row.
        const leaveByDate: Record<string, StaffLeaveResponse[]> = {};
        for (const l of leaves) {
          const s = new Date(l.startsOn + 'T00:00:00');
          const e = new Date(l.endsOn   + 'T00:00:00');
          for (const d of daysBetween(
            s < monthStart ? monthStart : s,
            e > monthEnd   ? monthEnd   : e,
          )) {
            const key = fmtIso(d);
            (leaveByDate[key] ||= []).push(l);
          }
        }

        const next: Record<string, DaySignals> = {};
        allDates.forEach((iso, idx) => {
          const roster: ShiftAssignmentResponse[] = rosters[idx] ?? [];
          next[iso] = {
            date: iso,
            scheduledCount: roster.length,
            leaveCount: (leaveByDate[iso] ?? []).length,
            coverage: deriveCoverage(roster),
          };
        });

        setMonthLeaves(leaves);
        setSignals(next);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();

    return () => { cancelled = true; };
  }, [cursor, hospitalId, reloadKey]);

  const calendarCells = useMemo(() => buildGrid(cursor), [cursor]);

  /* ─── Render ─── */

  if (!hospitalId) {
    return (
      <div className="p-8 text-sm text-gray-500">
        No hospital is associated with your account. The calendar requires a hospital context.
      </div>
    );
  }

  return (
    <div className="p-5 space-y-5">
      <header className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <Calendar className="w-5 h-5 text-gray-500" />
          <h1 className="text-xl font-bold text-gray-900 tracking-tight">Shift Calendar</h1>
          <span className="text-sm text-gray-400">·</span>
          <h2 className="text-base font-semibold text-gray-700">
            {cursor.toLocaleString('en-US', { month: 'long', year: 'numeric' })}
          </h2>
        </div>
        <div className="flex items-center gap-2">
          {canEdit && (
            <>
              <button
                onClick={() => setShowCopyWeek(true)}
                className="px-3 py-1.5 rounded-lg border border-gray-200 text-xs font-semibold text-gray-700 hover:bg-gray-50 transition-colors inline-flex items-center gap-1.5"
              >
                <Copy className="w-3.5 h-3.5" />
                Copy Week
              </button>
              <button
                onClick={() => setShowApplyTemplate(true)}
                className="px-3 py-1.5 rounded-lg border border-gray-200 text-xs font-semibold text-gray-700 hover:bg-gray-50 transition-colors inline-flex items-center gap-1.5"
              >
                <FileText className="w-3.5 h-3.5" />
                Apply Template
              </button>
              <span className="w-px h-6 bg-gray-200 mx-1" />
            </>
          )}
          <button
            onClick={() => setCursor((c) => addMonths(c, -1))}
            className="p-2 rounded-lg border border-gray-200 hover:bg-gray-50 transition-colors"
            aria-label="Previous month"
          >
            <ChevronLeft className="w-4 h-4 text-gray-600" />
          </button>
          <button
            onClick={() => setCursor(startOfMonth(new Date()))}
            className="px-3 py-1.5 rounded-lg border border-gray-200 text-xs font-semibold text-gray-700 hover:bg-gray-50 transition-colors"
          >
            Today
          </button>
          <button
            onClick={() => setCursor((c) => addMonths(c, 1))}
            className="p-2 rounded-lg border border-gray-200 hover:bg-gray-50 transition-colors"
            aria-label="Next month"
          >
            <ChevronRight className="w-4 h-4 text-gray-600" />
          </button>
        </div>
      </header>

      {toast && (
        <div className="rounded-lg bg-emerald-50 border border-emerald-200 text-emerald-800 px-4 py-2 text-sm">
          {toast}
        </div>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-5">
        {/* ── Calendar grid (2/3) ── */}
        <section className="lg:col-span-2 bg-white rounded-2xl border border-gray-200 p-4">
          <div className="grid grid-cols-7 gap-1 text-[11px] font-bold uppercase text-gray-400 mb-2">
            {['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].map((d) => (
              <div key={d} className="px-2 py-1">{d}</div>
            ))}
          </div>
          <div className="grid grid-cols-7 gap-1">
            {calendarCells.map((cell, i) => {
              const inMonth = cell.month === cursor.getMonth();
              const iso = fmtIso(cell.date);
              const sig = signals[iso];
              const isSelected = selectedDate === iso;
              const isToday = iso === fmtIso(new Date());

              return (
                <button
                  key={i}
                  onClick={() => setSelectedDate(iso)}
                  className={[
                    'group relative h-24 rounded-lg border p-2 text-left transition-all',
                    inMonth
                      ? 'bg-white border-gray-200 hover:border-gray-300 hover:bg-gray-50'
                      : 'bg-gray-50 border-gray-100 text-gray-400',
                    isSelected ? 'ring-2 ring-blue-500 border-blue-300' : '',
                  ].join(' ')}
                >
                  <div className="flex items-center justify-between">
                    <span
                      className={[
                        'text-xs font-bold',
                        isToday ? 'inline-flex items-center justify-center w-6 h-6 rounded-full bg-blue-600 text-white' : '',
                      ].join(' ')}
                    >
                      {cell.date.getDate()}
                    </span>
                    {sig?.coverage && <CoveragePill level={sig.coverage} />}
                  </div>
                  {sig && inMonth && (
                    <div className="mt-2 space-y-0.5 text-[11px] text-gray-600">
                      {sig.scheduledCount > 0 && (
                        <div className="flex items-center gap-1">
                          <Users className="w-3 h-3" />
                          <span>{sig.scheduledCount} on shift</span>
                        </div>
                      )}
                      {sig.leaveCount > 0 && (
                        <div className="flex items-center gap-1 text-amber-700">
                          <UserMinus className="w-3 h-3" />
                          <span>{sig.leaveCount} on leave</span>
                        </div>
                      )}
                    </div>
                  )}
                </button>
              );
            })}
          </div>
          {loading && (
            <div className="mt-3 text-xs text-gray-400">Loading roster…</div>
          )}
        </section>

        {/* ── Day-detail panel (1/3) ── */}
        <DayDetailPanel
          hospitalId={hospitalId}
          dateIso={selectedDate}
          monthLeaves={monthLeaves}
          canEdit={canEdit}
          reloadKey={reloadKey}
          onChange={onPlanningChange}
          onToast={showToast}
        />
      </div>

      {showCopyWeek && (
        <CopyWeekModal
          hospitalId={hospitalId}
          defaultFromMonday={fmtIso(mondayOf(new Date(selectedDate + 'T00:00:00')))}
          onClose={() => setShowCopyWeek(false)}
          onDone={(result) => {
            setShowCopyWeek(false);
            onPlanningChange();
            showToast(
              `Copy-week: ${result.slotsFilled} slot(s) filled, ${result.slotsSkipped} skipped, ${result.rowsCreated} rows created.`,
            );
          }}
        />
      )}

      {showApplyTemplate && (
        <ApplyTemplateModal
          hospitalId={hospitalId}
          defaultFromDate={selectedDate}
          onClose={() => setShowApplyTemplate(false)}
          onDone={(result) => {
            setShowApplyTemplate(false);
            onPlanningChange();
            showToast(
              `Apply-template: ${result.slotsFilled} slot(s) filled, ${result.slotsSkipped} skipped, ${result.rowsCreated} rows created.`,
            );
          }}
        />
      )}
    </div>
  );
}

/* ─── Calendar-cell grid builder ─── */

interface Cell { date: Date; month: number; }

function buildGrid(cursor: Date): Cell[] {
  const first = startOfMonth(cursor);
  const last  = endOfMonth(cursor);
  // Pad start to the previous Monday and end to the next Sunday.
  const start = new Date(first);
  start.setDate(first.getDate() - monDow(first));
  const end = new Date(last);
  end.setDate(last.getDate() + (6 - monDow(last)));

  const cells: Cell[] = [];
  for (const d of daysBetween(start, end)) {
    cells.push({ date: d, month: d.getMonth() });
  }
  return cells;
}

function deriveCoverage(roster: ShiftAssignmentResponse[]): CoverageLevel {
  const byZoneShift: Record<string, number> = {};
  for (const r of roster) {
    if (!r.active) continue;
    const k = `${r.zone}::${r.shiftPeriod}`;
    byZoneShift[k] = (byZoneShift[k] ?? 0) + 1;
  }
  const periods: ShiftPeriod[] = ['DAY', 'NIGHT'];
  let worst: CoverageLevel = 'GOOD';
  for (const z of TIER_1_ZONES) {
    for (const p of periods) {
      const n = byZoneShift[`${z}::${p}`] ?? 0;
      if (n === 0) return 'GAP';
      if (n === 1 && worst === 'GOOD') worst = 'THIN';
    }
  }
  return worst;
}

/* ─── Coverage pill ─── */

function CoveragePill({ level }: { level: CoverageLevel }) {
  if (level === 'GOOD') {
    return (
      <span className="px-1.5 py-0.5 rounded-full bg-emerald-50 text-emerald-700 text-[10px] font-bold border border-emerald-200">
        GOOD
      </span>
    );
  }
  if (level === 'THIN') {
    return (
      <span className="px-1.5 py-0.5 rounded-full bg-amber-50 text-amber-700 text-[10px] font-bold border border-amber-200">
        THIN
      </span>
    );
  }
  return (
    <span className="px-1.5 py-0.5 rounded-full bg-rose-50 text-rose-700 text-[10px] font-bold border border-rose-200 inline-flex items-center gap-0.5">
      <AlertTriangle className="w-2.5 h-2.5" />
      GAP
    </span>
  );
}

/* ─── Day detail panel ─── */

interface DayDetailProps {
  hospitalId: string;
  dateIso: string;
  monthLeaves: StaffLeaveResponse[];
  canEdit: boolean;
  reloadKey: number;
  onChange: () => void;
  onToast: (msg: string) => void;
}

function DayDetailPanel({
  hospitalId, dateIso, monthLeaves, canEdit, reloadKey, onChange, onToast,
}: DayDetailProps) {
  const [roster, setRoster] = useState<ShiftAssignmentResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [drawerPeriod, setDrawerPeriod] = useState<ShiftPeriod | null>(null);

  // Past dates are read-only — backend rejects writes; mirror that
  // server rule in the UI so we don't dangle non-functional buttons.
  const isPast = useMemo(() => {
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    return new Date(dateIso + 'T00:00:00') < today;
  }, [dateIso]);
  const editableHere = canEdit && !isPast;

  const reload = useCallback(() => {
    if (!hospitalId || !dateIso) return;
    setLoading(true);
    shiftApi
      .getByDate(hospitalId, dateIso)
      .then((rows) => setRoster(rows))
      .finally(() => setLoading(false));
  }, [hospitalId, dateIso]);

  useEffect(() => {
    let cancelled = false;
    if (!hospitalId || !dateIso) return;
    setLoading(true);
    shiftApi
      .getByDate(hospitalId, dateIso)
      .then((rows) => { if (!cancelled) setRoster(rows); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [hospitalId, dateIso, reloadKey]);

  const handleRemove = useCallback(async (assignmentId: string, label: string) => {
    if (!confirm(`Remove ${label} from this shift?`)) return;
    try {
      await shiftApi.remove(assignmentId);
      onToast(`Removed ${label}.`);
      reload();
      onChange();
    } catch (e: any) {
      onToast(`Failed to remove: ${e?.message ?? 'server error'}`);
    }
  }, [onToast, reload, onChange]);

  const dayLeaves = useMemo(() => {
    const target = new Date(dateIso + 'T00:00:00');
    return monthLeaves.filter((l) => {
      const s = new Date(l.startsOn + 'T00:00:00');
      const e = new Date(l.endsOn + 'T00:00:00');
      return target >= s && target <= e;
    });
  }, [monthLeaves, dateIso]);

  const dayDate = new Date(dateIso + 'T00:00:00');

  return (
    <aside className="bg-white rounded-2xl border border-gray-200 p-4 space-y-4">
      <div>
        <div className="text-[11px] font-bold uppercase text-gray-400">Selected day</div>
        <div className="text-sm font-bold text-gray-900">
          {dayDate.toLocaleDateString('en-US', {
            weekday: 'long', year: 'numeric', month: 'long', day: 'numeric',
          })}
        </div>
      </div>

      <ShiftSection
        title="Day shift" icon={<Sun className="w-3.5 h-3.5" />}
        rows={roster.filter(r => r.shiftPeriod === 'DAY' && r.active)}
        editable={editableHere}
        onAdd={() => setDrawerPeriod('DAY')}
        onRemove={handleRemove}
      />
      <ShiftSection
        title="Night shift" icon={<Moon className="w-3.5 h-3.5" />}
        rows={roster.filter(r => r.shiftPeriod === 'NIGHT' && r.active)}
        editable={editableHere}
        onAdd={() => setDrawerPeriod('NIGHT')}
        onRemove={handleRemove}
      />

      <ZoneCoverageSummary roster={roster.filter(r => r.active)} />

      <LeaveSection leaves={dayLeaves} />

      {drawerPeriod && (
        <QuickAssignDrawer
          hospitalId={hospitalId}
          dateIso={dateIso}
          period={drawerPeriod}
          existing={roster.filter(r => r.shiftPeriod === drawerPeriod && r.active)}
          onClose={() => setDrawerPeriod(null)}
          onAdded={() => {
            setDrawerPeriod(null);
            onToast('Staff assigned.');
            reload();
            onChange();
          }}
          onError={(msg) => onToast(msg)}
        />
      )}

      {loading && <div className="text-xs text-gray-400">Loading…</div>}
    </aside>
  );
}

interface ShiftSectionProps {
  title: string;
  icon: React.ReactNode;
  rows: ShiftAssignmentResponse[];
  editable: boolean;
  onAdd: () => void;
  onRemove: (assignmentId: string, label: string) => void;
}

function ShiftSection({ title, icon, rows, editable, onAdd, onRemove }: ShiftSectionProps) {
  if (rows.length === 0) {
    return (
      <div>
        <div className="flex items-center justify-between">
          <SectionTitle title={title} icon={icon} count={0} />
          {editable && <AddStaffButton onClick={onAdd} />}
        </div>
        <div className="text-xs text-gray-400 italic">No staff scheduled.</div>
      </div>
    );
  }

  // Group by zone for readability.
  const byZone: Record<string, ShiftAssignmentResponse[]> = {};
  for (const r of rows) (byZone[r.zone] ||= []).push(r);

  return (
    <div>
      <div className="flex items-center justify-between">
        <SectionTitle title={title} icon={icon} count={rows.length} />
        {editable && <AddStaffButton onClick={onAdd} />}
      </div>
      <div className="space-y-1.5">
        {Object.entries(byZone).map(([zone, zRows]) => (
          <div key={zone} className="text-[12px]">
            <div className="font-bold text-gray-700">{zone}</div>
            <ul className="ml-3 mt-0.5 space-y-0.5">
              {zRows.map((r) => (
                <li key={r.id} className="text-gray-600 flex items-baseline gap-2 group">
                  <span className="text-[10px] uppercase font-bold text-gray-400 w-24">
                    {r.shiftFunction.replace(/_/g, ' ')}
                  </span>
                  <span>{r.userName}</span>
                  {r.isShiftLead && (
                    <span className="text-[10px] font-bold text-violet-700 bg-violet-50 px-1.5 py-0.5 rounded">
                      Shift Lead
                    </span>
                  )}
                  {editable && (
                    <button
                      onClick={() => onRemove(r.id, r.userName)}
                      className="ml-auto text-gray-300 hover:text-rose-600 opacity-0 group-hover:opacity-100 transition"
                      aria-label={`Remove ${r.userName}`}
                      title="Remove from shift"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                  )}
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>
    </div>
  );
}

function AddStaffButton({ onClick }: { onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className="text-[10px] inline-flex items-center gap-1 px-1.5 py-0.5 rounded border border-dashed border-gray-300 text-gray-500 hover:bg-gray-50 hover:text-gray-700"
    >
      <Plus className="w-3 h-3" />
      Add staff
    </button>
  );
}

function SectionTitle({ title, icon, count }: { title: string; icon: React.ReactNode; count: number }) {
  return (
    <div className="flex items-center gap-2 mb-1.5">
      <span className="text-gray-500">{icon}</span>
      <span className="text-[11px] font-bold uppercase text-gray-500">{title}</span>
      <span className="text-[10px] font-semibold text-gray-400">({count})</span>
    </div>
  );
}

function ZoneCoverageSummary({ roster }: { roster: ShiftAssignmentResponse[] }) {
  const counts = useMemo(() => {
    const m: Record<string, { day: number; night: number }> = {};
    for (const r of roster) {
      const slot = (m[r.zone] ||= { day: 0, night: 0 });
      if (r.shiftPeriod === 'DAY') slot.day++; else slot.night++;
    }
    return m;
  }, [roster]);

  const zones = Object.keys(counts).sort();
  if (zones.length === 0) return null;

  return (
    <div>
      <div className="text-[11px] font-bold uppercase text-gray-500 mb-1.5">Zone coverage</div>
      <div className="text-[11px] grid grid-cols-3 gap-y-0.5 gap-x-2">
        <div />
        <div className="text-gray-400 font-bold">DAY</div>
        <div className="text-gray-400 font-bold">NIGHT</div>
        {zones.map((z) => {
          const isTier1 = TIER_1_ZONES.includes(z as EdZone);
          const c = counts[z];
          return (
            <div key={z} className="contents">
              <div className="font-semibold text-gray-700">{z}</div>
              <NumCell n={c.day}   tier1={isTier1} />
              <NumCell n={c.night} tier1={isTier1} />
            </div>
          );
        })}
      </div>
    </div>
  );
}

function NumCell({ n, tier1 }: { n: number; tier1: boolean }) {
  if (!tier1) return <div className="text-gray-600">{n}</div>;
  if (n === 0)   return <div className="text-rose-700 font-bold">{n}</div>;
  if (n === 1)   return <div className="text-amber-700 font-bold">{n}</div>;
  return <div className="text-emerald-700 font-bold">{n}</div>;
}

function LeaveSection({ leaves }: { leaves: StaffLeaveResponse[] }) {
  if (leaves.length === 0) return null;
  return (
    <div>
      <SectionTitle title="On leave" icon={<UserMinus className="w-3.5 h-3.5" />} count={leaves.length} />
      <ul className="space-y-0.5 text-[12px]">
        {leaves.map((l) => (
          <li key={l.id} className="text-gray-600 flex items-baseline gap-2">
            <span className="text-[10px] uppercase font-bold text-amber-700 bg-amber-50 px-1.5 py-0.5 rounded w-24 text-center">
              {prettyLeaveType(l.leaveType)}
            </span>
            <span>{l.userName}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function prettyLeaveType(t: LeaveType): string {
  switch (t) {
    case 'ANNUAL': return 'Annual';
    case 'SICK': return 'Sick';
    case 'MATERNITY': return 'Maternity';
    case 'BEREAVEMENT': return 'Bereavement';
    case 'COMPASSIONATE': return 'Compassionate';
    case 'STUDY': return 'Study';
    case 'OTHER': return 'Other';
  }
}

/* ════════════════════════════════════════════════════════════════════
   Quick-Assign Drawer
   Inline form on the day-detail panel for assigning a single staff
   member to a single (date, period) shift. Skips users already
   assigned to this slot to avoid the "ended + reinserted" churn.
   ════════════════════════════════════════════════════════════════════ */

interface QuickAssignDrawerProps {
  hospitalId: string;
  dateIso: string;
  period: ShiftPeriod;
  existing: ShiftAssignmentResponse[];
  onClose: () => void;
  onAdded: () => void;
  onError: (msg: string) => void;
}

function QuickAssignDrawer({
  hospitalId, dateIso, period, existing, onClose, onAdded, onError,
}: QuickAssignDrawerProps) {
  const [users, setUsers] = useState<UserResponse[]>([]);
  const [userId, setUserId] = useState('');
  const [zone, setZone] = useState<EdZone>('GENERAL');
  const [shiftFunction, setShiftFunction] = useState<ShiftFunction>('ZONE_NURSE');
  const [isShiftLead, setIsShiftLead] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [loadingUsers, setLoadingUsers] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoadingUsers(true);
    // Page through enough users for a typical hospital; bumping to 200
    // covers KFH/CHUK ED rosters comfortably without paging UX.
    userApi
      .getByHospital(hospitalId, 0, 200)
      .then((page) => { if (!cancelled) setUsers(page.content ?? []); })
      .catch(() => { if (!cancelled) setUsers([]); })
      .finally(() => { if (!cancelled) setLoadingUsers(false); });
    return () => { cancelled = true; };
  }, [hospitalId]);

  const alreadyAssigned = useMemo(
    () => new Set(existing.map((r) => r.userId)),
    [existing],
  );
  const eligible = useMemo(
    () => users.filter(
      (u) => u.accountStatus === 'ACTIVE' && !alreadyAssigned.has(u.id),
    ),
    [users, alreadyAssigned],
  );

  const submit = async () => {
    if (!userId) { onError('Pick a staff member.'); return; }
    setSubmitting(true);
    try {
      const body: CreateShiftAssignmentRequest = {
        userId, zone, shiftFunction, isShiftLead,
        shiftDate: dateIso, shiftPeriod: period,
      };
      await shiftApi.assign(hospitalId, body);
      onAdded();
    } catch (e: any) {
      onError(e?.message ?? 'Failed to assign.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center bg-black/40 p-4">
      <div className="bg-white w-full sm:max-w-md rounded-2xl shadow-xl p-5 space-y-4">
        <div className="flex items-center justify-between">
          <div>
            <div className="text-[11px] font-bold uppercase text-gray-400">Assign staff</div>
            <div className="text-sm font-bold text-gray-900">
              {dateIso} · {period} shift
            </div>
          </div>
          <button onClick={onClose} className="p-1 rounded hover:bg-gray-100">
            <X className="w-4 h-4 text-gray-500" />
          </button>
        </div>

        <div className="space-y-3 text-sm">
          <Field label="Staff member">
            <select
              value={userId}
              onChange={(e) => setUserId(e.target.value)}
              disabled={loadingUsers}
              className="w-full border border-gray-300 rounded-lg px-2 py-1.5 text-sm"
            >
              <option value="">{loadingUsers ? 'Loading…' : 'Select…'}</option>
              {eligible.map((u) => (
                <option key={u.id} value={u.id}>
                  {u.firstName} {u.lastName} · {u.role}{u.designation ? ` · ${u.designation}` : ''}
                </option>
              ))}
            </select>
            {!loadingUsers && eligible.length === 0 && (
              <div className="text-[11px] text-gray-400 mt-1">
                Every active user is already assigned to this shift.
              </div>
            )}
          </Field>

          <div className="grid grid-cols-2 gap-3">
            <Field label="Zone">
              <select
                value={zone}
                onChange={(e) => setZone(e.target.value as EdZone)}
                className="w-full border border-gray-300 rounded-lg px-2 py-1.5 text-sm"
              >
                {ALL_ZONES.map((z) => <option key={z} value={z}>{z}</option>)}
              </select>
            </Field>
            <Field label="Function">
              <select
                value={shiftFunction}
                onChange={(e) => setShiftFunction(e.target.value as ShiftFunction)}
                className="w-full border border-gray-300 rounded-lg px-2 py-1.5 text-sm"
              >
                {ALL_FUNCTIONS.map((f) => (
                  <option key={f} value={f}>{f.replace(/_/g, ' ')}</option>
                ))}
              </select>
            </Field>
          </div>

          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={isShiftLead}
              onChange={(e) => setIsShiftLead(e.target.checked)}
            />
            <span className="text-gray-700">Make shift-lead for this shift</span>
            <span className="text-[10px] text-gray-400">
              (clears any existing badge holder)
            </span>
          </label>
        </div>

        <div className="flex justify-end gap-2 pt-1">
          <button
            onClick={onClose}
            className="px-3 py-1.5 rounded-lg text-sm font-semibold text-gray-600 hover:bg-gray-50"
          >
            Cancel
          </button>
          <button
            onClick={submit}
            disabled={submitting || !userId}
            className="px-3 py-1.5 rounded-lg text-sm font-semibold text-white bg-blue-600 hover:bg-blue-700 disabled:bg-gray-300 inline-flex items-center gap-1.5"
          >
            {submitting && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
            Assign
          </button>
        </div>
      </div>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <div className="text-[10px] uppercase font-bold text-gray-500 mb-1">{label}</div>
      {children}
    </label>
  );
}

/* ════════════════════════════════════════════════════════════════════
   Copy-Week Modal
   ════════════════════════════════════════════════════════════════════ */

interface BulkOpModalProps<TResult> {
  hospitalId: string;
  onClose: () => void;
  onDone: (result: TResult) => void;
}

interface CopyWeekModalProps extends BulkOpModalProps<BulkPlanResult> {
  defaultFromMonday: string;
}

function CopyWeekModal({ hospitalId, defaultFromMonday, onClose, onDone }: CopyWeekModalProps) {
  // Default the source to the most recently completed Monday (i.e., the
  // week containing the currently-selected day) and the target to the
  // following Monday. That matches the bread-and-butter case: "copy
  // last week into next week."
  const [fromMonday, setFromMonday] = useState(defaultFromMonday);
  const [toMonday, setToMonday] = useState(() => {
    const d = new Date(defaultFromMonday + 'T00:00:00');
    d.setDate(d.getDate() + 7);
    return fmtIso(d);
  });
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fromIsMon = isMondayIso(fromMonday);
  const toIsMon = isMondayIso(toMonday);
  const ordered = fromMonday < toMonday;

  const submit = async () => {
    setError(null);
    if (!fromIsMon || !toIsMon) {
      setError('Both dates must be Mondays.'); return;
    }
    if (!ordered) {
      setError('Target week must be after source week.'); return;
    }
    setSubmitting(true);
    try {
      const req: CopyWeekRequest = { fromWeekStart: fromMonday, toWeekStart: toMonday };
      const result = await shiftApi.copyWeek(hospitalId, req);
      onDone(result);
    } catch (e: any) {
      setError(e?.message ?? 'Server error.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <ModalShell title="Copy Week" subtitle="Replicate one week's roster into another." onClose={onClose}>
      <div className="space-y-3 text-sm">
        <Field label="Source week (Monday)">
          <input
            type="date"
            value={fromMonday}
            onChange={(e) => setFromMonday(e.target.value)}
            className="w-full border border-gray-300 rounded-lg px-2 py-1.5 text-sm"
          />
          {fromMonday && !fromIsMon && (
            <div className="text-[11px] text-rose-600 mt-1">Pick a Monday.</div>
          )}
        </Field>
        <Field label="Target week (Monday)">
          <input
            type="date"
            value={toMonday}
            onChange={(e) => setToMonday(e.target.value)}
            className="w-full border border-gray-300 rounded-lg px-2 py-1.5 text-sm"
          />
          {toMonday && !toIsMon && (
            <div className="text-[11px] text-rose-600 mt-1">Pick a Monday.</div>
          )}
        </Field>
        <div className="text-[11px] text-gray-500">
          Slots that already have rows are skipped — a hand-edited day will
          not be overwritten. Users on approved leave are dropped from the
          copied roster automatically.
        </div>
        {error && <div className="text-[12px] text-rose-700 bg-rose-50 border border-rose-200 rounded px-2 py-1">{error}</div>}
      </div>
      <ModalActions
        submitting={submitting}
        disabled={!fromIsMon || !toIsMon || !ordered}
        submitLabel="Copy week"
        onCancel={onClose}
        onSubmit={submit}
      />
    </ModalShell>
  );
}

function isMondayIso(iso: string): boolean {
  if (!iso) return false;
  return new Date(iso + 'T00:00:00').getDay() === 1;
}

/* ════════════════════════════════════════════════════════════════════
   Apply-Template Modal
   ════════════════════════════════════════════════════════════════════ */

interface ApplyTemplateModalProps extends BulkOpModalProps<BulkPlanResult> {
  defaultFromDate: string;
}

function ApplyTemplateModal({ hospitalId, defaultFromDate, onClose, onDone }: ApplyTemplateModalProps) {
  const [templates, setTemplates] = useState<ShiftTemplateResponse[]>([]);
  const [templateId, setTemplateId] = useState('');
  const [fromDate, setFromDate] = useState(defaultFromDate);
  const [toDate, setToDate] = useState(() => {
    const d = new Date(defaultFromDate + 'T00:00:00');
    d.setDate(d.getDate() + 6);
    return fmtIso(d);
  });
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loadingTpls, setLoadingTpls] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoadingTpls(true);
    shiftTemplateApi
      .listForHospital(hospitalId)
      .then((rows) => { if (!cancelled) setTemplates(rows); })
      .catch(() => { if (!cancelled) setTemplates([]); })
      .finally(() => { if (!cancelled) setLoadingTpls(false); });
    return () => { cancelled = true; };
  }, [hospitalId]);

  const selected = templates.find((t) => t.id === templateId) || null;

  const submit = async () => {
    setError(null);
    if (!selected) { setError('Pick a template.'); return; }
    if (!fromDate || !toDate) { setError('Set both dates.'); return; }
    if (new Date(fromDate) > new Date(toDate)) {
      setError('Start date must be on or before end date.'); return;
    }
    setSubmitting(true);
    try {
      const req: ApplyTemplateRequest = {
        templateId: selected.id,
        fromDate, toDate,
        // Server rejects period mismatches; we lock to the template's own
        // period rather than letting the CN tick incompatible boxes.
        periods: [selected.shiftPeriod],
      };
      const result = await shiftApi.applyTemplate(hospitalId, req);
      onDone(result);
    } catch (e: any) {
      setError(e?.message ?? 'Server error.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <ModalShell title="Apply Template" subtitle="Materialise a template across a date range." onClose={onClose}>
      <div className="space-y-3 text-sm">
        <Field label="Template">
          <select
            value={templateId}
            onChange={(e) => setTemplateId(e.target.value)}
            disabled={loadingTpls}
            className="w-full border border-gray-300 rounded-lg px-2 py-1.5 text-sm"
          >
            <option value="">{loadingTpls ? 'Loading…' : 'Select…'}</option>
            {templates.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name} · {t.shiftPeriod} ({t.assignments?.length ?? 0} rows)
              </option>
            ))}
          </select>
          {!loadingTpls && templates.length === 0 && (
            <div className="text-[11px] text-gray-400 mt-1">
              No active templates. Create one in Shift Templates first.
            </div>
          )}
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="From">
            <input
              type="date"
              value={fromDate}
              onChange={(e) => setFromDate(e.target.value)}
              className="w-full border border-gray-300 rounded-lg px-2 py-1.5 text-sm"
            />
          </Field>
          <Field label="To">
            <input
              type="date"
              value={toDate}
              onChange={(e) => setToDate(e.target.value)}
              className="w-full border border-gray-300 rounded-lg px-2 py-1.5 text-sm"
            />
          </Field>
        </div>
        {selected && (
          <div className="text-[11px] text-gray-500">
            Will materialise <strong>{selected.shiftPeriod}</strong> shifts only —
            the template's own period. To stage day + night together, run
            this twice with the matching template for each.
          </div>
        )}
        {error && <div className="text-[12px] text-rose-700 bg-rose-50 border border-rose-200 rounded px-2 py-1">{error}</div>}
      </div>
      <ModalActions
        submitting={submitting}
        disabled={!templateId}
        submitLabel="Apply template"
        onCancel={onClose}
        onSubmit={submit}
      />
    </ModalShell>
  );
}

/* ════════════════════════════════════════════════════════════════════
   Modal shell + actions
   ════════════════════════════════════════════════════════════════════ */

function ModalShell({
  title, subtitle, children, onClose,
}: { title: string; subtitle?: string; children: React.ReactNode; onClose: () => void }) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div className="bg-white w-full max-w-md rounded-2xl shadow-xl p-5 space-y-4">
        <div className="flex items-start justify-between">
          <div>
            <div className="text-base font-bold text-gray-900">{title}</div>
            {subtitle && <div className="text-[11px] text-gray-500">{subtitle}</div>}
          </div>
          <button onClick={onClose} className="p-1 rounded hover:bg-gray-100">
            <X className="w-4 h-4 text-gray-500" />
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}

function ModalActions({
  submitting, disabled, submitLabel, onCancel, onSubmit,
}: {
  submitting: boolean;
  disabled: boolean;
  submitLabel: string;
  onCancel: () => void;
  onSubmit: () => void;
}) {
  return (
    <div className="flex justify-end gap-2 pt-1">
      <button
        onClick={onCancel}
        className="px-3 py-1.5 rounded-lg text-sm font-semibold text-gray-600 hover:bg-gray-50"
      >
        Cancel
      </button>
      <button
        onClick={onSubmit}
        disabled={submitting || disabled}
        className="px-3 py-1.5 rounded-lg text-sm font-semibold text-white bg-blue-600 hover:bg-blue-700 disabled:bg-gray-300 inline-flex items-center gap-1.5"
      >
        {submitting && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
        {submitLabel}
      </button>
    </div>
  );
}
