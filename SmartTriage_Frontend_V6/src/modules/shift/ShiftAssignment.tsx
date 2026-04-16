/* ═══════════════════════════════════════════════════════════════
   Shift Zone Assignment — Premium glass design matching app theme
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback } from 'react';
import {
  MapPin, UserPlus, Clock, Users, Stethoscope, HeartPulse,
  Trash2, StopCircle, Loader2, RefreshCw, ShieldAlert, Info,
  Crown,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { shiftApi } from '@/api/shifts';
import { userApi } from '@/api/users';
import type { ShiftAssignmentResponse, UserResponse, EdZone, ShiftFunction, ShiftPeriod } from '@/api/types';

/* ── Constants ── */

const ZONES: { zone: EdZone; label: string; icon: string; gradient: string; borderAccent: string }[] = [
  { zone: 'RESUS',       label: 'Resuscitation', icon: '🔴', gradient: 'from-red-500/20 to-red-600/5',     borderAccent: 'border-red-500/40' },
  { zone: 'ACUTE',       label: 'Acute',         icon: '🟠', gradient: 'from-orange-500/20 to-orange-600/5', borderAccent: 'border-orange-500/40' },
  { zone: 'GENERAL',     label: 'General',        icon: '🟡', gradient: 'from-yellow-500/15 to-yellow-600/5', borderAccent: 'border-yellow-500/30' },
  { zone: 'TRIAGE',      label: 'Triage',         icon: '🔵', gradient: 'from-blue-500/20 to-blue-600/5',    borderAccent: 'border-blue-500/40' },
  { zone: 'OBSERVATION', label: 'Observation',    icon: '🟢', gradient: 'from-emerald-500/15 to-emerald-600/5', borderAccent: 'border-emerald-500/30' },
  { zone: 'ISOLATION',   label: 'Isolation',      icon: '🟣', gradient: 'from-purple-500/20 to-purple-600/5', borderAccent: 'border-purple-500/40' },
  { zone: 'PEDIATRIC',   label: 'Pediatric',      icon: '🩷', gradient: 'from-pink-500/15 to-pink-600/5',    borderAccent: 'border-pink-500/30' },
];

const ZONE_TEXT_COLORS: Record<EdZone, string> = {
  RESUS: 'text-red-500',
  ACUTE: 'text-orange-500',
  GENERAL: 'text-yellow-500',
  TRIAGE: 'text-blue-500',
  OBSERVATION: 'text-emerald-500',
  ISOLATION: 'text-purple-500',
  PEDIATRIC: 'text-pink-500',
};

const SHIFT_FUNCTIONS: { value: ShiftFunction; label: string }[] = [
  { value: 'PRIMARY_DOCTOR',      label: 'Primary Doctor' },
  { value: 'SUPERVISING_DOCTOR',  label: 'Supervising Doctor' },
  { value: 'RESIDENT',            label: 'Resident' },
  { value: 'CHARGE_NURSE',        label: 'Charge Nurse' },
  { value: 'TRIAGE_NURSE',        label: 'Triage Nurse' },
  { value: 'ZONE_NURSE',          label: 'Zone Nurse' },
];

const DOCTOR_FUNCTIONS: ShiftFunction[] = ['PRIMARY_DOCTOR', 'SUPERVISING_DOCTOR', 'RESIDENT'];
const NURSE_FUNCTIONS: ShiftFunction[]  = ['CHARGE_NURSE', 'TRIAGE_NURSE', 'ZONE_NURSE'];

const SHIFT_LABELS: Record<ShiftPeriod, { label: string; time: string; icon: string }> = {
  DAY:   { label: 'Day Shift',   time: '07:00 – 19:00', icon: '☀️' },
  NIGHT:     { label: 'Night',     time: '19:00 – 07:00', icon: '🌙' },
};

const SHIFT_PERIOD_STYLES: Record<ShiftPeriod, { gradient: string; border: string; glow: string }> = {
  DAY:   { gradient: 'from-amber-500/20 via-orange-500/10 to-yellow-500/20', border: 'border-amber-500/30', glow: 'shadow-amber-500/10' },
  NIGHT:     { gradient: 'from-indigo-500/20 via-violet-500/10 to-purple-500/20', border: 'border-indigo-500/30', glow: 'shadow-indigo-500/10' },
};

