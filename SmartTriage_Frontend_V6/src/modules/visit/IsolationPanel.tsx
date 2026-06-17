/* ═══════════════════════════════════════════════════════════════
   Infection Isolation Panel — per-visit chart entry point

   Where a clinician initiates an infection screening, sees this patient's
   isolation status + PPE requirements (staff-safety signage), assigns an
   isolation room, records public-health notification, and clears isolation.
   Before this panel the isolation API had no working caller from the chart
   (screen/notify 404'd, assign-room sent the wrong field) and there was no
   chart entry point at all.
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback, useRef } from 'react';
import {
  ShieldAlert, AlertTriangle, CheckCircle2, Loader2, Clock, Play,
  Biohazard, Timer, BedDouble, Megaphone, RefreshCw,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { isolationApi, type InfectionScreening, type ScreenInfectionRequest } from '@/api/isolation';
import { subscribeToIsolation } from '@/api/websocket';
import { useWebSocketGeneration } from '@/hooks/useWebSocket';
import { ApiError } from '@/api/client';
import { format } from 'date-fns';

/* Isolation-type colour — never downgrade an unknown type to a low-urgency colour. */
const ISO_FALLBACK = { color: 'text-red-600', bg: 'bg-red-500/15', label: 'ISOLATION' };
const ISO_CONFIG: Record<string, { color: string; bg: string; label: string }> = {
  STRICT:     { color: 'text-red-700',     bg: 'bg-red-600/20',     label: 'STRICT (VHF)' },
  AIRBORNE:   { color: 'text-red-500',     bg: 'bg-red-500/15',     label: 'AIRBORNE' },
  DROPLET:    { color: 'text-amber-500',   bg: 'bg-amber-500/15',   label: 'DROPLET' },
  CONTACT:    { color: 'text-yellow-500',  bg: 'bg-yellow-500/15',  label: 'CONTACT' },
  PROTECTIVE: { color: 'text-sky-500',     bg: 'bg-sky-500/15',     label: 'PROTECTIVE' },
};
const RISK_LABEL: Record<string, string> = {
  CONFIRMED: 'CONFIRMED', HIGH_RISK: 'HIGH RISK', MODERATE_RISK: 'MODERATE', LOW_RISK: 'LOW', CLEARED: 'CLEARED',
};

const PPE_FIELDS: Array<{ key: keyof InfectionScreening; label: string }> = [
  { key: 'requiresN95', label: 'N95' },
  { key: 'requiresGown', label: 'Gown' },
  { key: 'requiresGloves', label: 'Gloves' },
  { key: 'requiresFaceShield', label: 'Face shield' },
  { key: 'requiresApron', label: 'Apron' },
  { key: 'requiresBootCovers', label: 'Boot covers' },
];

const emptyForm = (): ScreenInfectionRequest => ({
  hasFever: false, hasCough: false, hasCoughDurationWeeks: undefined,
  hasNightSweats: false, hasWeightLoss: false, hasRash: false, hasDiarrhea: false,
  hasRecentTravel: false, recentTravelLocation: undefined,
  hasContactWithInfectious: false, contactDetails: undefined,
  hasBleedingSymptoms: false, isHealthcareWorker: false,
  immunocompromised: false, hasNeckStiffness: false, notes: undefined,
});

function recheckLabel(dueIso: string | null): { text: string; overdue: boolean } | null {
  if (!dueIso) return null;
  const mins = Math.round((new Date(dueIso).getTime() - Date.now()) / 60000);
  if (mins <= 0) return { text: `placement overdue by ${Math.abs(mins)}m`, overdue: true };
  return { text: `place within ${mins}m`, overdue: false };
}

interface IsolationPanelProps {
  visitId: string;
  onChanged?: () => void;
}

