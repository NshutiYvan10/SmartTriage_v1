import { useEffect, useRef, useCallback, useState } from 'react';
import { VitalSigns } from '@/types';
import { useVitalStore } from '@/store/vitalStore';
import { useAlertStore } from '@/store/alertStore';
import { useDeviceStore } from '@/store/deviceStore';
import {
  simulateHealthTick,
  shouldSimulateDisconnect,
  shouldSimulateReconnect,
  SIMULATED_DEVICE_FLEET,
} from '@/utils/iotDeviceManager';

/**
 * IoT Device Simulation Status returned to consuming components
 */
export interface DeviceSimStatus {
  /** Whether at least one device is connected and streaming */
  hasActiveDevice: boolean;
  /** Number of connected + streaming devices */
  activeDeviceCount: number;
  /** Overall device health: HEALTHY | WARNING | CRITICAL */
  overallHealth: 'HEALTHY' | 'WARNING' | 'CRITICAL';
  /** Lowest battery % across paired devices */
  lowestBattery: number;
  /** Whether any device is currently disconnected/reconnecting */
  hasDisconnected: boolean;
  /** Whether device fleet has been auto-provisioned */
  isProvisioned: boolean;
}

/**
 * Simulates continuous IoT vital sign monitoring with device management.
 * Updates every 2 seconds with realistic variations.
 * Automatically provisions a default device, simulates device health,
 * and handles reconnection events.
 */
