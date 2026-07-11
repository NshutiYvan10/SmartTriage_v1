/* ═══════════════════════════════════════════════════════════════
   Monitor Management — the paramedic's device page.

   Extracted from the Siren (/ems) page so device management no longer
   hides inside the run workflow. Everything monitor-specific lives here:

     • Register / pair a field vitals monitor (one-time pairing key)
     • Power on/off        — in/out of the active pool (V53 semantics,
                             owner-operable since V99)
     • Start/stop recording — freezes the vitals snapshot between
                             patients so "Pull from my monitor" can never
                             grab the previous patient's numbers (V99)
     • Live status          — online/offline, battery, last heartbeat,
                             latest reading + its age

   The run-side "Pull from my monitor" stays in EmsRunForm — that is part
   of documenting a run, not managing a device.
   ═══════════════════════════════════════════════════════════════ */

import { useCallback, useEffect, useState } from 'react';
import {
  Radio, Plus, RefreshCw, Loader2, Copy, Check, KeyRound, Power,
  CircleDot, PauseCircle, BatteryMedium, Clock, HeartPulse, X,
} from 'lucide-react';
import { iotApi } from '@/api/iot';
import type { DeviceResponse, DeviceLatestVitalsResponse } from '@/api/types';
import { formatDistanceToNow } from 'date-fns';
import { useTheme } from '@/hooks/useTheme';

