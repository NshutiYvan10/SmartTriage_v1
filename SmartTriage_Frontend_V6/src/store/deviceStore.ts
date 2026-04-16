import { create } from 'zustand';
import type {
  IoTDevice,
  DeviceType,
  ConnectionStatus,
  SignalQuality,
  DeviceHealth,
  DeviceConnectionEvent,
  PatientDeviceSummary,
  VitalSigns,
} from '@/types';
import { iotApi } from '@/api/iot';
import type { DeviceResponse } from '@/api/types';

// ── Helper: generate unique ID ──
let _deviceCounter = 0;
function nextDeviceId(): string {
  _deviceCounter++;
  return `DEV-${Date.now().toString(36)}-${_deviceCounter}`;
}

// ── Vitals covered by each device type ──
const DEVICE_VITAL_MAP: Record<DeviceType, (keyof Omit<VitalSigns, 'timestamp' | 'deviceConnected'>)[]> = {
  PULSE_OXIMETER: ['heartRate', 'spo2'],
  ECG_MONITOR: ['heartRate', 'ecg'],
  BP_MONITOR: ['systolicBP', 'diastolicBP'],
  THERMOMETER: ['temperature'],
  GLUCOMETER: ['glucose'],
  RESPIRATORY_MONITOR: ['respiratoryRate'],
  MULTI_PARAMETER: ['heartRate', 'respiratoryRate', 'spo2', 'systolicBP', 'diastolicBP', 'temperature', 'ecg', 'glucose'],
};

// ── Signal quality from numeric strength ──
export function signalQualityFromStrength(strength: number): SignalQuality {
  if (strength >= 80) return 'EXCELLENT';
  if (strength >= 60) return 'GOOD';
  if (strength >= 40) return 'FAIR';
  if (strength >= 15) return 'POOR';
  return 'LOST';
}

// ── Default device health ──
function defaultHealth(): DeviceHealth {
  return {
    batteryPercent: 100,
    signalStrength: 85,
    signalQuality: 'EXCELLENT',
    lastDataReceived: null,
    dataDropRate: 0,
    uptimeMinutes: 0,
    firmwareUpToDate: true,
    errorCount: 0,
  };
}

// ── Map backend DeviceResponse → frontend IoTDevice ──
function mapDeviceResponse(d: DeviceResponse): IoTDevice {
  const typeMap: Record<string, DeviceType> = {
    PULSE_OXIMETER: 'PULSE_OXIMETER',
    ECG_MONITOR: 'ECG_MONITOR',
    BP_MONITOR: 'BP_MONITOR',
    THERMOMETER: 'THERMOMETER',
    GLUCOMETER: 'GLUCOMETER',
    MULTI_PARAMETER: 'MULTI_PARAMETER',
    RESPIRATORY_MONITOR: 'RESPIRATORY_MONITOR',
  };
  const type = typeMap[d.deviceType] || 'MULTI_PARAMETER';

  const statusMap: Record<string, ConnectionStatus> = {
    REGISTERED: 'DISCONNECTED',
    ONLINE: 'CONNECTED',
    OFFLINE: 'DISCONNECTED',
    MONITORING: 'CONNECTED',
    ERROR: 'ERROR',
    DECOMMISSIONED: 'DISCONNECTED',
  };
  const connectionStatus = statusMap[d.status] || 'DISCONNECTED';

  const rssi = d.wifiRssi ?? 0;
  const signalStrength = Math.max(0, Math.min(100, rssi + 100)); // rough RSSI → 0-100
  return {
    id: d.id,
    name: d.deviceName,
    type,
    manufacturer: '',
    model: '',
    serialNumber: d.serialNumber,
    firmwareVersion: d.firmwareVersion || '',
    connectionStatus,
    health: {
      batteryPercent: d.batteryLevel ?? 100,
      signalStrength,
      signalQuality: signalQualityFromStrength(signalStrength),
      lastDataReceived: d.lastDataAt ? new Date(d.lastDataAt) : null,
      dataDropRate: 0,
      uptimeMinutes: 0,
      firmwareUpToDate: true,
      errorCount: 0,
    },
    pairedPatientId: d.activeVisitId,
    pairedAt: d.lastHeartbeatAt ? new Date(d.lastHeartbeatAt) : null,
    providedVitals: DEVICE_VITAL_MAP[type] || [],
    isStreaming: d.status === 'MONITORING',
    samplingIntervalMs: 2000,
    connectionLog: [],
  };
}

// ── Store interface ──
interface DeviceState {
  devices: Map<string, IoTDevice>;

  /** Fetch all devices for a hospital from the API */
  fetchDevicesFromApi: (hospitalId: string) => Promise<void>;

  // ── Actions ──
  addDevice: (config: {
    name: string;
    type: DeviceType;
    manufacturer: string;
    model: string;
    serialNumber: string;
    firmwareVersion: string;
  }) => IoTDevice;

  removeDevice: (deviceId: string) => void;

  pairDevice: (deviceId: string, patientId: string) => boolean;
  unpairDevice: (deviceId: string) => void;