export function useVitalSimulator(patientId: string, baseVitals?: Partial<VitalSigns>) {
  const updateVitals = useVitalStore((state) => state.updateVitals);
  const addAlert = useAlertStore((state) => state.addAlert);
  const {
    getDevicesForPatient,
    addDevice,
    pairDevice,
    updateDeviceHealth,
    setConnectionStatus,
    getPatientDeviceSummary,
  } = useDeviceStore();

  const intervalRef = useRef<number | null>(null);
  const previousVitalsRef = useRef<VitalSigns | null>(null);
  const provisionedRef = useRef(false);

  const [deviceStatus, setDeviceStatus] = useState<DeviceSimStatus>({
    hasActiveDevice: false,
    activeDeviceCount: 0,
    overallHealth: 'HEALTHY',
    lowestBattery: 100,
    hasDisconnected: false,
    isProvisioned: false,
  });

  // Auto-provision a multi-parameter monitor if none paired
  const provisionDevice = useCallback(() => {
    if (provisionedRef.current) return;
    const existing = getDevicesForPatient(patientId);
    if (existing.length > 0) {
      provisionedRef.current = true;
      return;
    }

    // Add and pair a multi-parameter monitor (covers all vitals in one unit)
    const config = SIMULATED_DEVICE_FLEET[1]; // Philips IntelliVue
    const device = addDevice(config);
    pairDevice(device.id, patientId);
    provisionedRef.current = true;
  }, [patientId, getDevicesForPatient, addDevice, pairDevice]);

  useEffect(() => {
    // Provision default device
    provisionDevice();

    // Initialize base vitals with realistic defaults
    const initial: VitalSigns = {
      heartRate: baseVitals?.heartRate || 75,
      respiratoryRate: baseVitals?.respiratoryRate || 16,
      spo2: baseVitals?.spo2 || 98,
      systolicBP: baseVitals?.systolicBP || 120,
      diastolicBP: baseVitals?.diastolicBP || 80,
      temperature: baseVitals?.temperature || 36.8,
      ecg: baseVitals?.ecg || 0.1,
      glucose: baseVitals?.glucose || 95,
      timestamp: new Date(),
      deviceConnected: true,
    };

    previousVitalsRef.current = initial;
    updateVitals(patientId, initial);

    // Simulate vital sign updates every 2 seconds
    intervalRef.current = window.setInterval(() => {
      const prev = previousVitalsRef.current!;
      const devices = getDevicesForPatient(patientId);

      // ── Device health simulation ──
      let anyConnected = false;
      let anyDisconnected = false;

      devices.forEach((device) => {
        if (device.connectionStatus === 'CONNECTED') {
          // Simulate health metrics
          const healthUpdate = simulateHealthTick(device);
          updateDeviceHealth(device.id, healthUpdate);

          // Rare random disconnection
          if (shouldSimulateDisconnect()) {
            setConnectionStatus(device.id, 'RECONNECTING', 'Signal temporarily lost');
            anyDisconnected = true;

            // Generate alert for disconnection
            addAlert({
              patientId,
              type: 'THRESHOLD_BREACH',
              severity: 'MEDIUM',
              message: `Device "${device.name}" lost connection — attempting reconnect`,
              contributingFactors: ['IoT device disconnected'],
            });
          } else {
            anyConnected = true;
          }
        } else if (device.connectionStatus === 'RECONNECTING') {
          // Attempt auto-reconnect
          if (shouldSimulateReconnect()) {
            setConnectionStatus(device.id, 'CONNECTED', 'Reconnected successfully');
            anyConnected = true;
          } else {
            anyDisconnected = true;
          }
        } else {
          anyDisconnected = true;
        }
      });

      // Determine device-connected flag
      const deviceConnected = anyConnected || devices.length === 0;

      // ── Vital sign simulation (only if at least one device connected) ──
      const newVitals: VitalSigns = deviceConnected
        ? {
            heartRate: addVariation(prev.heartRate, 2, 50, 180),
            respiratoryRate: addVariation(prev.respiratoryRate, 1, 8, 40),
            spo2: addVariation(prev.spo2, 0.5, 85, 100),
            systolicBP: addVariation(prev.systolicBP, 3, 80, 180),
            diastolicBP: addVariation(prev.diastolicBP, 2, 50, 100),
            temperature: addVariation(prev.temperature, 0.1, 35, 41),
            ecg: addVariation(prev.ecg, 0.05, -0.5, 2.0),
            glucose: addVariation(prev.glucose, 3, 40, 400),
            timestamp: new Date(),
            deviceConnected: true,
          }
        : {
            // Keep last known values when disconnected
            ...prev,
            timestamp: new Date(),
            deviceConnected: false,
          };

      previousVitalsRef.current = newVitals;
      updateVitals(patientId, newVitals);

      // Check for threshold breaches (only when device is providing data)
      if (deviceConnected) {
        checkThresholds(patientId, newVitals, addAlert);
      }

      // ── Update device status for consuming components ──
      const summary = getPatientDeviceSummary(patientId);
      setDeviceStatus({
        hasActiveDevice: anyConnected || devices.length === 0,
        activeDeviceCount: summary.connectedDevices,
        overallHealth: summary.overallHealth,
        lowestBattery: summary.lowestBattery,
        hasDisconnected: anyDisconnected,
        isProvisioned: provisionedRef.current,
      });
    }, 2000);

    return () => {
      if (intervalRef.current) {
        clearInterval(intervalRef.current);
      }
    };
  }, [patientId, updateVitals, addAlert, getDevicesForPatient, updateDeviceHealth, setConnectionStatus, getPatientDeviceSummary, provisionDevice]);

  return { deviceStatus };
}

/**
 * Add realistic variation to vital sign
 */
function addVariation(current: number, maxChange: number, min: number, max: number): number {
  const change = (Math.random() - 0.5) * 2 * maxChange;
  const newValue = current + change;
  return Math.max(min, Math.min(max, Math.round(newValue * 10) / 10));
}

/**
 * Check vital sign thresholds and generate alerts
 */
