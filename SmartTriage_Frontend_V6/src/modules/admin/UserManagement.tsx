/* ═══════════════════════════════════════════════════════════════
   User Management — SUPER_ADMIN / HOSPITAL_ADMIN CRUD for users
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback } from 'react';
import {
  Users, Plus, RefreshCw, Loader2, Pencil, Send, MailPlus, RotateCw,
  Shield, User, Mail, Phone, CheckCircle2, Building2, AlertTriangle, X, BadgeCheck, Clock,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { userApi } from '@/api/users';
import { hospitalApi } from '@/api/hospitals';
import type { UserResponse, HospitalResponse, Role, Designation, AccountStatus } from '@/api/types';

// Badge styling follows the Pathways standard: translucent bg/border
// (rgba COLOR @ 0.08 / 0.2) + semantic text-COLOR-600, preserving each
// role/status hue.
const ROLE_COLORS: Record<string, { color: string; style: { background: string; border: string } }> = {
  SUPER_ADMIN:    { color: 'text-red-600',     style: { background: 'rgba(239,68,68,0.08)',  border: '1px solid rgba(239,68,68,0.2)' } },
  HOSPITAL_ADMIN: { color: 'text-violet-600',  style: { background: 'rgba(139,92,246,0.08)', border: '1px solid rgba(139,92,246,0.2)' } },
  DOCTOR:         { color: 'text-cyan-600',     style: { background: 'rgba(6,182,212,0.08)',  border: '1px solid rgba(6,182,212,0.2)' } },
  NURSE:          { color: 'text-emerald-600',  style: { background: 'rgba(16,185,129,0.08)', border: '1px solid rgba(16,185,129,0.2)' } },
  REGISTRAR:      { color: 'text-blue-600',     style: { background: 'rgba(59,130,246,0.08)', border: '1px solid rgba(59,130,246,0.2)' } },
  PARAMEDIC:      { color: 'text-amber-600',    style: { background: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.2)' } },
  LAB_TECHNICIAN: { color: 'text-pink-600',     style: { background: 'rgba(236,72,153,0.08)', border: '1px solid rgba(236,72,153,0.2)' } },
  READ_ONLY:      { color: 'text-slate-600',    style: { background: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)' } },
};

const STATUS_STYLES: Record<AccountStatus, { color: string; style: { background: string; border: string }; label: string }> = {
  PENDING_ACTIVATION: { color: 'text-amber-600',   style: { background: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.2)' },  label: 'Pending' },
  ACTIVE:             { color: 'text-emerald-600', style: { background: 'rgba(16,185,129,0.08)', border: '1px solid rgba(16,185,129,0.2)' }, label: 'Active' },
  DEACTIVATED:        { color: 'text-slate-600',   style: { background: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)' }, label: 'Deactivated' },
};

// Role dropdown values. Triage-station assignment is a per-shift
// function (ShiftAssignment.shiftFunction = TRIAGE_NURSE), not a
// separate role — a NURSE who works triage today is still a NURSE.
//
// Privilege escalation guard: SUPER_ADMIN cannot be created via the
// admin UI (system-bootstrap concern); HOSPITAL_ADMIN cannot be
// created by another HOSPITAL_ADMIN (no peer-creation, prevents a
// HA from minting peers). The `rolesAvailableTo` helper below applies
// these limits at the render layer; the backend's UserAdminAuthz
// enforces the same rules independently.
const ALL_ROLES = ['SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'NURSE', 'REGISTRAR', 'PARAMEDIC', 'LAB_TECHNICIAN', 'READ_ONLY'];

function rolesAvailableTo(callerRole: string | undefined): string[] {
  // SUPER_ADMIN cannot be minted via the API even by another SA.
  const noSA = ALL_ROLES.filter((r) => r !== 'SUPER_ADMIN');
  if (callerRole === 'SUPER_ADMIN') return noSA;
  if (callerRole === 'HOSPITAL_ADMIN') return noSA.filter((r) => r !== 'HOSPITAL_ADMIN');
  return [];
}

/**
 * Common Rwandan hospital departments. Free-text on the backend, but
 * a closed list on the UI to stop spelling drift ("Emergency" vs "ED"
 * vs "Casualty"). Sourced from the standard MoH facility-services
 * taxonomy used in District / Provincial / Referral hospitals.
 */
