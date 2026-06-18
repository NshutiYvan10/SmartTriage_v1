/**
 * useWebSocket – Manages STOMP WebSocket lifecycle.
 * Connects on auth, subscribes to hospital-wide + zone-scoped + user-targeted topics,
 * pipes real-time data into Zustand stores.
 */
import { useEffect, useRef, useState } from 'react';
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
  subscribeConnectionState,
  getConnectionGeneration,
} from '@/api/websocket';
import { ensureAccessToken } from '@/api/client';
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
    case 'LAB_SPECIMEN_REJECTED':
    case 'LAB_VERIFICATION_OVERRIDDEN':
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

/**
 * Deduplication across overlapping topics (hospital + zone + user all carry the same
 * alert). Keyed on id + escalationTier + severity, NOT id alone: an escalation re-broadcast
 * of the same id (tier bumped / severity raised — e.g. the no-doctor auto-Tier-2 frame that
 * fires synchronously milliseconds after the Tier-1 frame, or the 5-min re-page) carries a
 * DIFFERENT key and so falls through to addAlert's upsert instead of being silently dropped.
 * An identical re-delivery (same id+tier+severity, e.g. the same frame on three topics) is
 * dropped. Map entry expires after 30s; cleanup only removes the entry if it hasn't been
 * superseded by a newer key in the meantime.
 */
const recentAlertKeys = new Map<string, string>();
function dedupeAndAdd(raw: any) {
  const backendId = raw.id;
  if (backendId) {
    const key = `${raw.escalationTier ?? 0}:${raw.severity ?? ''}`;
    if (recentAlertKeys.get(backendId) === key) return; // identical re-delivery — drop
    recentAlertKeys.set(backendId, key);
    setTimeout(() => {
      if (recentAlertKeys.get(backendId) === key) recentAlertKeys.delete(backendId);
    }, 30_000);
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
    connected.current = true; // guard re-entry synchronously (the connect below is async)
    let alive = true;

    const hospitalId = user.hospitalId || 'a0000000-0000-0000-0000-000000000001';

    // Ensure an in-memory access token before opening the socket. The backend now requires
    // a valid bearer token at STOMP CONNECT, and after a page reload the access token is null
    // until refreshed — without this, realtime would stay dark until the first REST
    // 401→refresh. Best-effort: even if it fails, we still activate (beforeConnect re-reads
    // the token on each 5s retry, so the channel recovers once a token appears).
    ensureAccessToken().catch(() => {}).finally(() => {
      if (!alive) return;
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
    });

    return () => {
      alive = false;
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

/**
 * useWebSocketGeneration – returns a counter that increments on every WebSocket
 * (re)connect. Include it in a subscription effect's dependency array so the
 * effect re-runs (and re-subscribes) after the shared client is torn down and
 * rebuilt — e.g. when the app-level alert hook reconnects on a covered-zone
 * change. Without this, an ad-hoc feature subscription (sepsis dashboard/panel)
 * would silently die after such a reconnect.
 */
export function useWebSocketGeneration(): number {
  const [generation, setGeneration] = useState<number>(getConnectionGeneration());
  useEffect(() => subscribeConnectionState(setGeneration), []);
  return generation;
}
