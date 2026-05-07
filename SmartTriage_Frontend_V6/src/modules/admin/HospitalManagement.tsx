/* ═══════════════════════════════════════════════════════════════
   Hospital Management — SUPER_ADMIN CRUD for hospitals
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback } from 'react';
import {
  Building2, Plus, RefreshCw, Loader2, Pencil, Send,
  MapPin, Phone, Globe, CheckCircle2, AlertTriangle, X, Power, PowerOff,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { hospitalApi } from '@/api/hospitals';
import type { HospitalResponse } from '@/api/types';
import { RwandaLocationPicker } from '@/components/RwandaLocationPicker';

export function HospitalManagement() {
  const { glassCard, glassInner, isDark, text } = useTheme();

  const [hospitals, setHospitals] = useState<HospitalResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [editId, setEditId] = useState<string | null>(null);
  const [formLoading, setFormLoading] = useState(false);

  /* ── Feedback toast ── */
  const [toast, setToast] = useState<{ type: 'success' | 'error'; message: string } | null>(null);
  const flash = (type: 'success' | 'error', message: string) => {
    setToast({ type, message });
    setTimeout(() => setToast(null), 4000);
  };

  const emptyForm = {
    name: '', hospitalCode: '', address: '',
    phoneNumber: '', email: '', tier: 'DISTRICT',
    bedCapacity: '' as string,
    edCapacity: '' as string,
    icuCapacity: '' as string,
    hasPediatricResus: false,
    hasNeonatalUnit: false,
    // V46+ structured Rwanda location IDs.
    provinceId: undefined as string | undefined,
    districtId: undefined as string | undefined,
    sectorId: undefined as string | undefined,
    cellId: undefined as string | undefined,
    villageId: undefined as string | undefined,
  };
  const [form, setForm] = useState(emptyForm);

  const loadHospitals = useCallback(async () => {
    setLoading(true);
    try {
      const data = await hospitalApi.getAll(0, 100);
      setHospitals(data.content || []);
    } catch (err) {
      console.error('Failed to load hospitals:', err);
      flash('error', 'Failed to load hospitals');
      setHospitals([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadHospitals(); }, [loadHospitals]);

  const handleSave = async () => {
    if (!form.name) return;
    setFormLoading(true);
    try {
      const toNum = (v: string) => (v === '' ? undefined : Number(v));
      const payload: any = {
        name: form.name,
        address: form.address || undefined,
        phoneNumber: form.phoneNumber || undefined,
        email: form.email || undefined,
        tier: form.tier,
        bedCapacity: toNum(form.bedCapacity),
        edCapacity: toNum(form.edCapacity),
        icuCapacity: toNum(form.icuCapacity),
        hasPediatricResus: form.hasPediatricResus,
        hasNeonatalUnit: form.hasNeonatalUnit,
        provinceId: form.provinceId,
        districtId: form.districtId,
        sectorId: form.sectorId,
        cellId: form.cellId,
        villageId: form.villageId,
      };
      if (editId) {
        // hospitalCode is intentionally not part of update payload
        await hospitalApi.update(editId, payload);
        flash('success', 'Hospital updated successfully');
      } else {
        // hospitalCode omitted on create → server auto-generates
        await hospitalApi.create(payload);
        flash('success', 'Hospital created successfully');
      }
      setShowForm(false);
      setEditId(null);
      setForm(emptyForm);
      loadHospitals();
    } catch (err: any) {
      flash('error', err?.message || 'Failed to save hospital');
    } finally {
      setFormLoading(false);
    }
  };

  const handleDeactivate = async (h: HospitalResponse) => {
    if (!window.confirm(`Deactivate ${h.name}? Users at this hospital will lose access until it is reactivated.`)) return;
    try {
      await hospitalApi.deactivate(h.id);
      flash('success', 'Hospital deactivated');
      loadHospitals();
    } catch (err: any) {
      flash('error', err?.message || 'Failed to deactivate');
    }
  };

  const handleReactivate = async (h: HospitalResponse) => {
    try {
      await hospitalApi.reactivate(h.id);
      flash('success', 'Hospital reactivated');
      loadHospitals();
    } catch (err: any) {
      flash('error', err?.message || 'Failed to reactivate');
    }
  };

  const startEdit = (h: HospitalResponse) => {
    setForm({
      name: h.name || '',
      hospitalCode: h.hospitalCode || '',
      address: h.address || '',
      phoneNumber: h.phoneNumber || '',
      email: h.email || '',
      tier: h.tier || 'DISTRICT',
      bedCapacity: h.bedCapacity != null ? String(h.bedCapacity) : '',
      edCapacity: h.edCapacity != null ? String(h.edCapacity) : '',
      icuCapacity: h.icuCapacity != null ? String(h.icuCapacity) : '',
      hasPediatricResus: !!h.hasPediatricResus,
      hasNeonatalUnit: !!h.hasNeonatalUnit,
      provinceId: h.provinceId ?? undefined,
      districtId: h.districtId ?? undefined,
      sectorId: h.sectorId ?? undefined,
      cellId: h.cellId ?? undefined,
      villageId: h.villageId ?? undefined,
    });
    setEditId(h.id);
    setShowForm(true);
  };

  const TIERS = ['NATIONAL_REFERRAL', 'PROVINCIAL', 'DISTRICT', 'HEALTH_CENTER', 'CLINIC'];

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-6xl mx-auto space-y-4 animate-fade-in">

        {/* Header */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-violet-500/20 flex items-center justify-center">
                  <Building2 className="w-5 h-5 text-violet-400" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">Hospital Management</h1>
                  <p className="text-white/50 text-xs">Manage healthcare facilities</p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                <div className="px-3 py-1.5 rounded-lg bg-white/10">
                  <span className="text-white/70 text-xs font-bold">{hospitals.length} Hospitals</span>
                </div>
                <button onClick={loadHospitals} className="w-9 h-9 rounded-xl bg-white/10 flex items-center justify-center hover:bg-white/20 transition-colors">
                  <RefreshCw className="w-4 h-4 text-white" />
                </button>
                <button onClick={() => { setForm(emptyForm); setEditId(null); setShowForm(!showForm); }} className="inline-flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-cyan-500 to-cyan-600 text-white rounded-xl text-xs font-bold shadow-lg hover:-translate-y-0.5 transition-all">
                  <Plus className="w-3.5 h-3.5" /> Add Hospital
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

        {/* Form */}
        {showForm && (
          <div className="rounded-2xl p-5 animate-fade-up" style={glassCard}>
            <h4 className={`text-sm font-bold mb-4 ${text.heading}`}>{editId ? 'Edit Hospital' : 'Register New Hospital'}</h4>
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
              {([
                { label: 'Hospital Name', key: 'name', placeholder: 'e.g., King Faisal Hospital' },
                { label: 'Address', key: 'address', placeholder: 'Kigali, Rwanda' },
                { label: 'Phone', key: 'phoneNumber', placeholder: '+250 788 000 000' },
                { label: 'Email', key: 'email', placeholder: 'info@hospital.rw' },
              ] as const).map(({ label, key, placeholder }) => (
                <div key={key}>
                  <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>{label}</label>
                  <input value={(form as any)[key] || ''} onChange={(e) => setForm({ ...form, [key]: e.target.value })} placeholder={placeholder} className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`} style={glassInner} />
                </div>
              ))}
              {editId && (
                <div>
                  <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Code (auto-generated)</label>
                  <input value={form.hospitalCode} readOnly className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none font-mono opacity-70 ${isDark ? 'text-white' : 'text-slate-800'}`} style={glassInner} />
                </div>
              )}
              <div>
                <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Tier</label>
                <select value={form.tier} onChange={(e) => setForm({ ...form, tier: e.target.value })} className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white' : 'text-slate-800'}`} style={glassInner}>
                  {TIERS.map(t => <option key={t} value={t}>{t.replace(/_/g, ' ')}</option>)}
                </select>
              </div>
              {([
                { label: 'Bed Capacity', key: 'bedCapacity', placeholder: 'Total inpatient beds' },
                { label: 'ED Capacity', key: 'edCapacity', placeholder: 'Emergency dept beds' },
                { label: 'ICU Capacity', key: 'icuCapacity', placeholder: 'Intensive care beds' },
              ] as const).map(({ label, key, placeholder }) => (
                <div key={key}>
                  <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>{label}</label>
                  <input
                    type="number"
                    min={0}
                    value={(form as any)[key]}
                    onChange={(e) => setForm({ ...form, [key]: e.target.value })}
                    placeholder={placeholder}
                    className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
                    style={glassInner}
                  />
                </div>
              ))}
            </div>

            {/* Pediatric / Neonatal capability toggles — drive triage routing
                (a hospital without pediatric resus cannot accept a RED peds
                case; neonatal flag controls neonate referral). */}
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3 mt-3">
              <label className="flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer" style={glassInner}>
                <input type="checkbox" checked={form.hasPediatricResus} onChange={(e) => setForm({ ...form, hasPediatricResus: e.target.checked })} className="w-4 h-4 accent-cyan-500" />
                <span className={`text-xs font-semibold ${text.body}`}>Has Pediatric Resuscitation</span>
              </label>
              <label className="flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer" style={glassInner}>
                <input type="checkbox" checked={form.hasNeonatalUnit} onChange={(e) => setForm({ ...form, hasNeonatalUnit: e.target.checked })} className="w-4 h-4 accent-cyan-500" />
                <span className={`text-xs font-semibold ${text.body}`}>Has Neonatal Unit</span>
              </label>
            </div>

            {!editId && (
              <p className={`text-[11px] mt-2 ${text.muted}`}>
                Hospital code will be auto-generated from the hospital name (e.g. "King Faisal Hospital" → KFH-001).
              </p>
            )}

            {/* Cascading Rwanda location picker — V46+. Replaces the
                free-text location guesswork on the Address field with a
                FK chain that maps to the same units MoH uses. */}
            <div className="mt-4">
              <RwandaLocationPicker
                value={{
                  provinceId: form.provinceId,
                  districtId: form.districtId,
                  sectorId: form.sectorId,
                  cellId: form.cellId,
                  villageId: form.villageId,
                }}
                onChange={(next) => setForm((f) => ({
                  ...f,
                  provinceId: next.provinceId,
                  districtId: next.districtId,
                  sectorId: next.sectorId,
                  cellId: next.cellId,
                  villageId: next.villageId,
                }))}
              />
            </div>

            <div className="flex items-center gap-3 mt-4">
              <button onClick={handleSave} disabled={formLoading || !form.name} className="inline-flex items-center gap-2 px-5 py-2.5 bg-gradient-to-r from-slate-800 to-slate-700 text-white rounded-xl text-xs font-bold shadow-lg hover:-translate-y-0.5 transition-all disabled:opacity-50">
                {formLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Send className="w-3.5 h-3.5" />} {editId ? 'Update' : 'Register'}
              </button>
              <button onClick={() => { setShowForm(false); setEditId(null); }} className={`px-4 py-2.5 text-xs font-bold rounded-xl ${text.muted}`}>Cancel</button>
            </div>
          </div>
        )}

        {/* Hospital List */}
        {loading ? (
          <div className="flex items-center justify-center py-12">
            <Loader2 className="w-6 h-6 animate-spin text-cyan-500" />
          </div>
        ) : hospitals.length === 0 ? (
          <div className="rounded-2xl p-8 text-center animate-fade-up" style={glassCard}>
            <Building2 className="w-10 h-10 mx-auto mb-3 text-slate-400" />
            <p className={`text-sm font-bold ${text.heading}`}>No hospitals registered</p>
            <p className={text.muted}>Add your first hospital to get started</p>
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {hospitals.map((h, i) => (
              <div
                key={h.id}
                className="rounded-2xl p-5 animate-fade-up"
                style={{ ...glassCard, animationDelay: `${i * 0.03}s` }}
              >
                <div className="flex items-start justify-between mb-3">
                  <div className="flex items-center gap-3">
                    <div className="w-10 h-10 rounded-xl bg-violet-500/10 flex items-center justify-center">
                      <Building2 className="w-5 h-5 text-violet-500" />
                    </div>
                    <div>
                      <h4 className={`text-sm font-bold ${text.heading}`}>{h.name}</h4>
                      <p className={`text-[10px] font-mono ${text.muted}`}>{h.hospitalCode}</p>
                    </div>
                  </div>
                  <div className="flex items-center gap-2">
                    {h.active === false ? (
                      <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg text-rose-500 bg-rose-500/10">Inactive</span>
                    ) : (
                      <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg text-emerald-500 bg-emerald-500/10">Active</span>
                    )}
                    <button title="Edit" onClick={() => startEdit(h)} className={`w-7 h-7 rounded-lg flex items-center justify-center ${isDark ? 'hover:bg-white/10' : 'hover:bg-slate-100'} transition-colors`}>
                      <Pencil className="w-3.5 h-3.5 text-slate-400" />
                    </button>
                    {h.active === false ? (
                      <button title="Reactivate" onClick={() => handleReactivate(h)} className={`w-7 h-7 rounded-lg flex items-center justify-center hover:bg-emerald-500/10 transition-colors`}>
                        <Power className="w-3.5 h-3.5 text-emerald-500" />
                      </button>
                    ) : (
                      <button title="Deactivate" onClick={() => handleDeactivate(h)} className={`w-7 h-7 rounded-lg flex items-center justify-center hover:bg-rose-500/10 transition-colors`}>
                        <PowerOff className="w-3.5 h-3.5 text-rose-500" />
                      </button>
                    )}
                  </div>
                </div>

                <div className="space-y-1.5">
                  <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg text-slate-500 bg-slate-500/10`}>{h.tier?.replace(/_/g, ' ')}</span>
                  {h.address && (
                    <p className={`flex items-center gap-1.5 text-xs ${text.body}`}>
                      <MapPin className="w-3 h-3 text-slate-400 shrink-0" /> {h.address}
                    </p>
                  )}
                  {h.phoneNumber && (
                    <p className={`flex items-center gap-1.5 text-xs ${text.body}`}>
                      <Phone className="w-3 h-3 text-slate-400 shrink-0" /> {h.phoneNumber}
                    </p>
                  )}
                  {h.email && (
                    <p className={`flex items-center gap-1.5 text-xs ${text.body}`}>
                      <Globe className="w-3 h-3 text-slate-400 shrink-0" /> {h.email}
                    </p>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