const DEPARTMENTS = [
  'Emergency Department',
  'Outpatient (OPD)',
  'Internal Medicine',
  'Pediatrics',
  'Obstetrics & Gynecology',
  'Surgery',
  'Anesthesia & Critical Care',
  'Intensive Care (ICU)',
  'Maternity',
  'Cardiology',
  'Oncology',
  'Mental Health',
  'Radiology / Imaging',
  'Laboratory',
  'Pharmacy',
  'Physiotherapy',
  'Dental',
  'Ophthalmology',
  'Health Records',
  'Administration',
  'IT / Informatics',
];

/**
 * Role-aware department picker rules:
 *
 *   LAB_TECHNICIAN → can only be in "Laboratory". The role IS the
 *                    department; offering anything else is a misconfig
 *                    that breaks downstream Lab-tech dashboards.
 *   PARAMEDIC      → no department; they work cross-department in
 *                    the ambulance + ED bay. Field is shown but
 *                    disabled so admins know the slot is intentional,
 *                    not forgotten.
 *   otherwise      → full list.
 */
function departmentsForRole(role: string): string[] {
  if (role === 'LAB_TECHNICIAN') return ['Laboratory'];
  return DEPARTMENTS;
}
function departmentDisabledForRole(role: string): boolean {
  return role === 'PARAMEDIC';
}

