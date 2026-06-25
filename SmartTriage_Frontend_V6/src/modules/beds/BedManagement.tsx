/* ── BedManagement ────────────────────────────────────────────────────
 *
 * Admin-facing CRUD for the hospital's bed inventory. Lets HOSPITAL_ADMIN
 * (and SUPER_ADMIN, for any hospital) create, rename, re-zone, take out
 * of service, or retire beds. Also surfaces the assigned monitor so a
 * bed can be physically paired with its ESP32.
 *
 * This page is not intended for clinical placement — that lives in
 * BedGridView. Think of this as the equivalent of "User Management" for
 * the bedside hardware layout.
 */
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  BedDouble, Plus, RefreshCw, Loader2, Pencil, Trash2, X, Building2,
  AlertTriangle, CheckCircle2, Monitor, Save, Link2, Link2Off, Sparkles,
} from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { useBedStore } from '@/store/bedStore';
import { useTheme } from '@/hooks/useTheme';
import { hospitalApi } from '@/api/hospitals';
import { iotApi } from '@/api/iot';
import { subscribeToBedChanges, subscribeToDevices } from '@/api/websocket';
import type {
  BedResponse,
  BedStatus,
  CreateBedRequest,
  DeviceResponse,
  EdZone,
  HospitalResponse,
  UpdateBedRequest,
} from '@/api/types';

const ZONES: EdZone[] = ['RESUS', 'ACUTE', 'GENERAL', 'TRIAGE', 'OBSERVATION', 'ISOLATION', 'PEDIATRIC', 'NEONATAL', 'AMBULATORY'];

const STATUS_STYLES: Record<BedStatus, { color: string; rgb: string; label: string }> = {
  AVAILABLE:      { color: 'text-emerald-600', rgb: '16,185,129',  label: 'Available' },
  OCCUPIED:       { color: 'text-slate-600',   rgb: '100,116,139', label: 'Occupied' },
  CLEANING:       { color: 'text-amber-600',   rgb: '245,158,11',  label: 'Cleaning' },
  OUT_OF_SERVICE: { color: 'text-rose-600',    rgb: '244,63,94',   label: 'Out of service' },
};

type ToastState = { type: 'success' | 'error'; message: string } | null;

