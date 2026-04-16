/**
 * IoT Device Manager — Module 4
 *
 * Provides device lifecycle management, health monitoring simulation,
 * connection-loss handling, and data quality assessment utilities.
 */

import type {
  IoTDevice,
  DeviceType,
  ConnectionStatus,
  SignalQuality,
  DeviceHealth,
  PatientDeviceSummary,
} from '@/types';

// ── Device type display metadata ──────────────────

export interface DeviceTypeMeta {
  label: string;
  shortLabel: string;
  icon: string;          // lucide-react icon name suggestion
  vitalLabels: string[];
  color: string;         // tailwind color stem
}

export const DEVICE_TYPE_META: Record<DeviceType, DeviceTypeMeta> = {
  PULSE_OXIMETER: {
    label: 'Pulse Oximeter',
    shortLabel: 'SpO2',
    icon: 'Activity',
    vitalLabels: ['Heart Rate', 'SpO2'],
    color: 'indigo',
  },
  ECG_MONITOR: {
    label: 'ECG Monitor',
    shortLabel: 'ECG',
    icon: 'Zap',
    vitalLabels: ['Heart Rate', 'ECG'],
    color: 'yellow',
  },
  BP_MONITOR: {
    label: 'Blood Pressure Monitor',
    shortLabel: 'BP',
    icon: 'Droplet',
    vitalLabels: ['Systolic BP', 'Diastolic BP'],
    color: 'red',
  },
  THERMOMETER: {
    label: 'Digital Thermometer',
    shortLabel: 'Temp',
    icon: 'Thermometer',
    vitalLabels: ['Temperature'],
    color: 'orange',
  },
  GLUCOMETER: {
    label: 'Glucometer',
    shortLabel: 'Gluc',
    icon: 'Candy',
    vitalLabels: ['Glucose'],
    color: 'pink',
  },
  RESPIRATORY_MONITOR: {
    label: 'Respiratory Monitor',
    shortLabel: 'Resp',
    icon: 'Wind',
    vitalLabels: ['Respiratory Rate'],
    color: 'blue',
  },
  MULTI_PARAMETER: {
    label: 'Multi-Parameter Monitor',
    shortLabel: 'Multi',
    icon: 'Monitor',
    vitalLabels: ['HR', 'RR', 'SpO2', 'BP', 'Temp', 'ECG', 'Gluc'],
    color: 'cyan',
  },
};

// ── Connection status display metadata ──────────────

export interface ConnectionStatusMeta {
  label: string;
  color: string;         // tailwind color stem
  bgColor: string;       // background class
  borderColor: string;   // border class
  textColor: string;     // text class
  animate: boolean;      // whether to animate indicator
}

export const CONNECTION_STATUS_META: Record<ConnectionStatus, ConnectionStatusMeta> = {
  CONNECTED: {
    label: 'Connected',
    color: 'green',
    bgColor: 'bg-green-50',
    borderColor: 'border-green-200',
    textColor: 'text-green-700',
    animate: true,
  },
  DISCONNECTED: {
    label: 'Disconnected',
    color: 'gray',
    bgColor: 'bg-gray-50',
    borderColor: 'border-gray-200',
    textColor: 'text-gray-500',
    animate: false,
  },
  SCANNING: {
    label: 'Scanning...',
    color: 'blue',
    bgColor: 'bg-blue-50',
    borderColor: 'border-blue-200',
    textColor: 'text-blue-600',
    animate: true,
  },
  PAIRING: {
    label: 'Pairing...',
    color: 'cyan',
    bgColor: 'bg-cyan-50',
    borderColor: 'border-cyan-200',
    textColor: 'text-cyan-600',
    animate: true,
  },
  RECONNECTING: {
    label: 'Reconnecting...',
    color: 'amber',
    bgColor: 'bg-amber-50',
    borderColor: 'border-amber-200',
    textColor: 'text-amber-600',
    animate: true,
  },
  ERROR: {
    label: 'Error',
    color: 'red',
    bgColor: 'bg-red-50',
    borderColor: 'border-red-200',
    textColor: 'text-red-600',
    animate: false,
  },
};

// ── Signal quality display metadata ──────────────

export interface SignalQualityMeta {
  label: string;
  bars: number;          // 0-4
  color: string;
  textColor: string;
}

