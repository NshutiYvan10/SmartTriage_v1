/* ── IoT Devices API ── */
import { get, post } from './client';
import type {
  RegisterDeviceRequest,
  DeviceResponse,
  StartMonitoringRequest,
  DeviceSessionResponse,
  VitalStreamResponse,
  Page,
} from './types';

export const iotApi = {
  // Device management
  registerDevice: (data: RegisterDeviceRequest) =>
    post<DeviceResponse>('/iot/devices', data),

  getDevice: (id: string) =>
    get<DeviceResponse>(`/iot/devices/${id}`),

  getDevicesByHospital: (hospitalId: string, page = 0, size = 20) =>
    get<Page<DeviceResponse>>(`/iot/devices/hospital/${hospitalId}?page=${page}&size=${size}`),

  getAvailableDevices: (hospitalId: string) =>
    get<DeviceResponse[]>(`/iot/devices/available/${hospitalId}`),

  powerOnDevice: (deviceId: string) =>
    post<DeviceResponse>(`/iot/devices/${deviceId}/power-on`),

  powerOffDevice: (deviceId: string) =>
    post<DeviceResponse>(`/iot/devices/${deviceId}/power-off`),

  // Monitoring sessions
  startMonitoring: (data: StartMonitoringRequest) =>
    post<DeviceSessionResponse>('/iot/monitoring/start', data),

  stopMonitoring: (sessionId: string, endedByName: string, reason?: string) =>
    post<DeviceSessionResponse>(
      `/iot/monitoring/stop/${sessionId}?endedByName=${encodeURIComponent(endedByName)}${reason ? `&reason=${encodeURIComponent(reason)}` : ''}`
    ),

  getActiveSessions: (hospitalId: string) =>
    get<DeviceSessionResponse[]>(`/iot/monitoring/active/${hospitalId}`),

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
