import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Heart, Wind, Droplet, Thermometer, Activity, Zap, Candy,
  AlertTriangle, TrendingUp, TrendingDown, Minus, Clock, User, ArrowLeft,
  FileText, Stethoscope, Bell, Wifi, WifiOff,
  Battery, BatteryLow, BatteryMedium, BatteryFull, BatteryWarning,
  Radio, MonitorSmartphone, Unplug, PlugZap, ShieldCheck, ShieldAlert,
  Play, Pause, Hash, Cpu, CircleDot, BarChart3
} from 'lucide-react';
import {
  XAxis, YAxis, Tooltip, ResponsiveContainer, Area, AreaChart
} from 'recharts';
import { usePatientStore } from '@/store/patientStore';
import { useVitalStore } from '@/store/vitalStore';
import { useDeviceStore } from '@/store/deviceStore';
import { useAuthStore } from '@/store/authStore';
import { Badge } from '@/components/ui/Badge';
import { useVisitVitalsWebSocket } from '@/hooks/useWebSocket';
import { getPediatricThresholds } from '@/utils/pediatricAdjustments';
import { useTheme } from '@/hooks/useTheme';
import {
  DEVICE_TYPE_META,
  CONNECTION_STATUS_META,
  SIGNAL_QUALITY_META,
  getBatteryColor,
  getBatteryBgColor,
  formatDeviceUptime,
  formatLastDataReceived,
  getConnectionDuration,
} from '@/utils/iotDeviceManager';
import { ClinicalNotesPanel } from './ClinicalNotesPanel';

