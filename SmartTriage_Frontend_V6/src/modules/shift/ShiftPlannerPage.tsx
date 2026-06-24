/* ═══════════════════════════════════════════════════════════════
   Shift Planner — Template editor for DAY / NIGHT shifts.

   Hospital Admins define a reusable roster per period. At the next
   shift boundary (06:45 / 18:45 Africa/Kigali), the backend scheduler
   materializes the template into real ShiftAssignment rows.

   Only SUPER_ADMIN and HOSPITAL_ADMIN can access this page (enforced
   by RoleGuard + the server-side ShiftAssignmentAuthz permission bean).
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback, useMemo } from 'react';
import {
  CalendarDays, Loader2, RefreshCw, Save, Trash2, UserPlus,
  Crown, Users, Search, AlertCircle, CheckCircle2, Info,
  Sun, Moon, FileText, Plus,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { shiftTemplateApi } from '@/api/shiftTemplates';
import { userApi } from '@/api/users';
import type {
  ShiftTemplateResponse,
  ShiftTemplateAssignmentDto,
  UserResponse,
  EdZone,
  ShiftFunction,
  ShiftPeriod,
} from '@/api/types';

/* ── Constants (mirrors ShiftAssignment.tsx) ── */

const ZONES: { zone: EdZone; label: string; icon: string }[] = [
  { zone: 'RESUS',       label: 'Resuscitation', icon: '🔴' },
  { zone: 'ACUTE',       label: 'Acute',         icon: '🟠' },
  { zone: 'GENERAL',     label: 'General',       icon: '🟡' },
  { zone: 'TRIAGE',      label: 'Triage',        icon: '🔵' },
  { zone: 'OBSERVATION', label: 'Observation',   icon: '🟢' },
  { zone: 'ISOLATION',   label: 'Isolation',     icon: '🟣' },
  { zone: 'PEDIATRIC',   label: 'Pediatric',     icon: '🩷' },
];

const SHIFT_FUNCTIONS: { value: ShiftFunction; label: string; kind: 'DOCTOR' | 'NURSE' }[] = [
  { value: 'PRIMARY_DOCTOR',      label: 'Primary Doctor',     kind: 'DOCTOR' },
  { value: 'SUPERVISING_DOCTOR',  label: 'Supervising Doctor', kind: 'DOCTOR' },
  { value: 'RESIDENT',            label: 'Resident',           kind: 'DOCTOR' },
  { value: 'CHARGE_NURSE',        label: 'Charge Nurse',       kind: 'NURSE'  },
  { value: 'TRIAGE_NURSE',        label: 'Triage Nurse',       kind: 'NURSE'  },
  { value: 'ZONE_NURSE',          label: 'Zone Nurse',         kind: 'NURSE'  },
];

const DOCTOR_FUNCTIONS: ShiftFunction[] = ['PRIMARY_DOCTOR', 'SUPERVISING_DOCTOR', 'RESIDENT'];
const NURSE_FUNCTIONS:  ShiftFunction[] = ['CHARGE_NURSE', 'TRIAGE_NURSE', 'ZONE_NURSE'];

const SHIFT_PERIOD_META: Record<ShiftPeriod, { label: string; time: string; icon: typeof Sun; gradient: string; border: string; glow: string }> = {
  DAY: {
    label: 'Day Shift',
    time: '07:00 – 19:00',
    icon: Sun,
    gradient: 'from-amber-500/20 via-orange-500/10 to-yellow-500/20',
    border: 'border-amber-500/30',
    glow: 'shadow-amber-500/10',
  },
  NIGHT: {
    label: 'Night Shift',
    time: '19:00 – 07:00',
    icon: Moon,
    gradient: 'from-indigo-500/20 via-violet-500/10 to-purple-500/20',
    border: 'border-indigo-500/30',
    glow: 'shadow-indigo-500/10',
  },
};

const FUNCTION_BADGES: Record<ShiftFunction, { color: string; bg: string }> = {
  PRIMARY_DOCTOR:     { color: 'text-cyan-500',    bg: 'bg-cyan-500/10' },
  SUPERVISING_DOCTOR: { color: 'text-blue-500',    bg: 'bg-blue-500/10' },
  RESIDENT:           { color: 'text-indigo-500',  bg: 'bg-indigo-500/10' },
  CHARGE_NURSE:       { color: 'text-amber-500',   bg: 'bg-amber-500/10' },
  TRIAGE_NURSE:       { color: 'text-emerald-500', bg: 'bg-emerald-500/10' },
  ZONE_NURSE:         { color: 'text-green-500',   bg: 'bg-green-500/10' },
};