export const SIGNAL_QUALITY_META: Record<SignalQuality, SignalQualityMeta> = {
  EXCELLENT: { label: 'Excellent', bars: 4, color: 'bg-green-500', textColor: 'text-green-600' },
  GOOD: { label: 'Good', bars: 3, color: 'bg-green-400', textColor: 'text-green-500' },
  FAIR: { label: 'Fair', bars: 2, color: 'bg-amber-400', textColor: 'text-amber-500' },
  POOR: { label: 'Poor', bars: 1, color: 'bg-red-400', textColor: 'text-red-500' },
  LOST: { label: 'Lost', bars: 0, color: 'bg-red-500', textColor: 'text-red-600' },
};

// ── Battery status helpers ──────────────

export function getBatteryColor(percent: number): string {
  if (percent > 50) return 'text-green-500';
  if (percent > 25) return 'text-amber-500';
  if (percent > 10) return 'text-orange-500';
  return 'text-red-500';
}

export function getBatteryBgColor(percent: number): string {
  if (percent > 50) return 'bg-green-500';
  if (percent > 25) return 'bg-amber-500';
  if (percent > 10) return 'bg-orange-500';
  return 'bg-red-500';
}

export function getBatteryLabel(percent: number): string {
  if (percent > 80) return 'Full';
  if (percent > 50) return 'Good';
  if (percent > 25) return 'Low';
  if (percent > 10) return 'Very Low';
  return 'Critical';
}

// ── Overall health assessment ──────────────

export function assessOverallHealth(summary: PatientDeviceSummary): {
  status: 'HEALTHY' | 'WARNING' | 'CRITICAL';
  message: string;
  issues: string[];
} {
  const issues: string[] = [];

  if (summary.anyDisconnected) {
    issues.push('One or more devices disconnected');
  }
  if (summary.lowestBattery < 15) {
    issues.push(`Device battery critically low (${summary.lowestBattery}%)`);
  } else if (summary.lowestBattery < 30) {
    issues.push(`Device battery low (${summary.lowestBattery}%)`);
  }
  if (summary.weakestSignal === 'LOST') {
    issues.push('Signal lost on one or more devices');
  } else if (summary.weakestSignal === 'POOR') {
    issues.push('Poor signal quality detected');
  }
  if (summary.uncoveredVitals.length > 0) {
    issues.push(`Uncovered vitals: ${summary.uncoveredVitals.join(', ')}`);
  }

  let status: 'HEALTHY' | 'WARNING' | 'CRITICAL' = 'HEALTHY';
  let message = 'All devices operating normally';

  if (issues.length > 0 && summary.overallHealth === 'CRITICAL') {
    status = 'CRITICAL';
    message = 'Immediate attention required — device issues detected';
  } else if (issues.length > 0) {
    status = 'WARNING';
    message = 'Some device issues detected — review recommended';
  }

  return { status, message, issues };
}

// ── Simulated device fleet ──────────────
// Pre-configured devices that can be "scanned" and paired in the demo

export interface SimulatedDeviceConfig {
  name: string;
  type: DeviceType;
  manufacturer: string;
  model: string;
  serialNumber: string;
  firmwareVersion: string;
}

