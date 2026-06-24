import { get, post } from './client';

/**
 * RFID registration-reader API (V95). The physical reader posts taps to the backend; the registrar
 * UI arms tap-to-capture for the desk reader, opens a visit for a found patient, and lists readers.
 */

export type RfidEventType = 'CARD_FOUND' | 'CARD_NOT_FOUND' | 'CARD_BIND';

/** Payload pushed on /topic/rfid/{hospitalId} when a card is tapped. */
export interface RfidEvent {
  type: RfidEventType;
  cardId: string;
  identityId?: string;
  patientName?: string;
  nationalId?: string | null;
  linkedHospitalCount?: number;
}

export interface RfidDevice {
  id: string;
  deviceName: string;
  serialNumber: string;
  status: string;
  deviceType: string;
}

export interface OpenVisitForCardRequest {
  cardId: string;
  hospitalId: string;
  arrivalMode?: string;
  chiefComplaint?: string;
}

export const rfidApi = {
  /** Arm the 30s tap-to-capture bind window on a desk reader (next tap pre-fills the card field). */
  armBindMode: (deviceId: string) => post<void>(`/iot/rfid/devices/${deviceId}/bind-mode`),

  /** Confirm an RFID-found patient and open a fresh visit at this hospital (returns patient + visit). */
  openVisit: (data: OpenVisitForCardRequest) =>
    post<{ patient: { id: string; firstName: string; lastName: string }; visit: { id: string; visitNumber: string } }>(
      `/iot/rfid/open-visit`, data),

  /** RFID readers registered at a hospital — for the desk-device picker. */
  listDevices: (hospitalId: string) => get<RfidDevice[]>(`/iot/rfid/devices/hospital/${hospitalId}`),
};
