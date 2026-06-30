import { create } from 'zustand';
import { AIAlert, TriageCategory } from '@/types';
import { alertApi } from '@/api/alerts';
import type { ClinicalAlertResponse, AlertType } from '@/api/types';

// ── Map backend AlertType → frontend AIAlert['type'] ──
function mapAlertType(t: AlertType): AIAlert['type'] {
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

function mapToAIAlert(a: ClinicalAlertResponse): AIAlert {
  return {
    id: a.id,
    patientId: a.visitId,
    timestamp: new Date(a.createdAt),
    type: mapAlertType(a.alertType),
    severity: a.severity as AIAlert['severity'],
    message: a.message,
    title: a.title || undefined,
    contributingFactors: [],
    acknowledged: a.acknowledged,
    acknowledgedBy: a.acknowledgedByName || undefined,
    acknowledgedAt: a.acknowledgedAt ? new Date(a.acknowledgedAt) : undefined,
    // Zone-aware fields
    targetZone: a.targetZone || undefined,
    escalationTier: a.escalationTier,
    targetDoctorName: a.targetDoctorName || undefined,
    satsTargetMinutes: a.satsTargetMinutes || undefined,
    visitNumber: a.visitNumber || undefined,
    patientName: a.patientName || undefined,
  };
}

interface AlertState {
  alerts: AIAlert[];
  isLoading: boolean;
  /** Fetch unacknowledged alerts from the API */
  fetchAlerts: (hospitalId: string) => Promise<void>;
  /** Fetch all alerts including acknowledged */
  fetchAllAlerts: (hospitalId: string) => Promise<void>;
  addAlert: (alert: Omit<AIAlert, 'id' | 'timestamp' | 'acknowledged'> & { backendId?: string; acknowledged?: boolean }) => void;
  acknowledgeAlert: (id: string, clinicianId: string, comment?: string) => void;
  /** Acknowledge via API then update local store */
  acknowledgeAlertApi: (alertId: string) => Promise<void>;
  applyRecommendation: (alertId: string, clinicianId: string, clinicianName: string) => { patientId: string; previousCategory?: TriageCategory; newCategory?: TriageCategory } | null;
  dismissAlert: (id: string, clinicianId: string, reason: string) => void;
  getActiveAlerts: () => AIAlert[];
  getPatientAlerts: (patientId: string) => AIAlert[];
  getCriticalAlerts: () => AIAlert[];
  getAlertsByType: (type: AIAlert['type']) => AIAlert[];
  clearPatientAlerts: (patientId: string) => void;
}

export const useAlertStore = create<AlertState>((set, get) => ({
  alerts: [],
  isLoading: false,

  fetchAlerts: async (hospitalId: string) => {
    set({ isLoading: true });
    try {
      const page = await alertApi.getUnacknowledged(hospitalId, 0, 200);
      set({ alerts: page.content.map(mapToAIAlert), isLoading: false });
    } catch (err) {
      console.error('[alertStore] fetchAlerts failed:', err);
      set({ isLoading: false });
    }
  },

  fetchAllAlerts: async (hospitalId: string) => {
    set({ isLoading: true });
    try {
      const page = await alertApi.getAll(hospitalId, 0, 200);
      set({ alerts: page.content.map(mapToAIAlert), isLoading: false });
    } catch (err) {
      console.error('[alertStore] fetchAllAlerts failed:', err);
      set({ isLoading: false });
    }
  },

  acknowledgeAlertApi: async (alertId: string) => {
    try {
      await alertApi.acknowledge(alertId);
      set((state) => ({
        alerts: state.alerts.map((a) =>
          a.id === alertId ? { ...a, acknowledged: true, acknowledgedAt: new Date() } : a
        ),
      }));
    } catch (err) {
      console.error('[alertStore] acknowledgeAlertApi failed:', err);
    }
  },

  addAlert: (alertData) => {
    const id = alertData.backendId || `AL${Date.now()}${Math.random().toString(36).substr(2, 9).toUpperCase()}`;
    set((state) => {
      const idx = state.alerts.findIndex((a) => a.id === id);
      if (idx >= 0) {
        // UPSERT — a re-broadcast of an existing alert (e.g. the escalation scheduler
        // re-paging an unacknowledged time-critical alert) must UPDATE the row, not be
        // silently dropped, so the bumped escalationTier / raised severity / "[ESCALATED]"
        // message reach the UI and re-trigger the CriticalAlertNotifier (which keys on
        // id+escalationTier). Acknowledgement state is PRESERVED — a re-page never un-acks.
        const existing = state.alerts[idx];
        const merged: AIAlert = {
          ...existing,
          message: alertData.message ?? existing.message,
          title: alertData.title ?? existing.title,
          severity: alertData.severity ?? existing.severity,
          escalationTier: alertData.escalationTier ?? existing.escalationTier,
          targetZone: alertData.targetZone ?? existing.targetZone,
          targetDoctorName: alertData.targetDoctorName ?? existing.targetDoctorName,
          // Honor an incoming ACK monotonically: a re-broadcast that carries
          // acknowledged=true (e.g. an EMS alert auto-acknowledged server-side when the
          // patient arrives or care is handed over) clears the row from the Alert Center
          // live, so the same arrival is never acknowledged twice. Never UN-acks (a
          // re-page keeps it acked) — matches the "re-page never un-acks" rule.
          acknowledged: (alertData as { acknowledged?: boolean }).acknowledged === true
            ? true
            : existing.acknowledged,
          acknowledgedAt: (alertData as { acknowledged?: boolean }).acknowledged === true
            ? (existing.acknowledgedAt ?? new Date())
            : existing.acknowledgedAt,
        };
        const next = state.alerts.slice();
        next[idx] = merged;
        return { alerts: next };
      }
      const alert: AIAlert = {
        ...alertData,
        id,
        timestamp: new Date(),
        // A first-sighting frame that already carries acknowledged=true (an alert
        // auto-resolved server-side before this client ever saw it live) must not
        // resurface as unacknowledged.
        acknowledged: alertData.acknowledged === true,
      };
      return { alerts: [...state.alerts, alert] };
    });
  },

  acknowledgeAlert: (id, clinicianId, comment) => {
    set((state) => ({
      alerts: state.alerts.map((alert) =>
        alert.id === id
          ? {
              ...alert,
              acknowledged: true,
              acknowledgedBy: clinicianId,
              acknowledgedAt: new Date(),
              comment,
            }
          : alert
      ),
    }));
  },

  /**
   * Apply the AI-recommended category change from an alert.
   * Returns the patientId and category info for audit logging.
   */
  applyRecommendation: (alertId, clinicianId, clinicianName) => {
    const alert = get().alerts.find(a => a.id === alertId);
    if (!alert || alert.acknowledged || !alert.recommendedCategory) return null;

    // Mark alert as acknowledged with action taken
    set((state) => ({
      alerts: state.alerts.map((a) =>
        a.id === alertId
          ? {
              ...a,
              acknowledged: true,
              acknowledgedBy: clinicianName,
              acknowledgedAt: new Date(),
              comment: `AI recommendation applied — category changed to ${alert.recommendedCategory}`,
            }
          : a
      ),
    }));

    return {
      patientId: alert.patientId,
      previousCategory: alert.previousCategory,
      newCategory: alert.recommendedCategory,
    };
  },

  /**
   * Dismiss an alert with a reason (clinician overrides AI recommendation)
   */
  dismissAlert: (id, clinicianId, reason) => {
    set((state) => ({
      alerts: state.alerts.map((alert) =>
        alert.id === id
          ? {
              ...alert,
              acknowledged: true,
              acknowledgedBy: clinicianId,
              acknowledgedAt: new Date(),
              comment: `Dismissed: ${reason}`,
            }
          : alert
      ),
    }));
  },

  getActiveAlerts: () => {
    return get().alerts.filter((alert) => !alert.acknowledged);
  },

  getPatientAlerts: (patientId) => {
    return get().alerts.filter((alert) => alert.patientId === patientId);
  },

  getCriticalAlerts: () => {
    return get().alerts.filter(
      (alert) => !alert.acknowledged && alert.severity === 'CRITICAL'
    );
  },

  getAlertsByType: (type) => {
    return get().alerts.filter((alert) => alert.type === type);
  },

  clearPatientAlerts: (patientId) => {
    set((state) => ({
      alerts: state.alerts.filter((alert) => alert.patientId !== patientId),
    }));
  },
}));