export function UserManagement() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const authUser = useAuthStore((s) => s.user);
  const isSuperAdmin = authUser?.role === 'SUPER_ADMIN';

  /* ── Hospital selector (SUPER_ADMIN picks hospital) ── */
  const [hospitals, setHospitals] = useState<HospitalResponse[]>([]);
  const [selectedHospitalId, setSelectedHospitalId] = useState<string>(
    authUser?.hospitalId || 'a0000000-0000-0000-0000-000000000001',
  );
  const hospitalId = isSuperAdmin ? selectedHospitalId : (authUser?.hospitalId || 'a0000000-0000-0000-0000-000000000001');

  const [users, setUsers] = useState<UserResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [editId, setEditId] = useState<string | null>(null);
  const [formLoading, setFormLoading] = useState(false);
  const [resendingId, setResendingId] = useState<string | null>(null);
  // In-app confirmation dialog (replaces native window.confirm) for the
  // destructive row actions: cancel-invite and deactivate-user.
  const [confirmDialog, setConfirmDialog] = useState<{ kind: 'cancel-invite' | 'deactivate'; user: UserResponse } | null>(null);
  const [confirmLoading, setConfirmLoading] = useState(false);

  /* ── Feedback toast ── */
  const [toast, setToast] = useState<{ type: 'success' | 'error'; message: string } | null>(null);
  const flash = (type: 'success' | 'error', message: string) => {
    setToast({ type, message });
    setTimeout(() => setToast(null), 4000);
  };

  /* ── Invite form (new users) ── */
  const emptyInviteForm = { email: '', role: 'NURSE', designation: '', department: '' };
  const [inviteForm, setInviteForm] = useState(emptyInviteForm);

  /* ── Edit form (existing users) ── */
  const emptyEditForm = {
    firstName: '', lastName: '', email: '', phoneNumber: '',
    role: 'NURSE', designation: '', department: '', hospitalId,
  };
  const [editForm, setEditForm] = useState(emptyEditForm);

  const [designationOptions, setDesignationOptions] = useState<{ value: string; label: string }[]>([]);

  const activeRole = editId ? editForm.role : inviteForm.role;

  /* ── Load designation options when role changes ── */
  useEffect(() => {
    if (!activeRole) return;
    userApi.getDesignations(activeRole as Role)
      .then((opts) => {
        setDesignationOptions(opts || []);
        if (editId) {
          if (editForm.designation && opts && !opts.some((o) => o.value === editForm.designation)) {
            setEditForm((f) => ({ ...f, designation: '' }));
          }
        } else {
          if (inviteForm.designation && opts && !opts.some((o) => o.value === inviteForm.designation)) {
            setInviteForm((f) => ({ ...f, designation: '' }));
          }
        }
      })
      .catch(() => setDesignationOptions([]));
  }, [activeRole]);

  /* ── Load hospitals list (SUPER_ADMIN only) ── */
  useEffect(() => {
    if (!isSuperAdmin) return;
    hospitalApi.getAll(0, 100).then((res) => {
      setHospitals(res.content || []);
      if (res.content?.length && !authUser?.hospitalId) {
        setSelectedHospitalId(res.content[0].id);
      }
    }).catch(() => { /* ignore */ });
  }, [isSuperAdmin]);

  const loadUsers = useCallback(async () => {
    setLoading(true);
    try {
      const data = await userApi.getByHospital(hospitalId, 0, 100, true);
      setUsers(data.content || []);
    } catch (err) {
      console.error('Failed to load users:', err);
      flash('error', 'Failed to load users');
      setUsers([]);
    } finally {
      setLoading(false);
    }
  }, [hospitalId]);

  useEffect(() => { loadUsers(); }, [loadUsers]);

  /* ── Invite new user ── */
  const handleInvite = async () => {
    if (!inviteForm.email || !inviteForm.role) return;
    setFormLoading(true);
    try {
      await userApi.invite({
        email: inviteForm.email,
        role: inviteForm.role as Role,
        designation: (inviteForm.designation || undefined) as Designation | undefined,
        department: inviteForm.department || undefined,
        hospitalId,
      });
      flash('success', `Invitation sent to ${inviteForm.email}`);
      setShowForm(false);
      setInviteForm(emptyInviteForm);
      loadUsers();
    } catch (err: any) {
      const fieldErrors = err?.data;
      if (fieldErrors && typeof fieldErrors === 'object') {
        const details = Object.values(fieldErrors).join(', ');
        flash('error', details || err?.message || 'Failed to send invitation');
      } else {
        flash('error', err?.message || 'Failed to send invitation');
      }
    } finally {
      setFormLoading(false);
    }
  };

  /* ── Update existing user ── */
  const handleUpdate = async () => {
    if (!editId || !editForm.firstName || !editForm.lastName || !editForm.email) return;
    setFormLoading(true);
    try {
      await userApi.update(editId, editForm as any);
      flash('success', 'User updated successfully');
      setShowForm(false);
      setEditId(null);
      setEditForm(emptyEditForm);
      loadUsers();
    } catch (err: any) {
      const fieldErrors = err?.data;
      if (fieldErrors && typeof fieldErrors === 'object') {
        const details = Object.values(fieldErrors).join(', ');
        flash('error', details || err?.message || 'Failed to update user');
      } else {
        flash('error', err?.message || 'Failed to update user');
      }
    } finally {
      setFormLoading(false);
    }
  };

  /* ── Resend invitation ── */
  const handleResend = async (userId: string) => {
    setResendingId(userId);
    try {
      await userApi.resendInvite(userId);
      flash('success', 'Invitation resent successfully');
    } catch (err: any) {
      flash('error', err?.message || 'Failed to resend invitation');
    } finally {
      setResendingId(null);
    }
  };

  /* ── Cancel pending invitation (opens in-app confirm dialog) ── */
  const handleCancelInvite = (u: UserResponse) => {
    setConfirmDialog({ kind: 'cancel-invite', user: u });
  };

  /* ── Deactivate active user (opens in-app confirm dialog) ── */
  const handleDeactivate = (u: UserResponse) => {
    setConfirmDialog({ kind: 'deactivate', user: u });
  };

  /* ── Reactivate a deactivated user (non-destructive → no confirm dialog) ── */
  const handleReactivate = async (u: UserResponse) => {
    try {
      await userApi.reactivate(u.id);
      flash('success', `${u.firstName} ${u.lastName} reactivated`);
      loadUsers();
    } catch (err: any) {
      flash('error', err?.message || 'Failed to reactivate user');
    }
  };

  /* ── Execute the pending confirm action ── */
  const runConfirm = async () => {
    if (!confirmDialog || confirmLoading) return;
    const { kind, user: u } = confirmDialog;
    setConfirmLoading(true);
    try {
      if (kind === 'cancel-invite') {
        await userApi.cancelInvite(u.id);
        flash('success', 'Invitation cancelled');
      } else {
        await userApi.delete(u.id);
        flash('success', `${u.firstName} ${u.lastName} deactivated`);
      }
      setConfirmDialog(null);
      loadUsers();
    } catch (err: any) {
      flash('error', err?.message || (kind === 'cancel-invite' ? 'Failed to cancel invitation' : 'Failed to deactivate user'));
    } finally {
      setConfirmLoading(false);
    }
  };

  const startEdit = (u: UserResponse) => {
    setEditForm({
      firstName: u.firstName || '',
      lastName: u.lastName || '',
      email: u.email || '',
      phoneNumber: u.phoneNumber || '',
      role: u.role || 'NURSE',
      designation: u.designation || '',
      department: (u as unknown as { department?: string }).department || '',
      hospitalId: u.hospitalId || hospitalId,
    });
    setEditId(u.id);
    setShowForm(true);
  };

  const openInviteForm = () => {
    setInviteForm(emptyInviteForm);
    setEditId(null);
    setShowForm(true);
  };

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-6xl mx-auto space-y-4 animate-fade-in">

        {/* Header */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-cyan-500/20 flex items-center justify-center">
                  <Users className="w-5 h-5 text-cyan-300" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">User Management</h1>
                  <p className="text-sm text-white/50">Invite staff and manage accounts</p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                <div className="px-3 py-1.5 rounded-lg bg-white/10">
                  <span className="text-white/70 text-xs font-bold">{users.length} Users</span>
                </div>
                <button onClick={loadUsers} className="w-9 h-9 rounded-xl bg-white/10 flex items-center justify-center hover:bg-white/20 transition-colors">
                  <RefreshCw className="w-4 h-4 text-white" />
                </button>
                <button onClick={openInviteForm} className="inline-flex items-center gap-2 px-4 py-2 bg-cyan-600 hover:bg-cyan-700 text-white rounded-xl text-xs font-bold shadow-lg transition-all">
                  <MailPlus className="w-3.5 h-3.5" /> Invite User
                </button>
              </div>
            </div>
          </div>
        </div>

        {/* Toast */}
        {toast && (
          <div className={`flex items-center gap-3 px-4 py-3 rounded-2xl text-sm font-semibold animate-fade-up ${
            toast.type === 'success'
              ? 'bg-emerald-500/15 text-emerald-400 border border-emerald-500/20'
              : 'bg-red-500/15 text-red-400 border border-red-500/20'
          }`}>
            {toast.type === 'success' ? <CheckCircle2 className="w-4 h-4" /> : <AlertTriangle className="w-4 h-4" />}
            {toast.message}
            <button onClick={() => setToast(null)} className="ml-auto"><X className="w-4 h-4" /></button>
          </div>
        )}

        {/* Hospital Selector (SUPER_ADMIN) */}
        {isSuperAdmin && hospitals.length > 0 && (
          <div className="rounded-2xl p-4 animate-fade-up" style={glassCard}>
            <div className="flex items-center gap-3">
              <Building2 className="w-5 h-5 text-violet-400" />
              <label className={`text-xs font-bold uppercase tracking-wider ${text.label}`}>Manage users for hospital:</label>
              <select
                value={selectedHospitalId}
                onChange={(e) => setSelectedHospitalId(e.target.value)}
                className={`flex-1 max-w-sm px-3 py-2 rounded-xl text-sm font-semibold focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
                style={glassInner}
              >
                {hospitals.map((h) => (
                  <option key={h.id} value={h.id}>{h.name} — {h.hospitalCode}</option>
                ))}
              </select>
            </div>
          </div>
        )}

        {/* Invite Form (new user) */}
        {showForm && !editId && (
          <div className="rounded-2xl p-5 animate-fade-up" style={glassCard}>
            <div className="flex items-center gap-2 mb-4">
              <MailPlus className="w-4 h-4 text-cyan-400" />
              <h4 className={`text-sm font-bold ${text.heading}`}>Invite New User</h4>
            </div>
            <p className={`text-xs mb-4 ${text.muted}`}>
              An invitation email will be sent. The user will set up their name and password when they accept the invitation.
            </p>
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-3">
              <div>
                <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Email *</label>
                <input value={inviteForm.email} onChange={(e) => setInviteForm({ ...inviteForm, email: e.target.value })} placeholder="john@hospital.rw" type="email" className={`w-full px-3 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body} ${isDark ? 'placeholder-slate-500' : 'placeholder-slate-400'}`} style={glassInner} />
              </div>
              <div>
                <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Role *</label>
                <select
                  value={inviteForm.role}
                  onChange={(e) => {
                    const newRole = e.target.value;
                    // Role-aware department auto-fill:
                    //   LAB_TECHNICIAN → snap to Laboratory (the only valid option)
                    //   PARAMEDIC      → clear (field will be disabled)
                    //   anything else  → keep current value
                    const newDept = newRole === 'LAB_TECHNICIAN' ? 'Laboratory'
                      : newRole === 'PARAMEDIC' ? ''
                      : inviteForm.department;
                    setInviteForm({ ...inviteForm, role: newRole, designation: '', department: newDept });
                  }}
                  className={`w-full px-3 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
                  style={glassInner}
                >
                  {rolesAvailableTo(authUser?.role).map(r => <option key={r} value={r}>{r.replace(/_/g, ' ')}</option>)}
                </select>
              </div>
              <div>
                <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Designation</label>
                <select value={inviteForm.designation} onChange={(e) => setInviteForm({ ...inviteForm, designation: e.target.value })} className={`w-full px-3 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`} style={glassInner}>
                  <option value="">Select designation...</option>
                  {designationOptions.map(d => <option key={d.value} value={d.value}>{d.label}</option>)}
                </select>
              </div>
              <div>
                <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Department</label>
                <select
                  value={inviteForm.department}
                  onChange={(e) => setInviteForm({ ...inviteForm, department: e.target.value })}
                  disabled={departmentDisabledForRole(inviteForm.role)}
                  className={`w-full px-3 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body} disabled:opacity-50 disabled:cursor-not-allowed`}
                  style={glassInner}
                >
                  <option value="">
                    {departmentDisabledForRole(inviteForm.role) ? 'N/A — paramedics are cross-department' : 'Select department...'}
                  </option>
                  {departmentsForRole(inviteForm.role).map(d => <option key={d} value={d}>{d}</option>)}
                </select>
              </div>
            </div>
            <div className="flex items-center gap-3 mt-4">
              <button onClick={handleInvite} disabled={formLoading || !inviteForm.email} className="inline-flex items-center gap-2 px-5 py-2.5 bg-cyan-600 hover:bg-cyan-700 text-white rounded-xl text-xs font-bold shadow-lg transition-all disabled:opacity-50">
                {formLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Send className="w-3.5 h-3.5" />} Send Invitation
              </button>
              <button onClick={() => { setShowForm(false); }} className={`px-4 py-2.5 text-xs font-bold rounded-xl ${text.muted}`}>Cancel</button>
            </div>
          </div>
        )}

        {/* Edit Form (existing user) */}
        {showForm && editId && (
          <div className="rounded-2xl p-5 animate-fade-up" style={glassCard}>
            <h4 className={`text-sm font-bold mb-4 ${text.heading}`}>Edit User</h4>
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
              <div>
                <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>First Name</label>
                <input
                  value={editForm.firstName}
                  onChange={(e) => setEditForm({ ...editForm, firstName: e.target.value })}
                  placeholder="John"
                  name="given-name"
                  autoComplete="off"
                  className={`w-full px-3 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body} ${isDark ? 'placeholder-slate-500' : 'placeholder-slate-400'}`}
                  style={glassInner}
                />
              </div>
              <div>
                <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Last Name</label>
                <input
                  value={editForm.lastName}
                  onChange={(e) => setEditForm({ ...editForm, lastName: e.target.value })}
                  placeholder="Doe"
                  name="family-name"
                  autoComplete="off"
                  className={`w-full px-3 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body} ${isDark ? 'placeholder-slate-500' : 'placeholder-slate-400'}`}
                  style={glassInner}
                />
              </div>
              <div>
                <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Email</label>
                <input
                  value={editForm.email}
                  onChange={(e) => setEditForm({ ...editForm, email: e.target.value })}
                  placeholder="john@hospital.rw"
                  type="email"
                  name="email-target"
                  autoComplete="off"
                  className={`w-full px-3 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body} ${isDark ? 'placeholder-slate-500' : 'placeholder-slate-400'}`}
                  style={glassInner}
                />
              </div>
              <div>
                <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Phone</label>
                <input
                  value={editForm.phoneNumber}
                  onChange={(e) => setEditForm({ ...editForm, phoneNumber: e.target.value })}
                  placeholder="+250 788 000 000"
                  name="tel-target"
                  autoComplete="off"
                  className={`w-full px-3 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body} ${isDark ? 'placeholder-slate-500' : 'placeholder-slate-400'}`}
                  style={glassInner}
                />
              </div>
              <div>
                <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Role</label>
                <select
                  value={editForm.role}
                  onChange={(e) => {
                    const newRole = e.target.value;
                    const newDept = newRole === 'LAB_TECHNICIAN' ? 'Laboratory'
                      : newRole === 'PARAMEDIC' ? ''
                      : editForm.department;
                    setEditForm({ ...editForm, role: newRole, designation: '', department: newDept });
                  }}
                  className={`w-full px-3 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
                  style={glassInner}
                >
                  {rolesAvailableTo(authUser?.role).map(r => <option key={r} value={r}>{r.replace(/_/g, ' ')}</option>)}
                </select>
              </div>
              <div>
                <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Designation</label>
                <select value={editForm.designation} onChange={(e) => setEditForm({ ...editForm, designation: e.target.value })} className={`w-full px-3 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`} style={glassInner}>
                  <option value="">Select designation...</option>
                  {designationOptions.map(d => <option key={d.value} value={d.value}>{d.label}</option>)}
                </select>
              </div>
              <div>
                <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Department</label>
                <select
                  value={editForm.department}
                  onChange={(e) => setEditForm({ ...editForm, department: e.target.value })}
                  disabled={departmentDisabledForRole(editForm.role)}
                  className={`w-full px-3 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body} disabled:opacity-50 disabled:cursor-not-allowed`}
                  style={glassInner}
                >
                  <option value="">
                    {departmentDisabledForRole(editForm.role) ? 'N/A — paramedics are cross-department' : 'Select department...'}
                  </option>
                  {departmentsForRole(editForm.role).map(d => <option key={d} value={d}>{d}</option>)}
                </select>
              </div>
            </div>
            <div className="flex items-center gap-3 mt-4">
              <button onClick={handleUpdate} disabled={formLoading || !editForm.firstName || !editForm.lastName || !editForm.email} className="inline-flex items-center gap-2 px-5 py-2.5 bg-cyan-600 hover:bg-cyan-700 text-white rounded-xl text-xs font-bold shadow-lg transition-all disabled:opacity-50">
                {formLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Send className="w-3.5 h-3.5" />} Update
              </button>
              <button onClick={() => { setShowForm(false); setEditId(null); }} className={`px-4 py-2.5 text-xs font-bold rounded-xl ${text.muted}`}>Cancel</button>
            </div>
          </div>
        )}

        {/* Users Table */}
        {loading ? (
          <div className="flex items-center justify-center py-12">
            <Loader2 className="w-6 h-6 animate-spin text-cyan-500" />
          </div>
        ) : users.length === 0 ? (
          <div className="rounded-2xl p-8 text-center animate-fade-up" style={glassCard}>
            <Users className="w-10 h-10 mx-auto mb-3 text-slate-400" />
            <p className={`text-sm font-bold ${text.heading}`}>No users found</p>
            <p className={text.muted}>Invite your first staff member</p>
          </div>
        ) : (
          <div className="rounded-2xl overflow-hidden animate-fade-up" style={glassCard}>
            <div className="overflow-x-auto">
              <table className="w-full">
                <thead>
                  <tr style={{ borderBottom: isDark ? '1px solid rgba(2,132,199,0.15)' : '1px solid rgba(203,213,225,0.3)' }}>
                    {['Name', 'Email', 'Role', 'Designation', 'Phone', 'Status', ''].map((h) => (
                      <th key={h} className={`px-4 py-3 text-left text-[10px] font-bold uppercase tracking-wider ${text.label}`}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {users.map((u) => {
                    const rc = ROLE_COLORS[u.role] || ROLE_COLORS.READ_ONLY;
                    const status = u.accountStatus || 'ACTIVE';
                    const sc = STATUS_STYLES[status] || STATUS_STYLES.ACTIVE;
                    const isPending = status === 'PENDING_ACTIVATION';
                    return (
                      <tr
                        key={u.id}
                        className={`transition-colors ${isDark ? 'hover:bg-white/5' : 'hover:bg-slate-50'}`}
                        style={{ borderBottom: isDark ? '1px solid rgba(2,132,199,0.08)' : '1px solid rgba(203,213,225,0.15)' }}
                      >
                        <td className="px-4 py-3">
                          <div className="flex items-center gap-3">
                            <div className={`w-8 h-8 rounded-lg flex items-center justify-center ${isPending ? 'bg-amber-500/20' : 'bg-gradient-to-br from-cyan-500/20 to-blue-500/20'}`}>
                              {isPending ? <Clock className="w-4 h-4 text-amber-500" /> : <User className="w-4 h-4 text-cyan-500" />}
                            </div>
                            <div>
                              <p className={`text-xs font-bold ${text.heading}`}>
                                {u.firstName && u.lastName ? `${u.firstName} ${u.lastName}` : <span className={`italic ${text.muted}`}>Pending setup</span>}
                              </p>
                            </div>
                          </div>
                        </td>
                        <td className="px-4 py-3">
                          <p className={`text-xs ${text.body}`}>{u.email}</p>
                        </td>
                        <td className="px-4 py-3">
                          <span className={`inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider ${rc.color}`} style={rc.style}>
                            {u.role?.replace(/_/g, ' ')}
                          </span>
                        </td>
                        <td className="px-4 py-3">
                          {u.designationLabel ? (
                            <span className="inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg text-violet-600" style={{ background: 'rgba(139,92,246,0.08)', border: '1px solid rgba(139,92,246,0.2)' }}>
                              {u.designationLabel}
                            </span>
                          ) : (
                            <span className={`text-[10px] italic ${text.muted}`}>Not set</span>
                          )}
                        </td>
                        <td className="px-4 py-3">
                          <p className={`text-xs ${text.muted}`}>{u.phoneNumber || '—'}</p>
                        </td>
                        <td className="px-4 py-3">
                          <span className={`inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider ${sc.color}`} style={sc.style}>
                            {sc.label}
                          </span>
                        </td>
                        <td className="px-4 py-3">
                          {/* Row actions, ordered by frequency of use:
                              resend / cancel for pending invites,
                              edit + deactivate for active users.
                              All four buttons gate themselves on the
                              backend's UserAdminAuthz; the UI just
                              hides clearly out-of-scope ones (e.g.
                              SUPER_ADMIN row never shows an action
                              button to a HOSPITAL_ADMIN viewer). */}
                          <div className="flex items-center gap-1">
                            {isPending && (
                              <>
                                <button
                                  onClick={() => handleResend(u.id)}
                                  disabled={resendingId === u.id}
                                  title="Resend invitation email"
                                  className={`w-7 h-7 rounded-lg flex items-center justify-center ${isDark ? 'hover:bg-white/10' : 'hover:bg-slate-100'} transition-colors disabled:opacity-50`}
                                >
                                  {resendingId === u.id
                                    ? <Loader2 className="w-3.5 h-3.5 animate-spin text-amber-400" />
                                    : <RotateCw className="w-3.5 h-3.5 text-amber-400" />}
                                </button>
                                <button
                                  onClick={() => handleCancelInvite(u)}
                                  title="Cancel invitation"
                                  className={`w-7 h-7 rounded-lg flex items-center justify-center ${isDark ? 'hover:bg-rose-500/15' : 'hover:bg-rose-50'} transition-colors`}
                                >
                                  <X className="w-3.5 h-3.5 text-rose-500" />
                                </button>
                              </>
                            )}
                            {!isPending && u.accountStatus !== 'DEACTIVATED' && u.role !== 'SUPER_ADMIN' && (
                              <>
                                <button
                                  onClick={() => startEdit(u)}
                                  title="Edit user"
                                  className={`w-7 h-7 rounded-lg flex items-center justify-center ${isDark ? 'hover:bg-white/10' : 'hover:bg-slate-100'} transition-colors`}
                                >
                                  <Pencil className="w-3.5 h-3.5 text-slate-400" />
                                </button>
                                {/* Deactivate hidden for the row that
                                    represents the logged-in user
                                    (foot-gun: deactivating yourself)
                                    and for HOSPITAL_ADMIN rows when the
                                    viewer is not a SUPER_ADMIN. */}
                                {u.id !== authUser?.id
                                 && (u.role !== 'HOSPITAL_ADMIN' || authUser?.role === 'SUPER_ADMIN') && (
                                  <button
                                    onClick={() => handleDeactivate(u)}
                                    title="Deactivate user"
                                    className={`w-7 h-7 rounded-lg flex items-center justify-center ${isDark ? 'hover:bg-rose-500/15' : 'hover:bg-rose-50'} transition-colors`}
                                  >
                                    <AlertTriangle className="w-3.5 h-3.5 text-rose-500" />
                                  </button>
                                )}
                              </>
                            )}
                            {!isPending && u.accountStatus === 'DEACTIVATED' && u.role !== 'SUPER_ADMIN'
                             && (u.role !== 'HOSPITAL_ADMIN' || authUser?.role === 'SUPER_ADMIN') && (
                              <button
                                onClick={() => handleReactivate(u)}
                                title="Reactivate user"
                                className={`w-7 h-7 rounded-lg flex items-center justify-center ${isDark ? 'hover:bg-emerald-500/15' : 'hover:bg-emerald-50'} transition-colors`}
                              >
                                <RotateCw className="w-3.5 h-3.5 text-emerald-500" />
                              </button>
                            )}
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>

      {/* In-app confirmation dialog for destructive row actions (replaces window.confirm) */}
      {confirmDialog && (
        <div
          className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm"
          style={{ background: 'rgba(2,6,23,0.65)' }}
          onClick={() => !confirmLoading && setConfirmDialog(null)}
        >
          <div
            className="w-full max-w-md rounded-2xl overflow-hidden shadow-2xl animate-scale-in"
            style={glassCard}
            onClick={(e) => e.stopPropagation()}
          >
            <div className="p-5">
            <div className="flex items-start gap-3">
              <div className={`w-10 h-10 rounded-xl flex items-center justify-center shrink-0 ${confirmDialog.kind === 'deactivate' ? 'bg-rose-500/15' : 'bg-amber-500/15'}`}>
                <AlertTriangle className={`w-5 h-5 ${confirmDialog.kind === 'deactivate' ? 'text-rose-500' : 'text-amber-500'}`} />
              </div>
              <div className="flex-1">
                <h4 className={`text-sm font-bold ${text.heading}`}>
                  {confirmDialog.kind === 'cancel-invite' ? 'Cancel invitation?' : 'Deactivate user?'}
                </h4>
                <p className={`text-xs mt-1.5 leading-relaxed ${text.muted}`}>
                  {confirmDialog.kind === 'cancel-invite'
                    ? <>The activation link sent to <span className="font-semibold">{confirmDialog.user.email}</span> will stop working immediately. You can re-invite later.</>
                    : <><span className="font-semibold">{confirmDialog.user.firstName} {confirmDialog.user.lastName}</span> will no longer be able to log in. Their clinical history is preserved.</>}
                </p>
              </div>
            </div>
            <div className="flex justify-end gap-2 mt-5">
              <button
                onClick={() => setConfirmDialog(null)}
                disabled={confirmLoading}
                className={`px-4 py-2 rounded-xl text-xs font-bold ${text.muted} hover:bg-white/5 disabled:opacity-50`}
              >
                Keep
              </button>
              <button
                onClick={runConfirm}
                disabled={confirmLoading}
                className={`inline-flex items-center gap-2 px-5 py-2 rounded-xl text-xs font-bold text-white shadow-lg disabled:opacity-50 ${confirmDialog.kind === 'deactivate' ? 'bg-gradient-to-r from-rose-500 to-rose-600' : 'bg-gradient-to-r from-amber-500 to-amber-600'}`}
              >
                {confirmLoading && <span className="w-3.5 h-3.5 rounded-full border-2 border-white/40 border-t-white animate-spin" />}
                {confirmDialog.kind === 'cancel-invite' ? 'Cancel invitation' : 'Deactivate'}
              </button>
            </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