function isDoctorRole(role: string): boolean {
  return role === 'DOCTOR';
}

function defaultFunctionFor(role: string): ShiftFunction {
  return isDoctorRole(role) ? 'PRIMARY_DOCTOR' : 'ZONE_NURSE';
}

/**
 * Smart zone default — when the shift function clearly maps to a
 * Tier-1 zone, the new draft row should start in that zone instead
 * of GENERAL. Prevents the "I added a triage nurse but the TRIAGE
 * zone is still empty" coverage GAP caused by the GENERAL default.
 *
 * Only TRIAGE_NURSE has an unambiguous zone mapping today.
 */
function defaultZoneFor(fn: ShiftFunction): EdZone {
  return fn === 'TRIAGE_NURSE' ? 'TRIAGE' : 'GENERAL';
}

/**
 * V55 — Client-side mirror of the backend ShiftRoleZonePolicy.
 *
 * Returns true when the (shiftFunction, zone) pair is clinically allowed:
 *   - TRIAGE_NURSE         → only TRIAGE
 *   - ZONE_NURSE / doctors → any zone except TRIAGE
 *   - CHARGE_NURSE         → any zone (operational role)
 *
 * Mirroring the rule client-side lets us disable invalid zone options in
 * the dropdown so the CN can't even pick a bad combination. The backend
 * still validates (defence in depth + a DB CHECK constraint).
 */
function isZoneAllowedForFunction(fn: ShiftFunction, zone: EdZone): boolean {
  if (fn === 'TRIAGE_NURSE') return zone === 'TRIAGE';
  if (fn === 'CHARGE_NURSE') return true;
  // ZONE_NURSE, PRIMARY_DOCTOR, SUPERVISING_DOCTOR, RESIDENT
  return zone !== 'TRIAGE';
}

/* ═══════════════════════════════════════════════════════════════ */

interface DraftTemplate {
  id: string | null;             // null → creating new
  name: string;
  description: string;
  shiftPeriod: ShiftPeriod;
  assignments: ShiftTemplateAssignmentDto[];
}

function emptyDraft(period: ShiftPeriod): DraftTemplate {
  return {
    id: null,
    name: `${period === 'DAY' ? 'Day' : 'Night'} Shift Default`,
    description: `Standard ${period.toLowerCase()} shift roster`,
    shiftPeriod: period,
    assignments: [],
  };
}

function toDraft(template: ShiftTemplateResponse): DraftTemplate {
  return {
    id: template.id,
    name: template.name,
    description: template.description ?? '',
    shiftPeriod: template.shiftPeriod,
    assignments: template.assignments.map((a) => ({ ...a })),
  };
}

