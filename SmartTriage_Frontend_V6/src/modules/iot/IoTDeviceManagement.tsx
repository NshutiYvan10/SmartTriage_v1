/* ═══════════════════════════════════════════════════════════════
   IoT Device Management — Register, monitor, manage devices
   with device-to-patient (visit) binding per KFH ED model.
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback, useMemo } from 'react';
import {
  Cpu, Plus, RefreshCw, Loader2, Power, PowerOff, Wifi,
  WifiOff, Settings, Activity, Send, XCircle,
  UserCheck, Users, X, Search, Heart, AlertTriangle, Monitor,
  BatteryFull, Zap, Copy, CheckCircle, Key, BedDouble,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { usePatientStore } from '@/store/patientStore';
import { useDeviceStore } from '@/store/deviceStore';
import { useBedStore } from '@/store/bedStore';
import { iotApi } from '@/api/iot';
import { hospitalApi } from '@/api/hospitals';
import type { DeviceResponse, DeviceSessionResponse, HospitalResponse, BedResponse } from '@/api/types';
import { format } from 'date-fns';

const STATUS_CONFIG: Record<string, { color: string; bg: string; icon: typeof Wifi }> = {
  REGISTERED:   { color: 'text-slate-500', bg: 'bg-slate-500/10', icon: WifiOff },
  ONLINE:       { color: 'text-emerald-500', bg: 'bg-emerald-500/10', icon: Wifi },
  OFFLINE:      { color: 'text-slate-500', bg: 'bg-slate-500/10', icon: WifiOff },
  MONITORING:   { color: 'text-cyan-500', bg: 'bg-cyan-500/10', icon: Activity },
  ERROR:        { color: 'text-red-500', bg: 'bg-red-500/10', icon: XCircle },
  DECOMMISSIONED: { color: 'text-amber-500', bg: 'bg-amber-500/10', icon: Settings },
};

const DEVICE_TYPES = ['ESP32_MONITOR', 'PULSE_OXIMETER', 'ECG_MONITOR', 'BP_MONITOR', 'TEMPERATURE_PROBE', 'GLUCOMETER', 'AMBULANCE_MONITOR', 'OTHER'];

export function IoTDeviceManagement() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const user = useAuthStore((s) => s.user);
  const patients = usePatientStore((s) => s.patients);
  const userHospitalId = user?.hospitalId || 'a0000000-0000-0000-0000-000000000001';

  // Role checks
  const isAdmin = useMemo(() => {
    const role = user?.role;
    return role === 'SUPER_ADMIN' || role === 'HOSPITAL_ADMIN';
  }, [user?.role]);

  const isSuperAdmin = user?.role === 'SUPER_ADMIN';

  const isClinicalStaff = useMemo(() => {
    const role = user?.role;
    return role === 'NURSE' || role === 'DOCTOR';
  }, [user?.role]);

  // Hospital selector state (SUPER_ADMIN can pick any hospital)
  const [hospitals, setHospitals] = useState<HospitalResponse[]>([]);
  const [selectedHospitalId, setSelectedHospitalId] = useState(userHospitalId);

  // The effective hospitalId used for loading devices and registering
  const hospitalId = isSuperAdmin ? selectedHospitalId : userHospitalId;

  const [devices, setDevices] = useState<DeviceResponse[]>([]);
  const [sessions, setSessions] = useState<DeviceSessionResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [formLoading, setFormLoading] = useState(false);
  const [formError, setFormError] = useState('');
  const [form, setForm] = useState({ deviceName: '', deviceType: 'ESP32_MONITOR', serialNumber: '', firmwareVersion: '', location: '' });

  // Power operation feedback
  const [poweringDeviceId, setPoweringDeviceId] = useState<string | null>(null);
  const [powerError, setPowerError] = useState<{ deviceId: string; message: string } | null>(null);

  // API key display after registration (only shown once)
  const [registeredDevice, setRegisteredDevice] = useState<{ deviceName: string; serialNumber: string; apiKey: string } | null>(null);
  const [apiKeyCopied, setApiKeyCopied] = useState(false);

  // Assign-to-patient dialog
  const [assignDevice, setAssignDevice] = useState<DeviceResponse | null>(null);
  const [patientSearch, setPatientSearch] = useState('');
  const [assignLoading, setAssignLoading] = useState(false);
  const [assigningPatientId, setAssigningPatientId] = useState<string | null>(null);
  const [assignError, setAssignError] = useState('');

  // Assign-device-to-bed dialog
  const [bedAssignDevice, setBedAssignDevice] = useState<DeviceResponse | null>(null);
  const [bedAssignLoading, setBedAssignLoading] = useState(false);
  const [bedAssignTargetId, setBedAssignTargetId] = useState<string | null>(null);
  const [bedAssignError, setBedAssignError] = useState('');
  const [bedSearch, setBedSearch] = useState('');

  // ── Bed store ── subscribe to beds so device cards know which bed owns a device.
  const loadHospitalBeds = useBedStore((s) => s.loadHospital);
  const bedsMap = useBedStore((s) => s.beds);
  const assignDeviceToBed = useBedStore((s) => s.assignDevice);
  useEffect(() => {
    if (hospitalId) loadHospitalBeds(hospitalId).catch(() => {});
  }, [hospitalId, loadHospitalBeds]);
  const bedByDeviceId = useMemo(() => {
    const idx = new Map<string, BedResponse>();
    bedsMap.forEach((b) => { if (b.assignedDeviceId) idx.set(b.assignedDeviceId, b); });
    return idx;
  }, [bedsMap]);
  const allBeds = useMemo(() => Array.from(bedsMap.values()).sort((a, b) => {
    if (a.zone !== b.zone) return a.zone.localeCompare(b.zone);
    return a.code.localeCompare(b.code);
  }), [bedsMap]);

  const loadDevices = useCallback(async () => {
    setLoading(true);
    try {
      const [deviceData, sessionData] = await Promise.all([
        iotApi.getDevicesByHospital(hospitalId, 0, 100),
        iotApi.getActiveSessions(hospitalId).catch(() => []),
      ]);
      setDevices(deviceData.content || []);
      setSessions(sessionData || []);
    } catch (err) {
      console.error('Failed to load devices:', err);
      setDevices([]);
    } finally {
      setLoading(false);
    }
  }, [hospitalId]);

  useEffect(() => { loadDevices(); }, [loadDevices]);

  // Fetch hospitals list for SUPER_ADMIN hospital selector
  useEffect(() => {
    if (isSuperAdmin) {
      hospitalApi.getAll(0, 100)
        .then(data => setHospitals(data.content || []))
        .catch(() => setHospitals([]));
    }
  }, [isSuperAdmin]);

  const handleRegister = async () => {
    if (!form.deviceName || !form.serialNumber) return;
    setFormLoading(true);
    setFormError('');
    try {
      const response = await iotApi.registerDevice({ hospitalId, ...form } as any);
      setShowForm(false);
      setForm({ deviceName: '', deviceType: 'ESP32_MONITOR', serialNumber: '', firmwareVersion: '', location: '' });
      // Show the API key modal (only time it's returned)
      if (response?.apiKey) {
        setRegisteredDevice({ deviceName: response.deviceName, serialNumber: response.serialNumber, apiKey: response.apiKey });
        setApiKeyCopied(false);
      }
      loadDevices();
    } catch (err: any) {
      console.error(err);
      setFormError(err?.message || 'Failed to register device. Check your permissions and try again.');
    } finally {
      setFormLoading(false);
    }
  };

  const handleCopyApiKey = async () => {
    if (!registeredDevice?.apiKey) return;
    try {
      await navigator.clipboard.writeText(registeredDevice.apiKey);
      setApiKeyCopied(true);
      setTimeout(() => setApiKeyCopied(false), 3000);
    } catch {
      // Fallback for older browsers
      const textarea = document.createElement('textarea');
      textarea.value = registeredDevice.apiKey;
      document.body.appendChild(textarea);
      textarea.select();
      document.execCommand('copy');
      document.body.removeChild(textarea);
      setApiKeyCopied(true);
      setTimeout(() => setApiKeyCopied(false), 3000);
    }
  };

  const handleStartMonitoring = async (device: DeviceResponse) => {
    // Open patient selection dialog
    setAssignDevice(device);
    setPatientSearch('');
  };

  const handlePowerOn = async (device: DeviceResponse) => {
    setPoweringDeviceId(device.id);
    setPowerError(null);
    try {
      await iotApi.powerOnDevice(device.id);
      await loadDevices();
    } catch (err: any) {
      console.error('Failed to power on device:', err);
      setPowerError({ deviceId: device.id, message: err?.message || 'Failed to power on device. Make sure the server is running.' });
    } finally {
      setPoweringDeviceId(null);
    }
  };

  const handlePowerOff = async (device: DeviceResponse) => {
    setPoweringDeviceId(device.id);
    setPowerError(null);
    try {
      await iotApi.powerOffDevice(device.id);
      await loadDevices();
    } catch (err: any) {
      console.error('Failed to power off device:', err);
      setPowerError({ deviceId: device.id, message: err?.message || 'Failed to power off device.' });
    } finally {
      setPoweringDeviceId(null);
    }
  };

  const handleAssignToPatient = async (visitId: string) => {
    if (!assignDevice) return;
    setAssignLoading(true);
    setAssigningPatientId(visitId);
    setAssignError('');
    try {
      await iotApi.startMonitoring({
        deviceId: assignDevice.id,
        visitId,
        startedByName: user?.fullName,
      });
      setAssignDevice(null);
      setAssigningPatientId(null);
      loadDevices();
      // Refresh the global Zustand device store so VitalMonitoring sees the assignment
      useDeviceStore.getState().fetchDevicesFromApi(hospitalId);
    } catch (err: any) {
      console.error('Failed to start monitoring:', err);
      setAssignError(err?.message || 'Failed to assign device. Please check your connection and try again.');
      setAssigningPatientId(null);
    } finally {
      setAssignLoading(false);
    }
  };

  const handleAssignToBed = async (bedId: string) => {
    if (!bedAssignDevice) return;
    setBedAssignLoading(true);
    setBedAssignTargetId(bedId);
    setBedAssignError('');
    try {
      await assignDeviceToBed(bedId, { deviceId: bedAssignDevice.id });
      setBedAssignDevice(null);
      setBedAssignTargetId(null);
      // Refresh device list so any status change (e.g., MONITORING) propagates.
      loadDevices();
    } catch (err: any) {
      console.error('Failed to assign device to bed:', err);
      setBedAssignError(err?.message || 'Failed to assign device to bed.');
      setBedAssignTargetId(null);
    } finally {
      setBedAssignLoading(false);
    }
  };

  const handleUnassignFromBed = async (bedId: string) => {
    setBedAssignLoading(true);
    setBedAssignError('');
    try {
      await assignDeviceToBed(bedId, { deviceId: null });
      loadDevices();
    } catch (err: any) {
      console.error('Failed to unassign device from bed:', err);
      setBedAssignError(err?.message || 'Failed to unassign device.');
    } finally {
      setBedAssignLoading(false);
    }
  };

  const handleStopMonitoring = async (sessionId: string) => {
    try {
      await iotApi.stopMonitoring(sessionId, user?.fullName || 'System', 'Manual disconnect');
      loadDevices();
      // Refresh the global Zustand device store
      useDeviceStore.getState().fetchDevicesFromApi(hospitalId);
    } catch (err) {
      console.error(err);
    }
  };

  // Helper: get the session for a device in MONITORING state
  const getSessionForDevice = (deviceId: string) => sessions.find(s => s.deviceId === deviceId && s.sessionActive);
  // Helper: get patient name for a visit
  const getPatientForVisit = (visitId: string) => patients.find(p => p.id === visitId);
  // Filtered patients for the assign dialog
  const filteredPatients = patients.filter(p => {
    if (!patientSearch.trim()) return true;
    const q = patientSearch.toLowerCase();
    return p.fullName.toLowerCase().includes(q) || p.chiefComplaint?.toLowerCase().includes(q);
  });

  const activeCount = devices.filter(d => d.status === 'ONLINE' || d.status === 'MONITORING').length;
  const monitoringCount = devices.filter(d => d.status === 'MONITORING').length;

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-6xl mx-auto space-y-4 animate-fade-in">

        {/* Header */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between flex-wrap gap-3">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-cyan-500/20 flex items-center justify-center">
                  <Cpu className="w-5 h-5 text-cyan-400" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">IoT Device Management</h1>
                  <p className="text-white/50 text-xs">Register and manage medical monitoring devices</p>
                </div>
              </div>
              <div className="flex items-center gap-3 flex-wrap">
                {/* Hospital Selector — SUPER_ADMIN can view any hospital's devices */}
                {isSuperAdmin && hospitals.length > 1 && (
                  <select
                    value={selectedHospitalId}
                    onChange={(e) => setSelectedHospitalId(e.target.value)}
                    className="px-3 py-1.5 rounded-lg bg-white/10 border border-white/20 text-white text-xs font-bold outline-none cursor-pointer hover:bg-white/15 transition-colors"
                  >
                    {hospitals.map(h => (
                      <option key={h.id} value={h.id} className="bg-slate-800 text-white">{h.name}</option>
                    ))}
                  </select>
                )}
                <div className="px-3 py-1.5 rounded-lg bg-emerald-500/20 border border-emerald-500/30">
                  <span className="text-emerald-400 text-xs font-bold">{activeCount} Active</span>
                </div>
                <div className="px-3 py-1.5 rounded-lg bg-cyan-500/20 border border-cyan-500/30">
                  <span className="text-cyan-400 text-xs font-bold">{monitoringCount} Monitoring</span>
                </div>
                <button onClick={loadDevices} className="w-9 h-9 rounded-xl bg-white/10 flex items-center justify-center hover:bg-white/20 transition-colors">
                  <RefreshCw className="w-4 h-4 text-white" />
                </button>
                {isAdmin && (
                  <button onClick={() => setShowForm(!showForm)} className="inline-flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-cyan-500 to-cyan-600 text-white rounded-xl text-xs font-bold shadow-lg hover:-translate-y-0.5 transition-all">
                    <Plus className="w-3.5 h-3.5" /> Register Device
                  </button>
                )}
              </div>
            </div>
          </div>
        </div>

        {/* Register Form — Admin only */}
        {showForm && isAdmin && (
          <div className="rounded-2xl p-5 animate-fade-up" style={glassCard}>
            <h4 className={`text-sm font-bold mb-4 ${text.heading}`}>Register New Device</h4>
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
              {/* Hospital Selector — SUPER_ADMIN only */}
              {isSuperAdmin && hospitals.length > 0 && (
                <div className="md:col-span-2 lg:col-span-3">
                  <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Register To Hospital</label>
                  <select
                    value={selectedHospitalId}
                    onChange={(e) => setSelectedHospitalId(e.target.value)}
                    className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none font-medium ${isDark ? 'text-white' : 'text-slate-800'}`}
                    style={glassInner}
                  >
                    {hospitals.map(h => (
                      <option key={h.id} value={h.id}>{h.name} ({h.hospitalCode})</option>
                    ))}
                  </select>
                </div>
              )}
              <div>
                <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Device Name</label>
                <input value={form.deviceName} onChange={(e) => setForm({ ...form, deviceName: e.target.value })} placeholder="e.g., Ward A Monitor 1" className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`} style={glassInner} />
              </div>
              <div>
                <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Device Type</label>
                <select value={form.deviceType} onChange={(e) => setForm({ ...form, deviceType: e.target.value })} className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white' : 'text-slate-800'}`} style={glassInner}>
                  {DEVICE_TYPES.map(t => <option key={t} value={t}>{t.replace(/_/g, ' ')}</option>)}
                </select>
              </div>
              <div>
                <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Serial Number</label>
                <input value={form.serialNumber} onChange={(e) => setForm({ ...form, serialNumber: e.target.value })} placeholder="e.g., SN-12345678" className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`} style={glassInner} />
              </div>
              <div>
                <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Firmware Version</label>
                <input value={form.firmwareVersion} onChange={(e) => setForm({ ...form, firmwareVersion: e.target.value })} placeholder="e.g., v2.1.0" className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`} style={glassInner} />
              </div>
              <div>
                <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Location</label>
                <input value={form.location} onChange={(e) => setForm({ ...form, location: e.target.value })} placeholder="e.g., Ward A, Bed 3" className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`} style={glassInner} />
              </div>
            </div>
            <div className="flex items-center gap-3 mt-4">
              {formError && (
                <div className="flex-1 px-3 py-2 rounded-lg bg-red-500/10 border border-red-500/30 text-red-400 text-xs font-medium">
                  {formError}
                </div>
              )}
              <button onClick={handleRegister} disabled={formLoading || !form.deviceName || !form.serialNumber} className="inline-flex items-center gap-2 px-5 py-2.5 bg-gradient-to-r from-slate-800 to-slate-700 text-white rounded-xl text-xs font-bold shadow-lg hover:-translate-y-0.5 transition-all disabled:opacity-50">
                {formLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Send className="w-3.5 h-3.5" />} Register
              </button>
              <button onClick={() => setShowForm(false)} className={`px-4 py-2.5 text-xs font-bold rounded-xl ${text.muted}`}>Cancel</button>
            </div>
          </div>
        )}

        {/* Device Grid */}
        {loading ? (
          <div className="flex items-center justify-center py-12">
            <Loader2 className="w-6 h-6 animate-spin text-cyan-500" />
          </div>
        ) : devices.length === 0 ? (
          <div className="rounded-2xl p-8 text-center animate-fade-up" style={glassCard}>
            <Cpu className="w-10 h-10 mx-auto mb-3 text-slate-400" />
            <p className={`text-sm font-bold ${text.heading}`}>No devices registered</p>
            <p className={text.muted}>Register your first medical monitoring device to get started</p>
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {devices.map((device, i) => {
              const status = STATUS_CONFIG[device.status] || STATUS_CONFIG.OFFLINE;
              const Icon = status.icon;
              return (
                <div
                  key={device.id}
                  className="rounded-2xl p-4 animate-fade-up"
                  style={{ ...glassCard, animationDelay: `${i * 0.03}s` }}
                >
                  <div className="flex items-start justify-between mb-3">
                    <div className={`w-10 h-10 rounded-xl ${status.bg} flex items-center justify-center`}>
                      <Icon className={`w-5 h-5 ${status.color}`} />
                    </div>
                    <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg ${status.bg} ${status.color}`}>
                      {device.status?.replace(/_/g, ' ')}
                    </span>
                  </div>

                  <h4 className={`text-sm font-bold mb-1 ${text.heading}`}>{device.deviceName}</h4>
                  <p className={`text-[10px] font-bold uppercase tracking-wider mb-3 ${text.muted}`}>{device.deviceType?.replace(/_/g, ' ')}</p>

                  <div className="space-y-1.5">
                    <div className="flex items-center justify-between">
                      <span className={`text-[10px] ${text.muted}`}>Serial</span>
                      <span className={`text-[10px] font-mono font-bold ${isDark ? 'text-white' : 'text-slate-800'}`}>{device.serialNumber}</span>
                    </div>
                    {device.firmwareVersion && (
                      <div className="flex items-center justify-between">
                        <span className={`text-[10px] ${text.muted}`}>Firmware</span>
                        <span className={`text-[10px] font-mono ${isDark ? 'text-white' : 'text-slate-800'}`}>{device.firmwareVersion}</span>
                      </div>
                    )}
                    {device.location && (
                      <div className="flex items-center justify-between">
                        <span className={`text-[10px] ${text.muted}`}>Location</span>
                        <span className={`text-[10px] font-bold ${isDark ? 'text-white' : 'text-slate-800'}`}>{device.location}</span>
                      </div>
                    )}
                    {device.lastHeartbeatAt && (
                      <div className="flex items-center justify-between">
                        <span className={`text-[10px] ${text.muted}`}>Last Heartbeat</span>
                        <span className={`text-[10px] ${isDark ? 'text-white' : 'text-slate-800'}`}>{format(new Date(device.lastHeartbeatAt), 'HH:mm:ss')}</span>
                      </div>
                    )}
                  </div>

                  {/* Battery & WiFi indicators */}
                  {(device.batteryLevel != null || device.wifiRssi != null) && (
                    <div className="flex items-center gap-3 mt-3 pt-3" style={{ borderTop: isDark ? '1px solid rgba(2,132,199,0.08)' : '1px solid rgba(203,213,225,0.15)' }}>
                      {device.batteryLevel != null && (
                        <div className="flex items-center gap-1.5">
                          <BatteryFull className={`w-3.5 h-3.5 ${device.batteryLevel > 50 ? 'text-emerald-500' : device.batteryLevel > 20 ? 'text-amber-500' : 'text-red-500'}`} />
                          <span className={`text-[10px] font-bold ${device.batteryLevel > 50 ? 'text-emerald-500' : device.batteryLevel > 20 ? 'text-amber-500' : 'text-red-500'}`}>{device.batteryLevel}%</span>
                        </div>
                      )}
                      {device.wifiRssi != null && (
                        <div className="flex items-center gap-1.5">
                          <Wifi className={`w-3.5 h-3.5 ${device.wifiRssi > -50 ? 'text-emerald-500' : device.wifiRssi > -70 ? 'text-amber-500' : 'text-red-500'}`} />
                          <span className={`text-[10px] font-bold ${device.wifiRssi > -50 ? 'text-emerald-500' : device.wifiRssi > -70 ? 'text-amber-500' : 'text-red-500'}`}>{device.wifiRssi} dBm</span>
                        </div>
                      )}
                    </div>
                  )}

                  {/* Power error for this device */}
                  {powerError?.deviceId === device.id && (
                    <div className="flex items-center gap-1.5 mt-2 px-2.5 py-1.5 rounded-lg bg-red-500/10 border border-red-500/20">
                      <AlertTriangle className="w-3.5 h-3.5 text-red-400 flex-shrink-0" />
                      <p className="text-[10px] font-medium text-red-400">{powerError.message}</p>
                    </div>
                  )}

                  {/* Actions — role-based */}
                  <div className="flex flex-col gap-2 mt-3 pt-3" style={{ borderTop: isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.2)' }}>
                    {/* Admin: Power On for REGISTERED / OFFLINE devices */}
                    {isAdmin && (device.status === 'REGISTERED' || device.status === 'OFFLINE') && (
                      <button
                        onClick={() => handlePowerOn(device)}
                        disabled={poweringDeviceId === device.id}
                        className="flex-1 px-3 py-1.5 text-[10px] font-bold rounded-lg bg-emerald-500/10 text-emerald-500 hover:bg-emerald-500/20 transition-colors flex items-center justify-center gap-1 disabled:opacity-50"
                      >
                        {poweringDeviceId === device.id ? <Loader2 className="w-3 h-3 animate-spin" /> : <Zap className="w-3 h-3" />} Power On
                      </button>
                    )}
                    {/* Admin: Power Off for ONLINE devices */}
                    {isAdmin && device.status === 'ONLINE' && (
                      <button
                        onClick={() => handlePowerOff(device)}
                        disabled={poweringDeviceId === device.id}
                        className="flex-1 px-3 py-1.5 text-[10px] font-bold rounded-lg bg-red-500/10 text-red-500 hover:bg-red-500/20 transition-colors flex items-center justify-center gap-1 disabled:opacity-50"
                      >
                        {poweringDeviceId === device.id ? <Loader2 className="w-3 h-3 animate-spin" /> : <PowerOff className="w-3 h-3" />} Power Off
                      </button>
                    )}
                    {/* Clinical staff (Nurse): Assign to Patient for ONLINE devices */}
                    {isClinicalStaff && device.status === 'ONLINE' && (
                      <button onClick={() => handleStartMonitoring(device)} className="flex-1 px-3 py-1.5 text-[10px] font-bold rounded-lg bg-cyan-500/10 text-cyan-500 hover:bg-cyan-500/20 transition-colors flex items-center justify-center gap-1">
                        <Power className="w-3 h-3" /> Assign to Patient
                      </button>
                    )}
                    {/* Current bed assignment (if any) + Assign/Re-assign to bed — for admin & clinical staff */}
                    {(() => {
                      const attachedBed = bedByDeviceId.get(device.id);
                      if (attachedBed) {
                        return (
                          <div className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg border border-emerald-500/20 bg-emerald-500/10">
                            <BedDouble className="w-3.5 h-3.5 text-emerald-500 flex-shrink-0" />
                            <div className="flex-1 min-w-0">
                              <p className="text-[10px] font-bold text-emerald-600">
                                Bed {attachedBed.code} · {attachedBed.zone}
                              </p>
                              {attachedBed.label && (
                                <p className="text-[9px] text-emerald-500/80 truncate">{attachedBed.label}</p>
                              )}
                            </div>
                            {(isAdmin || isClinicalStaff) && (
                              <button
                                onClick={() => handleUnassignFromBed(attachedBed.id)}
                                disabled={bedAssignLoading}
                                className="text-[9px] font-bold text-emerald-500 hover:text-emerald-600 disabled:opacity-50"
                                title="Detach this device from the bed"
                              >
                                Detach
                              </button>
                            )}
                          </div>
                        );
                      }
                      return (isAdmin || isClinicalStaff) && device.status !== 'DECOMMISSIONED' ? (
                        <button
                          onClick={() => { setBedAssignDevice(device); setBedSearch(''); setBedAssignError(''); }}
                          className="flex-1 px-3 py-1.5 text-[10px] font-bold rounded-lg bg-emerald-500/10 text-emerald-600 hover:bg-emerald-500/20 border border-emerald-500/20 transition-colors flex items-center justify-center gap-1"
                        >
                          <BedDouble className="w-3 h-3" /> Assign to Bed
                        </button>
                      ) : null;
                    })()}
                    {/* Nurse hint: device not powered on yet */}
                    {isClinicalStaff && (device.status === 'REGISTERED' || device.status === 'OFFLINE') && (
                      <div className={`flex items-center gap-1.5 px-2.5 py-2 rounded-lg ${isDark ? 'bg-amber-500/10 border border-amber-500/20' : 'bg-amber-50 border border-amber-200'}`}>
                        <AlertTriangle className={`w-3.5 h-3.5 flex-shrink-0 ${isDark ? 'text-amber-400' : 'text-amber-500'}`} />
                        <p className={`text-[10px] font-medium ${isDark ? 'text-amber-300' : 'text-amber-600'}`}>
                          Device not powered on — ask admin to activate
                        </p>
                      </div>
                    )}
                    {device.status === 'MONITORING' && (() => {
                      const session = getSessionForDevice(device.id);
                      const pt = session ? getPatientForVisit(session.visitId) : null;
                      return (
                        <div className="flex-1 space-y-1.5">
                          {pt && (
                            <div className={`flex items-center gap-1.5 text-[10px] font-bold px-2 py-1 rounded-lg ${isDark ? 'text-cyan-400 bg-cyan-500/10' : 'text-cyan-600 bg-cyan-50'}`}>
                              <UserCheck className="w-3 h-3" />
                              {pt.fullName}
                              {pt.category && <span className={`ml-auto px-1.5 py-0.5 rounded text-[8px] font-bold text-white ${pt.category === 'RED' ? 'bg-red-500' : pt.category === 'ORANGE' ? 'bg-orange-500' : pt.category === 'YELLOW' ? 'bg-yellow-500' : 'bg-green-500'}`}>{pt.category}</span>}
                            </div>
                          )}
                          {/* Clinical staff can stop the monitoring session */}
                          {isClinicalStaff && session && (
                            <button onClick={() => handleStopMonitoring(session.id)} className="w-full px-3 py-1.5 text-[10px] font-bold rounded-lg bg-red-500/10 text-red-500 hover:bg-red-500/20 transition-colors flex items-center justify-center gap-1">
                              <PowerOff className="w-3 h-3" /> Stop Monitoring
                            </button>
                          )}
                          {/* Admin can power off (which also ends the session) */}
                          {isAdmin && (
                            <button onClick={() => handlePowerOff(device)} className="w-full px-3 py-1.5 text-[10px] font-bold rounded-lg bg-red-500/10 text-red-500 hover:bg-red-500/20 transition-colors flex items-center justify-center gap-1">
                              <PowerOff className="w-3 h-3" /> Power Off
                            </button>
                          )}
                        </div>
                      );
                    })()}
                  </div>
                </div>
              );
            })}
          </div>
        )}

        {/* Active Monitoring Sessions Panel */}
        {sessions.length > 0 && (
          <div className="rounded-2xl p-5 animate-fade-up" style={glassCard}>
            <div className="flex items-center gap-2 mb-4">
              <Heart className="w-4 h-4 text-cyan-500" />
              <h3 className={`text-sm font-bold ${text.heading}`}>Active Monitoring Sessions</h3>
              <span className="ml-auto px-2 py-0.5 rounded-lg bg-cyan-500/10 text-cyan-500 text-[10px] font-bold">{sessions.length} active</span>
            </div>
            <div className="space-y-2">
              {sessions.map(s => {
                const pt = getPatientForVisit(s.visitId);
                const dev = devices.find(d => d.id === s.deviceId);
                return (
                  <div key={s.id} className="flex items-center gap-3 rounded-xl p-3" style={glassInner}>
                    <Activity className="w-4 h-4 text-cyan-500 flex-shrink-0" />
                    <div className="flex-1 min-w-0">
                      <p className={`text-xs font-bold truncate ${text.heading}`}>{pt?.fullName || 'Unknown Patient'}</p>
                      <p className={`text-[10px] ${text.muted}`}>{dev?.deviceName || s.deviceId} · Started {format(new Date(s.startedAt), 'HH:mm')}</p>
                    </div>
                    {pt?.category && (
                      <span className={`px-2 py-0.5 rounded text-[9px] font-bold text-white ${pt.category === 'RED' ? 'bg-red-500' : pt.category === 'ORANGE' ? 'bg-orange-500' : pt.category === 'YELLOW' ? 'bg-yellow-500' : 'bg-green-500'}`}>{pt.category}</span>
                    )}
                    <button onClick={() => handleStopMonitoring(s.id)} className="px-2.5 py-1 text-[10px] font-bold rounded-lg bg-red-500/10 text-red-500 hover:bg-red-500/20 transition-colors">
                      Stop
                    </button>
                  </div>
                );
              })}
            </div>
          </div>
        )}

        {/* ── Patient Selection Dialog (modal overlay) ── */}
        {assignDevice && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm animate-fade-in" onClick={() => { setAssignDevice(null); setAssignError(''); }}>
            <div
              className="w-full max-w-lg rounded-3xl shadow-2xl overflow-hidden mx-4 animate-fade-up"
              style={glassCard}
              onClick={(e) => e.stopPropagation()}
            >
              {/* Modal header */}
              <div className="bg-gradient-to-r from-cyan-600 to-cyan-500 px-6 py-4">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-3">
                    <div className="w-9 h-9 rounded-xl bg-white/20 flex items-center justify-center">
                      <Monitor className="w-4.5 h-4.5 text-white" />
                    </div>
                    <div>
                      <h3 className="text-sm font-bold text-white">Assign Device to Patient</h3>
                      <p className="text-[10px] text-white/70 mt-0.5">
                        {assignDevice.deviceName} · <span className="font-mono">{assignDevice.serialNumber}</span>
                      </p>
                    </div>
                  </div>
                  <button
                    onClick={() => { setAssignDevice(null); setAssignError(''); }}
                    className="w-8 h-8 rounded-xl bg-white/15 flex items-center justify-center hover:bg-white/25 transition-colors"
                  >
                    <X className="w-4 h-4 text-white" />
                  </button>
                </div>
              </div>

              {/* Search bar */}
              <div className="px-5 pt-4 pb-2">
                <div className="relative">
                  <Search className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
                  <input
                    value={patientSearch}
                    onChange={(e) => setPatientSearch(e.target.value)}
                    placeholder="Search by name or complaint..."
                    className={`w-full pl-10 pr-4 py-2.5 rounded-xl text-xs font-medium outline-none border-2 border-transparent focus:border-cyan-500/40 transition-colors ${
                      isDark ? 'text-white placeholder-slate-500 bg-white/5' : 'text-slate-800 placeholder-slate-400 bg-slate-50'
                    }`}
                    style={glassInner}
                    autoFocus
                  />
                </div>
              </div>

              {/* Error message */}
              {assignError && (
                <div className="mx-5 mt-2 flex items-center gap-2 px-3.5 py-2.5 rounded-xl bg-red-500/10 border border-red-500/20">
                  <AlertTriangle className="w-3.5 h-3.5 text-red-400 flex-shrink-0" />
                  <p className="text-[11px] font-medium text-red-400">{assignError}</p>
                </div>
              )}

              {/* Patient list */}
              <div className="px-5 pb-5 pt-2">
                <div className="max-h-72 overflow-y-auto space-y-2 pr-1 custom-scrollbar">
                  {filteredPatients.length === 0 ? (
                    <div className="text-center py-10">
                      <div className={`w-14 h-14 mx-auto mb-3 rounded-2xl flex items-center justify-center ${isDark ? 'bg-white/5' : 'bg-slate-100'}`}>
                        <Users className="w-6 h-6 text-slate-400" />
                      </div>
                      <p className={`text-xs font-bold ${text.heading}`}>No patients found</p>
                      <p className={`text-[10px] mt-1 ${text.muted}`}>Try a different search term</p>
                    </div>
                  ) : filteredPatients.map(p => {
                    const initials = p.fullName
                      .split(' ')
                      .filter(Boolean)
                      .slice(0, 2)
                      .map(n => n[0]?.toUpperCase())
                      .join('');
                    const isThisAssigning = assigningPatientId === p.id;
                    const categoryColor = !p.category ? 'from-slate-400 to-slate-500'
                      : p.category === 'RED' ? 'from-red-500 to-red-600'
                      : p.category === 'ORANGE' ? 'from-orange-500 to-orange-600'
                      : p.category === 'YELLOW' ? 'from-yellow-500 to-yellow-600'
                      : 'from-emerald-500 to-emerald-600';
                    const categoryBadgeBg = !p.category ? 'bg-slate-500/10 text-slate-500'
                      : p.category === 'RED' ? 'bg-red-500/10 text-red-500'
                      : p.category === 'ORANGE' ? 'bg-orange-500/10 text-orange-500'
                      : p.category === 'YELLOW' ? 'bg-yellow-500/10 text-yellow-600'
                      : 'bg-emerald-500/10 text-emerald-600';

                    return (
                      <button
                        key={p.id}
                        onClick={() => handleAssignToPatient(p.id)}
                        disabled={assignLoading}
                        className={`w-full flex items-center gap-3.5 rounded-2xl p-3 text-left transition-all group
                          ${ isThisAssigning ? 'ring-2 ring-cyan-500/50 scale-[0.98]' : '' }
                          ${ isDark
                            ? 'hover:bg-white/5 active:bg-white/10'
                            : 'hover:bg-cyan-50/60 active:bg-cyan-100/50'
                          }
                          disabled:opacity-50 disabled:pointer-events-none
                        `}
                        style={glassInner}
                      >
                        {/* Avatar with initials */}
                        <div className={`w-11 h-11 rounded-2xl bg-gradient-to-br ${categoryColor} flex items-center justify-center flex-shrink-0 shadow-sm`}>
                          <span className="text-xs font-extrabold text-white leading-none tracking-wide">
                            {initials || '??'}
                          </span>
                        </div>

                        {/* Patient info */}
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-2">
                            <p className={`text-[13px] font-bold truncate ${text.heading}`}>{p.fullName}</p>
                            {p.category && (
                              <span className={`px-1.5 py-0.5 rounded-md text-[8px] font-extrabold uppercase tracking-wider ${categoryBadgeBg}`}>
                                {p.category}
                              </span>
                            )}
                          </div>
                          <div className="flex items-center gap-1.5 mt-0.5">
                            <p className={`text-[10px] truncate ${text.muted}`}>
                              {p.chiefComplaint || 'No complaint'}
                            </p>
                            <span className={`text-[8px] ${text.muted}`}>·</span>
                            <p className={`text-[10px] font-medium ${
                              p.triageStatus === 'WAITING' ? (isDark ? 'text-amber-400' : 'text-amber-600')
                              : p.triageStatus === 'IN_TRIAGE' ? (isDark ? 'text-cyan-400' : 'text-cyan-600')
                              : p.triageStatus === 'TRIAGED' ? (isDark ? 'text-emerald-400' : 'text-emerald-600')
                              : isDark ? 'text-violet-400' : 'text-violet-600'
                            }`}>
                              {p.triageStatus?.replace(/_/g, ' ')}
                            </p>
                          </div>
                          {p.age > 0 && (
                            <p className={`text-[9px] mt-0.5 ${text.muted}`}>
                              {p.age}y · {p.gender}{p.isPediatric ? ' · Pediatric' : ''}
                            </p>
                          )}
                        </div>

                        {/* Action indicator */}
                        <div className="flex-shrink-0">
                          {isThisAssigning ? (
                            <div className="w-9 h-9 rounded-xl bg-cyan-500/10 flex items-center justify-center">
                              <Loader2 className="w-4 h-4 animate-spin text-cyan-500" />
                            </div>
                          ) : (
                            <div className={`w-9 h-9 rounded-xl flex items-center justify-center transition-colors ${
                              isDark ? 'bg-white/5 group-hover:bg-cyan-500/15' : 'bg-slate-100 group-hover:bg-cyan-500/10'
                            }`}>
                              <Power className="w-4 h-4 text-cyan-500" />
                            </div>
                          )}
                        </div>
                      </button>
                    );
                  })}
                </div>

                {/* Footer hint */}
                {filteredPatients.length > 0 && (
                  <p className={`text-center text-[9px] mt-3 ${text.muted}`}>
                    {filteredPatients.length} patient{filteredPatients.length !== 1 ? 's' : ''} · Click to assign monitor
                  </p>
                )}
              </div>
            </div>
          </div>
        )}

        {/* ── Bed-Assignment Dialog (Assign device to bed) ── */}
        {bedAssignDevice && (() => {
          const q = bedSearch.trim().toLowerCase();
          const visibleBeds = allBeds.filter((b) => {
            if (!q) return true;
            return (
              b.code.toLowerCase().includes(q) ||
              b.zone.toLowerCase().includes(q) ||
              (b.label?.toLowerCase().includes(q) ?? false)
            );
          });
          return (
            <div
              className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm animate-fade-in"
              onClick={() => { setBedAssignDevice(null); setBedAssignError(''); }}
            >
              <div
                className="w-full max-w-lg rounded-3xl shadow-2xl overflow-hidden mx-4 animate-fade-up"
                style={glassCard}
                onClick={(e) => e.stopPropagation()}
              >
                <div className="bg-gradient-to-r from-emerald-600 to-emerald-500 px-6 py-4">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-3">
                      <div className="w-9 h-9 rounded-xl bg-white/20 flex items-center justify-center">
                        <BedDouble className="w-4.5 h-4.5 text-white" />
                      </div>
                      <div>
                        <h3 className="text-sm font-bold text-white">Assign Monitor to Bed</h3>
                        <p className="text-[10px] text-white/80 mt-0.5">
                          {bedAssignDevice.deviceName} · <span className="font-mono">{bedAssignDevice.serialNumber}</span>
                        </p>
                      </div>
                    </div>
                    <button
                      onClick={() => { setBedAssignDevice(null); setBedAssignError(''); }}
                      className="w-8 h-8 rounded-xl bg-white/15 flex items-center justify-center hover:bg-white/25 transition-colors"
                    >
                      <X className="w-4 h-4 text-white" />
                    </button>
                  </div>
                </div>

                <div className="px-5 pt-4 pb-2">
                  <div className="relative">
                    <Search className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
                    <input
                      value={bedSearch}
                      onChange={(e) => setBedSearch(e.target.value)}
                      placeholder="Filter by code, zone, or label…"
                      className={`w-full pl-10 pr-4 py-2.5 rounded-xl text-xs font-medium outline-none border-2 border-transparent focus:border-emerald-500/40 transition-colors ${
                        isDark ? 'text-white placeholder-slate-500 bg-white/5' : 'text-slate-800 placeholder-slate-400 bg-slate-50'
                      }`}
                      style={glassInner}
                      autoFocus
                    />
                  </div>
                </div>

                {bedAssignError && (
                  <div className="mx-5 mt-2 flex items-center gap-2 px-3.5 py-2.5 rounded-xl bg-red-500/10 border border-red-500/20">
                    <AlertTriangle className="w-3.5 h-3.5 text-red-400 flex-shrink-0" />
                    <p className="text-[11px] font-medium text-red-400">{bedAssignError}</p>
                  </div>
                )}

                <div className="px-5 pb-5 pt-2">
                  <div className="max-h-72 overflow-y-auto space-y-1.5 pr-1 custom-scrollbar">
                    {visibleBeds.length === 0 ? (
                      <div className="text-center py-10">
                        <div className={`w-14 h-14 mx-auto mb-3 rounded-2xl flex items-center justify-center ${isDark ? 'bg-white/5' : 'bg-slate-100'}`}>
                          <BedDouble className="w-6 h-6 text-slate-400" />
                        </div>
                        <p className={`text-xs font-bold ${text.heading}`}>No beds match</p>
                        <p className={`text-[10px] mt-1 ${text.muted}`}>
                          {bedsMap.size === 0 ? 'No beds configured for this hospital yet.' : 'Try a different search.'}
                        </p>
                      </div>
                    ) : visibleBeds.map((b) => {
                      const isThisAssigning = bedAssignTargetId === b.id;
                      const hasOtherDevice = !!b.assignedDeviceId && b.assignedDeviceId !== bedAssignDevice.id;
                      return (
                        <button
                          key={b.id}
                          onClick={() => handleAssignToBed(b.id)}
                          disabled={bedAssignLoading}
                          className={`w-full flex items-center gap-3 rounded-2xl p-3 text-left transition-all
                            ${ isThisAssigning ? 'ring-2 ring-emerald-500/50 scale-[0.99]' : '' }
                            ${ isDark ? 'hover:bg-white/5 active:bg-white/10' : 'hover:bg-emerald-50/60 active:bg-emerald-100/50' }
                            disabled:opacity-50 disabled:pointer-events-none`}
                          style={glassInner}
                        >
                          <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-emerald-500 to-emerald-600 flex items-center justify-center flex-shrink-0 shadow-sm">
                            <span className="text-[10px] font-extrabold text-white leading-none tracking-wide">{b.code}</span>
                          </div>
                          <div className="flex-1 min-w-0">
                            <div className="flex items-center gap-2">
                              <p className={`text-[13px] font-bold truncate ${text.heading}`}>Bed {b.code}</p>
                              <span className={`px-1.5 py-0.5 rounded-md text-[8px] font-extrabold uppercase tracking-wider ${
                                b.status === 'AVAILABLE' ? 'bg-emerald-500/10 text-emerald-600'
                                  : b.status === 'OCCUPIED' ? 'bg-slate-500/10 text-slate-500'
                                  : b.status === 'CLEANING' ? 'bg-amber-500/10 text-amber-600'
                                  : 'bg-rose-500/10 text-rose-600'
                              }`}>
                                {b.status}
                              </span>
                            </div>
                            <div className="flex items-center gap-2 mt-0.5">
                              <p className={`text-[10px] ${text.muted}`}>{b.zone}{b.label ? ` · ${b.label}` : ''}</p>
                              {hasOtherDevice && (
                                <span className="text-[9px] font-bold text-amber-600">replaces {b.assignedDeviceName || 'current monitor'}</span>
                              )}
                            </div>
                          </div>
                          <div className="flex-shrink-0">
                            {isThisAssigning ? (
                              <div className="w-9 h-9 rounded-xl bg-emerald-500/10 flex items-center justify-center">
                                <Loader2 className="w-4 h-4 animate-spin text-emerald-500" />
                              </div>
                            ) : (
                              <div className={`w-9 h-9 rounded-xl flex items-center justify-center transition-colors ${isDark ? 'bg-white/5' : 'bg-slate-100'}`}>
                                <BedDouble className="w-4 h-4 text-emerald-500" />
                              </div>
                            )}
                          </div>
                        </button>
                      );
                    })}
                  </div>

                  {visibleBeds.length > 0 && (
                    <p className={`text-center text-[9px] mt-3 ${text.muted}`}>
                      {visibleBeds.length} bed{visibleBeds.length !== 1 ? 's' : ''} · Click to link monitor
                    </p>
                  )}
                </div>
              </div>
            </div>
          );
        })()}

        {/* ─── API Key Modal (shown once after registration) ─── */}
        {registeredDevice && (
          <div className="fixed inset-0 z-[9999] flex items-center justify-center bg-black/60 backdrop-blur-sm animate-fade-up">
            <div className="w-full max-w-md rounded-2xl p-6 mx-4" style={glassCard}>
              <div className="flex items-center gap-3 mb-4">
                <div className="w-10 h-10 rounded-xl bg-amber-500/10 flex items-center justify-center">
                  <Key className="w-5 h-5 text-amber-500" />
                </div>
                <div>
                  <h3 className={`text-sm font-bold ${text.heading}`}>Device API Key</h3>
                  <p className={`text-[10px] ${text.muted}`}>Save this key — it won't be shown again!</p>
                </div>
              </div>

              <div className="space-y-3 mb-5">
                <div className="flex items-center justify-between">
                  <span className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Device</span>
                  <span className={`text-xs font-bold ${isDark ? 'text-white' : 'text-slate-800'}`}>{registeredDevice.deviceName}</span>
                </div>
                <div className="flex items-center justify-between">
                  <span className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Serial</span>
                  <span className={`text-xs font-mono font-bold ${isDark ? 'text-white' : 'text-slate-800'}`}>{registeredDevice.serialNumber}</span>
                </div>
                <div>
                  <span className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.muted}`}>API Key</span>
                  <div className={`flex items-center gap-2 p-3 rounded-xl font-mono text-xs break-all ${isDark ? 'bg-slate-800/80 text-emerald-400' : 'bg-slate-100 text-emerald-700'}`}>
                    <span className="flex-1 select-all">{registeredDevice.apiKey}</span>
                    <button
                      onClick={handleCopyApiKey}
                      className={`flex-shrink-0 p-1.5 rounded-lg transition-colors ${apiKeyCopied ? 'bg-emerald-500/20 text-emerald-500' : 'bg-white/5 hover:bg-white/10 text-slate-400 hover:text-white'}`}
                      title="Copy to clipboard"
                    >
                      {apiKeyCopied ? <CheckCircle className="w-4 h-4" /> : <Copy className="w-4 h-4" />}
                    </button>
                  </div>
                </div>
              </div>

              <div className={`flex items-center gap-2 px-3 py-2 rounded-xl mb-4 ${isDark ? 'bg-amber-500/10 border border-amber-500/20' : 'bg-amber-50 border border-amber-200'}`}>
                <AlertTriangle className={`w-4 h-4 flex-shrink-0 ${isDark ? 'text-amber-400' : 'text-amber-500'}`} />
                <p className={`text-[10px] ${isDark ? 'text-amber-300' : 'text-amber-600'}`}>
                  This API key is needed by the Python monitor simulator. Copy it now — you won't see it again.
                </p>
              </div>

              <button
                onClick={() => setRegisteredDevice(null)}
                className="w-full px-4 py-2.5 bg-gradient-to-r from-slate-800 to-slate-700 text-white rounded-xl text-xs font-bold shadow-lg hover:-translate-y-0.5 transition-all"
              >
                {apiKeyCopied ? 'Done — Key Copied ✓' : 'I\'ve Saved the Key — Close'}
              </button>
            </div>
          </div>
        )}

      </div>
    </div>
  );
}
