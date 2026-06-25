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
  Plus, Trash2, Copy, FileText, X, Loader2, Pencil,
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
import { useTheme } from '@/hooks/useTheme';

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
  const { glassCard, glassInner, text } = useTheme();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  const isChargeNurse = user?.designation === 'CHARGE_NURSE';
  // Only the on-floor Charge Nurse may mutate. HOSPITAL_ADMIN can
  // view (read-only); SUPER_ADMIN has no access to shift surfaces.
  const canEdit = isChargeNurse;

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
      <div className="min-h-full">
        <div className="p-4 lg:p-6 max-w-7xl mx-auto animate-fade-in">
          <div className={`rounded-2xl p-8 text-sm ${text.body}`} style={glassCard}>
            No hospital is associated with your account. The calendar requires a hospital context.
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-7xl mx-auto space-y-4 animate-fade-in">
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5 flex flex-col md:flex-row md:items-center md:justify-between gap-4">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-cyan-500/20 flex items-center justify-center">
                <Calendar className="w-5 h-5 text-cyan-300" />
              </div>
              <div>
                <div className="text-white/50 text-xs font-bold uppercase">Shift Calendar</div>
                <div className="text-lg font-bold text-white tracking-tight">
                  {cursor.toLocaleString('en-US', { month: 'long', year: 'numeric' })}
                </div>
              </div>
            </div>
            <div className="flex items-center gap-2">
              {canEdit && (
                <>
                  <button
                    onClick={() => setShowCopyWeek(true)}
                    className="px-3 py-1.5 rounded-xl bg-white/10 hover:bg-white/15 text-xs font-semibold text-white transition-colors inline-flex items-center gap-1.5"
                  >
                    <Copy className="w-3.5 h-3.5" />
                    Copy Week
                  </button>
                  <button
                    onClick={() => setShowApplyTemplate(true)}
                    className="px-3 py-1.5 rounded-xl bg-white/10 hover:bg-white/15 text-xs font-semibold text-white transition-colors inline-flex items-center gap-1.5"
                  >
                    <FileText className="w-3.5 h-3.5" />
                    Apply Template
                  </button>
                  <span className="w-px h-6 bg-white/15 mx-1" />
                </>
              )}
              <button
                onClick={() => setCursor((c) => addMonths(c, -1))}
                className="w-9 h-9 rounded-xl bg-white/10 hover:bg-white/15 transition-colors inline-flex items-center justify-center"
                aria-label="Previous month"
              >
                <ChevronLeft className="w-4 h-4 text-white" />
              </button>
              <button
                onClick={() => setCursor(startOfMonth(new Date()))}
                className="px-3 py-1.5 rounded-xl bg-white/10 hover:bg-white/15 text-xs font-semibold text-white transition-colors"
              >
                Today
              </button>
              <button
                onClick={() => setCursor((c) => addMonths(c, 1))}
                className="w-9 h-9 rounded-xl bg-white/10 hover:bg-white/15 transition-colors inline-flex items-center justify-center"
                aria-label="Next month"
              >
                <ChevronRight className="w-4 h-4 text-white" />
              </button>
            </div>
          </div>
        </div>

        {toast && (
          <div className="rounded-xl bg-emerald-500/20 border border-emerald-500/30 text-emerald-300 px-4 py-2 text-sm">
            {toast}
          </div>
        )}

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-5">
        {/* ── Calendar grid (2/3) ── */}
        <section className="lg:col-span-2 rounded-2xl p-4" style={glassCard}>
          <div className={`grid grid-cols-7 gap-1 text-[11px] font-bold uppercase ${text.muted} mb-2`}>
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
                  style={inMonth ? glassInner : undefined}
                  className={[
                    'group relative h-24 rounded-xl p-2 text-left transition-all',
                    inMonth
                      ? `${text.body} hover:bg-white/5`
                      : `${text.muted} opacity-60`,
                    isSelected ? 'ring-2 ring-cyan-500' : '',
                  ].join(' ')}
                >
                  <div className="flex items-center justify-between">
                    <span
                      className={[
                        'text-xs font-bold',
                        isToday
                          ? 'inline-flex items-center justify-center w-6 h-6 rounded-full bg-cyan-600 text-white'
                          : text.heading,
                      ].join(' ')}
                    >
                      {cell.date.getDate()}
                    </span>
                    {sig?.coverage && <CoveragePill level={sig.coverage} />}
                  </div>
                  {sig && inMonth && (
                    <div className={`mt-2 space-y-0.5 text-[11px] ${text.body}`}>
                      {sig.scheduledCount > 0 && (
                        <div className="flex items-center gap-1">
                          <Users className="w-3 h-3" />
                          <span>{sig.scheduledCount} on shift</span>
                        </div>
                      )}
                      {sig.leaveCount > 0 && (
                        <div className="flex items-center gap-1 text-amber-400">
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
            <div className={`mt-3 text-xs ${text.muted}`}>Loading roster…</div>
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
            // V55 — `replaced` reports slots whose existing roster was
            // soft-deleted and re-materialised from the template (OVERWRITE
            // mode). Include it in the toast so the CN sees exactly what
            // happened, especially when re-applying onto already-filled
            // dates.
            const replaced = (result as any).slotsReplaced ?? 0;
            const parts = [
              result.slotsFilled > 0 ? `${result.slotsFilled} filled` : null,
              replaced > 0 ? `${replaced} replaced` : null,
              result.slotsSkipped > 0 ? `${result.slotsSkipped} skipped` : null,
              result.rowsCreated > 0 ? `${result.rowsCreated} row(s)` : null,
            ].filter(Boolean).join(', ');
            showToast(`Apply template: ${parts || 'no changes'}.`);
            // Trigger calendar reload AFTER the toast so the re-fetch fires
            // post-commit (small but real defence against any read-after-
            // write timing). The async ordering is what makes the calendar
            // visibly reflect the change immediately.
            queueMicrotask(onPlanningChange);
          }}
        />
      )}
      </div>
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
      <span
        className="inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-emerald-600"
        style={{ background: 'rgba(16,185,129,0.08)', border: '1px solid rgba(16,185,129,0.2)' }}
      >
        GOOD
      </span>
    );
  }
  if (level === 'THIN') {
    return (
      <span
        className="inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-amber-600"
        style={{ background: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.2)' }}
      >
        THIN
      </span>
    );
  }
  return (
    <span
      className="inline-flex items-center gap-0.5 px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-rose-600"
      style={{ background: 'rgba(244,63,94,0.08)', border: '1px solid rgba(244,63,94,0.2)' }}
    >
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
  const { glassCard, text } = useTheme();
  const [roster, setRoster] = useState<ShiftAssignmentResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [drawerPeriod, setDrawerPeriod] = useState<ShiftPeriod | null>(null);
  const [editTarget, setEditTarget] = useState<ShiftAssignmentResponse | null>(null);

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
    <aside className="rounded-2xl p-4 space-y-4" style={glassCard}>
      <div>
        <div className={`text-[11px] font-bold uppercase ${text.muted}`}>Selected day</div>
        <div className={`text-sm font-bold ${text.heading}`}>
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
        onEdit={setEditTarget}
      />
      <ShiftSection
        title="Night shift" icon={<Moon className="w-3.5 h-3.5" />}
        rows={roster.filter(r => r.shiftPeriod === 'NIGHT' && r.active)}
        editable={editableHere}
        onAdd={() => setDrawerPeriod('NIGHT')}
        onRemove={handleRemove}
        onEdit={setEditTarget}
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

      {editTarget && (
        <EditAssignmentDrawer
          assignment={editTarget}
          onClose={() => setEditTarget(null)}
          onSaved={() => {
            setEditTarget(null);
            onToast('Assignment updated.');
            reload();
            onChange();
          }}
          onError={(msg) => onToast(msg)}
        />
      )}

      {loading && <div className={`text-xs ${text.muted}`}>Loading…</div>}
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
  onEdit: (row: ShiftAssignmentResponse) => void;
}

function ShiftSection({ title, icon, rows, editable, onAdd, onRemove, onEdit }: ShiftSectionProps) {
  const { text } = useTheme();
  if (rows.length === 0) {
    return (
      <div>
        <div className="flex items-center justify-between">
          <SectionTitle title={title} icon={icon} count={0} />
          {editable && <AddStaffButton onClick={onAdd} />}
        </div>
        <div className={`text-xs italic ${text.muted}`}>No staff scheduled.</div>
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
            <div className={`font-bold ${text.label}`}>{zone}</div>
            <ul className="ml-3 mt-0.5 space-y-0.5">
              {zRows.map((r) => (
                <li key={r.id} className={`flex items-baseline gap-2 group ${text.body}`}>
                  <span className={`text-[10px] uppercase font-bold w-24 ${text.muted}`}>
                    {r.shiftFunction.replace(/_/g, ' ')}
                  </span>
                  <span>{r.userName}</span>
                  {r.isShiftLead && (
                    <span
                      className="inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-violet-600"
                      style={{ background: 'rgba(139,92,246,0.08)', border: '1px solid rgba(139,92,246,0.2)' }}
                    >
                      Shift Lead
                    </span>
                  )}
                  {editable && (
                    <div className="ml-auto flex items-center gap-1 opacity-0 group-hover:opacity-100 transition">
                      <button
                        onClick={() => onEdit(r)}
                        className={`${text.muted} hover:text-cyan-400`}
                        aria-label={`Edit ${r.userName}`}
                        title="Edit zone / function / shift-lead"
                      >
                        <Pencil className="w-3.5 h-3.5" />
                      </button>
                      <button
                        onClick={() => onRemove(r.id, r.userName)}
                        className={`${text.muted} hover:text-rose-400`}
                        aria-label={`Remove ${r.userName}`}
                        title="Remove from shift"
                      >
                        <Trash2 className="w-3.5 h-3.5" />
                      </button>
                    </div>
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
      className="text-[10px] inline-flex items-center gap-1 px-1.5 py-0.5 rounded-xl bg-cyan-600 hover:bg-cyan-700 text-white"
    >
      <Plus className="w-3 h-3" />
      Add staff
    </button>
  );
}

function SectionTitle({ title, icon, count }: { title: string; icon: React.ReactNode; count: number }) {
  const { text } = useTheme();
  return (
    <div className="flex items-center gap-2 mb-1.5">
      <span className={text.body}>{icon}</span>
      <span className={`text-[11px] font-bold uppercase ${text.body}`}>{title}</span>
      <span className={`text-[10px] font-semibold ${text.muted}`}>({count})</span>
    </div>
  );
}

function ZoneCoverageSummary({ roster }: { roster: ShiftAssignmentResponse[] }) {
  const { text } = useTheme();
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
      <div className={`text-[11px] font-bold uppercase ${text.body} mb-1.5`}>Zone coverage</div>
      <div className="text-[11px] grid grid-cols-3 gap-y-0.5 gap-x-2">
        <div />
        <div className={`${text.muted} font-bold`}>DAY</div>
        <div className={`${text.muted} font-bold`}>NIGHT</div>
        {zones.map((z) => {
          const isTier1 = TIER_1_ZONES.includes(z as EdZone);
          const c = counts[z];
          return (
            <div key={z} className="contents">
              <div className={`font-semibold ${text.label}`}>{z}</div>
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
  const { text } = useTheme();
  if (!tier1) return <div className={text.body}>{n}</div>;
  if (n === 0)   return <div className="text-rose-400 font-bold">{n}</div>;
  if (n === 1)   return <div className="text-amber-400 font-bold">{n}</div>;
  return <div className="text-emerald-400 font-bold">{n}</div>;
}

function LeaveSection({ leaves }: { leaves: StaffLeaveResponse[] }) {
  const { text } = useTheme();
  if (leaves.length === 0) return null;
  return (
    <div>
      <SectionTitle title="On leave" icon={<UserMinus className="w-3.5 h-3.5" />} count={leaves.length} />
      <ul className="space-y-0.5 text-[12px]">
        {leaves.map((l) => (
          <li key={l.id} className={`flex items-baseline gap-2 ${text.body}`}>
            <span
              className="inline-flex items-center justify-center text-[10px] font-bold text-amber-600 px-2.5 py-0.5 rounded-lg w-24 text-center"
              style={{ background: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.2)' }}
            >
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

/**
 * Smart zone default: if the shift function clearly maps to a Tier-1
 * zone (TRIAGE_NURSE → TRIAGE), seed the zone dropdown accordingly so
 * the CN doesn't have to switch it manually. Prevents the
 * "I assigned a triage nurse but the TRIAGE zone is still empty" bug
 * caused by the form's GENERAL default.
 *
 * Only TRIAGE_NURSE has an unambiguous zone today — RESUS / ACUTE
 * roles are role-by-zone (a primary doctor can be assigned to RESUS
 * or ACUTE; the function alone doesn't decide).
 */
function zoneForFunction(fn: ShiftFunction): EdZone | null {
  if (fn === 'TRIAGE_NURSE') return 'TRIAGE';
  return null;
}

function QuickAssignDrawer({
  hospitalId, dateIso, period, existing, onClose, onAdded, onError,
}: QuickAssignDrawerProps) {
  const { glassCard, glassInner, text } = useTheme();
  const [users, setUsers] = useState<UserResponse[]>([]);
  const [userId, setUserId] = useState('');
  const [zone, setZone] = useState<EdZone>('GENERAL');
  const [shiftFunction, setShiftFunction] = useState<ShiftFunction>('ZONE_NURSE');
  // Track whether the user has manually picked a zone — if so, the
  // smart-default sync stops overriding their choice.
  const [zoneTouched, setZoneTouched] = useState(false);
  const [isShiftLead, setIsShiftLead] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [loadingUsers, setLoadingUsers] = useState(true);

  // Smart zone default — re-evaluate when shiftFunction changes.
  useEffect(() => {
    if (zoneTouched) return;
    const suggested = zoneForFunction(shiftFunction);
    if (suggested) setZone(suggested);
  }, [shiftFunction, zoneTouched]);

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
    <div className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm" style={{ background: 'rgba(2,6,23,0.65)' }}>
      <div className="w-full sm:max-w-md rounded-2xl overflow-hidden shadow-2xl animate-scale-in p-5 space-y-4" style={glassCard}>
        <div className="flex items-center justify-between">
          <div>
            <div className={`text-[11px] font-bold uppercase ${text.muted}`}>Assign staff</div>
            <div className={`text-sm font-bold ${text.heading}`}>
              {dateIso} · {period} shift
            </div>
          </div>
          <button onClick={onClose} className={`w-8 h-8 rounded-xl inline-flex items-center justify-center hover:bg-white/10 ${text.muted}`}>
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="space-y-3 text-sm">
          <Field label="Staff member">
            <select
              value={userId}
              onChange={(e) => setUserId(e.target.value)}
              disabled={loadingUsers}
              className={`w-full rounded-lg px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
              style={glassInner}
            >
              <option value="">{loadingUsers ? 'Loading…' : 'Select…'}</option>
              {eligible.map((u) => (
                <option key={u.id} value={u.id}>
                  {u.firstName} {u.lastName} · {u.role}{u.designation ? ` · ${u.designation}` : ''}
                </option>
              ))}
            </select>
            {!loadingUsers && eligible.length === 0 && (
              <div className={`text-[11px] mt-1 ${text.muted}`}>
                Every active user is already assigned to this shift.
              </div>
            )}
          </Field>

          <div className="grid grid-cols-2 gap-3">
            <Field label="Zone">
              <select
                value={zone}
                onChange={(e) => { setZone(e.target.value as EdZone); setZoneTouched(true); }}
                className={`w-full rounded-lg px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
                style={glassInner}
              >
                {ALL_ZONES.map((z) => <option key={z} value={z}>{z}</option>)}
              </select>
            </Field>
            <Field label="Function">
              <select
                value={shiftFunction}
                onChange={(e) => setShiftFunction(e.target.value as ShiftFunction)}
                className={`w-full rounded-lg px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
                style={glassInner}
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
              className="accent-cyan-600"
            />
            <span className={text.label}>Make shift-lead for this shift</span>
            <span className={`text-[10px] ${text.muted}`}>
              (clears any existing badge holder)
            </span>
          </label>
        </div>

        <div className="flex justify-end gap-2 pt-1">
          <button
            onClick={onClose}
            className={`px-3 py-1.5 rounded-xl text-sm font-semibold hover:bg-white/10 ${text.body}`}
          >
            Cancel
          </button>
          <button
            onClick={submit}
            disabled={submitting || !userId}
            className="px-3 py-1.5 rounded-xl text-sm font-semibold text-white bg-cyan-600 hover:bg-cyan-700 disabled:opacity-50 inline-flex items-center gap-1.5"
          >
            {submitting && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
            Assign
          </button>
        </div>
      </div>
    </div>
  );
}

/* ════════════════════════════════════════════════════════════════════
   Edit Assignment Drawer
   Inline edit for an existing shift-assignment row. Changes zone /
   function / shift-lead flag in place via PUT /shifts/{id}, so the CN
   no longer has to remove + re-add a staff member to change their
   zone (which used to fail because the user-picker filters out
   already-assigned staff).
   ════════════════════════════════════════════════════════════════════ */

interface EditAssignmentDrawerProps {
  assignment: ShiftAssignmentResponse;
  onClose: () => void;
  onSaved: () => void;
  onError: (msg: string) => void;
}

function EditAssignmentDrawer({
  assignment, onClose, onSaved, onError,
}: EditAssignmentDrawerProps) {
  const { glassCard, glassInner, text } = useTheme();
  const [zone, setZone] = useState<EdZone>(assignment.zone as EdZone);
  const [shiftFunction, setShiftFunction] = useState<ShiftFunction>(assignment.shiftFunction as ShiftFunction);
  const [zoneTouched, setZoneTouched] = useState(false);
  const [isShiftLead, setIsShiftLead] = useState(!!assignment.isShiftLead);
  const [submitting, setSubmitting] = useState(false);

  // Same smart default as the assign drawer — if the CN changes
  // function to e.g. TRIAGE_NURSE and hasn't manually picked a zone,
  // snap the zone to match.
  useEffect(() => {
    if (zoneTouched) return;
    const suggested = zoneForFunction(shiftFunction);
    if (suggested) setZone(suggested);
  }, [shiftFunction, zoneTouched]);

  const submit = async () => {
    setSubmitting(true);
    try {
      const body: CreateShiftAssignmentRequest = {
        userId: assignment.userId,
        zone, shiftFunction, isShiftLead,
        shiftDate: assignment.shiftDate,
        shiftPeriod: assignment.shiftPeriod,
      };
      await shiftApi.update(assignment.id, body);
      onSaved();
    } catch (e: any) {
      onError(e?.message ?? 'Failed to update.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm" style={{ background: 'rgba(2,6,23,0.65)' }}>
      <div className="w-full sm:max-w-md rounded-2xl overflow-hidden shadow-2xl animate-scale-in p-5 space-y-4" style={glassCard}>
        <div className="flex items-center justify-between">
          <div>
            <div className={`text-[11px] font-bold uppercase ${text.muted}`}>Edit assignment</div>
            <div className={`text-sm font-bold ${text.heading}`}>
              {assignment.userName} · {assignment.shiftDate} · {assignment.shiftPeriod} shift
            </div>
          </div>
          <button onClick={onClose} className={`w-8 h-8 rounded-xl inline-flex items-center justify-center hover:bg-white/10 ${text.muted}`}>
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="space-y-3 text-sm">
          <div className="grid grid-cols-2 gap-3">
            <Field label="Zone">
              <select
                value={zone}
                onChange={(e) => { setZone(e.target.value as EdZone); setZoneTouched(true); }}
                className={`w-full rounded-lg px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
                style={glassInner}
              >
                {ALL_ZONES.map((z) => <option key={z} value={z}>{z}</option>)}
              </select>
            </Field>
            <Field label="Function">
              <select
                value={shiftFunction}
                onChange={(e) => setShiftFunction(e.target.value as ShiftFunction)}
                className={`w-full rounded-lg px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
                style={glassInner}
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
              className="accent-cyan-600"
            />
            <span className={text.label}>Shift-lead for this shift</span>
            <span className={`text-[10px] ${text.muted}`}>
              (clears any other badge holder)
            </span>
          </label>
        </div>

        <div className="flex justify-end gap-2 pt-1">
          <button
            onClick={onClose}
            className={`px-3 py-1.5 rounded-xl text-sm font-semibold hover:bg-white/10 ${text.body}`}
          >
            Cancel
          </button>
          <button
            onClick={submit}
            disabled={submitting}
            className="px-3 py-1.5 rounded-xl text-sm font-semibold text-white bg-cyan-600 hover:bg-cyan-700 disabled:opacity-50 inline-flex items-center gap-1.5"
          >
            {submitting && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
            Save changes
          </button>
        </div>
      </div>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  const { text } = useTheme();
  return (
    <label className="block">
      <div className={`text-[10px] uppercase font-bold mb-1 ${text.label}`}>{label}</div>
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
  const { glassInner, text } = useTheme();
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
            className={`w-full rounded-lg px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
            style={glassInner}
          />
          {fromMonday && !fromIsMon && (
            <div className="text-[11px] text-rose-400 mt-1">Pick a Monday.</div>
          )}
        </Field>
        <Field label="Target week (Monday)">
          <input
            type="date"
            value={toMonday}
            onChange={(e) => setToMonday(e.target.value)}
            className={`w-full rounded-lg px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
            style={glassInner}
          />
          {toMonday && !toIsMon && (
            <div className="text-[11px] text-rose-400 mt-1">Pick a Monday.</div>
          )}
        </Field>
        <div className={`text-[11px] ${text.muted}`}>
          Slots that already have rows are skipped — a hand-edited day will
          not be overwritten. Users on approved leave are dropped from the
          copied roster automatically.
        </div>
        {error && <div className="text-[12px] text-rose-300 bg-rose-500/20 border border-rose-500/30 rounded px-2 py-1">{error}</div>}
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
  const { glassInner, text } = useTheme();
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
  // V55 — Apply mode. Default OVERWRITE so the manual Apply button
  // behaves the way the CN expects: "make this template the truth for
  // these dates." If they want to preserve existing rosters, they can
  // tick the "Skip existing" checkbox to switch to FILL_EMPTY.
  const [skipExisting, setSkipExisting] = useState(false);

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
        // V55 — manual Apply defaults to OVERWRITE because that's what
        // the CN expects when they click the button. The checkbox lets
        // them opt back into the legacy "fill empty slots only" mode.
        mode: skipExisting ? 'FILL_EMPTY' : 'OVERWRITE',
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
            className={`w-full rounded-lg px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
            style={glassInner}
          >
            <option value="">{loadingTpls ? 'Loading…' : 'Select…'}</option>
            {templates.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name} · {t.shiftPeriod} ({t.assignments?.length ?? 0} rows)
              </option>
            ))}
          </select>
          {!loadingTpls && templates.length === 0 && (
            <div className={`text-[11px] mt-1 ${text.muted}`}>
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
              className={`w-full rounded-lg px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
              style={glassInner}
            />
          </Field>
          <Field label="To">
            <input
              type="date"
              value={toDate}
              onChange={(e) => setToDate(e.target.value)}
              className={`w-full rounded-lg px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
              style={glassInner}
            />
          </Field>
        </div>
        {selected && (
          <div className={`text-[11px] ${text.muted}`}>
            Will materialise <strong>{selected.shiftPeriod}</strong> shifts only —
            the template's own period. To stage day + night together, run
            this twice with the matching template for each.
          </div>
        )}
        {/* V55 — overwrite-by-default with explicit opt-out for fill-empty.
            Without this checkbox, clicking Apply on already-filled dates was
            silently a no-op (the user's reported bug). */}
        <label className={`flex items-start gap-2 text-[11px] cursor-pointer select-none ${text.body}`}>
          <input
            type="checkbox"
            checked={skipExisting}
            onChange={(e) => setSkipExisting(e.target.checked)}
            className="mt-0.5 accent-cyan-600"
          />
          <span>
            <span className={`font-semibold ${text.label}`}>Skip dates that already have a roster</span>
            <span className={`block ${text.muted}`}>
              {skipExisting
                ? 'Existing rosters will be left untouched. Only empty slots are filled.'
                : 'Existing rosters on these dates will be replaced with this template (default).'}
            </span>
          </span>
        </label>
        {error && <div className="text-[12px] text-rose-300 bg-rose-500/20 border border-rose-500/30 rounded px-2 py-1">{error}</div>}
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
  const { glassCard, text } = useTheme();
  return (
    <div className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm" style={{ background: 'rgba(2,6,23,0.65)' }}>
      <div className="w-full max-w-md rounded-2xl overflow-hidden shadow-2xl animate-scale-in p-5 space-y-4" style={glassCard}>
        <div className="flex items-start justify-between">
          <div>
            <div className={`text-base font-bold ${text.heading}`}>{title}</div>
            {subtitle && <div className={`text-[11px] ${text.muted}`}>{subtitle}</div>}
          </div>
          <button onClick={onClose} className={`w-8 h-8 rounded-xl inline-flex items-center justify-center hover:bg-white/10 ${text.muted}`}>
            <X className="w-4 h-4" />
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
  const { text } = useTheme();
  return (
    <div className="flex justify-end gap-2 pt-1">
      <button
        onClick={onCancel}
        className={`px-3 py-1.5 rounded-xl text-sm font-semibold hover:bg-white/10 ${text.body}`}
      >
        Cancel
      </button>
      <button
        onClick={onSubmit}
        disabled={submitting || disabled}
        className="px-3 py-1.5 rounded-xl text-sm font-semibold text-white bg-cyan-600 hover:bg-cyan-700 disabled:opacity-50 inline-flex items-center gap-1.5"
      >
        {submitting && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
        {submitLabel}
      </button>
    </div>
  );
}