export function IsolationPanel({ visitId, onChanged }: IsolationPanelProps) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const hospitalId = useAuthStore((s) => s.user?.hospitalId) || '';
  const wsGen = useWebSocketGeneration();

  const [screenings, setScreenings] = useState<InfectionScreening[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [, setTick] = useState(0);
  const tickRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState<ScreenInfectionRequest>(emptyForm());
  const [roomFor, setRoomFor] = useState<string | null>(null);
  const [room, setRoom] = useState('');
  const [endFor, setEndFor] = useState<string | null>(null);
  const [endReason, setEndReason] = useState('');
  const [notifyFor, setNotifyFor] = useState<string | null>(null);
  const [notifyRef, setNotifyRef] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await isolationApi.getForVisit(visitId);
      setScreenings(Array.isArray(data) ? data : []);
      setError(null);
    } catch (err) {
      console.error('Failed to load isolation screenings:', err);
      setScreenings([]);
      setError(err instanceof ApiError ? err.message : 'Failed to load isolation screenings');
    } finally {
      setLoading(false);
    }
  }, [visitId]);

  useEffect(() => { load(); }, [load]);
  useEffect(() => {
    tickRef.current = setInterval(() => setTick((t) => t + 1), 30000);
    return () => { if (tickRef.current) clearInterval(tickRef.current); };
  }, []);
  useEffect(() => {
    if (!hospitalId) return;
    const unsub = subscribeToIsolation(hospitalId, (event: { visitId?: string }) => {
      if (event?.visitId === visitId) load();
    });
    return () => unsub();
  }, [hospitalId, visitId, load, wsGen]);

  const fail = (err: unknown, fallback: string) =>
    setError(err instanceof ApiError ? err.message : err instanceof Error ? err.message : fallback);

  const run = async (fn: () => Promise<unknown>, fallback: string, after?: () => void) => {
    setBusy(true); setError(null);
    try { await fn(); after?.(); await load(); onChanged?.(); }
    catch (err) { fail(err, fallback); }
    finally { setBusy(false); }
  };

  const submitScreen = () =>
    run(() => isolationApi.screen(visitId, form), 'Failed to run infection screening',
      () => { setShowForm(false); setForm(emptyForm()); });

  const submitRoom = (id: string) => {
    if (!room.trim()) return;
    run(() => isolationApi.assignRoom(id, room.trim()), 'Failed to assign isolation room',
      () => { setRoomFor(null); setRoom(''); });
  };

  const submitEnd = (id: string) => {
    if (!endReason.trim()) return;
    run(() => isolationApi.endIsolation(id, endReason.trim()), 'Failed to clear isolation',
      () => { setEndFor(null); setEndReason(''); });
  };

  const submitNotify = (id: string) =>
    run(() => isolationApi.notifyPublicHealth(id, notifyRef.trim() || undefined),
      'Failed to record public-health notification', () => { setNotifyFor(null); setNotifyRef(''); });

  const active = screenings.filter((s) => s.isolationType && !s.isolationEndedAt);
  const history = screenings.filter((s) => !s.isolationType || s.isolationEndedAt);

  const set = <K extends keyof ScreenInfectionRequest>(k: K, v: ScreenInfectionRequest[K]) =>
    setForm((f) => ({ ...f, [k]: v }));

  const Check = ({ k, label }: { k: keyof ScreenInfectionRequest; label: string }) => (
    <label className={`flex items-center gap-2 px-3 py-2 rounded-xl border cursor-pointer transition-all text-[11px] font-medium ${
      form[k] ? 'bg-red-500/10 border-red-500/40 text-red-500'
        : isDark ? 'border-white/10 text-slate-300 hover:bg-white/5' : 'border-slate-200 text-slate-600 hover:bg-slate-50'}`}>
      <input type="checkbox" className="accent-red-500" checked={!!form[k]} onChange={(e) => set(k, e.target.checked as never)} />
      {label}
    </label>
  );

  return (
    <div className="space-y-4">
      {/* Header + run-screen */}
      <div className="rounded-2xl p-5" style={glassCard}>
        <div className="flex items-start justify-between gap-4 flex-wrap">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-red-500/15 flex items-center justify-center shrink-0">
              <Biohazard className="w-5 h-5 text-red-500" />
            </div>
            <div>
              <h3 className={`text-sm font-extrabold tracking-tight ${text.heading}`}>Infection Isolation</h3>
              <p className={`text-xs ${text.muted}`}>Screen for airborne / droplet / contact / VHF risk and manage isolation + PPE.</p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            {screenings.length > 0 && (
              <button onClick={load} disabled={loading} title="Refresh"
                className={`w-9 h-9 rounded-xl flex items-center justify-center transition-colors ${isDark ? 'bg-white/5 hover:bg-white/10' : 'bg-slate-100 hover:bg-slate-200'}`}>
                <RefreshCw className={`w-4 h-4 ${text.muted} ${loading ? 'animate-spin' : ''}`} />
              </button>
            )}
            <button onClick={() => { setShowForm((v) => !v); setForm(emptyForm()); }} disabled={busy}
              className="inline-flex items-center gap-2 px-5 py-2.5 text-[11px] font-bold rounded-xl bg-gradient-to-r from-red-500 to-rose-500 text-white shadow-md hover:-translate-y-0.5 transition-all disabled:opacity-50">
              <Play className="w-3.5 h-3.5" /> Run infection screening
            </button>
          </div>
        </div>
        {error && (
          <div className="mt-3 flex items-start gap-2 rounded-xl px-3 py-2.5 bg-red-500/10 border border-red-500/20">
            <AlertTriangle className="w-4 h-4 text-red-500 shrink-0 mt-0.5" />
            <p className="text-[11px] font-semibold text-red-500">{error}</p>
          </div>
        )}

        {/* Screening form */}
        {showForm && (
          <div className="mt-4 pt-4 border-t" style={{ borderColor: isDark ? 'rgba(248,113,113,0.15)' : 'rgba(203,213,225,0.4)' }}>
            <p className={`text-[11px] font-bold uppercase tracking-wider mb-2 ${text.muted}`}>Symptoms & exposure</p>
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
              <Check k="hasFever" label="Fever" />
              <Check k="hasCough" label="Cough" />
              <Check k="hasNightSweats" label="Night sweats" />
              <Check k="hasWeightLoss" label="Weight loss" />
              <Check k="hasRash" label="Rash" />
              <Check k="hasNeckStiffness" label="Neck stiffness" />
              <Check k="hasDiarrhea" label="Diarrhea" />
              <Check k="hasBleedingSymptoms" label="Bleeding" />
              <Check k="hasRecentTravel" label="Recent travel" />
              <Check k="hasContactWithInfectious" label="Infectious contact" />
              <Check k="immunocompromised" label="Immunocompromised" />
              <Check k="isHealthcareWorker" label="Healthcare worker" />
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-2 mt-2">
              {form.hasCough && (
                <input type="number" min="0" max="104" placeholder="Cough weeks" value={form.hasCoughDurationWeeks ?? ''}
                  onChange={(e) => set('hasCoughDurationWeeks', e.target.value ? parseInt(e.target.value) : undefined)}
                  className="px-3 py-2 rounded-xl text-sm outline-none" style={glassInner} />
              )}
              {form.hasRecentTravel && (
                <input type="text" placeholder="Travel location" value={form.recentTravelLocation ?? ''}
                  onChange={(e) => set('recentTravelLocation', e.target.value || undefined)}
                  className="px-3 py-2 rounded-xl text-sm outline-none" style={glassInner} />
              )}
              {form.hasContactWithInfectious && (
                <input type="text" placeholder="Contact details" value={form.contactDetails ?? ''}
                  onChange={(e) => set('contactDetails', e.target.value || undefined)}
                  className="px-3 py-2 rounded-xl text-sm outline-none" style={glassInner} />
              )}
            </div>
            <div className="flex items-center gap-2 mt-3">
              <button onClick={submitScreen} disabled={busy}
                className="inline-flex items-center gap-1.5 px-4 py-2 text-[11px] font-bold rounded-xl bg-red-500/10 text-red-500 hover:bg-red-500/20 transition-colors disabled:opacity-50">
                {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ShieldAlert className="w-3.5 h-3.5" />} Screen
              </button>
              <button onClick={() => setShowForm(false)}
                className={`px-4 py-2 text-[11px] font-bold rounded-xl transition-colors ${isDark ? 'text-slate-400 hover:bg-white/5' : 'text-slate-500 hover:bg-slate-100'}`}>Cancel</button>
            </div>
          </div>
        )}
      </div>

      {loading ? (
        <div className="flex items-center justify-center py-12"><Loader2 className="w-6 h-6 animate-spin text-cyan-500" /></div>
      ) : screenings.length === 0 && !error ? (
        <div className="rounded-2xl p-8 text-center" style={glassCard}>
          <CheckCircle2 className={`w-10 h-10 mx-auto mb-3 ${text.muted}`} />
          <p className={`text-sm font-bold ${text.heading}`}>No infection screening yet</p>
          <p className={`text-xs mt-1 ${text.muted}`}>Run a screening to assess isolation needs for this patient.</p>
        </div>
      ) : (
        <>
          {active.map((s) => {
            const iso = ISO_CONFIG[String(s.isolationType)] || ISO_FALLBACK;
            const place = recheckLabel(s.placementDueAt);
            const ppe = PPE_FIELDS.filter((p) => s[p.key]);
            return (
              <div key={s.id} className="rounded-2xl overflow-hidden" style={glassCard}>
                <div className={`px-5 py-3 ${iso.bg} flex items-center gap-3 flex-wrap`}>
                  <Biohazard className={`w-5 h-5 ${iso.color}`} />
                  <span className={`text-sm font-black uppercase tracking-wide ${iso.color}`}>{iso.label} ISOLATION</span>
                  <span className={`text-[10px] font-bold px-2 py-0.5 rounded-lg ${iso.bg} ${iso.color}`}>
                    {RISK_LABEL[String(s.riskLevel)] || String(s.riskLevel)}
                  </span>
                  {s.notifiableDisease && (
                    <span className="text-[10px] font-bold px-2 py-0.5 rounded-lg bg-fuchsia-500/15 text-fuchsia-400 inline-flex items-center gap-1">
                      <Megaphone className="w-3 h-3" /> NOTIFIABLE: {s.notifiableDisease.replace(/_/g, ' ')}
                    </span>
                  )}
                  {place && (
                    <span className={`ml-auto text-[10px] font-bold px-2 py-0.5 rounded-lg inline-flex items-center gap-1 ${place.overdue ? 'bg-red-600/20 text-red-600 animate-pulse' : 'bg-amber-500/15 text-amber-500'}`}>
                      <Timer className="w-3 h-3" />{place.text}
                    </span>
                  )}
                </div>
                <div className="px-5 py-4">
                  {s.suspectedCondition && <p className={`text-xs mb-2 ${text.body}`}>Suspected: <span className="font-bold">{s.suspectedCondition}</span></p>}
                  {/* PPE signage */}
                  {ppe.length > 0 && (
                    <div className="flex items-center gap-2 flex-wrap mb-2">
                      <span className={`text-[10px] font-bold uppercase ${text.muted}`}>Required PPE:</span>
                      {ppe.map((p) => (
                        <span key={p.label} className="text-[10px] font-bold px-2 py-0.5 rounded-lg bg-red-500/15 text-red-500">{p.label}</span>
                      ))}
                    </div>
                  )}
                  <div className="flex items-center gap-3 flex-wrap text-[10px]">
                    <span className={text.muted}>Screened {format(new Date(s.screenedAt), 'dd MMM HH:mm')}{s.screenedByName ? ` by ${s.screenedByName}` : ''}</span>
                    {s.isolationRoomAssigned && (
                      <span className={`inline-flex items-center gap-1 ${text.body}`}>
                        <BedDouble className="w-3 h-3 text-emerald-500" />Room {s.isolationRoomAssigned}
                        {s.isolationAssignedByName ? ` · ${s.isolationAssignedByName}` : ''}
                      </span>
                    )}
                    {s.publicHealthNotifiedAt && (
                      <span className="inline-flex items-center gap-1 text-emerald-500"><CheckCircle2 className="w-3 h-3" />RBC notified{s.publicHealthReferenceNumber ? ` (${s.publicHealthReferenceNumber})` : ''}</span>
                    )}
                  </div>

                  {/* Actions */}
                  <div className="flex items-center gap-2 flex-wrap mt-3">
                    {roomFor !== s.id && (
                      <button onClick={() => { setRoomFor(s.id); setRoom(''); }} className="inline-flex items-center gap-1.5 px-4 py-2 text-[11px] font-bold rounded-xl bg-emerald-500/10 text-emerald-500 hover:bg-emerald-500/20 transition-colors">
                        <BedDouble className="w-3.5 h-3.5" />{s.isolationRoomAssigned ? 'Change room' : 'Assign room'}
                      </button>
                    )}
                    {s.notifiableDisease && !s.publicHealthNotifiedAt && notifyFor !== s.id && (
                      <button onClick={() => { setNotifyFor(s.id); setNotifyRef(''); }} className="inline-flex items-center gap-1.5 px-4 py-2 text-[11px] font-bold rounded-xl bg-fuchsia-500/10 text-fuchsia-400 hover:bg-fuchsia-500/20 transition-colors">
                        <Megaphone className="w-3.5 h-3.5" />Notify RBC
                      </button>
                    )}
                    {endFor !== s.id && (
                      <button onClick={() => { setEndFor(s.id); setEndReason(''); }} className="inline-flex items-center gap-1.5 px-4 py-2 text-[11px] font-bold rounded-xl bg-slate-500/10 text-slate-400 hover:bg-slate-500/20 transition-colors">
                        <CheckCircle2 className="w-3.5 h-3.5" />Clear isolation
                      </button>
                    )}
                  </div>
                  {roomFor === s.id && (
                    <div className="flex items-center gap-2 mt-2">
                      <input value={room} onChange={(e) => setRoom(e.target.value)} placeholder="Isolation room / bed no." className="w-48 px-3 py-2 rounded-xl text-sm outline-none" style={glassInner} />
                      <button onClick={() => submitRoom(s.id)} disabled={!room.trim() || busy} className="px-4 py-2 text-[11px] font-bold rounded-xl bg-emerald-500/10 text-emerald-500 hover:bg-emerald-500/20 disabled:opacity-50">Save</button>
                    </div>
                  )}
                  {notifyFor === s.id && (
                    <div className="flex items-center gap-2 mt-2">
                      <input value={notifyRef} onChange={(e) => setNotifyRef(e.target.value)} placeholder="RBC reference (optional)" className="w-56 px-3 py-2 rounded-xl text-sm outline-none" style={glassInner} />
                      <button onClick={() => submitNotify(s.id)} disabled={busy} className="px-4 py-2 text-[11px] font-bold rounded-xl bg-fuchsia-500/10 text-fuchsia-400 hover:bg-fuchsia-500/20 disabled:opacity-50">Record</button>
                    </div>
                  )}
                  {endFor === s.id && (
                    <div className="flex items-center gap-2 mt-2">
                      <input value={endReason} onChange={(e) => setEndReason(e.target.value)} placeholder="Clearance reason (required)" className="w-72 px-3 py-2 rounded-xl text-sm outline-none" style={glassInner} />
                      <button onClick={() => submitEnd(s.id)} disabled={!endReason.trim() || busy} className="px-4 py-2 text-[11px] font-bold rounded-xl bg-slate-500/10 text-slate-300 hover:bg-slate-500/20 disabled:opacity-50">Clear</button>
                    </div>
                  )}
                </div>
              </div>
            );
          })}

          {history.length > 0 && (
            <div className="rounded-2xl p-5" style={glassCard}>
              <h4 className={`text-xs font-bold uppercase tracking-wider mb-2 ${text.muted}`}>Screening history ({history.length})</h4>
              <div className="space-y-2">
                {history.map((s) => (
                  <div key={s.id} className="flex items-center gap-3 text-[11px] flex-wrap">
                    <span className={text.muted}>{format(new Date(s.screenedAt), 'dd MMM HH:mm')}</span>
                    <span className={`font-bold ${(ISO_CONFIG[String(s.isolationType)] || ISO_FALLBACK).color}`}>
                      {s.isolationType ? (ISO_CONFIG[String(s.isolationType)] || ISO_FALLBACK).label : 'No isolation'}
                    </span>
                    <span className={text.muted}>{RISK_LABEL[String(s.riskLevel)] || String(s.riskLevel)}</span>
                    {s.isolationEndedAt && <span className={text.muted}>· cleared{s.isolationEndedByName ? ` by ${s.isolationEndedByName}` : ''}{s.isolationEndReason ? ` (${s.isolationEndReason})` : ''}</span>}
                  </div>
                ))}
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}