export const SIMULATED_DEVICE_FLEET: SimulatedDeviceConfig[] = [
  {
    name: 'Masimo Rad-97 #1',
    type: 'PULSE_OXIMETER',
    manufacturer: 'Masimo',
    model: 'Rad-97',
    serialNumber: 'MAS-2024-001',
    firmwareVersion: '3.2.1',
  },
  {
    name: 'Philips IntelliVue #1',
    type: 'MULTI_PARAMETER',
    manufacturer: 'Philips',
    model: 'IntelliVue MX800',
    serialNumber: 'PHI-2024-001',
    firmwareVersion: '5.0.4',
  },
  {
    name: 'Nihon Kohden ECG-1350',
    type: 'ECG_MONITOR',
    manufacturer: 'Nihon Kohden',
    model: 'ECG-1350',
    serialNumber: 'NK-2024-001',
    firmwareVersion: '2.1.0',
  },
  {
    name: 'Omron HEM-7156',
    type: 'BP_MONITOR',
    manufacturer: 'Omron',
    model: 'HEM-7156',
    serialNumber: 'OMR-2024-001',
    firmwareVersion: '1.4.2',
  },
  {
    name: 'Braun ThermoScan 7',
    type: 'THERMOMETER',
    manufacturer: 'Braun',
    model: 'ThermoScan 7 IRT6520',
    serialNumber: 'BRN-2024-001',
    firmwareVersion: '1.0.3',
  },
  {
    name: 'Abbott FreeStyle Libre 3',
    type: 'GLUCOMETER',
    manufacturer: 'Abbott',
    model: 'FreeStyle Libre 3',
    serialNumber: 'ABT-2024-001',
    firmwareVersion: '2.3.0',
  },
  {
    name: 'Capnostream 35 #1',
    type: 'RESPIRATORY_MONITOR',
    manufacturer: 'Medtronic',
    model: 'Capnostream 35',
    serialNumber: 'MDT-2024-001',
    firmwareVersion: '4.1.2',
  },
  {
    name: 'Philips IntelliVue #2',
    type: 'MULTI_PARAMETER',
    manufacturer: 'Philips',
    model: 'IntelliVue MX550',
    serialNumber: 'PHI-2024-002',
    firmwareVersion: '4.8.1',
  },
  {
    name: 'Masimo Rad-97 #2',
    type: 'PULSE_OXIMETER',
    manufacturer: 'Masimo',
    model: 'Rad-97',
    serialNumber: 'MAS-2024-002',
    firmwareVersion: '3.2.1',
  },
  {
    name: 'Omron HEM-7156 #2',
    type: 'BP_MONITOR',
    manufacturer: 'Omron',
    model: 'HEM-7156',
    serialNumber: 'OMR-2024-002',
    firmwareVersion: '1.4.2',
  },
];

// ── Simulate health changes (called periodically) ──────────────

export function simulateHealthTick(device: IoTDevice): Partial<DeviceHealth> {
  const h = device.health;

  // Battery drain: ~0.05% per tick (2s interval → ~1.5%/min → ~90%/hr)
  // Slow it down for demo: 0.01% per tick → ~0.3%/min
  const batteryDrain = 0.01 + Math.random() * 0.005;
  const newBattery = Math.max(0, h.batteryPercent - batteryDrain);

  // Signal fluctuation: ±3 around current
  const signalChange = (Math.random() - 0.5) * 6;
  const newSignal = Math.max(0, Math.min(100, h.signalStrength + signalChange));

  // Data drop rate: slight random fluctuation
  const dropChange = (Math.random() - 0.5) * 0.02;
  const newDropRate = Math.max(0, Math.min(0.5, h.dataDropRate + dropChange));

  // Uptime ticks up
  const newUptime = h.uptimeMinutes + (device.samplingIntervalMs / 60000);

  return {
    batteryPercent: Math.round(newBattery * 100) / 100,
    signalStrength: Math.round(newSignal * 10) / 10,
    dataDropRate: Math.round(newDropRate * 1000) / 1000,
    uptimeMinutes: Math.round(newUptime * 100) / 100,
    lastDataReceived: new Date(),
  };
}

// ── Simulate random disconnection events ──────────────
// Returns true if a disconnect should be simulated this tick (~0.2% chance per tick)

export function shouldSimulateDisconnect(): boolean {
  return Math.random() < 0.002;
}

// Returns true if a reconnection should occur (~10% chance per tick while disconnected)
export function shouldSimulateReconnect(): boolean {
  return Math.random() < 0.1;
}

// ── Format helpers ──────────────

export function formatDeviceUptime(minutes: number): string {
  if (minutes < 1) return '< 1 min';
  if (minutes < 60) return `${Math.round(minutes)} min`;
  const hrs = Math.floor(minutes / 60);
  const mins = Math.round(minutes % 60);
  return `${hrs}h ${mins}m`;
}

export function formatLastDataReceived(date: Date | null): string {
  if (!date) return 'Never';
  const seconds = Math.floor((Date.now() - date.getTime()) / 1000);
  if (seconds < 5) return 'Just now';
  if (seconds < 60) return `${seconds}s ago`;
  const mins = Math.floor(seconds / 60);
  return `${mins}m ago`;
}

export function getConnectionDuration(device: IoTDevice): string {
  if (!device.pairedAt) return '—';
  const mins = Math.floor((Date.now() - device.pairedAt.getTime()) / 60000);
  if (mins < 1) return '< 1 min';
  if (mins < 60) return `${mins} min`;
  const hrs = Math.floor(mins / 60);
  return `${hrs}h ${mins % 60}m`;
}