export function VitalMonitoring() {
  const { patientId } = useParams<{ patientId: string }>();
  const navigate = useNavigate();
  const { isDark, glassCard } = useTheme();
  const storePatient = usePatientStore((state) => state.getPatient(patientId!));
  const vitals = useVitalStore((state) => state.getVitals(patientId!));
  const getVitalHistory = useVitalStore((state) => state.getVitalHistory);
  const [activeTab, setActiveTab] = useState<'vitals' | 'trends' | 'alerts' | 'devices' | 'notes'>('vitals');
  const [autoRefresh, setAutoRefresh] = useState(true);
  
  // Use real store patient only — no mock fallback
  const patient = storePatient;
  
  // Use vitals from store only (real data from API / WebSocket)
  const currentVitals = vitals || null;
  
  // Subscribe to real-time vitals via WebSocket (patientId IS the visitId)
  useVisitVitalsWebSocket(patientId);

  // Refresh device store on mount so we always see the latest assignments
  const authUser = useAuthStore((s) => s.user);
  useEffect(() => {
    if (authUser?.hospitalId) {
      useDeviceStore.getState().fetchDevicesFromApi(authUser.hospitalId);
    }
  }, [authUser?.hospitalId, patientId]);
  
  // Get paired devices from the device store (populated from real API)
  const pairedDevices = useDeviceStore((state) => state.getDevicesForPatient(patientId!));
  const deviceSummary = useDeviceStore((state) => state.getPatientDeviceSummary(patientId!));
  
  // Derive device status from real paired devices
  const hasActiveDevice = pairedDevices.some(d => d.connectionStatus === 'CONNECTED' && d.isStreaming);
  const activeDeviceCount = pairedDevices.filter(d => d.connectionStatus === 'CONNECTED' && d.isStreaming).length;
  
  useEffect(() => {
    if (autoRefresh) {
      const interval = setInterval(() => {
        // Force re-render for live updates
      }, 5000);
      return () => clearInterval(interval);
    }
  }, [autoRefresh]);

  if (!patient) {
    return (
      <div className={`min-h-screen flex items-center justify-center ${isDark ? 'bg-[#020b14]' : ''}`}>
        <div className="text-center">
          <AlertTriangle className={`w-12 h-12 mx-auto mb-3 ${isDark ? 'text-slate-500' : 'text-gray-400'}`} />
          <h3 className={`text-base font-semibold mb-2 ${isDark ? 'text-white' : 'text-gray-900'}`}>Patient Not Found</h3>
          <p className={`text-sm mb-4 ${isDark ? 'text-slate-400' : 'text-gray-600'}`}>The patient record could not be located</p>
          <button
            onClick={() => navigate('/monitoring')}
            className="px-4 py-2 bg-gradient-to-r from-slate-800 to-slate-700 text-white rounded-lg hover:from-slate-700 hover:to-slate-600 transition-all text-sm"
          >
            Return to Monitoring
          </button>
        </div>
      </div>
    );
  }

  const thresholds = patient.isPediatric
    ? getPediatricThresholds(patient.age)
    : undefined;

  const getVitalStatus = (
    value: number,
    min?: number,
    max?: number
  ): 'normal' | 'warning' | 'critical' => {
    if (min !== undefined && value < min) {
      return value < min * 0.9 ? 'critical' : 'warning';
    }
    if (max !== undefined && value > max) {
      return value > max * 1.1 ? 'critical' : 'warning';
    }
    return 'normal';
  };

  // Transform vital history to the expected format
  type VitalHistoryData = {
    hr: Array<{ timestamp: string | Date; value: number }>;
    rr: Array<{ timestamp: string | Date; value: number }>;
    spo2: Array<{ timestamp: string | Date; value: number }>;
    bp: Array<{ timestamp: string | Date; value: number }>;
    temp: Array<{ timestamp: string | Date; value: number }>;
    ecg: Array<{ timestamp: string | Date; value: number }>;
    glucose: Array<{ timestamp: string | Date; value: number }>;
  };

  const vitalHistory: VitalHistoryData | null = currentVitals ? {
    hr: getVitalHistory(patientId!, 'heartRate'),
    rr: getVitalHistory(patientId!, 'respiratoryRate'),
    spo2: getVitalHistory(patientId!, 'spo2'),
    bp: getVitalHistory(patientId!, 'systolicBP'),
    temp: getVitalHistory(patientId!, 'temperature'),
    ecg: getVitalHistory(patientId!, 'ecg'),
    glucose: getVitalHistory(patientId!, 'glucose'),
  } : null;

 return (
    <div className={`min-h-screen p-6 ${isDark ? 'bg-[#020b14]' : 'bg-gradient-to-br from-gray-50 to-gray-100'}`}>
      <div className="max-w-7xl mx-auto">
        
        {/* Header with Navigation */}
        <div className="mb-4">
          <button
            onClick={() => navigate('/monitoring')}
            className={`flex items-center gap-2 mb-3 transition-all duration-300 text-sm ${isDark ? 'text-slate-400 hover:text-white' : 'text-gray-600 hover:text-gray-900'}`}
          >
            <ArrowLeft className="w-4 h-4" />
            Back to Monitoring
          </button>
          
          <div className="rounded-3xl overflow-hidden" style={{ boxShadow: '0 8px 32px rgba(0,0,0,0.12)' }}>
            <div className="bg-gradient-to-r from-slate-800 via-slate-700 to-cyan-700 px-6 py-4 relative">
              <div className="absolute inset-0 bg-[url('data:image/svg+xml;base64,PHN2ZyB3aWR0aD0iMjAwIiBoZWlnaHQ9IjIwMCIgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIj48ZGVmcz48cGF0dGVybiBpZD0iYSIgcGF0dGVyblVuaXRzPSJ1c2VyU3BhY2VPblVzZSIgd2lkdGg9IjQwIiBoZWlnaHQ9IjQwIiBwYXR0ZXJuVHJhbnNmb3JtPSJyb3RhdGUoNDUpIj48cmVjdCB3aWR0aD0iMSIgaGVpZ2h0PSI0MCIgZmlsbD0icmdiYSgyNTUsMjU1LDI1NSwwLjA1KSIvPjwvcGF0dGVybj48L2RlZnM+PHJlY3QgZmlsbD0idXJsKCNhKSIgd2lkdGg9IjIwMCIgaGVpZ2h0PSIyMDAiLz48L3N2Zz4=')] opacity-50" />
              <div className="relative flex items-center justify-between">
                <div className="flex items-center gap-4">
                  <div className="w-12 h-12 rounded-2xl bg-gradient-to-br from-white/20 to-white/5 border border-white/30 shadow-lg flex items-center justify-center">
                    <span className="text-lg font-bold text-white">
                      {patient.fullName.split(' ').map(n => n[0]).join('').slice(0, 2)}
                    </span>
                  </div>
                  <div>
                    <h1 className="text-lg font-bold text-white tracking-tight">{patient.fullName}</h1>
                    <div className="flex items-center gap-2 mt-0.5">
                      <span className="text-xs text-white/60 flex items-center gap-1.5">
                        <User className="w-3 h-3" />
                        {patient.age}y {patient.gender === 'MALE' ? '♂' : '♀'}
                      </span>
                      <span className="text-white/30">·</span>
                      <span className="text-xs text-white/60">{patient.chiefComplaint}</span>
                      {patient.isPediatric && (
                        <>
                          <span className="text-white/30">·</span>
                          <span className="px-1.5 py-0.5 rounded-md text-[9px] font-bold bg-pink-500/30 text-pink-200 border border-pink-500/30">
                            Pediatric
                          </span>
                        </>
                      )}
                    </div>
                  </div>
                </div>
                
                <div className="flex items-center gap-3">
                  <Badge category={patient.category} />
                  <div className="px-3 py-1.5 rounded-xl bg-white/10 backdrop-blur-sm border border-white/15">
                    <span className="text-[10px] text-white/60 font-medium">TEWS</span>
                    <span className="ml-1.5 text-sm font-bold text-white">{patient.tewsScore}</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* Live Status Bar */}
        <div className={`mb-4 rounded-xl p-3 shadow-sm border ${
          hasActiveDevice
            ? (isDark ? 'bg-green-500/10 border-green-500/20' : 'bg-gradient-to-r from-green-50 to-emerald-50 border-green-200')
            : pairedDevices.length > 0
              ? (isDark ? 'bg-amber-500/10 border-amber-500/20' : 'bg-gradient-to-r from-amber-50 to-yellow-50 border-amber-200')
              : (isDark ? 'bg-slate-500/10 border-slate-500/20' : 'bg-gradient-to-r from-slate-50 to-gray-50 border-slate-200')
        }`}>
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="flex items-center gap-2">
                <div className={`w-2 h-2 rounded-full animate-pulse ${
                  hasActiveDevice ? 'bg-green-500' : pairedDevices.length > 0 ? 'bg-amber-500' : 'bg-slate-400'
                }`}></div>
                <span className={`text-sm font-semibold ${
                  hasActiveDevice
                    ? (isDark ? 'text-green-300' : 'text-green-900')
                    : pairedDevices.length > 0
                      ? (isDark ? 'text-amber-300' : 'text-amber-900')
                      : (isDark ? 'text-slate-400' : 'text-slate-600')
                }`}>
                  {hasActiveDevice ? 'Live Monitoring Active' : pairedDevices.length > 0 ? 'Device Connected' : 'No Device Assigned'}
                </span>
              </div>
              <span className={`text-xs ${isDark ? 'text-slate-400' : 'text-gray-600'}`}>
                Last update: {currentVitals?.timestamp ? new Date(currentVitals.timestamp).toLocaleTimeString() : '—'}
              </span>
              {activeDeviceCount > 0 && (
                <span className={`text-xs font-medium px-2 py-0.5 rounded-full ${isDark ? 'text-emerald-300 bg-emerald-500/15' : 'text-emerald-600 bg-emerald-100'}`}>
                  {activeDeviceCount} device{activeDeviceCount > 1 ? 's' : ''} streaming
                </span>
              )}
            </div>
            <div className="flex items-center gap-2">
              {pairedDevices.length > 0 && deviceSummary.lowestBattery < 30 && (
                <span className={`flex items-center gap-1 text-xs font-medium px-2 py-0.5 rounded-full border ${isDark ? 'text-amber-300 bg-amber-500/15 border-amber-500/30' : 'text-amber-600 bg-amber-50 border-amber-200'}`}>
                  <BatteryWarning className="w-3 h-3" />
                  Battery {Math.round(deviceSummary.lowestBattery)}%
                </span>
              )}
              <button
                onClick={()=> setAutoRefresh(!autoRefresh)}
                className={`px-2.5 py-1 rounded-lg text-xs font-medium transition-all ${
                  autoRefresh
                    ? 'bg-green-600 text-white'
                    : isDark
                      ? 'bg-slate-700 text-slate-300 hover:bg-slate-600'
                      : 'bg-gray-200 text-gray-700 hover:bg-gray-300'
                }`}
              >
                {autoRefresh ? 'Auto-refresh ON' : 'Auto-refresh OFF'}
              </button>
            </div>
          </div>
        </div>

        {/* ── IoT Device Status Panel ── */}
        {pairedDevices.length > 0 ? (
          <div className={`mb-4 rounded-xl shadow-md p-4 ${isDark ? glassCard + ' border border-white/10' : 'bg-white border border-gray-200'}`}>
            <div className="flex items-center justify-between mb-3">
              <h3 className={`text-sm font-bold flex items-center gap-2 ${isDark ? 'text-white' : 'text-gray-900'}`}>
                <MonitorSmartphone className={`w-4 h-4 ${isDark ? 'text-cyan-400' : 'text-cyan-600'}`} />
                Connected IoT Devices
                <span className={`text-xs font-medium ${isDark ? 'text-slate-500' : 'text-gray-400'}`}>({pairedDevices.length})</span>
              </h3>
              <div className="flex items-center gap-2">
                <span className={`text-xs font-bold px-2.5 py-1 rounded-lg border ${
                  deviceSummary.overallHealth === 'HEALTHY'
                    ? (isDark ? 'bg-green-500/15 text-green-300 border-green-500/30' : 'bg-green-50 text-green-700 border-green-200')
                    : deviceSummary.overallHealth === 'WARNING'
                      ? (isDark ? 'bg-amber-500/15 text-amber-300 border-amber-500/30' : 'bg-amber-50 text-amber-700 border-amber-200')
                      : (isDark ? 'bg-red-500/15 text-red-300 border-red-500/30' : 'bg-red-50 text-red-700 border-red-200')
                }`}>
                  {deviceSummary.overallHealth === 'HEALTHY' ? 'All Healthy' :
                   deviceSummary.overallHealth === 'WARNING' ? 'Needs Attention' : 'Critical'}
                </span>
              </div>
            </div>

            <div className="space-y-3">
              {pairedDevices.map((device) => {
                const typeMeta = DEVICE_TYPE_META[device.type];
                const connMeta = CONNECTION_STATUS_META[device.connectionStatus];
                const sigMeta = SIGNAL_QUALITY_META[device.health.signalQuality];

                return (
                  <div
                    key={device.id}
                    className={`rounded-xl border transition-all duration-300 overflow-hidden ${
                      device.connectionStatus === 'CONNECTED'
                        ? 'bg-gradient-to-r from-green-50/50 via-emerald-50/20 to-white border-green-200'
                        : device.connectionStatus === 'RECONNECTING'
                          ? 'bg-gradient-to-r from-amber-50/50 via-yellow-50/20 to-white border-amber-200'
                          : 'bg-gradient-to-r from-gray-50 via-gray-50/30 to-white border-gray-200'
                    }`}
                  >
                    <div className="flex">
                      {/* ═══ LEFT: Device Info & Metrics ═══ */}
                      <div className="w-[42%] p-3 border-r border-gray-200/60 flex flex-col">
                        {/* Device Header */}
                        <div className="flex items-center gap-2 mb-2">
                          <div className={`w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 ${
                            device.connectionStatus === 'CONNECTED'
                              ? 'bg-green-100'
                              : device.connectionStatus === 'RECONNECTING'
                                ? 'bg-amber-100'
                                : 'bg-gray-100'
                          }`}>
                            {device.connectionStatus === 'CONNECTED' ? (
                              <Wifi className="w-4 h-4 text-green-600" />
                            ) : device.connectionStatus === 'RECONNECTING' ? (
                              <Radio className="w-4 h-4 text-amber-600 animate-pulse" />
                            ) : (
                              <WifiOff className="w-4 h-4 text-gray-400" />
                            )}
                          </div>
                          <div className="min-w-0 flex-1">
                            <p className="text-xs font-bold text-gray-800 truncate">{device.name}</p>
                            <p className="text-[10px] text-gray-400 truncate">{typeMeta.shortLabel} · {device.manufacturer}</p>
                          </div>
                          <span className={`text-[9px] font-bold px-1.5 py-0.5 rounded-md flex-shrink-0 ${connMeta.bgColor} ${connMeta.textColor} border ${connMeta.borderColor}`}>
                            {connMeta.label}
                          </span>
                        </div>

                        {/* Model & Serial */}
                        <div className="grid grid-cols-2 gap-1.5 mb-2">
                          <div className="flex items-center gap-1.5 bg-gray-50/80 rounded-md px-2 py-1">
                            <Cpu className="w-3 h-3 text-gray-400 flex-shrink-0" />
                            <div className="min-w-0">
                              <p className="text-[8px] text-gray-400 uppercase tracking-wide">Model</p>
                              <p className="text-[10px] font-semibold text-gray-700 truncate">{device.model}</p>
                            </div>
                          </div>
                          <div className="flex items-center gap-1.5 bg-gray-50/80 rounded-md px-2 py-1">
                            <Hash className="w-3 h-3 text-gray-400 flex-shrink-0" />
                            <div className="min-w-0">
                              <p className="text-[8px] text-gray-400 uppercase tracking-wide">S/N</p>
                              <p className="text-[10px] font-semibold text-gray-700 truncate">{device.serialNumber}</p>
                            </div>
                          </div>
                        </div>

                        {/* Firmware & Streaming Status */}
                        <div className="grid grid-cols-2 gap-1.5 mb-2">
                          <div className={`flex items-center gap-1.5 rounded-md px-2 py-1 ${
                            device.health.firmwareUpToDate
                              ? 'bg-green-50/60 border border-green-100/60'
                              : 'bg-amber-50/60 border border-amber-100/60'
                          }`}>
                            {device.health.firmwareUpToDate ? (
                              <ShieldCheck className="w-3 h-3 text-green-500 flex-shrink-0" />
                            ) : (
                              <ShieldAlert className="w-3 h-3 text-amber-500 flex-shrink-0" />
                            )}
                            <div className="min-w-0">
                              <p className="text-[8px] text-gray-400 uppercase tracking-wide">Firmware</p>
                              <p className={`text-[10px] font-semibold truncate ${
                                device.health.firmwareUpToDate ? 'text-green-700' : 'text-amber-700'
                              }`}>
                                v{device.firmwareVersion}
                              </p>
                            </div>
                          </div>
                          <div className={`flex items-center gap-1.5 rounded-md px-2 py-1 ${
                            device.isStreaming
                              ? 'bg-blue-50/60 border border-blue-100/60'
                              : 'bg-gray-50/60 border border-gray-100/60'
                          }`}>
                            {device.isStreaming ? (
                              <Play className="w-3 h-3 text-blue-500 flex-shrink-0" />
                            ) : (
                              <Pause className="w-3 h-3 text-gray-400 flex-shrink-0" />
                            )}
                            <div className="min-w-0">
                              <p className="text-[8px] text-gray-400 uppercase tracking-wide">Stream</p>
                              <p className={`text-[10px] font-semibold truncate ${
                                device.isStreaming ? 'text-blue-700' : 'text-gray-500'
                              }`}>
                                {device.isStreaming ? `${(1000 / device.samplingIntervalMs).toFixed(0)}Hz` : 'Paused'}
                              </p>
                            </div>
                          </div>
                        </div>

                        {/* Device Metrics — Battery / Signal / Uptime */}
                        <div className="grid grid-cols-3 gap-1.5 mb-2">
                          {/* Battery */}
                          <div className="text-center bg-white/60 rounded-lg py-1.5 border border-gray-100/60">
                            <div className="flex items-center justify-center gap-1 mb-0.5">
                              {device.health.batteryPercent > 50 ? (
                                <BatteryFull className={`w-3.5 h-3.5 ${getBatteryColor(device.health.batteryPercent)}`} />
                              ) : device.health.batteryPercent > 25 ? (
                                <BatteryMedium className={`w-3.5 h-3.5 ${getBatteryColor(device.health.batteryPercent)}`} />
                              ) : (
                                <BatteryLow className={`w-3.5 h-3.5 ${getBatteryColor(device.health.batteryPercent)}`} />
                              )}
                            </div>
                            <p className={`text-xs font-bold ${getBatteryColor(device.health.batteryPercent)}`}>
                              {Math.round(device.health.batteryPercent)}%
                            </p>
                            <p className="text-[8px] text-gray-400">Battery</p>
                          </div>

                          {/* Signal */}
                          <div className="text-center bg-white/60 rounded-lg py-1.5 border border-gray-100/60">
                            <div className="flex items-center justify-center gap-0.5 mb-0.5 h-[14px]">
                              {[1, 2, 3, 4].map((bar) => (
                                <div
                                  key={bar}
                                  className={`w-1 rounded-sm transition-all ${
                                    bar <= sigMeta.bars ? sigMeta.color : 'bg-gray-200'
                                  }`}
                                  style={{ height: `${bar * 3 + 2}px` }}
                                />
                              ))}
                            </div>
                            <p className={`text-xs font-bold ${sigMeta.textColor}`}>{sigMeta.label}</p>
                            <p className="text-[8px] text-gray-400">Signal</p>
                          </div>

                          {/* Uptime */}
                          <div className="text-center bg-white/60 rounded-lg py-1.5 border border-gray-100/60">
                            <div className="flex items-center justify-center gap-1 mb-0.5">
                              <Clock className="w-3.5 h-3.5 text-gray-400" />
                            </div>
                            <p className="text-xs font-bold text-gray-700">
                              {formatDeviceUptime(device.health.uptimeMinutes)}
                            </p>
                            <p className="text-[8px] text-gray-400">Uptime</p>
                          </div>
                        </div>

                        {/* Data Quality Bar + Footer */}
                        <div className="mt-auto">
                          {/* Data Quality */}
                          <div className="mb-1.5">
                            <div className="flex items-center justify-between mb-0.5">
                              <span className="text-[8px] font-bold text-gray-400 uppercase tracking-wide flex items-center gap-1">
                                <BarChart3 className="w-2.5 h-2.5" /> Data Quality
                              </span>
                              <span className={`text-[9px] font-bold ${
                                device.health.dataDropRate < 0.02 ? 'text-green-600' :
                                device.health.dataDropRate < 0.1 ? 'text-amber-600' : 'text-red-600'
                              }`}>
                                {((1 - device.health.dataDropRate) * 100).toFixed(1)}%
                              </span>
                            </div>
                            <div className="w-full h-1.5 bg-gray-200/60 rounded-full overflow-hidden">
                              <div
                                className={`h-full rounded-full transition-all ${
                                  device.health.dataDropRate < 0.02 ? 'bg-green-500' :
                                  device.health.dataDropRate < 0.1 ? 'bg-amber-500' : 'bg-red-500'
                                }`}
                                style={{ width: `${(1 - device.health.dataDropRate) * 100}%` }}
                              />
                            </div>
                          </div>

                          {/* Paired Since + Errors footer */}
                          <div className="flex items-center justify-between text-[9px] text-gray-400">
                            <span className="flex items-center gap-0.5">
                              <CircleDot className="w-2.5 h-2.5" />
                              {device.pairedAt ? `Paired ${getConnectionDuration(device)}` : 'Not paired'}
                            </span>
                            {device.health.errorCount > 0 && (
                              <span className="text-red-400 font-medium">
                                {device.health.errorCount} error{device.health.errorCount > 1 ? 's' : ''}
                              </span>
                            )}
                          </div>
                        </div>
                      </div>

                      {/* ═══ RIGHT: Live Vital Channels ═══ */}
                      <div className="flex-1 p-3">
                        <p className="text-[9px] font-bold text-gray-400 uppercase tracking-wider mb-1.5">Live Vital Channels</p>
                        <div className="space-y-1">
                          {(() => {
                            const vitalChannels: { key: string; label: string; unit: string; icon: React.ComponentType<any>; color: string; format: (v: number) => string }[] = [
                              { key: 'heartRate', label: 'Heart Rate', unit: 'bpm', icon: Heart, color: 'text-red-500', format: (v) => Math.round(v).toString() },
                              { key: 'respiratoryRate', label: 'Resp Rate', unit: '/min', icon: Wind, color: 'text-blue-500', format: (v) => Math.round(v).toString() },
                              { key: 'spo2', label: 'SpO2', unit: '%', icon: Droplet, color: 'text-indigo-500', format: (v) => Math.round(v).toString() },
                              { key: 'systolicBP', label: 'Blood Pressure', unit: 'mmHg', icon: Activity, color: 'text-red-400', format: (v) => `${Math.round(v)}/${currentVitals ? Math.round(currentVitals.diastolicBP) : '—'}` },
                              { key: 'temperature', label: 'Temperature', unit: '°C', icon: Thermometer, color: 'text-orange-500', format: (v) => v.toFixed(1) },
                              { key: 'ecg', label: 'ECG', unit: 'mV', icon: Zap, color: 'text-yellow-500', format: (v) => v.toFixed(2) },
                              { key: 'glucose', label: 'Glucose', unit: 'mmol/L', icon: Candy, color: 'text-pink-500', format: (v) => v.toFixed(1) },
                            ];

                            return vitalChannels
                              .filter((ch) => device.providedVitals.includes(ch.key as any))
                              .map((ch) => {
                                const history = getVitalHistory(patientId!, ch.key);
                                const latestVal = currentVitals ? (currentVitals as any)[ch.key] as number : null;
                                let trendDir: 'up' | 'down' | 'stable' = 'stable';
                                if (history.length >= 3) {
                                  const recent = history.slice(-3);
                                  const delta = recent[recent.length - 1].value - recent[0].value;
                                  if (delta > 1) trendDir = 'up';
                                  else if (delta < -1) trendDir = 'down';
                                }
                                let status: 'normal' | 'warning' | 'critical' = 'normal';
                                if (latestVal !== null) {
                                  const raw = getVitalStatus(latestVal,
                                    ch.key === 'spo2' ? (thresholds?.spo2Threshold || 92) :
                                    ch.key === 'heartRate' ? (thresholds?.heartRate.min || 60) :
                                    ch.key === 'respiratoryRate' ? (thresholds?.respiratoryRate.min || 12) :
                                    ch.key === 'systolicBP' ? (thresholds?.systolicBP.min || 90) :
                                    ch.key === 'temperature' ? 36.1 :
                                    // ECG here is ST-segment deviation in mV,
                                    // not bpm — normal band is roughly ±0.5 mV.
                                    ch.key === 'ecg' ? -0.5 :
                                    // Glucose is stored/streamed in mmol/L — normal band ≈ 3.9–11.0.
                                    ch.key === 'glucose' ? 3.9 : 0,
                                    ch.key === 'spo2' ? 100 :
                                    ch.key === 'heartRate' ? (thresholds?.heartRate.max || 100) :
                                    ch.key === 'respiratoryRate' ? (thresholds?.respiratoryRate.max || 20) :
                                    ch.key === 'systolicBP' ? 140 :
                                    ch.key === 'temperature' ? 37.2 :
                                    ch.key === 'ecg' ? 0.5 :
                                    ch.key === 'glucose' ? 11.0 : 999
                                  );
                                  status = raw;
                                }
                                const ChIcon = ch.icon;
                                return (
                                  <div
                                    key={ch.key}
                                    className={`flex items-center gap-2 px-2 py-1.5 rounded-lg transition-all ${
                                      status === 'critical'
                                        ? 'bg-red-50/80 border border-red-200/60'
                                        : status === 'warning'
                                          ? 'bg-amber-50/80 border border-amber-200/60'
                                          : 'bg-white/60 border border-gray-100'
                                    }`}
                                  >
                                    <div className={`w-1.5 h-1.5 rounded-full flex-shrink-0 ${
                                      status === 'critical' ? 'bg-red-500 animate-pulse' :
                                      status === 'warning' ? 'bg-amber-500' :
                                      device.connectionStatus === 'CONNECTED' ? 'bg-green-500 animate-pulse' : 'bg-gray-300'
                                    }`} />
                                    <ChIcon className={`w-3 h-3 flex-shrink-0 ${ch.color}`} />
                                    <span className="text-[10px] font-semibold text-gray-600 flex-1 truncate">{ch.label}</span>
                                    <span className={`text-xs font-extrabold tabular-nums ${
                                      status === 'critical' ? 'text-red-700' :
                                      status === 'warning' ? 'text-amber-700' : 'text-gray-800'
                                    }`}>
                                      {latestVal !== null ? ch.format(latestVal) : '—'}
                                    </span>
                                    <span className="text-[9px] text-gray-400 w-8">{ch.unit}</span>
                                    {trendDir === 'up' ? (
                                      <TrendingUp className="w-3 h-3 text-red-400 flex-shrink-0" />
                                    ) : trendDir === 'down' ? (
                                      <TrendingDown className="w-3 h-3 text-blue-400 flex-shrink-0" />
                                    ) : (
                                      <Minus className="w-3 h-3 text-gray-300 flex-shrink-0" />
                                    )}
                                  </div>
                                );
                              });
                          })()}
                        </div>
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>

            {/* Coverage Summary */}
            {deviceSummary.uncoveredVitals.length > 0 && (
              <div className={`mt-3 p-2.5 rounded-lg border flex items-start gap-2 ${isDark ? 'bg-amber-500/10 border-amber-500/20' : 'bg-amber-50 border-amber-200'}`}>
                <AlertTriangle className={`w-3.5 h-3.5 mt-0.5 flex-shrink-0 ${isDark ? 'text-amber-400' : 'text-amber-500'}`} />
                <div>
                  <p className={`text-xs font-semibold ${isDark ? 'text-amber-300' : 'text-amber-700'}`}>Uncovered Vitals</p>
                  <p className={`text-[11px] mt-0.5 ${isDark ? 'text-amber-400/80' : 'text-amber-600'}`}>
                    No connected device is providing: {deviceSummary.uncoveredVitals.map((v) =>
                      v.replace(/([A-Z])/g, ' $1').replace(/^./, (s) => s.toUpperCase()).trim()
                    ).join(', ')}
                  </p>
                </div>
              </div>
            )}
          </div>
        ) : (
          <div className={`mb-4 rounded-xl shadow-md p-6 text-center ${isDark ? glassCard + ' border border-white/10' : 'bg-white border border-gray-200'}`}>
            <MonitorSmartphone className={`w-10 h-10 mx-auto mb-3 ${isDark ? 'text-slate-500' : 'text-gray-300'}`} />
            <h4 className={`text-sm font-bold mb-1 ${isDark ? 'text-white' : 'text-gray-900'}`}>No Device Assigned</h4>
            <p className={`text-xs mb-4 max-w-sm mx-auto ${isDark ? 'text-slate-400' : 'text-gray-500'}`}>
              Assign an IoT monitor to this patient via the IoT Devices page to enable real-time vital streaming.
            </p>
            <button
              onClick={() => navigate('/iot-devices')}
              className="inline-flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-cyan-500 to-cyan-600 text-white text-xs font-bold rounded-xl hover:-translate-y-0.5 transition-all shadow-md"
            >
              <Cpu className="w-3.5 h-3.5" /> Go to IoT Devices
            </button>
          </div>
        )}
        <div className="mb-4">
          <div className={`flex items-center gap-1.5 rounded-2xl p-1.5 ${isDark ? 'bg-white/5 border border-white/10' : 'bg-white/80 border border-gray-200/60'}`} style={{ backdropFilter: 'blur(12px)' }}>
            {[
              { id: 'vitals', label: 'Current Vitals', icon: Heart },
              { id: 'trends', label: 'Vital Trends', icon: TrendingUp },
              { id: 'alerts', label: 'Alerts', icon: Bell },
              { id: 'devices', label: 'Devices', icon: MonitorSmartphone },
              { id: 'notes', label: 'Notes', icon: FileText },
            ].map((tab) => {
              const Icon = tab.icon;
              const isActive = activeTab === tab.id;
              return (
                <button
                  key={tab.id}
                  onClick={() => setActiveTab(tab.id as any)}
                  className={`flex-1 flex items-center justify-center gap-2 px-3 py-2 rounded-xl text-xs font-semibold transition-all duration-300 ${
                    isActive
                      ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md shadow-slate-800/20'
                      : isDark
                        ? 'text-slate-400 hover:bg-white/5'
                        : 'text-gray-600 hover:bg-white/60'
                  }`}
                >
                  <Icon className={`w-3.5 h-3.5 ${isActive ? 'text-white' : isDark ? 'text-slate-500' : 'text-gray-400'}`} />
                  <span className={`font-bold ${isActive ? 'text-white' : isDark ? 'text-slate-300' : 'text-gray-800'}`}>{tab.label}</span>
                </button>
              );
            })}
          </div>
        </div>

        {/* Tab Content */}
        {activeTab === 'vitals' && !currentVitals && (
          <div className={`rounded-2xl p-8 text-center ${isDark ? 'bg-white/5 border border-white/10' : 'bg-white border border-gray-200'}`}>
            <Activity className={`w-10 h-10 mx-auto mb-3 ${isDark ? 'text-slate-500' : 'text-gray-300'}`} />
            <h4 className={`text-sm font-bold mb-1 ${isDark ? 'text-white' : 'text-gray-900'}`}>No Vital Data Available</h4>
            <p className={`text-xs max-w-sm mx-auto ${isDark ? 'text-slate-400' : 'text-gray-500'}`}>
              Vital signs will appear here once an IoT device is assigned and streaming, or vitals are recorded manually.
            </p>
          </div>
        )}
        {activeTab === 'vitals' && currentVitals && (
          <div className="space-y-4">
            {/* Vital Signs Grid */}
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
              {/* Heart Rate */}
              <VitalCard
                label="Heart Rate"
                value={Math.round(currentVitals.heartRate)}
                unit="bpm"
                icon={Heart}
                status={getVitalStatus(
                  currentVitals.heartRate,
                  thresholds?.heartRate.min || 60,
                  thresholds?.heartRate.max || 100
                )}
                trend="stable"
                range="60-100 bpm"
                subtitle="Regular rhythm"
              />
              
              {/* Respiratory Rate */}
              <VitalCard
                label="Respiratory Rate"
                value={Math.round(currentVitals.respiratoryRate)}
                unit="/min"
                icon={Wind}
                status={getVitalStatus(
                  currentVitals.respiratoryRate,
                  thresholds?.respiratoryRate.min || 12,
                  thresholds?.respiratoryRate.max || 20
                )}
                trend="stable"
                range="12-20 /min"
                subtitle="Clear breath sounds"
              />
              
              {/* SpO2 */}
              <VitalCard
                label="SpO2"
                value={Math.round(currentVitals.spo2)}
                unit="%"
                icon={Droplet}
                status={getVitalStatus(currentVitals.spo2, thresholds?.spo2Threshold || 92, 100)}
                trend="stable"
                range="95-100%"
                subtitle="On room air"
              />
              
              {/* Blood Pressure */}
              <VitalCard
                label="Blood Pressure"
                value={`${Math.round(currentVitals.systolicBP)}/${Math.round(currentVitals.diastolicBP)}`}
                unit="mmHg"
                icon={Activity}
                status={getVitalStatus(currentVitals.systolicBP, thresholds?.systolicBP.min || 90, 140)}
                trend="stable"
                range="90-140/60-90"
                subtitle="Sitting position"
              />
              
              {/* Temperature */}
              <VitalCard
                label="Temperature"
                value={currentVitals.temperature.toFixed(1)}
                unit="°C"
                icon={Thermometer}
                status={getVitalStatus(currentVitals.temperature, 36.1, 37.2)}
                trend="stable"
                range="36.1-37.2°C"
                subtitle="Oral measurement"
              />
              
              {/* ECG — ST-segment deviation (mV).
                  Important: this is NOT heart rate. Display range is ±0.5 mV
                  for normal; beyond that indicates ST elevation/depression.
                  Formatting with 2 decimals so sub-100µV drift is visible —
                  toFixed(0) would truncate a live 0.03 mV reading to "0"
                  and make ECG appear permanently static. */}
              <VitalCard
                label="ECG (ST)"
                value={(currentVitals.ecg ?? 0).toFixed(2)}
                unit="mV"
                icon={Zap}
                status={getVitalStatus(currentVitals.ecg ?? 0, -0.5, 0.5)}
                trend="stable"
                range="-0.5 – 0.5 mV"
                subtitle={currentVitals.ecgRhythm ?? 'Normal sinus rhythm'}
              />
              
              {/* Glucose — stored/streamed in mmol/L (the unit every threshold uses).
                  Only shown when a glucose value is actually present: bedside monitors
                  don't measure glucose (POC fingerstick / glucometer), so most monitor
                  feeds have none — and rendering .toFixed on an absent value would crash. */}
              {currentVitals.glucose != null && (
                <VitalCard
                  label="Glucose"
                  value={currentVitals.glucose.toFixed(1)}
                  unit="mmol/L"
                  icon={Candy}
                  status={getVitalStatus(currentVitals.glucose, 3.9, 11.0)}
                  trend="stable"
                  range="3.9-11.0 mmol/L"
                  subtitle="Random"
                />
              )}
            </div>
          </div>
        )}

        {activeTab=== 'trends' && vitalHistory && (
          <div className="space-y-4">
            {/* Trend Charts */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
              <TrendChartCard
                title="Heart Rate Trend"
                data={vitalHistory.hr}
                dataKey="value"
                color="#ef4444"
                unit="bpm"
              />
              <TrendChartCard
                title="SpO₂ Trend"
                data={vitalHistory.spo2}
                dataKey="value"
                color="#10b981"
                unit="%"
              />
              <TrendChartCard
                title="Blood Pressure Trend"
                data={vitalHistory.bp}
                dataKey="value"
                color="#8b5cf6"
                unit="mmHg"
              />
              <TrendChartCard
                title="Temperature Trend"
                data={vitalHistory.temp}
                dataKey="value"
                color="#f97316"
                unit="°C"
              />
            </div>
          </div>
        )}

        {activeTab === 'alerts' && (
          <div className="space-y-4">
            <div className={`rounded-xl shadow-md p-4 ${isDark ? glassCard + ' border border-white/10' : 'bg-white border border-gray-200'}`}>
              <h3 className={`text-sm font-bold mb-3 flex items-center gap-2 ${isDark ? 'text-white' : 'text-gray-900'}`}>
                <Bell className="w-4 h-4" />
                Active Alerts
              </h3>
              <div className="space-y-3">
                {patient.aiAlerts && patient.aiAlerts.length > 0 ? (
                  patient.aiAlerts.map((alert, i) => (
                    <div key={i} className={`flex items-start gap-2 p-3 rounded-lg border ${isDark ? 'bg-amber-500/10 border-amber-500/20' : 'bg-amber-50 border-amber-200'}`}>
                      <AlertTriangle className={`w-4 h-4 mt-0.5 ${isDark ? 'text-amber-400' : 'text-amber-600'}`} />
                      <div className="flex-1">
                        <div className={`text-sm font-medium ${isDark ? 'text-white' : 'text-gray-900'}`}>{alert.message}</div>
                        <div className={`text-xs mt-0.5 ${isDark ? 'text-slate-400' : 'text-gray-600'}`}>{alert.timestamp.toLocaleString()}</div>
                      </div>
                    </div>
                  ))
                ) : (
                  <div className={`text-center py-6 ${isDark ? 'text-slate-500' : 'text-gray-500'}`}>
                    <Bell className="w-10 h-10 mx-auto mb-2 opacity-30" />
                    <p className="text-sm">No active alerts at this time</p>
                  </div>
                )}
              </div>
            </div>

            {/* AI Monitoring Status */}
            <div className={`rounded-xl shadow-md p-4 ${isDark ? glassCard + ' border border-white/10' : 'bg-white border border-gray-200'}`}>
              <h3 className={`text-sm font-bold mb-3 flex items-center gap-2 ${isDark ? 'text-white' : 'text-gray-900'}`}>
                <Stethoscope className="w-4 h-4" />
                AI Monitoring Status
              </h3>
              <div className="flex items-center gap-3">
                <div className={`w-10 h-10 rounded-lg flex items-center justify-center ${isDark ? 'bg-green-500/15' : 'bg-green-100'}`}>
                  <div className="w-2.5 h-2.5 bg-green-500 rounded-full animate-pulse"></div>
                </div>
                <div className="flex-1">
                  <div className={`text-sm font-medium ${isDark ? 'text-white' : 'text-gray-900'}`}>Continuous AI Monitoring Active</div>
                  <div className={`text-xs mt-0.5 ${isDark ? 'text-slate-400' : 'text-gray-600'}`}>
                    The system is actively monitoring vital signs and will alert if deterioration is detected
                  </div>
                </div>
              </div>
            </div>
          </div>
        )}

        {activeTab === 'devices' && (
          <div className="space-y-4">
            {/* Device Fleet Overview */}
            <div className={`rounded-xl shadow-md p-4 ${isDark ? glassCard + ' border border-white/10' : 'bg-white border border-gray-200'}`}>
              <h3 className={`text-sm font-bold mb-3 flex items-center gap-2 ${isDark ? 'text-white' : 'text-gray-900'}`}>
                <MonitorSmartphone className={`w-4 h-4 ${isDark ? 'text-cyan-400' : 'text-cyan-600'}`} />
                Device Fleet — Patient {patient.fullName}
              </h3>

              {pairedDevices.length === 0 ? (
                <div className={`text-center py-8 ${isDark ? 'text-slate-500' : 'text-gray-500'}`}>
                  <Unplug className="w-12 h-12 mx-auto mb-3 opacity-30" />
                  <p className="text-sm font-medium">No IoT devices paired</p>
                  <p className={`text-xs mt-1 ${isDark ? 'text-slate-600' : 'text-gray-400'}`}>Devices will be automatically provisioned when monitoring begins</p>
                </div>
              ) : (
                <div className="space-y-3">
                  {pairedDevices.map((device) => {
                    const typeMeta = DEVICE_TYPE_META[device.type];
                    const connMeta = CONNECTION_STATUS_META[device.connectionStatus];
                    const sigMeta = SIGNAL_QUALITY_META[device.health.signalQuality];

                    return (
                      <div
                        key={device.id}
                        className={`rounded-xl border p-4 transition-all duration-300 ${
                          device.connectionStatus === 'CONNECTED'
                            ? 'bg-gradient-to-r from-green-50/60 to-white border-green-200'
                            : device.connectionStatus === 'RECONNECTING'
                              ? 'bg-gradient-to-r from-amber-50/60 to-white border-amber-200'
                              : 'bg-gradient-to-r from-gray-50 to-white border-gray-200'
                        }`}
                      >
                        <div className="flex items-start justify-between mb-3">
                          <div className="flex items-center gap-3">
                            <div className={`w-10 h-10 rounded-xl flex items-center justify-center ${
                              device.connectionStatus === 'CONNECTED'
                                ? 'bg-green-100'
                                : device.connectionStatus === 'RECONNECTING'
                                  ? 'bg-amber-100'
                                  : 'bg-gray-100'
                            }`}>
                              {device.connectionStatus === 'CONNECTED' ? (
                                <PlugZap className="w-5 h-5 text-green-600" />
                              ) : (
                                <WifiOff className="w-5 h-5 text-gray-400" />
                              )}
                            </div>
                            <div>
                              <p className="text-sm font-bold text-gray-900">{device.name}</p>
                              <p className="text-xs text-gray-500">
                                {typeMeta.label} · {device.manufacturer} {device.model}
                              </p>
                              <p className="text-[10px] text-gray-400 mt-0.5">
                                SN: {device.serialNumber} · FW: {device.firmwareVersion}
                              </p>
                            </div>
                          </div>
                          <span className={`text-xs font-bold px-3 py-1 rounded-lg border ${connMeta.bgColor} ${connMeta.textColor} border ${connMeta.borderColor}`}>
                            {connMeta.label}
                          </span>
                        </div>

                        {/* Device Stats Grid */}
                        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                          <div className="bg-white/80 rounded-lg p-2.5 border border-gray-100">
                            <p className="text-[10px] text-gray-400 font-medium mb-1">Battery</p>
                            <div className="flex items-center gap-2">
                              <div className="flex-1 h-2 bg-gray-100 rounded-full overflow-hidden">
                                <div
                                  className={`h-full rounded-full transition-all duration-500 ${getBatteryBgColor(device.health.batteryPercent)}`}
                                  style={{ width: `${device.health.batteryPercent}%` }}
                                />
                              </div>
                              <span className={`text-xs font-bold ${getBatteryColor(device.health.batteryPercent)}`}>
                                {Math.round(device.health.batteryPercent)}%
                              </span>
                            </div>
                          </div>

                          <div className="bg-white/80 rounded-lg p-2.5 border border-gray-100">
                            <p className="text-[10px] text-gray-400 font-medium mb-1">Signal Quality</p>
                            <div className="flex items-center gap-2">
                              <div className="flex items-center gap-0.5">
                                {[1, 2, 3, 4].map((bar) => (
                                  <div
                                    key={bar}
                                    className={`w-1.5 rounded-sm ${bar <= sigMeta.bars ? sigMeta.color : 'bg-gray-200'}`}
                                    style={{ height: `${bar * 4 + 2}px` }}
                                  />
                                ))}
                              </div>
                              <span className={`text-xs font-bold ${sigMeta.textColor}`}>{sigMeta.label}</span>
                            </div>
                          </div>

                          <div className="bg-white/80 rounded-lg p-2.5 border border-gray-100">
                            <p className="text-[10px] text-gray-400 font-medium mb-1">Uptime</p>
                            <p className="text-xs font-bold text-gray-700">{formatDeviceUptime(device.health.uptimeMinutes)}</p>
                          </div>

                          <div className="bg-white/80 rounded-lg p-2.5 border border-gray-100">
                            <p className="text-[10px] text-gray-400 font-medium mb-1">Connected Since</p>
                            <p className="text-xs font-bold text-gray-700">{getConnectionDuration(device)}</p>
                          </div>
                        </div>

                        {/* Provided Vitals */}
                        <div className="mt-3 pt-3 border-t border-gray-100">
                          <p className="text-[10px] text-gray-400 font-bold uppercase tracking-wider mb-1.5">Vitals Provided</p>
                          <div className="flex flex-wrap gap-1.5">
                            {device.providedVitals.map((vital) => (
                              <span
                                key={vital}
                                className="text-[10px] font-semibold bg-cyan-50 text-cyan-700 px-2 py-0.5 rounded-md border border-cyan-100"
                              >
                                {vital.replace(/([A-Z])/g, ' $1').replace(/^./, (s) => s.toUpperCase()).trim()}
                              </span>
                            ))}
                          </div>
                        </div>

                        {/* Connection Log (last 5) */}
                        {device.connectionLog.length > 0 && (
                          <div className="mt-3 pt-3 border-t border-gray-100">
                            <p className="text-[10px] text-gray-400 font-bold uppercase tracking-wider mb-1.5">Recent Events</p>
                            <div className="space-y-1">
                              {device.connectionLog.slice(-5).reverse().map((evt, i) => (
                                <div key={i} className="flex items-center gap-2 text-[11px]">
                                  <div className={`w-1.5 h-1.5 rounded-full flex-shrink-0 ${
                                    evt.event === 'CONNECTED' || evt.event === 'RECONNECTED' ? 'bg-green-500' :
                                    evt.event === 'PAIRED' ? 'bg-cyan-500' :
                                    evt.event === 'DISCONNECTED' || evt.event === 'SIGNAL_LOST' ? 'bg-red-500' :
                                    evt.event === 'BATTERY_LOW' ? 'bg-amber-500' :
                                    'bg-gray-400'
                                  }`} />
                                  <span className="text-gray-500 font-medium w-14 flex-shrink-0">
                                    {new Date(evt.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                                  </span>
                                  <span className="text-gray-700">{evt.details}</span>
                                </div>
                              ))}
                            </div>
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              )}
            </div>

            {/* Data Quality & Coverage */}
            <div className={`rounded-xl shadow-md p-4 ${isDark ? glassCard + ' border border-white/10' : 'bg-white border border-gray-200'}`}>
              <h3 className={`text-sm font-bold mb-3 flex items-center gap-2 ${isDark ? 'text-white' : 'text-gray-900'}`}>
                <Radio className={`w-4 h-4 ${isDark ? 'text-cyan-400' : 'text-cyan-600'}`} />
                Data Quality & Coverage
              </h3>
              <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                <div className="bg-gradient-to-br from-green-50 to-emerald-50 rounded-xl p-3 border border-green-200">
                  <p className="text-[10px] text-green-600 font-bold uppercase tracking-wider mb-1">Connected</p>
                  <p className="text-2xl font-extrabold text-green-700">{deviceSummary.connectedDevices}</p>
                  <p className="text-[10px] text-green-500">of {deviceSummary.totalDevices} devices</p>
                </div>
                <div className="bg-gradient-to-br from-cyan-50 to-blue-50 rounded-xl p-3 border border-cyan-200">
                  <p className="text-[10px] text-cyan-600 font-bold uppercase tracking-wider mb-1">Vitals Covered</p>
                  <p className="text-2xl font-extrabold text-cyan-700">{deviceSummary.coveredVitals.length}</p>
                  <p className="text-[10px] text-cyan-500">of 8 parameters</p>
                </div>
                <div className={`rounded-xl p-3 border ${
                  deviceSummary.lowestBattery > 50
                    ? 'bg-gradient-to-br from-green-50 to-emerald-50 border-green-200'
                    : deviceSummary.lowestBattery > 20
                      ? 'bg-gradient-to-br from-amber-50 to-yellow-50 border-amber-200'
                      : 'bg-gradient-to-br from-red-50 to-orange-50 border-red-200'
                }`}>
                  <p className="text-[10px] font-bold uppercase tracking-wider mb-1" style={{ color: deviceSummary.lowestBattery > 50 ? '#059669' : deviceSummary.lowestBattery > 20 ? '#d97706' : '#dc2626' }}>
                    Min Battery
                  </p>
                  <p className="text-2xl font-extrabold" style={{ color: deviceSummary.lowestBattery > 50 ? '#047857' : deviceSummary.lowestBattery > 20 ? '#b45309' : '#b91c1c' }}>
                    {Math.round(deviceSummary.lowestBattery)}%
                  </p>
                </div>
                <div className={`rounded-xl p-3 border ${
                  deviceSummary.overallHealth === 'HEALTHY'
                    ? 'bg-gradient-to-br from-green-50 to-emerald-50 border-green-200'
                    : deviceSummary.overallHealth === 'WARNING'
                      ? 'bg-gradient-to-br from-amber-50 to-yellow-50 border-amber-200'
                      : 'bg-gradient-to-br from-red-50 to-orange-50 border-red-200'
                }`}>
                  <p className="text-[10px] font-bold uppercase tracking-wider mb-1" style={{ color: deviceSummary.overallHealth === 'HEALTHY' ? '#059669' : deviceSummary.overallHealth === 'WARNING' ? '#d97706' : '#dc2626' }}>
                    Overall Health
                  </p>
                  <p className="text-lg font-extrabold" style={{ color: deviceSummary.overallHealth === 'HEALTHY' ? '#047857' : deviceSummary.overallHealth === 'WARNING' ? '#b45309' : '#b91c1c' }}>
                    {deviceSummary.overallHealth}
                  </p>
                </div>
              </div>
            </div>
          </div>
        )}

        {activeTab === 'notes' && patientId && (
          <ClinicalNotesPanel visitId={patientId} />
        )}

      </div>
    </div>
  );
}

// Helper Components
interface VitalCardProps {
  label: string;
  value: string | number;
  unit?: string;
  icon: React.ComponentType<any>;
  status: 'normal' | 'warning' | 'critical';
  trend?: 'up' | 'down' | 'stable';
  range?: string;
  subtitle?: string;
}

const VitalCard: React.FC<VitalCardProps> = ({ label, value, unit, icon: Icon, status, trend, range, subtitle }) => {
  const { isDark } = useTheme();

  const getStatusStyles = () => {
    if (isDark) {
      switch (status) {
        case 'critical':
          return {
            border: 'border-red-500/30',
            bg: 'bg-red-500/10',
            iconBg: 'bg-red-500/20',
            iconColor: 'text-red-400',
            valueColor: 'text-red-300',
            ring: 'ring-red-500/20'
          };
        case 'warning':
          return {
            border: 'border-amber-500/30',
            bg: 'bg-amber-500/10',
            iconBg: 'bg-amber-500/20',
            iconColor: 'text-amber-400',
            valueColor: 'text-amber-300',
            ring: 'ring-amber-500/20'
          };
        default:
          return {
            border: 'border-green-500/30',
            bg: 'bg-green-500/10',
            iconBg: 'bg-green-500/20',
            iconColor: 'text-green-400',
            valueColor: 'text-green-300',
            ring: 'ring-green-500/20'
          };
      }
    }
    switch (status) {
      case 'critical': 
        return {
          border: 'border-red-200',
          bg: 'bg-gradient-to-br from-red-50 to-red-100/50',
          iconBg: 'bg-red-100',
          iconColor: 'text-red-600',
          valueColor: 'text-red-700',
          ring: 'ring-red-100'
        };
      case 'warning': 
        return {
          border: 'border-amber-200',
          bg: 'bg-gradient-to-br from-amber-50 to-amber-100/50',
          iconBg: 'bg-amber-100',
          iconColor: 'text-amber-600', 
          valueColor: 'text-amber-700',
          ring: 'ring-amber-100'
        };
      default: 
        return {
          border: 'border-green-200',
          bg: 'bg-gradient-to-br from-green-50 to-emerald-100/50',
          iconBg: 'bg-green-100',
          iconColor: 'text-green-600',
          valueColor: 'text-green-700',
          ring: 'ring-green-100'
        };
    }
  };

  const getTrendIcon = () => {
    switch (trend) {
      case 'up': return <TrendingUp className="w-3 h-3 text-red-500" />;
      case 'down': return <TrendingDown className="w-3 h-3 text-blue-500" />;
      default: return <Minus className="w-3 h-3 text-gray-400" />;
    }
  };

  const styles = getStatusStyles();

  return (
    <div className={`rounded-2xl border-2 ${styles.border} ${styles.bg} p-4 hover:shadow-xl hover:-translate-y-0.5 transition-all duration-300 ring-1 ${styles.ring} relative overflow-hidden group ${isDark ? '' : 'bg-white'}`}>
      <div className={`absolute inset-0 bg-gradient-to-r ${isDark ? 'from-white/5' : 'from-white/10'} to-transparent opacity-0 group-hover:opacity-100 transition-opacity duration-300`}></div>
      <div className="relative">
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center gap-2">
            <div className={`w-8 h-8 rounded-lg ${styles.iconBg} flex items-center justify-center`}>
              <Icon className={`w-4 h-4 ${styles.iconColor}`} />
            </div>
            <span className={`text-sm font-semibold ${isDark ? 'text-slate-300' : 'text-gray-700'}`}>{label}</span>
          </div>
          {getTrendIcon()}
        </div>
        <div className="space-y-2">
          <div className="flex items-baseline gap-1">
            <span className={`text-2xl font-bold ${styles.valueColor} tracking-tight`}>{value}</span>
            {unit && <span className={`text-sm font-medium ${isDark ? 'text-slate-400' : 'text-gray-600'}`}>{unit}</span>}
          </div>
          {subtitle && (
            <p className={`text-xs font-medium ${isDark ? 'text-slate-400' : 'text-gray-600'}`}>{subtitle}</p>
          )}
          {range && (
            <p className={`text-xs px-2 py-1 rounded-md ${isDark ? 'text-slate-400 bg-white/5' : 'text-gray-500 bg-white/60'}`}>Normal: {range}</p>
          )}
        </div>
      </div>
    </div>
  );
};

const TrendChartCard: React.FC<{
  title: string;
  data: any[];
  dataKey: string;
  color: string;
  unit: string;
}> = ({ title, data, dataKey, color, unit }) => {
  const { isDark } = useTheme();

  return (
    <div className={`rounded-xl shadow-md p-4 ${isDark ? 'bg-[rgba(12,74,110,0.12)] backdrop-blur-xl border border-white/10' : 'bg-white border border-gray-200'}`}>
      <h4 className={`text-base font-bold mb-3 ${isDark ? 'text-white' : 'text-gray-900'}`}>{title}</h4>
      <div className="h-48">
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={data}>
            <defs>
              <linearGradient id={`gradient-${dataKey}`} x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%" stopColor={color} stopOpacity={0.3} />
                <stop offset="95%" stopColor={color} stopOpacity={0} />
              </linearGradient>
            </defs>
            <XAxis 
              dataKey="timestamp" 
              tick={{ fontSize: 11, fill: isDark ? '#94a3b8' : '#6b7280' }}
              tickFormatter={(value) => new Date(value).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
            />
            <YAxis 
              tick={{ fontSize: 11, fill: isDark ? '#94a3b8' : '#6b7280' }}
              label={{ value: unit, angle: -90, position: 'insideLeft', style: { fontSize: 12, fill: isDark ? '#94a3b8' : '#6b7280' } }}
            />
            <Tooltip 
              contentStyle={{ 
                backgroundColor: isDark ? 'rgba(12, 74, 110, 0.9)' : 'white', 
                border: isDark ? '1px solid rgba(2, 132, 199, 0.3)' : '1px solid #e5e7eb', 
                borderRadius: '8px',
                fontSize: 12,
                color: isDark ? '#e2e8f0' : '#1f2937',
                backdropFilter: isDark ? 'blur(12px)' : undefined,
              }}
              labelFormatter={(value) => new Date(value).toLocaleString()}
            />
            <Area 
              type="monotone" 
              dataKey={dataKey} 
              stroke={color} 
              fill={`url(#gradient-${dataKey})`}
              strokeWidth={2}
            />
          </AreaChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
};