export function BedManagement() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const authUser = useAuthStore((s) => s.user);
  const isSuperAdmin = authUser?.role === 'SUPER_ADMIN';

  // ── Hospital selector (SUPER_ADMIN switches, hospital admins fixed) ──
  const [hospitals, setHospitals] = useState<HospitalResponse[]>([]);
  const [selectedHospitalId, setSelectedHospitalId] = useState<string>(
    authUser?.hospitalId || 'a0000000-0000-0000-0000-000000000001'
  );
  const hospitalId = isSuperAdmin ? selectedHospitalId : (authUser?.hospitalId || selectedHospitalId);

  // ── Bed store ──
  const bedsMap = useBedStore((s) => s.beds);
  const loadHospital = useBedStore((s) => s.loadHospital);
  const createBed = useBedStore((s) => s.createBed);
  const updateBed = useBedStore((s) => s.updateBed);
  const deleteBed = useBedStore((s) => s.deleteBed);
  const assignDevice = useBedStore((s) => s.assignDevice);
  const markOutOfService = useBedStore((s) => s.markOutOfService);
  const markAvailable = useBedStore((s) => s.markAvailable);
  const markCleaned = useBedStore((s) => s.markCleaned);
  const seedDefaults = useBedStore((s) => s.seedDefaults);
  const loadingBeds = useBedStore((s) => s.loading);

  const beds = useMemo(() => {
    return Array.from(bedsMap.values())
      .filter((b) => b.hospitalId === hospitalId)
      .sort((a, b) => {
        if (a.zone !== b.zone) return a.zone.localeCompare(b.zone);
        if (a.displayOrder !== b.displayOrder) return a.displayOrder - b.displayOrder;
        return a.code.localeCompare(b.code);
      });
  }, [bedsMap, hospitalId]);

  // ── Device list (for device-assignment modal) ──
  const [devices, setDevices] = useState<DeviceResponse[]>([]);

  // ── Zone filter ──
  const [zoneFilter, setZoneFilter] = useState<EdZone | 'ALL'>('ALL');
  const filteredBeds = useMemo(() => {
    if (zoneFilter === 'ALL') return beds;
    return beds.filter((b) => b.zone === zoneFilter);
  }, [beds, zoneFilter]);

  // ── UI state ──
  const [showForm, setShowForm] = useState(false);
  const [editBed, setEditBed] = useState<BedResponse | null>(null);
  const [assignBed, setAssignBed] = useState<BedResponse | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [seeding, setSeeding] = useState(false);
  const [toast, setToast] = useState<ToastState>(null);

  const flash = (type: 'success' | 'error', message: string) => {
    setToast({ type, message });
    setTimeout(() => setToast(null), 3500);
  };

  // ── Effects: initial load ──
  const refresh = useCallback(async () => {
    if (!hospitalId) return;
    await loadHospital(hospitalId);
  }, [hospitalId, loadHospital]);

  useEffect(() => { refresh(); }, [refresh]);

  // Real-time updates — re-fetch the bed list whenever the backend
  // publishes a bed change (placePatient, discharge, status flip, device
  // assignment, etc.) or a device status change (e.g. a monitor flips to
  // MONITORING when a patient is auto-placed in its bed). Without this,
  // the page only showed stale data until the next manual refresh.
  //
  // Lightweight debounce — a burst of events (e.g. placePatient fires
  // bedChange + deviceStatusChange back-to-back) collapses into a single
  // refresh.
  useEffect(() => {
    if (!hospitalId) return;
    let debounceHandle: number | null = null;
    const scheduleRefresh = () => {
      if (debounceHandle != null) window.clearTimeout(debounceHandle);
      debounceHandle = window.setTimeout(() => { refresh(); }, 200);
    };
    const unsubBeds = subscribeToBedChanges(hospitalId, scheduleRefresh);
    const unsubDevices = subscribeToDevices(hospitalId, scheduleRefresh);
    return () => {
      if (debounceHandle != null) window.clearTimeout(debounceHandle);
      unsubBeds();
      unsubDevices();
    };
  }, [hospitalId, refresh]);

  useEffect(() => {
    if (isSuperAdmin) {
      hospitalApi.getAll(0, 100).then((data) => setHospitals(data.content || [])).catch(() => setHospitals([]));
    }
  }, [isSuperAdmin]);

  // Load devices once a bed is about to be wired up. Cached per hospital.
  useEffect(() => {
    if (!assignBed || !hospitalId) return;
    iotApi.getDevicesByHospital(hospitalId, 0, 200)
      .then((page) => setDevices(page.content || []))
      .catch(() => setDevices([]));
  }, [assignBed, hospitalId]);

  // ── Handlers ──
  const handleCreate = async (payload: CreateBedRequest) => {
    setSubmitting(true);
    try {
      await createBed(payload);
      setShowForm(false);
      flash('success', `Bed ${payload.code} created.`);
    } catch (e) {
      flash('error', e instanceof Error ? e.message : 'Failed to create bed');
    } finally {
      setSubmitting(false);
    }
  };

  const handleUpdate = async (bedId: string, payload: UpdateBedRequest) => {
    setSubmitting(true);
    try {
      await updateBed(bedId, payload);
      setEditBed(null);
      flash('success', 'Bed updated.');
    } catch (e) {
      flash('error', e instanceof Error ? e.message : 'Failed to update bed');
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async (bed: BedResponse) => {
    if (bed.status === 'OCCUPIED') {
      flash('error', `Cannot delete ${bed.code} — a patient is currently in this bed.`);
      return;
    }
    if (!confirm(`Delete bed ${bed.code}?\n\nThe bed will be removed from the inventory. This cannot be undone from this screen.`)) return;
    try {
      await deleteBed(bed.id);
      flash('success', `Bed ${bed.code} deleted.`);
    } catch (e) {
      flash('error', e instanceof Error ? e.message : 'Failed to delete bed');
    }
  };

  const handleAssignDevice = async (bedId: string, deviceId: string | null) => {
    try {
      await assignDevice(bedId, { deviceId });
      setAssignBed(null);
      flash('success', deviceId ? 'Monitor linked to bed.' : 'Monitor detached from bed.');
    } catch (e) {
      flash('error', e instanceof Error ? e.message : 'Failed to update device assignment');
    }
  };

  const handleSeedDefaults = async () => {
    if (!hospitalId) return;
    if (!confirm(
      'Seed the default bed inventory for this hospital?\n\n' +
      'Beds will be created in zones that are currently empty, using the ' +
      'Rwanda MoH bed standards for this hospital tier. Zones that already ' +
      'have any beds (active or out-of-service) will be skipped.'
    )) return;
    setSeeding(true);
    try {
      const result = await seedDefaults(hospitalId);
      if (result.bedsCreated === 0) {
        flash('success', 'No new beds seeded — all zones already have beds.');
      } else {
        flash(
          'success',
          `Seeded ${result.bedsCreated} bed${result.bedsCreated === 1 ? '' : 's'} ` +
          `across ${result.zonesSeeded.length} zone${result.zonesSeeded.length === 1 ? '' : 's'} ` +
          `(${result.tierUsed}).` +
          (result.zonesSkipped.length > 0
            ? ` Skipped ${result.zonesSkipped.length} populated zone${result.zonesSkipped.length === 1 ? '' : 's'}.`
            : '')
        );
      }
    } catch (e) {
      flash('error', e instanceof Error ? e.message : 'Failed to seed default beds');
    } finally {
      setSeeding(false);
    }
  };

  const handleStatusAction = async (bed: BedResponse, action: 'oos' | 'available' | 'cleaned') => {
    try {
      if (action === 'oos') {
        const reason = prompt(`Take ${bed.code} out of service?\nOptional reason:`, '');
        if (reason === null) return;
        await markOutOfService(bed.id, reason.trim() || undefined);
      } else if (action === 'available') {
        await markAvailable(bed.id);
      } else {
        await markCleaned(bed.id);
      }
      flash('success', `Bed ${bed.code} updated.`);
    } catch (e) {
      flash('error', e instanceof Error ? e.message : 'Failed to update bed status');
    }
  };

  // ── Stats ──
  const stats = useMemo(() => {
    const total = beds.length;
    const counts: Record<BedStatus, number> = { AVAILABLE: 0, OCCUPIED: 0, CLEANING: 0, OUT_OF_SERVICE: 0 };
    beds.forEach((b) => counts[b.status]++);
    return { total, ...counts };
  }, [beds]);

  // ──────────────────────────────────────────────────────────────────
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
  return (
    <div className="min-h-full p-4 lg:p-6 max-w-7xl mx-auto space-y-4 animate-fade-in">

      {/* Header */}
      <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
        <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
          <div className="flex items-center justify-between flex-wrap gap-3">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-cyan-500/20 flex items-center justify-center">
                <BedDouble className="w-5 h-5 text-cyan-300" />
              </div>
              <div>
                <h1 className="text-lg font-bold tracking-wide text-white">Bed Management</h1>
                <p className="text-white/50 text-xs">
                  Configure the physical beds in each ED zone and link them to bedside monitors.
                </p>
              </div>
            </div>

            <div className="flex items-center gap-3 flex-wrap">
              {isSuperAdmin && hospitals.length > 1 && (
                <select
                  value={selectedHospitalId}
                  onChange={(e) => setSelectedHospitalId(e.target.value)}
                  className="px-3 py-1.5 rounded-lg bg-white/10 border border-white/20 text-white text-xs font-bold focus:outline-none focus:ring-2 focus:ring-cyan-500/20 cursor-pointer hover:bg-white/15 transition-colors"
                >
                  {hospitals.map((h) => (
                    <option key={h.id} value={h.id} className="bg-slate-800 text-white">
                      {h.name}
                    </option>
                  ))}
                </select>
              )}
              <button
                onClick={refresh}
                className="w-9 h-9 rounded-xl bg-white/10 flex items-center justify-center text-white hover:bg-white/20 transition-colors"
                title="Refresh"
              >
                <RefreshCw className="w-4 h-4" />
              </button>
              <button
                onClick={() => setShowForm(true)}
                className="inline-flex items-center gap-2 px-4 py-2 bg-cyan-600 hover:bg-cyan-700 text-white rounded-xl text-xs font-bold shadow-lg hover:-translate-y-0.5 transition-all"
              >
                <Plus className="w-3.5 h-3.5" /> Add Bed
              </button>
            </div>
          </div>
        </div>

        {/* Quick stats */}
        <div className="grid grid-cols-2 sm:grid-cols-5 gap-0" style={{ background: isDark ? 'rgba(15,23,42,0.4)' : 'rgba(241,245,249,0.6)' }}>
          {[
            { label: 'Total', value: stats.total, tone: text.heading },
            { label: 'Available', value: stats.AVAILABLE, tone: 'text-emerald-600' },
            { label: 'Occupied', value: stats.OCCUPIED, tone: text.body },
            { label: 'Cleaning', value: stats.CLEANING, tone: 'text-amber-600' },
            { label: 'Out of Service', value: stats.OUT_OF_SERVICE, tone: 'text-rose-600' },
          ].map((s) => (
            <div key={s.label} className="px-5 py-3 text-center last:border-r-0" style={{ borderRight: borderStyle }}>
              <p className={`text-lg font-black ${s.tone}`}>{s.value}</p>
              <p className={`text-[9px] font-bold uppercase tracking-wider ${text.muted}`}>{s.label}</p>
            </div>
          ))}
        </div>
      </div>

      {/* Toast */}
      {toast && (
        <div
          className={`rounded-xl px-4 py-3 text-sm font-medium flex items-center gap-2 ${
            toast.type === 'success'
              ? 'bg-emerald-500/10 border border-emerald-500/30 text-emerald-600'
              : 'bg-rose-500/10 border border-rose-500/30 text-rose-600'
          }`}
        >
          {toast.type === 'success' ? <CheckCircle2 className="w-4 h-4" /> : <AlertTriangle className="w-4 h-4" />}
          {toast.message}
        </div>
      )}

      {/* Zone filter */}
      <div className="flex flex-wrap items-center gap-1.5">
        {(['ALL', ...ZONES] as (EdZone | 'ALL')[]).map((z) => (
          <button
            key={z}
            onClick={() => setZoneFilter(z)}
            className={`px-3 py-1.5 rounded-xl text-[11px] font-bold border transition-all ${
              zoneFilter === z
                ? 'bg-cyan-500/20 text-cyan-400 border-cyan-500/30'
                : `border-transparent ${text.body} hover:bg-white/5`
            }`}
            style={zoneFilter !== z ? glassInner : {}}
          >
            {z === 'ALL' ? 'All Zones' : z}
            <span className="ml-1.5 text-[10px] opacity-70">
              {z === 'ALL' ? beds.length : beds.filter((b) => b.zone === z).length}
            </span>
          </button>
        ))}
      </div>

      {/* Beds table */}
      <div className="rounded-2xl overflow-hidden" style={glassCard}>
        {loadingBeds && filteredBeds.length === 0 ? (
          <div className="py-16 text-center">
            <Loader2 className="w-6 h-6 animate-spin text-cyan-500 mx-auto mb-3" />
            <p className={`text-xs ${text.muted}`}>Loading beds…</p>
          </div>
        ) : filteredBeds.length === 0 ? (
          <div className="py-16 text-center px-4">
            <BedDouble className={`w-10 h-10 mx-auto mb-3 ${text.muted}`} />
            <p className={`text-sm font-bold ${text.heading}`}>No beds configured</p>
            <p className={`text-xs mt-1 ${text.muted}`}>
              {beds.length === 0
                ? 'This hospital has no bed inventory yet.'
                : <>Click <span className="font-bold">Add Bed</span> to create the first bed in the {zoneFilter} zone.</>}
            </p>

            {/* Seed defaults CTA — only when the hospital is fully empty.
                Backfills the Rwanda MoH default inventory for the hospital's
                tier. Idempotent per-zone, so it's safe to retry. */}
            {beds.length === 0 && (
              <div className="mt-5 flex flex-col items-center gap-2">
                <button
                  onClick={handleSeedDefaults}
                  disabled={seeding}
                  className="inline-flex items-center gap-2 px-4 py-2 rounded-xl text-xs font-bold text-white bg-cyan-600 hover:bg-cyan-700 shadow-lg disabled:opacity-60 disabled:cursor-not-allowed transition-colors"
                >
                  {seeding ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Sparkles className="w-3.5 h-3.5" />}
                  {seeding ? 'Seeding default beds…' : 'Seed default beds for this hospital'}
                </button>
                <p className={`text-[10px] max-w-md ${text.muted}`}>
                  Creates the standard bed inventory for the hospital's tier
                  (district / provincial-referral / national-teaching). Or use{' '}
                  <span className="font-bold">Add Bed</span> above to configure beds manually.
                </p>
              </div>
            )}
          </div>
        ) : (
          <div>
            {filteredBeds.map((b, i) => {
              const styles = STATUS_STYLES[b.status];
              return (
                <div key={b.id} className="grid grid-cols-12 gap-3 px-4 py-3 items-center hover:bg-white/5 transition-colors" style={i > 0 ? { borderTop: borderStyle } : undefined}>
                  {/* Code + zone */}
                  <div className="col-span-12 sm:col-span-3 flex items-center gap-3">
                    <div className="w-10 h-10 rounded-xl bg-cyan-500/20 flex items-center justify-center shadow-sm">
                      <span className="text-[11px] font-extrabold text-cyan-300 leading-none">{b.code}</span>
                    </div>
                    <div className="min-w-0">
                      <p className={`text-sm font-bold truncate ${text.heading}`}>Bed {b.code}</p>
                      <p className={`text-[10px] ${text.muted}`}>
                        {b.zone}
                        {b.label ? ` · ${b.label}` : ''}
                      </p>
                    </div>
                  </div>

                  {/* Status */}
                  <div className="col-span-6 sm:col-span-2">
                    <span
                      className={`inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider ${styles.color}`}
                      style={{ background: `rgba(${styles.rgb},0.08)`, border: `1px solid rgba(${styles.rgb},0.2)` }}
                    >
                      {styles.label}
                    </span>
                    {b.currentPatientName && (
                      <p className={`text-[10px] mt-1 truncate ${text.muted}`} title={b.currentPatientName}>
                        {b.currentPatientName}
                      </p>
                    )}
                  </div>

                  {/* Device */}
                  <div className="col-span-6 sm:col-span-3">
                    {b.assignedDeviceId ? (
                      <div className={`flex items-center gap-1.5 text-[11px] ${isDark ? 'text-cyan-300' : 'text-cyan-700'}`}>
                        <Monitor className="w-3 h-3" />
                        <span className="truncate font-medium">{b.assignedDeviceName || b.assignedDeviceId}</span>
                      </div>
                    ) : (
                      <div className={`flex items-center gap-1.5 text-[11px] italic ${text.muted}`}>
                        <Monitor className="w-3 h-3 opacity-40" />
                        No monitor
                      </div>
                    )}
                  </div>

                  {/* Actions */}
                  <div className="col-span-12 sm:col-span-4 flex items-center justify-end gap-1.5 flex-wrap">
                    <button
                      onClick={() => setAssignBed(b)}
                      title={b.assignedDeviceId ? 'Change or detach monitor' : 'Link a monitor to this bed'}
                      className="inline-flex items-center gap-1 px-2.5 py-1.5 text-[10px] font-bold rounded-lg bg-cyan-500/20 text-cyan-400 border border-cyan-500/30 hover:bg-cyan-500/30 transition-colors"
                    >
                      {b.assignedDeviceId ? <Link2Off className="w-3 h-3" /> : <Link2 className="w-3 h-3" />}
                      Monitor
                    </button>

                    {b.status === 'CLEANING' && (
                      <button
                        onClick={() => handleStatusAction(b, 'cleaned')}
                        className="inline-flex items-center gap-1 px-2.5 py-1.5 text-[10px] font-bold rounded-lg bg-emerald-500/20 text-emerald-400 border border-emerald-500/30 hover:bg-emerald-500/30 transition-colors"
                      >
                        Mark clean
                      </button>
                    )}
                    {b.status !== 'OUT_OF_SERVICE' && b.status !== 'OCCUPIED' && (
                      <button
                        onClick={() => handleStatusAction(b, 'oos')}
                        className="inline-flex items-center gap-1 px-2.5 py-1.5 text-[10px] font-bold rounded-lg bg-rose-500/20 text-rose-400 border border-rose-500/30 hover:bg-rose-500/30 transition-colors"
                      >
                        Take OOS
                      </button>
                    )}
                    {b.status === 'OUT_OF_SERVICE' && (
                      <button
                        onClick={() => handleStatusAction(b, 'available')}
                        className="inline-flex items-center gap-1 px-2.5 py-1.5 text-[10px] font-bold rounded-lg bg-emerald-500/20 text-emerald-400 border border-emerald-500/30 hover:bg-emerald-500/30 transition-colors"
                      >
                        Return to service
                      </button>
                    )}

                    <button
                      onClick={() => setEditBed(b)}
                      className={`inline-flex items-center gap-1 px-2.5 py-1.5 text-[10px] font-bold rounded-lg transition-colors ${text.body} ${isDark ? 'bg-white/10 hover:bg-white/15' : 'bg-slate-100 hover:bg-slate-200'}`}
                      title="Edit bed"
                    >
                      <Pencil className="w-3 h-3" />
                      Edit
                    </button>
                    <button
                      onClick={() => handleDelete(b)}
                      disabled={b.status === 'OCCUPIED'}
                      className="inline-flex items-center gap-1 px-2.5 py-1.5 text-[10px] font-bold rounded-lg bg-rose-500/20 text-rose-400 border border-rose-500/30 hover:bg-rose-500/30 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                      title={b.status === 'OCCUPIED' ? 'Cannot delete an occupied bed' : 'Delete bed'}
                    >
                      <Trash2 className="w-3 h-3" />
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Create / Edit modal */}
      {(showForm || editBed) && (
        <BedFormModal
          isDark={isDark}
          hospitalId={hospitalId}
          hospitals={hospitals}
          isSuperAdmin={isSuperAdmin}
          bed={editBed || undefined}
          submitting={submitting}
          onClose={() => { setShowForm(false); setEditBed(null); }}
          onSubmit={async (payload, updates) => {
            if (editBed && updates) await handleUpdate(editBed.id, updates);
            else if (payload) await handleCreate(payload);
          }}
        />
      )}

      {/* Device-assignment modal */}
      {assignBed && (
        <BedDeviceAssignmentModal
          bed={assignBed}
          devices={devices}
          onClose={() => setAssignBed(null)}
          onAssign={(deviceId) => handleAssignDevice(assignBed.id, deviceId)}
          isDark={isDark}
        />
      )}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────
// Bed form modal (Create / Edit)
// ─────────────────────────────────────────────────────────────────────
function BedFormModal({
  isDark,
  hospitalId,
  hospitals,
  isSuperAdmin,
  bed,
  submitting,
  onClose,
  onSubmit,
}: {
  isDark: boolean;
  hospitalId: string;
  hospitals: HospitalResponse[];
  isSuperAdmin: boolean;
  bed?: BedResponse;
  submitting: boolean;
  onClose: () => void;
  onSubmit: (payload: CreateBedRequest | null, updates: UpdateBedRequest | null) => Promise<void>;
}) {
  const { glassCard, glassInner, text } = useTheme();
  const editing = !!bed;
  const [code, setCode] = useState(bed?.code || '');
  const [label, setLabel] = useState(bed?.label || '');
  const [zone, setZone] = useState<EdZone>(bed?.zone || 'ACUTE');
  const [displayOrder, setDisplayOrder] = useState<number>(bed?.displayOrder ?? 0);
  const [notes, setNotes] = useState(bed?.notes || '');
  const [targetHospitalId, setTargetHospitalId] = useState(hospitalId);

  const canSubmit = code.trim().length >= 1 && !submitting;
  const inputClass = `w-full px-3 py-2 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.heading}`;
  const labelClass = `block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`;

  return (
    <div className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm" style={{ background: 'var(--modal-backdrop)' }} onClick={onClose}>
      <div
        className="w-full max-w-md rounded-2xl shadow-2xl overflow-hidden animate-scale-in"
        style={glassCard}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-5 py-3 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div className="w-7 h-7 rounded-lg bg-cyan-500/20 flex items-center justify-center">
              <BedDouble className="w-4 h-4 text-cyan-300" />
            </div>
            <h3 className="text-sm font-bold text-white">{editing ? `Edit bed ${bed!.code}` : 'Add new bed'}</h3>
          </div>
          <button onClick={onClose} className="w-7 h-7 rounded-lg bg-white/15 flex items-center justify-center text-white hover:bg-white/25">
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="p-5 space-y-3">
          {!editing && isSuperAdmin && hospitals.length > 0 && (
            <div>
              <label className={labelClass}>
                <Building2 className="inline w-3 h-3 mr-1" /> Hospital
              </label>
              <select
                value={targetHospitalId}
                onChange={(e) => setTargetHospitalId(e.target.value)}
                className={inputClass}
                style={glassInner}
              >
                {hospitals.map((h) => (
                  <option key={h.id} value={h.id}>{h.name}</option>
                ))}
              </select>
            </div>
          )}

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className={labelClass}>Code*</label>
              <input
                value={code}
                onChange={(e) => setCode(e.target.value)}
                placeholder="e.g. RES-01"
                maxLength={20}
                className={`${inputClass} font-mono`}
                style={glassInner}
                autoFocus={!editing}
              />
            </div>
            <div>
              <label className={labelClass}>Order</label>
              <input
                type="number"
                value={displayOrder}
                onChange={(e) => setDisplayOrder(parseInt(e.target.value, 10) || 0)}
                className={inputClass}
                style={glassInner}
              />
            </div>
          </div>

          <div>
            <label className={labelClass}>Label</label>
            <input
              value={label}
              onChange={(e) => setLabel(e.target.value)}
              placeholder="e.g. Crash bay A"
              maxLength={100}
              className={inputClass}
              style={glassInner}
            />
          </div>

          {!editing && (
            <div>
              <label className={labelClass}>Zone</label>
              <select
                value={zone}
                onChange={(e) => setZone(e.target.value as EdZone)}
                className={inputClass}
                style={glassInner}
              >
                {ZONES.map((z) => <option key={z} value={z}>{z}</option>)}
              </select>
            </div>
          )}

          <div>
            <label className={labelClass}>Notes</label>
            <textarea
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              rows={2}
              placeholder="Optional — e.g. isolation capable, bariatric, pediatric crib"
              className={inputClass}
              style={glassInner}
            />
          </div>
        </div>

        <div
          className="flex items-center justify-end gap-2 px-5 py-3"
          style={{ borderTop: isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)', background: isDark ? 'rgba(2,6,23,0.3)' : 'rgba(241,245,249,0.5)' }}
        >
          <button
            onClick={onClose}
            disabled={submitting}
            className={`px-3 py-1.5 rounded-xl text-xs font-bold ${text.body} ${isDark ? 'hover:bg-white/5' : 'hover:bg-slate-100'}`}
          >
            Cancel
          </button>
          <button
            disabled={!canSubmit}
            onClick={async () => {
              if (editing && bed) {
                await onSubmit(null, {
                  code: code.trim() !== bed.code ? code.trim() : undefined,
                  label: label.trim() !== (bed.label || '') ? (label.trim() || undefined) : undefined,
                  displayOrder: displayOrder !== bed.displayOrder ? displayOrder : undefined,
                  notes: notes.trim() !== (bed.notes || '') ? (notes.trim() || undefined) : undefined,
                });
              } else {
                await onSubmit({
                  hospitalId: targetHospitalId,
                  zone,
                  code: code.trim(),
                  label: label.trim() || undefined,
                  displayOrder,
                  notes: notes.trim() || undefined,
                }, null);
              }
            }}
            className={`inline-flex items-center gap-1.5 px-4 py-1.5 rounded-lg text-xs font-bold text-white transition-colors ${canSubmit ? 'bg-cyan-600 hover:bg-cyan-700' : 'bg-slate-400 cursor-not-allowed'}`}
          >
            {submitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Save className="w-3.5 h-3.5" />}
            {editing ? 'Save changes' : 'Create bed'}
          </button>
        </div>
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────
// Device assignment modal
// ─────────────────────────────────────────────────────────────────────
function BedDeviceAssignmentModal({
  bed,
  devices,
  onClose,
  onAssign,
  isDark,
}: {
  bed: BedResponse;
  devices: DeviceResponse[];
  onClose: () => void;
  onAssign: (deviceId: string | null) => void;
  isDark: boolean;
}) {
  const { glassCard, text } = useTheme();
  // Devices not currently attached to a different bed are candidates — the server
  // enforces this too, but filtering here makes the picker less noisy.
  const candidates = useMemo(() => {
    return devices
      .filter((d) => d.deviceType !== 'AMBULANCE_MONITOR' && d.status !== 'DECOMMISSIONED')
      .sort((a, b) => a.deviceName.localeCompare(b.deviceName));
  }, [devices]);

  return (
    <div className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm" style={{ background: 'var(--modal-backdrop)' }} onClick={onClose}>
      <div
        className="w-full max-w-md rounded-2xl shadow-2xl overflow-hidden animate-scale-in"
        style={glassCard}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-5 py-3 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div className="w-7 h-7 rounded-lg bg-cyan-500/20 flex items-center justify-center">
              <Monitor className="w-4 h-4 text-cyan-300" />
            </div>
            <h3 className="text-sm font-bold text-white">Monitor for bed {bed.code}</h3>
          </div>
          <button onClick={onClose} className="w-7 h-7 rounded-lg bg-white/15 flex items-center justify-center text-white hover:bg-white/25">
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="p-5 space-y-2 max-h-80 overflow-y-auto">
          {bed.assignedDeviceId && (
            <button
              onClick={() => onAssign(null)}
              className="w-full flex items-center justify-between gap-2 px-3 py-2 rounded-lg text-sm font-semibold bg-rose-500/20 text-rose-400 border border-rose-500/30 hover:bg-rose-500/30 transition-colors"
            >
              <span className="flex items-center gap-2">
                <Link2Off className="w-3.5 h-3.5" />
                Detach current monitor
                {bed.assignedDeviceName && <span className="text-[10px] font-normal text-rose-400/70">({bed.assignedDeviceName})</span>}
              </span>
            </button>
          )}

          {candidates.length === 0 ? (
            <div className={`text-center py-6 text-xs ${text.muted}`}>
              No devices registered for this hospital yet.
            </div>
          ) : candidates.map((d) => {
            const isCurrent = d.id === bed.assignedDeviceId;
            return (
              <button
                key={d.id}
                disabled={isCurrent}
                onClick={() => onAssign(d.id)}
                className={`w-full flex items-center gap-3 px-3 py-2 rounded-lg text-left border transition-colors ${
                  isCurrent
                    ? 'border-cyan-500/40 bg-cyan-500/10'
                    : isDark ? 'border-white/10 hover:border-white/20 hover:bg-white/5' : 'border-slate-200 hover:border-slate-400 hover:bg-slate-50'
                }`}
              >
                <div className={`w-8 h-8 rounded-lg flex items-center justify-center ${isDark ? 'bg-white/10' : 'bg-slate-100'}`}>
                  <Monitor className={`w-4 h-4 ${d.status === 'MONITORING' ? 'text-cyan-500' : d.status === 'ONLINE' ? 'text-emerald-500' : 'text-slate-400'}`} />
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <p className={`text-sm font-bold truncate ${text.heading}`}>{d.deviceName}</p>
                    {isCurrent && <span className="text-[9px] uppercase font-extrabold tracking-wider text-cyan-500">Current</span>}
                  </div>
                  <p className={`text-[10px] truncate ${text.muted}`}>
                    {d.deviceType?.replace(/_/g, ' ')} · {d.serialNumber} · {d.status}
                  </p>
                </div>
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
}
