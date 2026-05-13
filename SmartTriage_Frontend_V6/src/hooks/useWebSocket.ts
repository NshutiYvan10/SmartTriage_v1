/**
 * useWebSocket – Manages STOMP WebSocket lifecycle.
 * Connects on auth, subscribes to hospital-wide + zone-scoped + user-targeted topics,
 * pipes real-time data into Zustand stores.
 */
import { useEffect, useRef } from 'react';
import { useAuthStore } from '@/store/authStore';
import { useAlertStore } from '@/store/alertStore';
import { useVitalStore } from '@/store/vitalStore';
import {
  connectWebSocket,
  disconnectWebSocket,
  subscribeToAlerts,
  subscribeToZoneAlerts,
  subscribeToUserAlerts,
  subscribeToVitals,
} from '@/api/websocket';
import type { ClinicalAlertResponse, AlertType, EdZone } from '@/api/types';
import type { AIAlert } from '@/types';

// ── Map helper (duplicated from alertStore to avoid coupling) ──
function mapAlertType(t: AlertType | string): AIAlert['type'] {
  switch (t) {
    case 'DETERIORATION_DETECTED':
      return 'DETERIORATION';
    case 'DOCTOR_NOTIFICATION':
    case 'DOCTOR_ESCALATION':
      return 'DOCTOR_NOTIFICATION';
    case 'VITAL_SIGN_ABNORMAL':
    case 'CRITICAL_LAB_RESULT':
    case 'IOT_DEVICE_DISCONNECTED':
    case 'IOT_DEVICE_LOW_BATTERY':
    case 'IOT_SIGNAL_QUALITY_DEGRADED':
      return 'THRESHOLD_BREACH';
    default:
      return 'TREND_WARNING';
  }
}

/**
 * Normalize an incoming WebSocket alert payload.
 * The hospital-wide topic may send Map<String,Object> (with "type" key),
 * while zone/user topics send ClinicalAlertResponse (with "alertType" key).
 * This handles both formats.
 */
function mapWsAlert(raw: any): Omit<AIAlert, 'id' | 'timestamp' | 'acknowledged'> {
  const alertType = raw.alertType ?? raw.type;
  return {
    patientId: raw.visitId,
    type: mapAlertType(alertType),
    severity: (raw.severity || 'HIGH') as AIAlert['severity'],
    message: raw.message,
    title: raw.title || undefined,
    contributingFactors: [],
    targetZone: raw.targetZone || undefined,
    escalationTier: raw.escalationTier,
    targetDoctorName: raw.targetDoctorName || undefined,
    satsTargetMinutes: raw.satsTargetMinutes || undefined,
    visitNumber: raw.visitNumber || undefined,
    patientName: raw.patientName || undefined,
  };
}

/** Deduplication: track alert IDs we've already added to prevent double-add from overlapping topics */
const recentAlertIds = new Set<string>();
function dedupeAndAdd(raw: any) {
  const backendId = raw.id;
  if (backendId && recentAlertIds.has(backendId)) return; // already handled
  if (backendId) {
    recentAlertIds.add(backendId);
    // Clean up after 30s to prevent memory leak
    setTimeout(() => recentAlertIds.delete(backendId), 30_000);
  }
  useAlertStore.getState().addAlert({ ...mapWsAlert(raw), backendId: backendId || undefined });
}

export function useWebSocket(myZone?: EdZone | null) {
  const user = useAuthStore((s) => s.user);
  const connected = useRef(false);
  const unsubFns = useRef<Array<() => void>>([]);

  // Workflow 4 — multi-zone coverage. The full covered set is the
  // primary zone (passed in or from auth) UNION the user's
  // additionalZones. We re-derive a stable key so re-renders that
  // produce the same set don't tear down + recreate subscriptions.
  const coveredZones: EdZone[] = (() => {
    const set = new Set<EdZone>();
    const primary = myZone ?? user?.currentZone ?? null;
    if (primary) set.add(primary);
    if (Array.isArray(user?.additionalZones)) {
      for (const z of user!.additionalZones!) set.add(z);
    }
    return Array.from(set);
  })();
  const coveredKey = coveredZones.slice().sort().join(',');

  useEffect(() => {
    if (!user || connected.current) return;

    const hospitalId = user.hospitalId || 'a0000000-0000-0000-0000-000000000001';

    connectWebSocket(() => {
      console.log('[useWebSocket] Connected, subscribing to hospital + user + zone topics');

      // Subscribe to hospital-wide alerts
      const unsubAlerts = subscribeToAlerts(hospitalId, (alert: any) => {
        dedupeAndAdd(alert);
      });
      unsubFns.current.push(unsubAlerts);

      // Subscribe to user-targeted alerts (for zone-routed doctor notifications)
      if (user.id) {
        const unsubUser = subscribeToUserAlerts(user.id, (alert: ClinicalAlertResponse) => {
          dedupeAndAdd(alert);
        });
        unsubFns.current.push(unsubUser);
      }

      // Workflow 4 — one /topic/alerts/{hospital}/{zone} subscription
      // per covered zone. Backend publishes per-zone today; we extend
      // the FAN-IN on the subscriber side so a single doctor covering
      // RESUS + ACUTE + PEDIATRIC receives alerts for all three.
      // Dedup against backendId in dedupeAndAdd keeps overlapping
      // hospital-wide + zone topics from double-rendering.
      for (const zone of coveredZones) {
        console.log(`[useWebSocket] Subscribing to zone: ${zone}`);
        const unsubZone = subscribeToZoneAlerts(hospitalId, zone, (alert: ClinicalAlertResponse) => {
          dedupeAndAdd(alert);
        });
        unsubFns.current.push(unsubZone);
      }
    });

    connected.current = true;

    return () => {
      unsubFns.current.forEach((fn) => fn());
      unsubFns.current = [];
      disconnectWebSocket();
      connected.current = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user, coveredKey]);
}

/**
 * useVisitVitalsWebSocket – Subscribe to real-time vitals for a specific visit.
 * Use in visit-detail or monitoring pages.
 */
export function useVisitVitalsWebSocket(visitId: string | undefined) {
  const unsubRef = useRef<(() => void) | null>(null);

  useEffect(() => {
    if (!visitId) return;

    const unsub = subscribeToVitals(visitId, (vs) => {
      // Map VitalStreamResponse → frontend VitalSigns.
      // ECG: the high-frequency VitalStream table DOES carry ST-segment
      // deviation (mV), QRS duration (ms), and rhythm classification —
      // see VitalStream entity / IoTMapper.toResponse on the backend.
      // Previously this hook hardcoded `ecg: 0` which silently clobbered
      // every WebSocket push, leaving the UI stuck at 0.00 mV.
      useVitalStore.getState().updateVitals(visitId, {
        heartRate: vs.heartRate ?? 0,
        respiratoryRate: vs.respiratoryRate ?? 0,
        spo2: vs.spo2 ?? 0,
        systolicBP: vs.systolicBp ?? 0,
        diastolicBP: vs.diastolicBp ?? 0,
        temperature: vs.temperature ?? 0,
        ecg: vs.ecgStDeviation ?? 0,
        ecgRhythm: vs.ecgRhythm ?? undefined,
        ecgQrsDuration: vs.ecgQrsDuration ?? undefined,
        glucose: vs.bloodGlucose ?? 0,
        timestamp: new Date(vs.capturedAt),
        deviceConnected: true,
      });
    });
    unsubRef.current = unsub;

    return () => {
      unsubRef.current?.();
      unsubRef.current = null;
    };
  }, [visitId]);
}
