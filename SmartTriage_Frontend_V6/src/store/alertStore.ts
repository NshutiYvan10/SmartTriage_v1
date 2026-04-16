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
  addAlert: (alert: Omit<AIAlert, 'id' | 'timestamp' | 'acknowledged'> & { backendId?: string }) => void;
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
    const alert: AIAlert = {
      ...alertData,
      id: alertData.backendId || `AL${Date.now()}${Math.random().toString(36).substr(2, 9).toUpperCase()}`,
      timestamp: new Date(),
      acknowledged: false,
    };
    // Deduplicate by ID
    set((state) => {
      if (state.alerts.some(a => a.id === alert.id)) return state;
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