export function ShiftPlannerPage() {
  const { glassCard, glassInner, isDark, text, cardClass } = useTheme();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';

  // Read-only mode: HOSPITAL_ADMIN sees the page for governance but
  // cannot mutate. Mutations are owned by the on-floor Charge Nurse
  // (and by extension the shift-lead / delegated users — the backend
  // ShiftAssignmentAuthz.canAssign is the authoritative gate).
  const isReadOnly = user?.role === 'HOSPITAL_ADMIN'
    && user?.designation !== 'CHARGE_NURSE';

  const [templates, setTemplates] = useState<Record<ShiftPeriod, ShiftTemplateResponse | null>>({
    DAY: null,
    NIGHT: null,
  });
  const [draft, setDraft] = useState<DraftTemplate>(emptyDraft('DAY'));
  const [activePeriod, setActivePeriod] = useState<ShiftPeriod>('DAY');
  const [users, setUsers] = useState<UserResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [search, setSearch] = useState('');
  const [toast, setToast] = useState<{ msg: string; type: 'success' | 'error' } | null>(null);

  const showToast = (msg: string, type: 'success' | 'error') => {
    setToast({ msg, type });
    setTimeout(() => setToast(null), 3500);
  };

  /* ── Load templates + users ── */

  const loadData = useCallback(async () => {
    if (!hospitalId) return;
    setLoading(true);

    // Defensive: load templates + users independently. Previously these
    // shared a Promise.all, so a single 403 on templates wiped the
    // entire page (including the staff pool). Each fetch now reports
    // its own failure without taking the other down.
    const [tplResult, userResult] = await Promise.allSettled([
      shiftTemplateApi.listForHospital(hospitalId),
      userApi.getByHospital(hospitalId, 0, 200),
    ]);

    const byPeriod: Record<ShiftPeriod, ShiftTemplateResponse | null> = { DAY: null, NIGHT: null };
    if (tplResult.status === 'fulfilled') {
      tplResult.value.forEach((t) => {
        if (t.active) byPeriod[t.shiftPeriod] = t;
      });
      setTemplates(byPeriod);
      const existing = byPeriod[activePeriod];
      setDraft(existing ? toDraft(existing) : emptyDraft(activePeriod));
    } else {
      console.error('[ShiftPlanner] Templates load failed:', tplResult.reason);
      showToast('Failed to load shift templates', 'error');
    }

    if (userResult.status === 'fulfilled') {
      setUsers(
        userResult.value.content.filter((u: UserResponse) =>
          ['DOCTOR', 'NURSE'].includes(u.role),
        ),
      );
    } else {
      console.error('[ShiftPlanner] Staff pool load failed:', userResult.reason);
      showToast('Failed to load staff pool', 'error');
    }

    setLoading(false);
  }, [hospitalId]); // intentionally excluding activePeriod — switch handler re-syncs

  useEffect(() => {
    loadData();
  }, [loadData]);

  /* ── Period switch: repopulate draft from loaded template ── */

  const handleSwitchPeriod = (period: ShiftPeriod) => {
    if (period === activePeriod) return;
    setActivePeriod(period);
    const existing = templates[period];
    setDraft(existing ? toDraft(existing) : emptyDraft(period));
    setSearch('');
  };

  /* ── Assignment mutations ── */

  const addUserToDraft = (u: UserResponse) => {
    if (isReadOnly) return;
    if (draft.assignments.some((a) => a.userId === u.id)) {
      showToast(`${u.firstName} ${u.lastName} is already in this template`, 'error');
      return;
    }
    const fn = defaultFunctionFor(u.role);
    const newAssignment: ShiftTemplateAssignmentDto = {
      userId: u.id,
      userName: `${u.firstName} ${u.lastName}`,
      userEmail: u.email,
      zone: defaultZoneFor(fn),
      shiftFunction: fn,
      isShiftLead: false,
    };
    setDraft((prev) => ({ ...prev, assignments: [...prev.assignments, newAssignment] }));
  };

  const removeFromDraft = (userId: string) => {
    if (isReadOnly) return;
    setDraft((prev) => ({
      ...prev,
      assignments: prev.assignments.filter((a) => a.userId !== userId),
    }));
  };

  const updateAssignment = (userId: string, patch: Partial<ShiftTemplateAssignmentDto>) => {
    if (isReadOnly) return;
    setDraft((prev) => ({
      ...prev,
      assignments: prev.assignments.map((a) =>
        a.userId === userId ? { ...a, ...patch } : a,
      ),
    }));
  };

  /** Radio-style lead toggle: only one row can be the lead at a time. */
  const setLead = (userId: string) => {
    if (isReadOnly) return;
    setDraft((prev) => ({
      ...prev,
      assignments: prev.assignments.map((a) => ({
        ...a,
        isShiftLead: a.userId === userId ? !a.isShiftLead : false,
      })),
    }));
  };

  /* ── Save / delete ── */

  const handleSave = async () => {
    if (!hospitalId) return;

    // Client-side guard: enforce at-most-one lead
    const leadCount = draft.assignments.filter((a) => a.isShiftLead).length;
    if (leadCount > 1) {
      showToast('Only one row can be marked as shift-lead', 'error');
      return;
    }
    if (!draft.name.trim()) {
      showToast('Template name is required', 'error');
      return;
    }

    setSaving(true);
    try {
      const body = {
        name: draft.name.trim(),
        description: draft.description.trim() || undefined,
        shiftPeriod: draft.shiftPeriod,
        assignments: draft.assignments.map((a) => ({
          userId: a.userId,
          zone: a.zone,
          shiftFunction: a.shiftFunction,
          isShiftLead: a.isShiftLead,
        })),
      };

      const saved = draft.id
        ? await shiftTemplateApi.update(draft.id, body)
        : await shiftTemplateApi.create(hospitalId, body);

      setTemplates((prev) => ({ ...prev, [saved.shiftPeriod]: saved }));
      setDraft(toDraft(saved));
      showToast(
        draft.id ? 'Template updated' : 'Template created — will materialize at next shift boundary',
        'success',
      );
    } catch (err: any) {
      console.error('[ShiftPlanner] Save failed:', err);
      showToast(err?.message || 'Failed to save template', 'error');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!draft.id) return;
    if (!window.confirm(`Delete the ${activePeriod.toLowerCase()} shift template? This cannot be undone.`)) {
      return;
    }
    setDeleting(true);
    try {
      await shiftTemplateApi.remove(draft.id);
      setTemplates((prev) => ({ ...prev, [activePeriod]: null }));
      setDraft(emptyDraft(activePeriod));
      showToast('Template deleted', 'success');
    } catch (err: any) {
      console.error('[ShiftPlanner] Delete failed:', err);
      showToast(err?.message || 'Failed to delete template', 'error');
    } finally {
      setDeleting(false);
    }
  };

  /* ── Derived data ── */

  const assignedUserIds = useMemo(
    () => new Set(draft.assignments.map((a) => a.userId)),
    [draft.assignments],
  );

  /** userId → UserResponse lookup so we can get role/designation for loaded rows. */
  const usersById = useMemo(() => {
    const map = new Map<string, UserResponse>();
    users.forEach((u) => map.set(u.id, u));
    return map;
  }, [users]);

  const unassignedUsers = useMemo(() => {
    const q = search.trim().toLowerCase();
    return users
      .filter((u) => !assignedUserIds.has(u.id))
      .filter((u) => {
        if (!q) return true;
        const full = `${u.firstName} ${u.lastName} ${u.email} ${u.designationLabel ?? ''}`.toLowerCase();
        return full.includes(q);
      });
  }, [users, assignedUserIds, search]);

  const doctorRows = draft.assignments.filter((a) =>
    DOCTOR_FUNCTIONS.includes(a.shiftFunction),
  );
  const nurseRows = draft.assignments.filter((a) =>
    NURSE_FUNCTIONS.includes(a.shiftFunction),
  );

  const leadRow = draft.assignments.find((a) => a.isShiftLead);
  const periodMeta = SHIFT_PERIOD_META[activePeriod];
  const PeriodIcon = periodMeta.icon;

  /* ── Shared select style ── */
  const selectClass = `${cardClass} px-2.5 py-1.5 text-xs font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-cyan-500/40 ${
    isDark
      ? 'bg-slate-900/60 border border-cyan-500/20 text-white'
      : 'bg-white border border-slate-200 text-slate-800'
  }`;

  /* ── Guard ── */
  if (!hospitalId) {
    return (
      <div className="p-8 max-w-3xl mx-auto">
        <div className={`${cardClass} p-8 text-center`} style={glassCard}>
          <AlertCircle className="w-8 h-8 text-amber-500 mx-auto mb-3" />
          <p className={`text-sm ${text.body}`}>
            No hospital is associated with your account. Shift planning is a per-hospital feature.
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-7xl mx-auto space-y-4 animate-fade-in">
      {/* Read-only banner (Hospital Admin governance view) */}
      {isReadOnly && (
        <div className="rounded-2xl px-5 py-3 bg-amber-500/15 border border-amber-500/30 text-amber-700 dark:text-amber-300 text-sm font-semibold flex items-center gap-3">
          <AlertCircle className="w-5 h-5 shrink-0" />
          <span>
            <strong>Read-only view.</strong> Shift planning is owned by the Charge Nurse — you can review the roster here but cannot edit it.
          </span>
        </div>
      )}

      {/* ── Toast ── */}
      {toast && (
        <div
          className={`fixed top-4 right-4 z-50 px-5 py-3 ${cardClass} text-sm font-semibold shadow-2xl transition-all ${
            toast.type === 'success' ? 'bg-emerald-500 text-white' : 'bg-red-500 text-white'
          }`}
        >
          {toast.msg}
        </div>
      )}

      {/* ══════════ HEADER ══════════ */}
      <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
        <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
          <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4">
            <div className="flex items-center gap-4">
              <div className="w-10 h-10 rounded-xl bg-cyan-500/20 flex items-center justify-center">
                <CalendarDays className="w-5 h-5 text-cyan-400" />
              </div>
              <div>
                <h1 className="text-lg font-bold text-white">
                  Shift Planner
                </h1>
                <p className="text-white/50 text-xs mt-0.5">
                  Design reusable rosters — the scheduler materializes them at 06:45 / 18:45 Kigali
                </p>
              </div>
            </div>

            <button
              onClick={loadData}
              disabled={loading}
              className="w-9 h-9 rounded-xl flex items-center justify-center transition-colors bg-white/10 hover:bg-white/15 text-white"
            >
              {loading ? <Loader2 className="w-4 h-4 animate-spin" /> : <RefreshCw className="w-4 h-4" />}
            </button>
          </div>
        </div>

        <div className="p-5 md:p-6">
          {/* Period tabs */}
          <div className="flex items-center gap-3">
            {(['DAY', 'NIGHT'] as ShiftPeriod[]).map((p) => {
              const meta = SHIFT_PERIOD_META[p];
              const Icon = meta.icon;
              const isActive = activePeriod === p;
              const hasTemplate = !!templates[p];
              return (
                <button
                  key={p}
                  onClick={() => handleSwitchPeriod(p)}
                  className={`${cardClass} flex-1 md:flex-none px-5 py-3 transition-all ${
                    isActive
                      ? `bg-gradient-to-r ${meta.gradient} border ${meta.border} shadow-lg ${meta.glow}`
                      : isDark
                        ? 'hover:bg-white/5 border border-slate-700/50'
                        : 'hover:bg-slate-50 border border-slate-200'
                  }`}
                  style={isActive ? undefined : glassInner}
                >
                  <div className="flex items-center gap-3">
                    <Icon className={`w-5 h-5 ${isActive ? text.heading : text.muted}`} />
                    <div className="text-left">
                      <p className={`text-sm font-bold ${text.heading}`}>{meta.label}</p>
                      <div className="flex items-center gap-1.5">
                        <span className={`text-[11px] font-medium ${text.muted}`}>{meta.time}</span>
                        <span className={`text-[10px] ${text.muted}`}>·</span>
                        <span
                          className={`text-[10px] font-bold uppercase tracking-wider ${
                            hasTemplate ? 'text-emerald-500' : 'text-slate-400'
                          }`}
                        >
                          {hasTemplate ? '✓ Template set' : 'No template'}
                        </span>
                      </div>
                    </div>
                  </div>
                </button>
              );
            })}
          </div>
        </div>
      </div>

      {/* ══════════ INFO BAR ══════════ */}
      <div
        className={`${cardClass} px-5 py-3 border ${
          isDark ? 'border-cyan-500/20' : 'border-cyan-200'
        }`}
        style={glassInner}
      >
        <div className="flex items-start gap-3">
          <Info className="w-4 h-4 text-cyan-500 mt-0.5 flex-shrink-0" />
          <p className={`text-xs leading-relaxed ${text.body}`}>
            Templates apply automatically at the next shift boundary. For the <strong>current</strong> shift, use{' '}
            <strong>Shift Zones</strong> to make one-off adjustments. If no template exists, the scheduler copies the
            previous shift's roster forward as a fallback.
          </p>
        </div>
      </div>

      {/* ══════════ MAIN GRID: editor (left) + staff pool (right) ══════════ */}
      {loading ? (
        <div className={`text-center py-16 ${text.muted}`}>
          <Loader2 className="w-8 h-8 animate-spin mx-auto mb-3 opacity-50" />
          <p className="text-sm">Loading shift planner…</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* ── LEFT: Template editor (2 cols) ── */}
          <div className="lg:col-span-2 space-y-4">
            {/* Template metadata */}
            <div className={`${cardClass} overflow-hidden`} style={glassCard}>
              <div
                className={`px-5 py-4 flex items-center gap-3 bg-gradient-to-r ${periodMeta.gradient}`}
              >
                <PeriodIcon className={`w-5 h-5 ${text.heading}`} />
                <div className="flex-1">
                  <h2 className={`text-sm font-bold ${text.heading}`}>
                    {periodMeta.label} Template
                  </h2>
                  <p className={`text-[11px] ${text.muted}`}>
                    {draft.id ? 'Editing existing template' : 'Creating new template'}
                  </p>
                </div>
                {leadRow && (
                  <div className="inline-flex items-center gap-1.5 px-2 py-1 rounded-md bg-amber-500/15 text-amber-500 text-[10px] font-bold uppercase tracking-wider">
                    <Crown className="w-3 h-3" />
                    Lead set
                  </div>
                )}
              </div>

              <div className="p-5 space-y-4">
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  <div>
                    <label className={`text-[10px] font-bold uppercase tracking-wider ${text.muted} mb-1.5 block`}>
                      Template Name
                    </label>
                    <input
                      type="text"
                      value={draft.name}
                      onChange={(e) => setDraft((prev) => ({ ...prev, name: e.target.value }))}
                      placeholder="e.g. Standard Day Shift"
                      className={`w-full ${cardClass} px-3 py-2.5 text-sm font-medium focus:outline-none focus:ring-2 focus:ring-cyan-500/40 ${
                        isDark
                          ? 'bg-slate-900/60 border border-cyan-500/20 text-white'
                          : 'bg-white border border-slate-200 text-slate-800'
                      }`}
                    />
                  </div>
                  <div>
                    <label className={`text-[10px] font-bold uppercase tracking-wider ${text.muted} mb-1.5 block`}>
                      Description
                    </label>
                    <input
                      type="text"
                      value={draft.description}
                      onChange={(e) =>
                        setDraft((prev) => ({ ...prev, description: e.target.value }))
                      }
                      placeholder="Optional note"
                      className={`w-full ${cardClass} px-3 py-2.5 text-sm font-medium focus:outline-none focus:ring-2 focus:ring-cyan-500/40 ${
                        isDark
                          ? 'bg-slate-900/60 border border-cyan-500/20 text-white'
                          : 'bg-white border border-slate-200 text-slate-800'
                      }`}
                    />
                  </div>
                </div>

                {/* Stats strip */}
                <div className="flex flex-wrap gap-2 pt-3" style={{ borderTop: isDark ? '1px solid rgba(2,132,199,0.15)' : '1px solid rgba(203,213,225,0.3)' }}>
                  {[
                    { label: 'Total', value: draft.assignments.length, icon: Users, color: 'text-cyan-500' },
                    { label: 'Doctors', value: doctorRows.length, icon: FileText, color: 'text-blue-500' },
                    { label: 'Nurses', value: nurseRows.length, icon: FileText, color: 'text-emerald-500' },
                    { label: 'Lead', value: leadRow ? 1 : 0, icon: Crown, color: 'text-amber-500' },
                  ].map((s) => (
                    <div
                      key={s.label}
                      className={`${cardClass} px-3 py-1.5 flex items-center gap-2`}
                      style={glassInner}
                    >
                      <s.icon className={`w-3.5 h-3.5 ${s.color}`} />
                      <span className={`text-xs font-bold ${text.heading}`}>{s.value}</span>
                      <span className={`text-[10px] uppercase tracking-wider font-medium ${text.muted}`}>
                        {s.label}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            </div>

            {/* Assignment rows */}
            <div className={`${cardClass} overflow-hidden`} style={glassCard}>
              <div className="px-5 py-4 flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <Users className={`w-4 h-4 ${text.accent}`} />
                  <h3 className={`text-sm font-bold ${text.heading}`}>Roster</h3>
                </div>
                <span className={`text-[11px] ${text.muted}`}>
                  Click the <Crown className="w-3 h-3 inline text-amber-500" /> to toggle shift-lead
                </span>
              </div>

              <div className="px-5 pb-5">
                {draft.assignments.length === 0 ? (
                  <div
                    className={`text-center py-10 ${cardClass} border-2 border-dashed ${
                      isDark ? 'border-slate-700/50' : 'border-slate-200'
                    }`}
                  >
                    <UserPlus className={`w-8 h-8 mx-auto mb-2 opacity-30 ${text.muted}`} />
                    <p className={`text-sm ${text.muted}`}>No staff in this template yet</p>
                    <p className={`text-[11px] mt-1 ${text.muted}`}>
                      Pick users from the staff pool on the right →
                    </p>
                  </div>
                ) : (
                  <div className="space-y-2">
                    {draft.assignments.map((a) => {
                      const badge = FUNCTION_BADGES[a.shiftFunction];
                      const userRec = usersById.get(a.userId);
                      const roleIsDoctor = userRec
                        ? isDoctorRole(userRec.role)
                        : DOCTOR_FUNCTIONS.includes(a.shiftFunction);
                      const allowedFunctions = roleIsDoctor ? DOCTOR_FUNCTIONS : NURSE_FUNCTIONS;
                      // Prefer the fresh user record for name/email (server enrichment may be stale).
                      const displayName = userRec
                        ? `${userRec.firstName} ${userRec.lastName}`
                        : a.userName || a.userId;
                      const displayEmail = userRec?.email ?? a.userEmail;
                      return (
                        <div
                          key={a.userId}
                          className={`${cardClass} px-3 py-2.5 flex flex-col md:flex-row md:items-center gap-3 transition-colors ${
                            a.isShiftLead ? 'ring-1 ring-amber-500/50 bg-amber-500/5' : ''
                          }`}
                          style={glassInner}
                        >
                          <div className="flex items-center gap-2 flex-1 min-w-0">
                            {a.isShiftLead && (
                              <span
                                title="Current shift lead"
                                className="inline-flex items-center justify-center w-5 h-5 rounded-md bg-gradient-to-br from-amber-500 to-yellow-600 shadow-sm shadow-amber-500/40 flex-shrink-0"
                              >
                                <Crown className="w-3 h-3 text-white" />
                              </span>
                            )}
                            <div className="min-w-0 flex-1">
                              <p className={`text-sm font-semibold truncate ${text.heading}`}>
                                {roleIsDoctor ? 'Dr. ' : ''}{displayName}
                              </p>
                              {displayEmail && (
                                <p className={`text-[10px] truncate ${text.muted}`}>{displayEmail}</p>
                              )}
                            </div>
                          </div>

                          <div className="flex items-center gap-2 flex-shrink-0">
                            {/* Zone — V55: filtered to only zones clinically
                                allowed for the row's shift function. A
                                TRIAGE_NURSE row shows only TRIAGE; other
                                functions hide TRIAGE. */}
                            <select
                              value={a.zone}
                              onChange={(e) =>
                                updateAssignment(a.userId, { zone: e.target.value as EdZone })
                              }
                              className={selectClass}
                            >
                              {ZONES
                                .filter((z) => isZoneAllowedForFunction(a.shiftFunction, z.zone))
                                .map((z) => (
                                  <option key={z.zone} value={z.zone}>
                                    {z.icon} {z.label}
                                  </option>
                                ))}
                            </select>

                            {/* Function — V55: when function changes, if the
                                current zone is no longer clinically allowed
                                (e.g. TRIAGE_NURSE → ZONE_NURSE while zone is
                                TRIAGE), reset to the safe default for the new
                                function. Previously this only re-snapped from
                                GENERAL, which let invalid pairs slip through. */}
                            <select
                              value={a.shiftFunction}
                              onChange={(e) => {
                                const newFn = e.target.value as ShiftFunction;
                                const patch: Partial<ShiftTemplateAssignmentDto> = { shiftFunction: newFn };
                                if (!isZoneAllowedForFunction(newFn, a.zone)) {
                                  patch.zone = defaultZoneFor(newFn);
                                } else if (a.zone === 'GENERAL' && defaultZoneFor(newFn) !== 'GENERAL') {
                                  patch.zone = defaultZoneFor(newFn);
                                }
                                updateAssignment(a.userId, patch);
                              }}
                              className={selectClass}
                            >
                              {SHIFT_FUNCTIONS
                                .filter((sf) => allowedFunctions.includes(sf.value))
                                .map((sf) => (
                                  <option key={sf.value} value={sf.value}>{sf.label}</option>
                                ))}
                            </select>

                            {/* Function badge */}
                            <span
                              className={`hidden lg:inline text-[10px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded-md ${badge.bg} ${badge.color}`}
                            >
                              {SHIFT_FUNCTIONS.find((sf) => sf.value === a.shiftFunction)?.label}
                            </span>

                            {/* Toggle lead */}
                            <button
                              onClick={() => setLead(a.userId)}
                              title={a.isShiftLead ? 'Remove shift-lead badge' : 'Make shift lead'}
                              className={`w-7 h-7 rounded-lg flex items-center justify-center transition-colors ${
                                a.isShiftLead
                                  ? 'bg-amber-500 text-white shadow-sm shadow-amber-500/30'
                                  : isDark
                                    ? 'hover:bg-amber-500/20 text-amber-400'
                                    : 'hover:bg-amber-50 text-amber-500'
                              }`}
                            >
                              <Crown className="w-3.5 h-3.5" />
                            </button>

                            {/* Remove */}
                            <button
                              onClick={() => removeFromDraft(a.userId)}
                              title="Remove from template"
                              className={`w-7 h-7 rounded-lg flex items-center justify-center transition-colors ${
                                isDark ? 'hover:bg-red-500/20 text-red-400' : 'hover:bg-red-50 text-red-500'
                              }`}
                            >
                              <Trash2 className="w-3.5 h-3.5" />
                            </button>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            </div>

            {/* Action bar */}
            <div className={`${cardClass} px-5 py-4 flex flex-col md:flex-row md:items-center md:justify-between gap-3`} style={glassCard}>
              <div className="flex items-center gap-2">
                {leadRow ? (
                  <>
                    <CheckCircle2 className="w-4 h-4 text-emerald-500" />
                    <span className={`text-xs ${text.body}`}>
                      <strong className="text-amber-500">{leadRow.userName}</strong> will hold the shift-lead badge.
                    </span>
                  </>
                ) : (
                  <>
                    <AlertCircle className="w-4 h-4 text-amber-500" />
                    <span className={`text-xs ${text.body}`}>
                      No shift-lead set — the fallback chain will route to Hospital Admin.
                    </span>
                  </>
                )}
              </div>
              <div className="flex items-center gap-2">
                {/* Mutate buttons hidden in read-only mode (HOSPITAL_ADMIN
                    viewing for governance). The backend
                    ShiftAssignmentAuthz.canAssign also rejects writes
                    from non-CN users as a defence-in-depth measure. */}
                {!isReadOnly && draft.id && (
                  <button
                    onClick={handleDelete}
                    disabled={deleting || saving}
                    className={`${cardClass} px-3.5 py-2 text-xs font-bold transition-all ${
                      isDark
                        ? 'bg-red-500/10 hover:bg-red-500/20 text-red-400 border border-red-500/20'
                        : 'bg-red-50 hover:bg-red-100 text-red-600 border border-red-200'
                    } disabled:opacity-40`}
                  >
                    <span className="flex items-center gap-1.5">
                      {deleting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Trash2 className="w-3.5 h-3.5" />}
                      Delete template
                    </span>
                  </button>
                )}
                {!isReadOnly && (
                  <button
                    onClick={handleSave}
                    disabled={saving || deleting}
                    className={`${cardClass} px-4 py-2 text-sm font-bold transition-all
                      bg-gradient-to-r from-cyan-600 to-blue-600 hover:from-cyan-500 hover:to-blue-500
                      text-white shadow-lg shadow-cyan-500/20 disabled:opacity-40 disabled:shadow-none`}
                  >
                    <span className="flex items-center gap-2">
                      {saving ? <Loader2 className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
                      {draft.id ? 'Save changes' : 'Create template'}
                    </span>
                  </button>
                )}
                {isReadOnly && (
                  <span className={`text-xs italic ${text.muted}`}>
                    Viewing only — shift changes are made by the Charge Nurse.
                  </span>
                )}
              </div>
            </div>
          </div>

          {/* ── RIGHT: Staff pool ── */}
          <div className={`${cardClass} overflow-hidden h-fit sticky top-4`} style={glassCard}>
            <div className="px-5 py-4 flex items-center gap-3">
              <div
                className={`w-9 h-9 rounded-xl flex items-center justify-center bg-gradient-to-br ${
                  isDark ? 'from-emerald-500/20 to-green-500/20' : 'from-emerald-50 to-green-50'
                }`}
              >
                <UserPlus className="w-4 h-4 text-emerald-500" />
              </div>
              <div className="flex-1">
                <h2 className={`text-sm font-bold ${text.heading}`}>Staff Pool</h2>
                <p className={`text-[11px] ${text.muted}`}>
                  {unassignedUsers.length} of {users.length} clinicians available
                </p>
              </div>
            </div>

            <div className="px-5 pb-4">
              <div className="relative">
                <Search className={`w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 ${text.muted}`} />
                <input
                  type="text"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  placeholder="Search by name, email…"
                  className={`w-full ${cardClass} pl-9 pr-3 py-2 text-xs font-medium focus:outline-none focus:ring-2 focus:ring-cyan-500/40 ${
                    isDark
                      ? 'bg-slate-900/60 border border-cyan-500/20 text-white placeholder-slate-500'
                      : 'bg-white border border-slate-200 text-slate-800 placeholder-slate-400'
                  }`}
                />
              </div>
            </div>

            <div className="px-3 pb-5 max-h-[600px] overflow-y-auto scrollbar-thin">
              {unassignedUsers.length === 0 ? (
                <div className={`text-center py-8 ${text.muted}`}>
                  <Users className="w-6 h-6 mx-auto mb-2 opacity-30" />
                  <p className="text-xs italic">
                    {search ? 'No matching staff' : 'All clinicians are in this template'}
                  </p>
                </div>
              ) : (
                <div className="space-y-1.5">
                  {unassignedUsers.map((u) => {
                    const doctor = isDoctorRole(u.role);
                    return (
                      <button
                        key={u.id}
                        onClick={() => addUserToDraft(u)}
                        className={`w-full ${cardClass} px-3 py-2 flex items-center gap-2.5 transition-all text-left ${
                          isDark
                            ? 'hover:bg-cyan-500/10 hover:border-cyan-500/30'
                            : 'hover:bg-cyan-50 hover:border-cyan-300'
                        }`}
                        style={glassInner}
                      >
                        <div
                          className={`w-7 h-7 rounded-lg flex items-center justify-center flex-shrink-0 ${
                            doctor
                              ? 'bg-cyan-500/15 text-cyan-500'
                              : 'bg-emerald-500/15 text-emerald-500'
                          }`}
                        >
                          <span className="text-[10px] font-bold">
                            {u.firstName[0]}{u.lastName[0]}
                          </span>
                        </div>
                        <div className="flex-1 min-w-0">
                          <p className={`text-xs font-semibold truncate ${text.heading}`}>
                            {doctor ? 'Dr. ' : ''}{u.firstName} {u.lastName}
                          </p>
                          <p className={`text-[10px] truncate ${text.muted}`}>
                            {u.designationLabel || u.role.replace(/_/g, ' ')}
                          </p>
                        </div>
                        <Plus className={`w-3.5 h-3.5 flex-shrink-0 ${text.muted}`} />
                      </button>
                    );
                  })}
                </div>
              )}
            </div>
          </div>
        </div>
      )}
      </div>
    </div>
  );
}