const FUNCTION_BADGES: Record<ShiftFunction, { color: string; bg: string }> = {
  PRIMARY_DOCTOR:     { color: 'text-cyan-500',    bg: 'bg-cyan-500/10' },
  SUPERVISING_DOCTOR: { color: 'text-blue-500',    bg: 'bg-blue-500/10' },
  RESIDENT:           { color: 'text-indigo-500',  bg: 'bg-indigo-500/10' },
  CHARGE_NURSE:       { color: 'text-amber-500',   bg: 'bg-amber-500/10' },
  TRIAGE_NURSE:       { color: 'text-emerald-500', bg: 'bg-emerald-500/10' },
  ZONE_NURSE:         { color: 'text-green-500',   bg: 'bg-green-500/10' },
};

function isDoctorFunction(fn: ShiftFunction): boolean {
  return fn === 'PRIMARY_DOCTOR' || fn === 'SUPERVISING_DOCTOR' || fn === 'RESIDENT';
}

/* ═══════════════════════════════════════════════════════════════ */

export function ShiftAssignment() {
  const { glassCard, glassInner, isDark, text, cardClass } = useTheme();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';

  const [assignments, setAssignments] = useState<ShiftAssignmentResponse[]>([]);
  const [users, setUsers] = useState<UserResponse[]>([]);
  const [shiftDate, setShiftDate] = useState('');
  const [shiftPeriod, setShiftPeriod] = useState<ShiftPeriod>('DAY');
  const [loading, setLoading] = useState(false);
  const [toast, setToast] = useState<{ msg: string; type: 'success' | 'error' } | null>(null);

  // Assign form state
  const [selectedUser, setSelectedUser] = useState('');
  const [selectedZone, setSelectedZone] = useState<EdZone>('GENERAL');
  const [selectedFunction, setSelectedFunction] = useState<ShiftFunction>('ZONE_NURSE');
  const [assignAsLead, setAssignAsLead] = useState(false);

  const showToast = (msg: string, type: 'success' | 'error') => {
    setToast({ msg, type });
    setTimeout(() => setToast(null), 3500);
  };

  const loadData = useCallback(async () => {
    if (!hospitalId) return;
    setLoading(true);
    try {
      const [shiftInfo, currentAssignments, allUsers] = await Promise.all([
        shiftApi.getCurrentPeriod(),
        shiftApi.getCurrentShift(hospitalId),
        userApi.getByHospital(hospitalId, 0, 200),
      ]);
      setShiftDate(shiftInfo.shiftDate);
      setShiftPeriod(shiftInfo.shiftPeriod);
      setAssignments(currentAssignments);
      setUsers(allUsers.content.filter((u: UserResponse) => ['DOCTOR', 'NURSE', 'TRIAGE_NURSE'].includes(u.role)));
    } catch (err) {
      console.error('Failed to load shift data:', err);
      showToast('Failed to load shift data', 'error');
    } finally {
      setLoading(false);
    }
  }, [hospitalId]);

  useEffect(() => { loadData(); }, [loadData]);

  const handleAssign = async () => {
    if (!selectedUser || !hospitalId) return;
    try {
      await shiftApi.assign(hospitalId, {
        userId: selectedUser,
        zone: selectedZone,
        shiftFunction: selectedFunction,
        isShiftLead: assignAsLead,
      });
      showToast(
        assignAsLead ? 'Staff assigned & shift-lead badge set' : 'Staff assigned to zone',
        'success',
      );
      setSelectedUser('');
      setAssignAsLead(false);
      loadData();
    } catch (err: any) {
      console.error('Failed to assign:', err);
      showToast(err?.message || 'Failed to assign staff', 'error');
    }
  };

  const handleTransferLead = async (assignmentId: string, userName: string) => {
    try {
      await shiftApi.transferShiftLead(assignmentId);
      showToast(`Shift-lead badge transferred to ${userName}`, 'success');
      loadData();
    } catch (err: any) {
      console.error('Failed to transfer shift-lead:', err);
      showToast(err?.message || 'Failed to transfer shift-lead', 'error');
    }
  };

  const handleRemove = async (assignmentId: string) => {
    try {
      await shiftApi.remove(assignmentId);
      showToast('Assignment removed', 'success');
      loadData();
    } catch (err) {
      console.error('Failed to remove:', err);
      showToast('Failed to remove assignment', 'error');
    }
  };

  const handleEnd = async (assignmentId: string) => {
    try {
      await shiftApi.endShift(assignmentId);
      showToast('Shift ended', 'success');
      loadData();
    } catch (err) {
      console.error('Failed to end shift:', err);
      showToast('Failed to end shift', 'error');
    }
  };

  const getZoneAssignments = (zone: EdZone) => assignments.filter((a: ShiftAssignmentResponse) => a.zone === zone);
  const unassignedUsers = users.filter((u: UserResponse) => !assignments.some((a: ShiftAssignmentResponse) => a.userId === u.id));

  /** Current shift-lead holder (exactly one, or none). */
  const currentShiftLead = assignments.find((a: ShiftAssignmentResponse) => a.isShiftLead && a.active);

  /* ── Filter shift functions by selected user's role ── */
  const selectedUserObj = users.find((u: UserResponse) => u.id === selectedUser);
  const isSelectedDoctor = selectedUserObj?.role === 'DOCTOR';
  const filteredFunctions = selectedUser
    ? (isSelectedDoctor ? DOCTOR_FUNCTIONS : NURSE_FUNCTIONS)
    : [...DOCTOR_FUNCTIONS, ...NURSE_FUNCTIONS];

  // Auto-select first valid function when user selection changes
  useEffect(() => {
    if (!selectedUser) return;
    if (!filteredFunctions.includes(selectedFunction)) {
      setSelectedFunction(filteredFunctions[0]);
    }
  }, [selectedUser]);

  const shiftMeta = SHIFT_LABELS[shiftPeriod] || { label: shiftPeriod, time: '', icon: '🕑' };
  const periodStyle = SHIFT_PERIOD_STYLES[shiftPeriod] || SHIFT_PERIOD_STYLES.DAY;
  const totalStaff = assignments.length;
  const totalDoctors = assignments.filter((a: ShiftAssignmentResponse) => isDoctorFunction(a.shiftFunction)).length;
  const totalNurses = totalStaff - totalDoctors;

  /* ── Select style helper ── */
  const selectClass = `w-full ${cardClass} px-3 py-2.5 text-sm font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-cyan-500/40 ${
    isDark
      ? 'bg-slate-900/60 border border-cyan-500/20 text-white'
      : 'bg-white border border-slate-200 text-slate-800'
  }`;

  return (
    <div className="p-4 md:p-6 space-y-6 max-w-7xl mx-auto">
      {/* ── Toast ── */}
      {toast && (
        <div className={`fixed top-4 right-4 z-50 px-5 py-3 ${cardClass} text-sm font-semibold shadow-2xl transition-all ${
          toast.type === 'success'
            ? 'bg-emerald-500 text-white'
            : 'bg-red-500 text-white'
        }`}>
          {toast.msg}
        </div>
      )}

      {/* ══════════ HEADER ══════════ */}
      <div className={`${cardClass} overflow-hidden`} style={glassCard}>
        <div className="p-5 md:p-6">
          <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4">
            {/* Left: Title */}
            <div className="flex items-center gap-4">
              <div className={`w-12 h-12 ${cardClass} flex items-center justify-center bg-gradient-to-br ${
                isDark ? 'from-cyan-500/20 to-blue-500/20' : 'from-cyan-50 to-blue-50'
              }`}>
                <MapPin className={`w-6 h-6 ${text.accent}`} />
              </div>
              <div>
                <h1 className={`text-xl md:text-2xl font-bold tracking-tight ${text.heading}`}>
                  Shift Zone Assignment
                </h1>
                <p className={`text-sm mt-0.5 ${text.muted}`}>
                  Assign staff to ED zones · Zone doctors receive routed alerts
                </p>
              </div>
            </div>

            {/* Right: Shift period chip + refresh */}
            <div className="flex items-center gap-3">
              <div className={`${cardClass} border ${periodStyle.border} bg-gradient-to-r ${periodStyle.gradient} shadow-lg ${periodStyle.glow} px-5 py-3`}>
                <div className="flex items-center gap-3">
                  <span className="text-2xl leading-none">{shiftMeta.icon}</span>
                  <div>
                    <p className={`text-sm font-bold ${text.heading}`}>{shiftMeta.label} Shift</p>
                    <div className="flex items-center gap-1.5 mt-0.5">
                      <Clock className={`w-3 h-3 ${text.muted}`} />
                      <span className={`text-[11px] font-medium ${text.muted}`}>{shiftMeta.time}</span>
                      <span className={`text-[10px] ${text.muted}`}>·</span>
                      <span className={`text-[11px] font-medium ${text.accent}`}>{shiftDate}</span>
                    </div>
                  </div>
                </div>
              </div>
              <button
                onClick={loadData}
                disabled={loading}
                className={`w-10 h-10 ${cardClass} flex items-center justify-center transition-colors ${
                  isDark ? 'hover:bg-white/10 text-slate-300' : 'hover:bg-slate-100 text-slate-500'
                }`}
                style={glassInner}
              >
                {loading ? <Loader2 className="w-4 h-4 animate-spin" /> : <RefreshCw className="w-4 h-4" />}
              </button>
            </div>
          </div>

          {/* Stats strip */}
          <div className="flex flex-wrap gap-3 mt-5 pt-4" style={{ borderTop: isDark ? '1px solid rgba(2,132,199,0.15)' : '1px solid rgba(203,213,225,0.3)' }}>
            {[
              { label: 'Total Staff', value: totalStaff, icon: Users, color: 'text-cyan-500' },
              { label: 'Doctors', value: totalDoctors, icon: Stethoscope, color: 'text-blue-500' },
              { label: 'Nurses', value: totalNurses, icon: HeartPulse, color: 'text-emerald-500' },
              { label: 'Zones Active', value: ZONES.filter(z => getZoneAssignments(z.zone).length > 0).length, icon: MapPin, color: 'text-violet-500' },
            ].map((s) => (
              <div key={s.label} className={`${cardClass} px-4 py-2.5 flex items-center gap-3`} style={glassInner}>
                <s.icon className={`w-4 h-4 ${s.color}`} />
                <div>
                  <p className={`text-lg font-bold leading-none ${text.heading}`}>{s.value}</p>
                  <p className={`text-[10px] uppercase tracking-wider font-medium ${text.muted}`}>{s.label}</p>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* ══════════ SHIFT-LEAD BADGE CARD ══════════ */}
      <div
        className={`${cardClass} overflow-hidden border ${
          currentShiftLead ? 'border-amber-500/40' : isDark ? 'border-slate-700/50' : 'border-slate-200'
        }`}
        style={glassCard}
      >
        <div
          className={`px-5 py-4 flex flex-col md:flex-row md:items-center md:justify-between gap-3 bg-gradient-to-r ${
            currentShiftLead
              ? 'from-amber-500/15 via-yellow-500/10 to-orange-500/15'
              : isDark
                ? 'from-slate-800/40 to-slate-900/40'
                : 'from-slate-50 to-white'
          }`}
        >
          <div className="flex items-center gap-4">
            <div
              className={`w-11 h-11 rounded-xl flex items-center justify-center ${
                currentShiftLead
                  ? 'bg-gradient-to-br from-amber-500 to-yellow-600 shadow-lg shadow-amber-500/30'
                  : isDark
                    ? 'bg-slate-800/60 border border-slate-700/60'
                    : 'bg-white border border-slate-200'
              }`}
            >
              <Crown className={`w-5 h-5 ${currentShiftLead ? 'text-white' : text.muted}`} />
            </div>
            <div>
              <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>
                Current Shift Lead
              </p>
              {currentShiftLead ? (
                <>
                  <p className={`text-base font-bold ${text.heading}`}>
                    {currentShiftLead.userRole === 'DOCTOR' ? 'Dr. ' : ''}
                    {currentShiftLead.userName}
                  </p>
                  <p className={`text-[11px] ${text.muted}`}>
                    {SHIFT_FUNCTIONS.find((sf) => sf.value === currentShiftLead.shiftFunction)?.label}
                    {' · '}
                    {ZONES.find((z) => z.zone === currentShiftLead.zone)?.label}
                  </p>
                </>
              ) : (
                <>
                  <p className={`text-sm font-semibold ${text.heading}`}>No shift-lead assigned</p>
                  <p className={`text-[11px] ${text.muted}`}>
                    Assign one below, or hover any staff row to promote them.
                  </p>
                </>
              )}
            </div>
          </div>
          {currentShiftLead && (
            <div className={`text-[11px] ${text.muted} max-w-xs`}>
              The shift-lead can reassign staff and receives all Tier-1 escalations until the badge
              is transferred or the shift ends.
            </div>
          )}
        </div>
      </div>

      {/* ══════════ ASSIGN FORM ══════════ */}
      <div className={`${cardClass} overflow-hidden`} style={glassCard}>
        <div className="p-5 md:p-6">
          <div className="flex items-center gap-3 mb-5">
            <div className={`w-9 h-9 rounded-xl flex items-center justify-center bg-gradient-to-br ${
              isDark ? 'from-emerald-500/20 to-green-500/20' : 'from-emerald-50 to-green-50'
            }`}>
              <UserPlus className="w-4 h-4 text-emerald-500" />
            </div>
            <div>
              <h2 className={`text-base font-bold ${text.heading}`}>Assign Staff to Zone</h2>
              <p className={`text-xs ${text.muted}`}>Select a team member, zone, and their function for this shift</p>
            </div>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
            {/* Staff */}
            <div>
              <label className={`text-[10px] font-bold uppercase tracking-wider ${text.muted} mb-1.5 block`}>Staff Member</label>
              <select value={selectedUser} onChange={(e) => setSelectedUser(e.target.value)} className={selectClass}>
                <option value="">Select staff…</option>
                {unassignedUsers.map((u: UserResponse) => (
                  <option key={u.id} value={u.id}>
                    {u.firstName} {u.lastName} ({u.designationLabel || u.role.replace(/_/g, ' ')})
                  </option>
                ))}
              </select>
            </div>

            {/* Zone */}
            <div>
              <label className={`text-[10px] font-bold uppercase tracking-wider ${text.muted} mb-1.5 block`}>ED Zone</label>
              <select value={selectedZone} onChange={(e) => setSelectedZone(e.target.value as EdZone)} className={selectClass}>
                {ZONES.map((z) => (
                  <option key={z.zone} value={z.zone}>{z.icon} {z.label}</option>
                ))}
              </select>
            </div>

            {/* Function — filtered by selected staff role */}
            <div>
              <label className={`text-[10px] font-bold uppercase tracking-wider ${text.muted} mb-1.5 block`}>
                Shift Function
                {selectedUserObj && (
                  <span className={`ml-1.5 normal-case tracking-normal font-medium ${text.accent}`}>
                    ({isSelectedDoctor ? 'Doctor' : 'Nurse'})
                  </span>
                )}
              </label>
              <select value={selectedFunction} onChange={(e) => setSelectedFunction(e.target.value as ShiftFunction)} className={selectClass}>
                {SHIFT_FUNCTIONS
                  .filter(sf => filteredFunctions.includes(sf.value))
                  .map((sf) => (
                    <option key={sf.value} value={sf.value}>{sf.label}</option>
                  ))}
              </select>
            </div>

            {/* Button */}
            <div className="flex items-end">
              <button
                onClick={handleAssign}
                disabled={!selectedUser}
                className={`w-full ${cardClass} px-4 py-2.5 text-sm font-bold transition-all
                  bg-gradient-to-r from-cyan-600 to-blue-600 hover:from-cyan-500 hover:to-blue-500
                  text-white shadow-lg shadow-cyan-500/20 disabled:opacity-40 disabled:shadow-none`}
              >
                <span className="flex items-center justify-center gap-2">
                  <UserPlus className="w-4 h-4" />
                  Assign to Zone
                </span>
              </button>
            </div>
          </div>

          {/* Shift-lead checkbox */}
          <div className="mt-4">
            <label
              className={`inline-flex items-center gap-3 px-4 py-2.5 cursor-pointer transition-colors ${cardClass} ${
                assignAsLead
                  ? 'ring-1 ring-amber-500/50 bg-amber-500/5'
                  : isDark
                    ? 'hover:bg-white/5'
                    : 'hover:bg-slate-50'
              }`}
              style={glassInner}
            >
              <input
                type="checkbox"
                checked={assignAsLead}
                onChange={(e) => setAssignAsLead(e.target.checked)}
                disabled={!selectedUser}
                className="w-4 h-4 rounded border-slate-400 text-amber-500 focus:ring-amber-500/40 disabled:opacity-40"
              />
              <Crown className={`w-4 h-4 ${assignAsLead ? 'text-amber-500' : text.muted}`} />
              <div className="flex-1">
                <span className={`text-sm font-semibold ${text.heading}`}>
                  Assign as shift lead
                </span>
                {currentShiftLead && assignAsLead && (
                  <span className={`block text-[11px] ${text.muted}`}>
                    Will transfer the badge from{' '}
                    <strong className="text-amber-500">{currentShiftLead.userName}</strong>
                  </span>
                )}
                {!currentShiftLead && (
                  <span className={`block text-[11px] ${text.muted}`}>
                    No one currently holds the badge for this shift.
                  </span>
                )}
              </div>
            </label>
          </div>
        </div>
      </div>

      {/* ══════════ ZONE CARDS GRID ══════════ */}
      {loading ? (
        <div className={`text-center py-16 ${text.muted}`}>
          <Loader2 className="w-8 h-8 animate-spin mx-auto mb-3 opacity-50" />
          <p className="text-sm">Loading shift assignments…</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
          {ZONES.map(({ zone, label, icon, gradient, borderAccent }) => {
            const zoneAssignments = getZoneAssignments(zone);
            const doctors = zoneAssignments.filter((a: ShiftAssignmentResponse) => isDoctorFunction(a.shiftFunction));
            const nurses = zoneAssignments.filter((a: ShiftAssignmentResponse) => !isDoctorFunction(a.shiftFunction));
            const zoneColor = ZONE_TEXT_COLORS[zone];

            return (
              <div key={zone} className={`${cardClass} overflow-hidden border ${borderAccent}`} style={glassCard}>
                {/* Zone header with gradient strip */}
                <div className={`bg-gradient-to-r ${gradient} px-5 py-4`}>
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-3">
                      <span className="text-xl">{icon}</span>
                      <h3 className={`text-base font-bold ${zoneColor}`}>{label}</h3>
                    </div>
                    <span className={`text-xs font-semibold px-2.5 py-1 ${cardClass} ${
                      isDark ? 'bg-black/30 text-white/70' : 'bg-white/60 text-slate-600'
                    }`}>
                      {zoneAssignments.length} staff
                    </span>
                  </div>
                </div>

                {/* Zone body */}
                <div className="p-4 min-h-[100px]">
                  {zoneAssignments.length === 0 ? (
                    <div className={`flex flex-col items-center justify-center py-6 ${text.muted}`}>
                      <MapPin className="w-6 h-6 opacity-30 mb-2" />
                      <p className="text-xs italic">No staff assigned</p>
                    </div>
                  ) : (
                    <div className="space-y-3">
                      {/* Doctors section */}
                      {doctors.length > 0 && (
                        <div>
                          <div className="flex items-center gap-1.5 mb-2">
                            <Stethoscope className="w-3 h-3 text-blue-500" />
                            <span className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>
                              Doctors ({doctors.length})
                            </span>
                          </div>
                          <div className="space-y-1.5">
                            {doctors.map((a: ShiftAssignmentResponse) => {
                              const fb = FUNCTION_BADGES[a.shiftFunction];
                              const isLead = a.isShiftLead && a.active;
                              return (
                                <div
                                  key={a.id}
                                  className={`${cardClass} px-3 py-2 flex items-center justify-between group transition-colors ${
                                    isLead ? 'ring-1 ring-amber-500/50 bg-amber-500/5' : ''
                                  }`}
                                  style={glassInner}
                                >
                                  <div className="min-w-0 flex-1">
                                    <div className="flex items-center gap-2 flex-wrap">
                                      {isLead && (
                                        <span
                                          title="Current shift lead"
                                          className="inline-flex items-center justify-center w-5 h-5 rounded-md bg-gradient-to-br from-amber-500 to-yellow-600 shadow-sm shadow-amber-500/40"
                                        >
                                          <Crown className="w-3 h-3 text-white" />
                                        </span>
                                      )}
                                      <span className={`text-sm font-semibold ${text.heading}`}>Dr. {a.userName}</span>
                                      <span className={`text-[10px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded-md ${fb.bg} ${fb.color}`}>
                                        {SHIFT_FUNCTIONS.find(sf => sf.value === a.shiftFunction)?.label}
                                      </span>
                                      {isLead && (
                                        <span className="text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded-md bg-amber-500/15 text-amber-500">
                                          Shift Lead
                                        </span>
                                      )}
                                    </div>
                                    {a.userDesignationLabel && (
                                      <p className={`text-[10px] mt-0.5 ${text.muted}`}>{a.userDesignationLabel}</p>
                                    )}
                                  </div>
                                  <div className="flex items-center gap-1.5 opacity-0 group-hover:opacity-100 transition-opacity flex-shrink-0">
                                    {!isLead && (
                                      <button
                                        onClick={() => handleTransferLead(a.id, `Dr. ${a.userName}`)}
                                        title="Make shift lead"
                                        className={`w-7 h-7 rounded-lg flex items-center justify-center transition-colors ${
                                          isDark ? 'hover:bg-amber-500/20 text-amber-400' : 'hover:bg-amber-50 text-amber-500'
                                        }`}
                                      >
                                        <Crown className="w-3.5 h-3.5" />
                                      </button>
                                    )}
                                    <button
                                      onClick={() => handleEnd(a.id)}
                                      title="End shift"
                                      className={`w-7 h-7 rounded-lg flex items-center justify-center transition-colors ${
                                        isDark ? 'hover:bg-amber-500/20 text-amber-400' : 'hover:bg-amber-50 text-amber-500'
                                      }`}
                                    >
                                      <StopCircle className="w-3.5 h-3.5" />
                                    </button>
                                    <button
                                      onClick={() => handleRemove(a.id)}
                                      title="Remove"
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
                        </div>
                      )}

                      {/* Nurses section */}
                      {nurses.length > 0 && (
                        <div>
                          <div className="flex items-center gap-1.5 mb-2">
                            <HeartPulse className="w-3 h-3 text-emerald-500" />
                            <span className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>
                              Nurses ({nurses.length})
                            </span>
                          </div>
                          <div className="space-y-1.5">
                            {nurses.map((a: ShiftAssignmentResponse) => {
                              const fb = FUNCTION_BADGES[a.shiftFunction];
                              const isLead = a.isShiftLead && a.active;
                              return (
                                <div
                                  key={a.id}
                                  className={`${cardClass} px-3 py-2 flex items-center justify-between group transition-colors ${
                                    isLead ? 'ring-1 ring-amber-500/50 bg-amber-500/5' : ''
                                  }`}
                                  style={glassInner}
                                >
                                  <div className="min-w-0 flex-1">
                                    <div className="flex items-center gap-2 flex-wrap">
                                      {isLead && (
                                        <span
                                          title="Current shift lead"
                                          className="inline-flex items-center justify-center w-5 h-5 rounded-md bg-gradient-to-br from-amber-500 to-yellow-600 shadow-sm shadow-amber-500/40"
                                        >
                                          <Crown className="w-3 h-3 text-white" />
                                        </span>
                                      )}
                                      <span className={`text-sm font-medium ${text.heading}`}>{a.userName}</span>
                                      <span className={`text-[10px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded-md ${fb.bg} ${fb.color}`}>
                                        {SHIFT_FUNCTIONS.find(sf => sf.value === a.shiftFunction)?.label}
                                      </span>
                                      {isLead && (
                                        <span className="text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded-md bg-amber-500/15 text-amber-500">
                                          Shift Lead
                                        </span>
                                      )}
                                    </div>
                                    {a.userDesignationLabel && (
                                      <p className={`text-[10px] mt-0.5 ${text.muted}`}>{a.userDesignationLabel}</p>
                                    )}
                                  </div>
                                  <div className="flex items-center gap-1.5 opacity-0 group-hover:opacity-100 transition-opacity flex-shrink-0">
                                    {!isLead && (
                                      <button
                                        onClick={() => handleTransferLead(a.id, a.userName)}
                                        title="Make shift lead"
                                        className={`w-7 h-7 rounded-lg flex items-center justify-center transition-colors ${
                                          isDark ? 'hover:bg-amber-500/20 text-amber-400' : 'hover:bg-amber-50 text-amber-500'
                                        }`}
                                      >
                                        <Crown className="w-3.5 h-3.5" />
                                      </button>
                                    )}
                                    <button
                                      onClick={() => handleEnd(a.id)}
                                      title="End shift"
                                      className={`w-7 h-7 rounded-lg flex items-center justify-center transition-colors ${
                                        isDark ? 'hover:bg-amber-500/20 text-amber-400' : 'hover:bg-amber-50 text-amber-500'
                                      }`}
                                    >
                                      <StopCircle className="w-3.5 h-3.5" />
                                    </button>
                                    <button
                                      onClick={() => handleRemove(a.id)}
                                      title="Remove"
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
                        </div>
                      )}
                    </div>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* ══════════ LEGEND / INFO ══════════ */}
      <div className={`${cardClass} overflow-hidden`} style={glassCard}>
        <div className="p-5">
          <div className="flex items-center gap-3 mb-4">
            <div className={`w-8 h-8 rounded-xl flex items-center justify-center bg-gradient-to-br ${
              isDark ? 'from-violet-500/20 to-purple-500/20' : 'from-violet-50 to-purple-50'
            }`}>
              <Info className="w-4 h-4 text-violet-500" />
            </div>
            <h3 className={`text-sm font-bold ${text.heading}`}>How Zone-Routed Alerts Work</h3>
          </div>
          <div className={`grid grid-cols-1 md:grid-cols-2 gap-3 text-xs ${text.body}`}>
            {[
              {
                icon: <ShieldAlert className="w-3.5 h-3.5 text-red-400 mt-0.5 flex-shrink-0" />,
                content: <><strong className={text.heading}>Tier 1 (instant):</strong> When a patient is triaged RED/ORANGE/YELLOW, the zone primary doctor + charge nurse receive a direct notification.</>,
              },
              {
                icon: <ShieldAlert className="w-3.5 h-3.5 text-orange-400 mt-0.5 flex-shrink-0" />,
                content: <><strong className={text.heading}>Tier 2 (+2 min):</strong> If unacknowledged, ALL on-duty doctors (primary, supervising, resident) are notified.</>,
              },
              {
                icon: <ShieldAlert className="w-3.5 h-3.5 text-amber-400 mt-0.5 flex-shrink-0" />,
                content: <><strong className={text.heading}>Tier 3 (+5 min):</strong> If still unacknowledged, EVERYONE on shift is alerted + audible alarm.</>,
              },
              {
                icon: <MapPin className="w-3.5 h-3.5 text-cyan-400 mt-0.5 flex-shrink-0" />,
                content: <><strong className={text.heading}>Auto-routing:</strong> RED → Resuscitation, ORANGE → Acute, YELLOW/GREEN → General zone.</>,
              },
            ].map((item, i) => (
              <div key={i} className={`flex items-start gap-2.5 ${cardClass} px-3.5 py-3`} style={glassInner}>
                {item.icon}
                <p className="leading-relaxed">{item.content}</p>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