function checkThresholds(
  patientId: string,
  vitals: VitalSigns,
  addAlert: (alert: any) => void
) {
  const alerts: any[] = [];

  // SpO2 < 92% - CRITICAL
  if (vitals.spo2 < 92) {
    alerts.push({
      patientId,
      type: 'THRESHOLD_BREACH',
      severity: 'CRITICAL',
      message: `SpO₂ critically low: ${vitals.spo2}%`,
      contributingFactors: ['SpO₂ < 92%'],
      recommendedCategory: 'RED',
    });
  } else if (vitals.spo2 < 94) {
    alerts.push({
      patientId,
      type: 'THRESHOLD_BREACH',
      severity: 'HIGH',
      message: `SpO₂ below normal: ${vitals.spo2}%`,
      contributingFactors: ['SpO₂ < 94%'],
    });
  }

  // Systolic BP < 90 - HIGH
  if (vitals.systolicBP < 90) {
    alerts.push({
      patientId,
      type: 'THRESHOLD_BREACH',
      severity: 'HIGH',
      message: `Systolic BP low: ${vitals.systolicBP} mmHg`,
      contributingFactors: ['Systolic BP < 90 mmHg'],
    });
  }

  // Respiratory Rate > 30 or < 10 - HIGH
  if (vitals.respiratoryRate > 30 || vitals.respiratoryRate < 10) {
    alerts.push({
      patientId,
      type: 'THRESHOLD_BREACH',
      severity: 'HIGH',
      message: `Respiratory rate abnormal: ${vitals.respiratoryRate} breaths/min`,
      contributingFactors: [`RR ${vitals.respiratoryRate > 30 ? '>' : '<'} ${vitals.respiratoryRate > 30 ? '30' : '10'}`],
    });
  }

  // Heart Rate > 120 or < 50 - MEDIUM
  if (vitals.heartRate > 120 || vitals.heartRate < 50) {
    alerts.push({
      patientId,
      type: 'THRESHOLD_BREACH',
      severity: 'MEDIUM',
      message: `Heart rate abnormal: ${vitals.heartRate} bpm`,
      contributingFactors: [`HR ${vitals.heartRate > 120 ? '>' : '<'} ${vitals.heartRate > 120 ? '120' : '50'}`],
    });
  }

  // Fever > 39°C - MEDIUM
  if (vitals.temperature > 39) {
    alerts.push({
      patientId,
      type: 'THRESHOLD_BREACH',
      severity: 'MEDIUM',
      message: `High fever: ${vitals.temperature}°C`,
      contributingFactors: ['Temperature > 39°C'],
    });
  }

  // ECG ST-segment deviation > 1.0 mV - HIGH
  if (Math.abs(vitals.ecg) > 1.0) {
    alerts.push({
      patientId,
      type: 'THRESHOLD_BREACH',
      severity: 'HIGH',
      message: `ECG ST-segment deviation: ${vitals.ecg} mV`,
      contributingFactors: ['ST deviation > 1.0 mV'],
    });
  }

  // Glucose < 70 - MEDIUM, < 54 - HIGH, > 250 - HIGH
  if (vitals.glucose < 54) {
    alerts.push({
      patientId,
      type: 'THRESHOLD_BREACH',
      severity: 'HIGH',
      message: `Severe hypoglycemia: ${vitals.glucose} mg/dL`,
      contributingFactors: ['Glucose < 54 mg/dL'],
      recommendedCategory: 'RED',
    });
  } else if (vitals.glucose < 70) {
    alerts.push({
      patientId,
      type: 'THRESHOLD_BREACH',
      severity: 'MEDIUM',
      message: `Low glucose: ${vitals.glucose} mg/dL`,
      contributingFactors: ['Glucose < 70 mg/dL'],
    });
  } else if (vitals.glucose > 250) {
    alerts.push({
      patientId,
      type: 'THRESHOLD_BREACH',
      severity: 'HIGH',
      message: `Severe hyperglycemia: ${vitals.glucose} mg/dL`,
      contributingFactors: ['Glucose > 250 mg/dL'],
    });
  }

  // Add unique alerts only
  alerts.forEach((alert) => addAlert(alert));
}
