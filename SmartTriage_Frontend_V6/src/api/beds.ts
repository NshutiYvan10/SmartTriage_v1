/* ── Beds API ─────────────────────────────────────────────────────────
 *
 * Bed-based placement is the clinical routing surface that binds a triaged
 * patient to a specific treatment space. When the bed has an assigned
 * IoT monitor, placement/transfer transparently opens a DeviceSession so
 * vitals start flowing to the patient's chart — no nurse click required.
 *
 * Endpoints below mirror BedController on the server.
 */
import { get, post, patch, del } from './client';
import type {
  AssignDeviceRequest,
  BedResponse,
  CreateBedRequest,
  EdZone,
  PlacePatientRequest,
  SeedResult,
  TransferPatientRequest,
  UpdateBedRequest,
  ZoneOccupancyResponse,
} from './types';

export const bedsApi = {
  // ── Admin CRUD ──
  createBed: (data: CreateBedRequest) =>
    post<BedResponse>('/beds', data),

  updateBed: (id: string, data: UpdateBedRequest) =>
    patch<BedResponse>(`/beds/${id}`, data),

  deleteBed: (id: string) =>
    del<void>(`/beds/${id}`),

  // ── Queries ──
  getBed: (id: string) =>
    get<BedResponse>(`/beds/${id}`),

  getBedsForHospital: (hospitalId: string) =>
    get<BedResponse[]>(`/beds/hospital/${hospitalId}`),

  getBedsByZone: (hospitalId: string, zone: EdZone) =>
    get<BedResponse[]>(`/beds/hospital/${hospitalId}/zone/${zone}`),

  getZoneOccupancy: (hospitalId: string, zone: EdZone) =>
    get<ZoneOccupancyResponse>(`/beds/hospital/${hospitalId}/zone/${zone}/occupancy`),

  getAvailableInZone: (hospitalId: string, zone: EdZone) =>
    get<BedResponse[]>(`/beds/hospital/${hospitalId}/zone/${zone}/available`),

  // ── Placement workflow ──
  placePatient: (bedId: string, data: PlacePatientRequest) =>
    post<BedResponse>(`/beds/${bedId}/place`, data),

  transferPatient: (sourceBedId: string, data: TransferPatientRequest) =>
    post<BedResponse>(`/beds/${sourceBedId}/transfer`, data),

  dischargePatient: (bedId: string, reason?: string) =>
    post<BedResponse>(
      `/beds/${bedId}/discharge${reason ? `?reason=${encodeURIComponent(reason)}` : ''}`
    ),

  // ── Housekeeping ──
  markCleaned: (bedId: string) =>
    post<BedResponse>(`/beds/${bedId}/mark-cleaned`),

  markOutOfService: (bedId: string, reason?: string) =>
    post<BedResponse>(
      `/beds/${bedId}/mark-out-of-service${reason ? `?reason=${encodeURIComponent(reason)}` : ''}`
    ),

  markAvailable: (bedId: string) =>
    post<BedResponse>(`/beds/${bedId}/mark-available`),

  // ── Device assignment ──
  assignDevice: (bedId: string, data: AssignDeviceRequest) =>
    post<BedResponse>(`/beds/${bedId}/assign-device`, data),

  // ── Seed defaults (Phase G #4) ──
  // Backfill the default bed inventory for a hospital. Idempotent per-zone:
  // zones that already have any beds are skipped. Used by the BedManagement
  // empty-state CTA when a hospital was created before the auto-seed hook
  // shipped, or when the hospital tier was corrected after creation.
  seedDefaults: (hospitalId: string) =>
    post<SeedResult>(`/beds/hospital/${hospitalId}/seed-defaults`),
};
