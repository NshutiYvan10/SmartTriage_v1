/* ── IoT Devices API ── */
import { get, post, patch } from './client';
import type {
  RegisterDeviceRequest,
  DeviceResponse,
  DeviceLatestVitalsResponse,
  StartMonitoringRequest,
  DeviceSessionResponse,
  VitalStreamResponse,
  Page,
} from './types';

export const iotApi = {
  // Device management
  registerDevice: (data: RegisterDeviceRequest) =>
    post<DeviceResponse>('/iot/devices', data),

  // ── Paramedic self-registered field monitor (V98) ──
  /** A paramedic registers their OWN monitor. Backend forces the type +
   *  ownership; returns the API key once (in the response) to pair the device. */
  selfRegisterDevice: (data: Pick<RegisterDeviceRequest, 'serialNumber' | 'deviceName' | 'macAddress' | 'notes'>) =>
    post<DeviceResponse>('/iot/devices/self-register', data),

  /** The caller's own registered monitors (no API key exposed). */
  myDevices: () =>
    get<DeviceResponse[]>('/iot/devices/mine'),

  /** Latest vitals snapshot the paramedic's monitor reported — for "pull from my monitor". */
  latestVitals: (deviceId: string) =>
    get<DeviceLatestVitalsResponse>(`/iot/devices/${deviceId}/latest-vitals`),

  getDevice: (id: string) =>
    get<DeviceResponse>(`/iot/devices/${id}`),

  getDevicesByHospital: (hospitalId: string, page = 0, size = 20) =>
    get<Page<DeviceResponse>>(`/iot/devices/hospital/${hospitalId}?page=${page}&size=${size}`),

  getAvailableDevices: (hospitalId: string) =>
    get<DeviceResponse[]>(`/iot/devices/available/${hospitalId}`),

  /**
   * V53 — admin toggles the device's inventory state.
   * inService=true puts the device into the active monitor pool;
   * inService=false takes it out (e.g. for repair / maintenance).
   */
  setServiceStatus: (deviceId: string, inService: boolean) =>
    patch<DeviceResponse>(`/iot/devices/${deviceId}/service-status`, { inService }),

  /**
   * V54 — admin toggles the triage-zone monitor flag.
   * Only flagged + in-service devices appear in the triage form's monitor picker.
   */
  setTriageMonitor: (deviceId: string, triageMonitor: boolean) =>
    patch<DeviceResponse>(`/iot/devices/${deviceId}/triage-monitor`, { triageMonitor }),

  /**
   * V54 — list the hospital's triage-zone monitors (flag + in-service).
   * Called by the triage form once at mount to populate the picker.
   */
  getTriageMonitors: (hospitalId: string) =>
    get<DeviceResponse[]>(`/iot/devices/triage-monitors/${hospitalId}`),

  // Monitoring sessions
  startMonitoring: (data: StartMonitoringRequest) =>
    post<DeviceSessionResponse>('/iot/monitoring/start', data),

  /**
   * Clinician-facing start: open a session for a visit without naming
   * the device. Backend walks visit → bed → assigned device.
   */
  startMonitoringForVisit: (visitId: string, startedByName?: string) =>
    post<DeviceSessionResponse>(
      `/iot/monitoring/start-for-visit/${visitId}${startedByName ? `?startedByName=${encodeURIComponent(startedByName)}` : ''}`,
      {}
    ),

  stopMonitoring: (sessionId: string, endedByName: string, reason?: string) =>
    post<DeviceSessionResponse>(
      `/iot/monitoring/stop/${sessionId}?endedByName=${encodeURIComponent(endedByName)}${reason ? `&reason=${encodeURIComponent(reason)}` : ''}`
    ),

  pauseMonitoring: (sessionId: string, pausedByName?: string) =>
    post<DeviceSessionResponse>(
      `/iot/monitoring/pause/${sessionId}${pausedByName ? `?pausedByName=${encodeURIComponent(pausedByName)}` : ''}`,
      {}
    ),

  resumeMonitoring: (sessionId: string, resumedByName?: string) =>
    post<DeviceSessionResponse>(
      `/iot/monitoring/resume/${sessionId}${resumedByName ? `?resumedByName=${encodeURIComponent(resumedByName)}` : ''}`,
      {}
    ),

  getActiveSessions: (hospitalId: string) =>
    get<DeviceSessionResponse[]>(`/iot/monitoring/active/${hospitalId}`),

  /**
   * Returns the active session for a visit, or null when monitoring
   * has not been started yet (clinician needs to press Start).
   */
  getActiveSessionForVisit: (visitId: string) =>
    get<DeviceSessionResponse | null>(`/iot/monitoring/active-for-visit/${visitId}`),

  getSession: (sessionId: string) =>
    get<DeviceSessionResponse>(`/iot/monitoring/session/${sessionId}`),

  getSessionHistory: (visitId: string, page = 0, size = 20) =>
    get<Page<DeviceSessionResponse>>(`/iot/monitoring/history/${visitId}?page=${page}&size=${size}`),

  // Vital stream
  getLatestStream: (visitId: string) =>
    get<VitalStreamResponse>(`/iot/stream/latest/${visitId}`),

  getRecentStream: (visitId: string, count = 60) =>
    get<VitalStreamResponse[]>(`/iot/stream/recent/${visitId}?count=${count}`),

  getStreamHistory: (visitId: string, page = 0, size = 20) =>
    get<Page<VitalStreamResponse>>(`/iot/stream/history/${visitId}?page=${page}&size=${size}`),
};