export function MonitorManagementView() {
  const { glassCard, glassInner, isDark, text } = useTheme();

  const [devices, setDevices] = useState<DeviceResponse[]>([]);
  const [vitalsByDevice, setVitalsByDevice] = useState<Record<string, DeviceLatestVitalsResponse | null>>({});
  const [loading, setLoading] = useState(true);
  const [busyDevice, setBusyDevice] = useState<string | null>(null);
  const [toast, setToast] = useState<{ type: 'ok' | 'err'; text: string } | null>(null);

  // Register form
  const [serialNumber, setSerialNumber] = useState('');
  const [deviceName, setDeviceName] = useState('');
  const [registering, setRegistering] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // The pairing key is returned ONCE by self-register — held in local state only.
  const [pairingKey, setPairingKey] = useState<{ deviceName: string; apiKey: string } | null>(null);
  const [copied, setCopied] = useState(false);

  const flash = (type: 'ok' | 'err', t: string) => {
    setToast({ type, text: t });
    setTimeout(() => setToast(null), 3500);
  };

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await iotApi.myDevices();
      const list = data || [];
      setDevices(list);
      // Latest snapshot per device, in parallel; a single failure must not
      // blank the page — that device simply shows "no reading".
      const snaps = await Promise.all(
        list.map((d) => iotApi.latestVitals(d.id).catch(() => null)),
      );
      const byId: Record<string, DeviceLatestVitalsResponse | null> = {};
      list.forEach((d, i) => { byId[d.id] = snaps[i]; });
      setVitalsByDevice(byId);
    } catch (e) {
      console.error('[MonitorManagement] load failed:', e);
      flash('err', 'Failed to load your monitors');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  // Auto-refresh the status/readings every 15s — a paramedic glances at this
  // page to confirm the device is transmitting before pulling vitals.
  useEffect(() => {
    const id = setInterval(() => { load(); }, 15_000);
    return () => clearInterval(id);
  }, [load]);

  const guardDevice = (deviceId: string, fn: () => Promise<void>) => async () => {
    if (busyDevice) return;
    setBusyDevice(deviceId);
    try { await fn(); } finally { setBusyDevice(null); }
  };

  const togglePower = (d: DeviceResponse) => guardDevice(d.id, async () => {
    try {
      await iotApi.setServiceStatus(d.id, !d.inService);
      flash('ok', d.inService ? `${d.deviceName} powered off` : `${d.deviceName} powered on`);
      await load();
    } catch (e: any) { flash('err', e?.message || 'Power toggle failed'); }
  });

  const toggleRecording = (d: DeviceResponse) => guardDevice(d.id, async () => {
    try {
      await iotApi.setRecording(d.id, !d.recordingEnabled);
      flash('ok', d.recordingEnabled ? 'Recording stopped — snapshot frozen' : 'Recording started');
      await load();
    } catch (e: any) { flash('err', e?.message || 'Recording toggle failed'); }
  });

  // Lost / leaked pairing key → issue a fresh one. The OLD key stops working
  // immediately; the new one shows once in the pairing panel below.
  const regenerateKey = (d: DeviceResponse) => guardDevice(d.id, async () => {
    try {
      const updated = await iotApi.regenerateKey(d.id);
      if (updated.apiKey) setPairingKey({ deviceName: updated.deviceName, apiKey: updated.apiKey });
      flash('ok', 'New pairing key issued — enter it in the monitor (the old key no longer works)');
    } catch (e: any) { flash('err', e?.message || 'Could not issue a new key'); }
  });

  const register = async () => {
    const sn = serialNumber.trim();
    const name = deviceName.trim();
    if (!sn || !name) { setError('Serial number and device name are both required.'); return; }
    setRegistering(true);
    setError(null);
    try {
      const created = await iotApi.selfRegisterDevice({ serialNumber: sn, deviceName: name });
      setSerialNumber('');
      setDeviceName('');
      // apiKey is present exactly once — right here.
      if (created.apiKey) setPairingKey({ deviceName: created.deviceName, apiKey: created.apiKey });
      await load();
    } catch (e: any) {
      const msg = String(e?.message ?? '');
      setError(
        /exist|duplicate|already|conflict|409/i.test(msg)
          ? 'A monitor with that serial number is already registered.'
          : (msg || 'Could not register the monitor.'),
      );
    } finally {
      setRegistering(false);
    }
  };

  const copyKey = async () => {
    if (!pairingKey) return;
    try {
      await navigator.clipboard.writeText(pairingKey.apiKey);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch { /* clipboard blocked — the key is still visible to copy manually */ }
  };

  const inputClass = `w-full px-3 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`;

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-4xl mx-auto space-y-4 animate-fade-in">

        {/* Header */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between flex-wrap gap-3">
              <div className="flex items-center gap-3">
                <div className="w-12 h-12 rounded-xl bg-cyan-500/20 flex items-center justify-center">
                  <Radio className="w-6 h-6 text-cyan-300" />
                </div>
                <div>
                  <h1 className="text-xl font-bold text-white tracking-wide">Monitor Management</h1>
                  <p className="text-white/60 text-sm">
                    Register, power and control your field vitals monitors
                  </p>
                </div>
              </div>
              <button onClick={load} className="w-11 h-11 rounded-xl bg-white/15 flex items-center justify-center hover:bg-white/25" title="Refresh">
                <RefreshCw className={`w-5 h-5 text-white ${loading ? 'animate-spin' : ''}`} />
              </button>
            </div>
          </div>
        </div>

        {toast && (
          <div className={`flex items-center gap-3 px-4 py-3 rounded-2xl text-sm font-semibold animate-fade-up ${
            toast.type === 'ok' ? 'bg-emerald-500/15 text-emerald-500 border border-emerald-500/20'
              : 'bg-rose-500/15 text-rose-500 border border-rose-500/20'}`}>
            <span className="flex-1">{toast.text}</span>
            <button type="button" onClick={() => setToast(null)} aria-label="Dismiss notification" className="p-0.5 rounded hover:opacity-70"><X className="w-3.5 h-3.5" /></button>
          </div>
        )}

        {/* Devices */}
        {loading && devices.length === 0 ? (
          <div className="flex items-center justify-center py-10"><Loader2 className="w-7 h-7 animate-spin text-cyan-500" /></div>
        ) : devices.length === 0 ? (
          <div className="rounded-2xl p-8 text-center" style={glassCard}>
            <Radio className="w-10 h-10 mx-auto mb-3 text-slate-400" />
            <p className={`text-base font-bold ${text.heading}`}>No monitors registered</p>
            <p className={`text-sm ${text.muted}`}>Register your vitals monitor below, then pair it with the one-time key.</p>
          </div>
        ) : (
          <div className="space-y-3">
            {devices.map((d) => {
              const v = vitalsByDevice[d.id];
              const busy = busyDevice === d.id;
              const online = d.status === 'ONLINE' || d.status === 'MONITORING';
              return (
                <div key={d.id} className={`rounded-2xl p-4 ${!d.inService ? 'opacity-75' : ''}`} style={glassCard}>
                  {/* Identity + status chips */}
                  <div className="flex items-start justify-between gap-2 mb-3 flex-wrap">
                    <div className="min-w-0">
                      <div className={`text-base font-bold truncate ${text.heading}`}>{d.deviceName}</div>
                      <div className={`text-xs font-mono truncate ${text.muted}`}>{d.serialNumber}</div>
                    </div>
                    <div className="flex items-center gap-1.5 flex-wrap">
                      <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-1 rounded-lg ${
                        online ? 'text-emerald-600' : 'text-slate-600'}`}
                        style={online
                          ? { background: 'rgba(16,185,129,0.08)', border: '1px solid rgba(16,185,129,0.2)' }
                          : { background: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)' }}>
                        {d.status}
                      </span>
                      <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-1 rounded-lg ${
                        d.inService ? 'text-cyan-600' : 'text-rose-600'}`}
                        style={d.inService
                          ? { background: 'rgba(6,182,212,0.08)', border: '1px solid rgba(6,182,212,0.2)' }
                          : { background: 'rgba(244,63,94,0.08)', border: '1px solid rgba(244,63,94,0.2)' }}>
                        {d.inService ? 'Powered on' : 'Powered off'}
                      </span>
                      <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-1 rounded-lg inline-flex items-center gap-1 ${
                        d.recordingEnabled ? 'text-rose-600' : 'text-amber-600'}`}
                        style={d.recordingEnabled
                          ? { background: 'rgba(244,63,94,0.08)', border: '1px solid rgba(244,63,94,0.2)' }
                          : { background: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.2)' }}>
                        {d.recordingEnabled ? <><CircleDot className="w-3 h-3 animate-pulse" /> Recording</> : <><PauseCircle className="w-3 h-3" /> Paused</>}
                      </span>
                    </div>
                  </div>

                  {/* Runtime metadata */}
                  <div className={`text-xs flex items-center gap-4 flex-wrap mb-3 ${text.muted}`}>
                    {d.batteryLevel != null && (
                      <span className="inline-flex items-center gap-1"><BatteryMedium className="w-3.5 h-3.5" /> {d.batteryLevel}%</span>
                    )}
                    {d.lastHeartbeatAt && (
                      <span className="inline-flex items-center gap-1">
                        <Clock className="w-3.5 h-3.5" /> Heartbeat {formatDistanceToNow(new Date(d.lastHeartbeatAt), { addSuffix: true })}
                      </span>
                    )}
                    {d.lastDataAt && (
                      <span className="inline-flex items-center gap-1">
                        <Radio className="w-3.5 h-3.5" /> Data {formatDistanceToNow(new Date(d.lastDataAt), { addSuffix: true })}
                      </span>
                    )}
                  </div>

                  {/* Latest reading */}
                  <div className="rounded-xl px-3 py-2.5 mb-3" style={glassInner}>
                    <div className={`text-[10px] uppercase font-bold mb-1 inline-flex items-center gap-1 ${text.label}`}>
                      <HeartPulse className="w-3 h-3" /> Latest reading
                      {v?.hasReading && v.ageSeconds != null && (
                        <span className={`normal-case font-medium ${v.ageSeconds > 120 ? 'text-amber-500' : ''}`}>
                          · {v.ageSeconds < 60 ? `${v.ageSeconds}s ago` : `${Math.floor(v.ageSeconds / 60)}m ago`}
                        </span>
                      )}
                    </div>
                    {v?.hasReading ? (
                      <div className="grid grid-cols-4 sm:grid-cols-7 gap-2 text-sm">
                        <MiniStat label="HR" value={v.heartRate} text={text} />
                        <MiniStat label="RR" value={v.respiratoryRate} text={text} />
                        <MiniStat label="SpO₂" value={v.spo2 != null ? `${v.spo2}%` : null} text={text} />
                        <MiniStat label="BP" value={v.systolicBp != null ? `${v.systolicBp}/${v.diastolicBp ?? '—'}` : null} text={text} />
                        <MiniStat label="Temp" value={v.temperature != null ? `${v.temperature}°` : null} text={text} />
                        <MiniStat label="Gluc" value={v.glucose} text={text} />
                      </div>
                    ) : (
                      <div className={`text-sm ${text.muted}`}>No reading received yet.</div>
                    )}
                  </div>

                  {/* Controls */}
                  <div className="flex flex-wrap gap-2">
                    <button onClick={togglePower(d)} disabled={busy}
                      className={`inline-flex items-center gap-1.5 px-4 py-2.5 rounded-xl text-sm font-bold disabled:opacity-50 ${
                        d.inService
                          ? (isDark ? 'bg-white/10 text-white hover:bg-white/15' : 'bg-slate-100 text-slate-700 hover:bg-slate-200')
                          : 'bg-cyan-600 text-white hover:bg-cyan-700'}`}>
                      {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : <Power className="w-4 h-4" />}
                      {d.inService ? 'Power off' : 'Power on'}
                    </button>
                    <button onClick={toggleRecording(d)} disabled={busy || !d.inService}
                      title={!d.inService ? 'Power the monitor on first' : undefined}
                      className={`inline-flex items-center gap-1.5 px-4 py-2.5 rounded-xl text-sm font-bold disabled:opacity-50 ${
                        d.recordingEnabled
                          ? 'bg-amber-500 text-white hover:bg-amber-600'
                          : 'bg-rose-500 text-white hover:bg-rose-600'}`}>
                      {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : d.recordingEnabled ? <PauseCircle className="w-4 h-4" /> : <CircleDot className="w-4 h-4" />}
                      {d.recordingEnabled ? 'Stop recording' : 'Start recording'}
                    </button>
                    <button onClick={regenerateKey(d)} disabled={busy}
                      title="Issue a fresh pairing key (the old one stops working immediately)"
                      className={`inline-flex items-center gap-1.5 px-4 py-2.5 rounded-xl text-sm font-bold disabled:opacity-50 ${
                        isDark ? 'bg-white/10 text-white hover:bg-white/15' : 'bg-slate-100 text-slate-700 hover:bg-slate-200'}`}>
                      <KeyRound className="w-4 h-4" /> New pairing key
                    </button>
                  </div>
                  {!d.recordingEnabled && (
                    <p className={`text-xs mt-2 ${text.muted}`}>
                      Recording is paused — the snapshot is frozen, so “Pull from my monitor” keeps the last
                      recorded values and won't pick up new readings until you start recording again.
                    </p>
                  )}
                </div>
              );
            })}
          </div>
        )}

        {/* Pairing key — shown ONCE right after register */}
        {pairingKey && (
          <div className="rounded-2xl p-4 ring-2 ring-cyan-500/40 bg-cyan-500/10 space-y-2 animate-fade-up">
            <div className="flex items-center gap-2">
              <KeyRound className="w-4 h-4 text-cyan-500 shrink-0" />
              <span className={`text-sm font-bold ${text.heading}`}>Pairing key for {pairingKey.deviceName}</span>
            </div>
            <p className={`text-xs ${text.muted}`}>
              Enter this in your monitor — <b>shown only once</b>. Copy it now; you can't retrieve it later.
            </p>
            <div className="flex items-center gap-2">
              <code className={`flex-1 min-w-0 px-3 py-2 rounded-lg text-sm font-mono break-all ${isDark ? 'bg-black/30 text-cyan-200' : 'bg-white text-cyan-700'}`} style={glassInner}>
                {pairingKey.apiKey}
              </code>
              <button onClick={copyKey}
                className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-bold bg-cyan-600 text-white hover:bg-cyan-700 shrink-0">
                {copied ? <Check className="w-4 h-4" /> : <Copy className="w-4 h-4" />} {copied ? 'Copied' : 'Copy'}
              </button>
            </div>
          </div>
        )}

        {/* Register a new monitor */}
        <div className="rounded-2xl p-4 space-y-3" style={glassCard}>
          <h2 className={`text-base font-bold ${text.heading}`}>
            <Plus className="w-5 h-5 inline mr-1.5 text-cyan-500" />
            Register a monitor
          </h2>
          <p className={`text-sm ${text.muted}`}>
            Register your vitals monitor once, pair it with the one-time key, then pull its readings
            straight into a run's field vitals from the Siren page.
          </p>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
            <input value={serialNumber} onChange={(e) => setSerialNumber(e.target.value)} placeholder="Serial number"
              className={inputClass} style={glassInner} />
            <input value={deviceName} onChange={(e) => setDeviceName(e.target.value)} placeholder="Device name (e.g. SAMU-K7 monitor)"
              className={inputClass} style={glassInner} />
          </div>
          {error && (
            <div className="rounded-xl px-3 py-2 text-sm font-semibold bg-rose-500/10 text-rose-500">{error}</div>
          )}
          <button onClick={register} disabled={registering || !serialNumber.trim() || !deviceName.trim()}
            className="inline-flex items-center gap-1.5 px-4 py-2.5 rounded-xl text-sm font-bold bg-cyan-600 text-white hover:bg-cyan-700 disabled:opacity-50">
            {registering ? <Loader2 className="w-4 h-4 animate-spin" /> : <Plus className="w-4 h-4" />} Register monitor
          </button>
        </div>
      </div>
    </div>
  );
}

function MiniStat({ label, value, text }: { label: string; value: string | number | null | undefined; text: any }) {
  return (
    <div>
      <div className={`text-[10px] uppercase font-bold ${text.label}`}>{label}</div>
      <div className={`font-bold ${text.heading}`}>{value ?? '—'}</div>
    </div>
  );
}

export default MonitorManagementView;