  setConnectionStatus: (deviceId: string, status: ConnectionStatus, details?: string) => void;
  updateDeviceHealth: (deviceId: string, health: Partial<DeviceHealth>) => void;
  addConnectionEvent: (deviceId: string, event: DeviceConnectionEvent) => void;
  setStreaming: (deviceId: string, streaming: boolean) => void;

  // ── Queries ──
  getDevice: (deviceId: string) => IoTDevice | undefined;
  getDevicesForPatient: (patientId: string) => IoTDevice[];
  getAvailableDevices: () => IoTDevice[];
  getAllDevices: () => IoTDevice[];
  getPatientDeviceSummary: (patientId: string) => PatientDeviceSummary;
  isVitalCovered: (patientId: string, vitalKey: string) => boolean;
}

export const useDeviceStore = create<DeviceState>((set, get) => ({
  devices: new Map(),

  fetchDevicesFromApi: async (hospitalId: string) => {
    try {
      const page = await iotApi.getDevicesByHospital(hospitalId, 0, 200);
      const newDevices = new Map<string, IoTDevice>();
      for (const d of page.content) {
        const mapped = mapDeviceResponse(d);
        newDevices.set(mapped.id, mapped);
      }
      set({ devices: newDevices });
    } catch (err) {
      console.error('[deviceStore] fetchDevicesFromApi failed:', err);
    }
  },

  // ── Add a new device to the registry ──
  addDevice: (config) => {
    const { devices } = get();
    const id = nextDeviceId();

    const device: IoTDevice = {
      id,
      name: config.name,
      type: config.type,
      manufacturer: config.manufacturer,
      model: config.model,
      serialNumber: config.serialNumber,
      firmwareVersion: config.firmwareVersion,
      connectionStatus: 'DISCONNECTED',
      health: defaultHealth(),
      pairedPatientId: null,
      pairedAt: null,
      providedVitals: DEVICE_VITAL_MAP[config.type] || [],
      isStreaming: false,
      samplingIntervalMs: 2000,
      connectionLog: [],
    };

    const newDevices = new Map(devices);
    newDevices.set(id, device);
    set({ devices: newDevices });
    return device;
  },

  // ── Remove device ──
  removeDevice: (deviceId) => {
    const { devices } = get();
    const newDevices = new Map(devices);
    newDevices.delete(deviceId);
    set({ devices: newDevices });
  },

  // ── Pair device to patient ──
  pairDevice: (deviceId, patientId) => {
    const { devices } = get();
    const device = devices.get(deviceId);
    if (!device) return false;

    // Check if already paired to another patient
    if (device.pairedPatientId && device.pairedPatientId !== patientId) return false;

    const now = new Date();
    const updated: IoTDevice = {
      ...device,
      pairedPatientId: patientId,
      pairedAt: now,
      connectionStatus: 'CONNECTED',
      isStreaming: true,
      connectionLog: [
        ...device.connectionLog.slice(-49),
        { timestamp: now, event: 'PAIRED', details: `Paired to patient ${patientId}` },
        { timestamp: now, event: 'CONNECTED', details: 'Device connected and streaming' },
      ],
    };

    const newDevices = new Map(devices);
    newDevices.set(deviceId, updated);
    set({ devices: newDevices });
    return true;
  },

  // ── Unpair device ──
  unpairDevice: (deviceId) => {
    const { devices } = get();
    const device = devices.get(deviceId);
    if (!device) return;

    const now = new Date();
    const updated: IoTDevice = {
      ...device,
      pairedPatientId: null,
      pairedAt: null,
      connectionStatus: 'DISCONNECTED',
      isStreaming: false,
      connectionLog: [
        ...device.connectionLog.slice(-49),
        { timestamp: now, event: 'UNPAIRED', details: 'Device unpaired from patient' },
      ],
    };

    const newDevices = new Map(devices);
    newDevices.set(deviceId, updated);
    set({ devices: newDevices });
  },

  // ── Update connection status ──
  setConnectionStatus: (deviceId, status, details) => {
    const { devices } = get();
    const device = devices.get(deviceId);
    if (!device) return;

    const now = new Date();
    const eventMap: Record<ConnectionStatus, DeviceConnectionEvent['event']> = {
      CONNECTED: 'CONNECTED',
      DISCONNECTED: 'DISCONNECTED',
      RECONNECTING: 'DISCONNECTED',
      SCANNING: 'DISCONNECTED',
      PAIRING: 'CONNECTED',
      ERROR: 'ERROR',
    };

    const updated: IoTDevice = {
      ...device,
      connectionStatus: status,
      isStreaming: status === 'CONNECTED',
      connectionLog: [
        ...device.connectionLog.slice(-49),
        { timestamp: now, event: eventMap[status], details: details || `Status changed to ${status}` },
      ],
    };

    // If disconnected/error, stop streaming
    if (status === 'DISCONNECTED' || status === 'ERROR') {
      updated.isStreaming = false;
    }

    const newDevices = new Map(devices);
    newDevices.set(deviceId, updated);
    set({ devices: newDevices });
  },

  // ── Update device health metrics ──
  updateDeviceHealth: (deviceId, healthUpdate) => {
    const { devices } = get();
    const device = devices.get(deviceId);
    if (!device) return;

    const newHealth: DeviceHealth = {
      ...device.health,
      ...healthUpdate,
    };

    // Recompute signal quality from strength
    if (healthUpdate.signalStrength !== undefined) {
      newHealth.signalQuality = signalQualityFromStrength(healthUpdate.signalStrength);
    }

    const connectionLog = [...device.connectionLog];

    // Auto-log battery warnings
    if (newHealth.batteryPercent <= 15 && device.health.batteryPercent > 15) {
      connectionLog.push({
        timestamp: new Date(),
        event: 'BATTERY_LOW',
        details: `Battery critically low: ${newHealth.batteryPercent}%`,
      });
    }

    // Auto-log signal loss
    if (newHealth.signalQuality === 'LOST' && device.health.signalQuality !== 'LOST') {
      connectionLog.push({
        timestamp: new Date(),
        event: 'SIGNAL_LOST',
        details: 'Signal quality degraded to LOST',
      });
    }

    const updated: IoTDevice = {
      ...device,
      health: newHealth,
      connectionLog: connectionLog.slice(-50),
    };

    const newDevices = new Map(devices);
    newDevices.set(deviceId, updated);
    set({ devices: newDevices });
  },

  // ── Add connection event manually ──
  addConnectionEvent: (deviceId, event) => {
    const { devices } = get();
    const device = devices.get(deviceId);
    if (!device) return;

    const updated: IoTDevice = {
      ...device,
      connectionLog: [...device.connectionLog.slice(-49), event],
    };

    const newDevices = new Map(devices);
    newDevices.set(deviceId, updated);
    set({ devices: newDevices });
  },

  // ── Toggle streaming ──
  setStreaming: (deviceId, streaming) => {
    const { devices } = get();
    const device = devices.get(deviceId);
    if (!device) return;

    const updated: IoTDevice = { ...device, isStreaming: streaming };
    const newDevices = new Map(devices);
    newDevices.set(deviceId, updated);
    set({ devices: newDevices });
  },

  // ── Get single device ──
  getDevice: (deviceId) => get().devices.get(deviceId),

  // ── Get all devices paired to patient ──
  getDevicesForPatient: (patientId) => {
    const all = Array.from(get().devices.values());
    return all.filter((d) => d.pairedPatientId === patientId);
  },

  // ── Get unpaired devices ──
  getAvailableDevices: () => {
    const all = Array.from(get().devices.values());
    return all.filter((d) => d.pairedPatientId === null);
  },

  // ── Get all devices ──
  getAllDevices: () => Array.from(get().devices.values()),

  // ── Patient device summary ──
  getPatientDeviceSummary: (patientId) => {
    const patientDevices = get().getDevicesForPatient(patientId);
    const connectedDevices = patientDevices.filter((d) => d.connectionStatus === 'CONNECTED');

    const allVitals: (keyof Omit<VitalSigns, 'timestamp' | 'deviceConnected'>)[] = [
      'heartRate', 'respiratoryRate', 'spo2', 'systolicBP', 'diastolicBP', 'temperature', 'ecg', 'glucose',
    ];

    const coveredSet = new Set<string>();
    connectedDevices.forEach((d) => d.providedVitals.forEach((v) => coveredSet.add(v)));
    const coveredVitals = Array.from(coveredSet);
    const uncoveredVitals = allVitals.filter((v) => !coveredSet.has(v));

    const lowestBattery = patientDevices.length > 0
      ? Math.min(...patientDevices.map((d) => d.health.batteryPercent))
      : 100;

    const signalOrder: SignalQuality[] = ['LOST', 'POOR', 'FAIR', 'GOOD', 'EXCELLENT'];
    const weakestSignal = patientDevices.length > 0
      ? signalOrder[Math.min(...patientDevices.map((d) => signalOrder.indexOf(d.health.signalQuality)))]
      : 'EXCELLENT';

    const anyDisconnected = patientDevices.some((d) => d.connectionStatus !== 'CONNECTED');

    let overallHealth: 'HEALTHY' | 'WARNING' | 'CRITICAL' = 'HEALTHY';
    if (anyDisconnected || lowestBattery < 15 || weakestSignal === 'LOST') {
      overallHealth = 'CRITICAL';
    } else if (lowestBattery < 30 || weakestSignal === 'POOR' || weakestSignal === 'FAIR') {
      overallHealth = 'WARNING';
    }

    return {
      patientId,
      connectedDevices: connectedDevices.length,
      totalDevices: patientDevices.length,
      overallHealth,
      lowestBattery,
      weakestSignal,
      anyDisconnected,
      coveredVitals,
      uncoveredVitals,
    };
  },

  // ── Check if a vital is covered by connected devices ──
  isVitalCovered: (patientId, vitalKey) => {
    const patientDevices = get().getDevicesForPatient(patientId);
    return patientDevices.some(
      (d) => d.connectionStatus === 'CONNECTED' && d.providedVitals.includes(vitalKey as any)
    );
  },
}));
