/* ── Zone transfer API ──
 *
 * Phase 2 of the zone-routing workflow. ZoneTransfer is the state
 * machine that gates inter-zone moves on receiving-doctor acceptance,
 * preventing the "patient owned by nobody" failure mode during
 * auto-retriages and manual reassignments.
 *
 * Lifecycle: PENDING_ACCEPT → ACCEPTED | DECLINED | RESUS_IN_PLACE | CANCELLED
 */
import { get, post } from './client';
import type { EdZone } from './types';

export type ZoneTransferStatus =
  | 'PENDING_ACCEPT'
  | 'ACCEPTED'
  | 'DECLINED'
  | 'RESUS_IN_PLACE'
  | 'CANCELLED';

export interface ZoneTransferResponse {
  id: string;
  visitId: string;
  visitNumber: string;
  patientName: string | null;
  isPediatric: boolean;
  fromZone: EdZone | null;
  toZone: EdZone;
  status: ZoneTransferStatus;
  reason: string | null;
  initiatedAt: string;
  initiatedById: string | null;
  initiatedByName: string | null;
  proposedClinicianId: string | null;
  proposedClinicianName: string | null;
  acceptedAt: string | null;
  acceptedById: string | null;
  acceptedByName: string | null;
  declinedAt: string | null;
  declinedById: string | null;
  declinedByName: string | null;
  declinedReason: string | null;
  handoverNote: string | null;
  triggeringAlertId: string | null;
  triggeringSignEventId: string | null;
  createdAt: string;
}

export const zoneTransferApi = {
  accept: (transferId: string, handoverNote?: string) =>
    post<ZoneTransferResponse>(`/zone-transfers/${transferId}/accept`,
      handoverNote ? { handoverNote } : {}),

  decline: (transferId: string, reason: string) =>
    post<ZoneTransferResponse>(`/zone-transfers/${transferId}/decline`, { reason }),

  markResusInPlace: (transferId: string, note?: string) =>
    post<ZoneTransferResponse>(`/zone-transfers/${transferId}/resus-in-place`,
      note ? { note } : {}),

  cancel: (transferId: string, reason?: string) =>
    post<ZoneTransferResponse>(`/zone-transfers/${transferId}/cancel`,
      reason ? { reason } : {}),

  /** All pending transfers across the hospital — for charge-nurse dashboard. */
  pendingForHospital: (hospitalId: string) =>
    get<ZoneTransferResponse[]>(`/zone-transfers/hospital/${hospitalId}/pending`),

  /** Pending transfers into a specific zone. */
  pendingIntoZone: (hospitalId: string, zone: EdZone) =>
    get<ZoneTransferResponse[]>(`/zone-transfers/hospital/${hospitalId}/pending/zone/${zone}`),

  /** Visit-scoped pending transfer lookup; null when no pending transfer exists. */
  pendingForVisit: (visitId: string) =>
    get<ZoneTransferResponse | null>(`/zone-transfers/visit/${visitId}/pending`),

  /** Visit-scoped audit log of every transfer that ever existed for the visit. */
  historyForVisit: (visitId: string) =>
    get<ZoneTransferResponse[]>(`/zone-transfers/visit/${visitId}/history`),
};
