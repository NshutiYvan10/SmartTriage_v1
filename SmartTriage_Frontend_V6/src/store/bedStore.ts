/* ── Bed Store ────────────────────────────────────────────────────────
 *
 * Zustand store for the hospital's bed inventory. Shape mirrors the
 * Sprint B API: every bed is keyed by id, and we cache both the full list
 * and per-zone occupancy snapshots so the bed-grid view can render
 * without hitting the network on every tab switch.
 *
 * Realtime: a /topic/beds/{hospitalId} subscription (see websocket.ts)
 * triggers selective re-fetches when events arrive — simpler and safer
 * than applying partial patches to a Map we can't fully trust.
 */
import { create } from 'zustand';
import { bedsApi } from '@/api/beds';
import type {
  BedResponse,
  BedStatus,
  EdZone,
  ZoneOccupancyResponse,
  AssignDeviceRequest,
  PlacePatientRequest,
  TransferPatientRequest,
  CreateBedRequest,
  UpdateBedRequest,
} from '@/api/types';

interface BedState {
  beds: Map<string, BedResponse>;
  zoneSnapshots: Map<EdZone, ZoneOccupancyResponse>;
  loading: boolean;
  error: string | null;

  // ── Loaders ──
  loadHospital: (hospitalId: string) => Promise<void>;
  loadZone: (hospitalId: string, zone: EdZone) => Promise<ZoneOccupancyResponse | null>;
  refreshBed: (bedId: string) => Promise<BedResponse | null>;

  // ── Workflow actions (all re-fetch affected state on success) ──
  placePatient: (bedId: string, req: PlacePatientRequest) => Promise<BedResponse>;
  transferPatient: (sourceBedId: string, req: TransferPatientRequest) => Promise<BedResponse>;
  dischargePatient: (bedId: string, reason?: string) => Promise<BedResponse>;
  markCleaned: (bedId: string) => Promise<BedResponse>;
  markOutOfService: (bedId: string, reason?: string) => Promise<BedResponse>;
  markAvailable: (bedId: string) => Promise<BedResponse>;

  // ── Admin CRUD ──
  createBed: (req: CreateBedRequest) => Promise<BedResponse>;
  updateBed: (bedId: string, req: UpdateBedRequest) => Promise<BedResponse>;
  deleteBed: (bedId: string) => Promise<void>;
  assignDevice: (bedId: string, req: AssignDeviceRequest) => Promise<BedResponse>;

  // ── Selectors ──
  getBed: (bedId: string) => BedResponse | undefined;
  getBedsByZone: (zone: EdZone) => BedResponse[];
  getAvailableBedsByZone: (zone: EdZone) => BedResponse[];
  getBedByVisitId: (visitId: string) => BedResponse | undefined;
  countByStatus: (status: BedStatus) => number;
}

export const useBedStore = create<BedState>((set, get) => ({
  beds: new Map(),
  zoneSnapshots: new Map(),
  loading: false,
  error: null,

  // ── Loaders ──
  loadHospital: async (hospitalId) => {
    set({ loading: true, error: null });
    try {
      const list = await bedsApi.getBedsForHospital(hospitalId);
      const map = new Map<string, BedResponse>();
      list.forEach((b) => map.set(b.id, b));
      set({ beds: map, loading: false });
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to load beds';
      console.error('[bedStore] loadHospital:', err);
      set({ error: msg, loading: false });
    }
  },

  loadZone: async (hospitalId, zone) => {
    try {
      const snap = await bedsApi.getZoneOccupancy(hospitalId, zone);
      const { beds, zoneSnapshots } = get();
      const newBeds = new Map(beds);
      snap.beds.forEach((b) => newBeds.set(b.id, b));
      const newSnapshots = new Map(zoneSnapshots);
      newSnapshots.set(zone, snap);
      set({ beds: newBeds, zoneSnapshots: newSnapshots });
      return snap;
    } catch (err) {
      console.error('[bedStore] loadZone:', err);
      return null;
    }
  },

  refreshBed: async (bedId) => {
    try {
      const bed = await bedsApi.getBed(bedId);
      mergeBed(set, get, bed);
      return bed;
    } catch (err) {
      console.error('[bedStore] refreshBed:', err);
      return null;
    }
  },

  // ── Workflow actions ──
  placePatient: async (bedId, req) => {
    const bed = await bedsApi.placePatient(bedId, req);
    mergeBed(set, get, bed);
    return bed;
  },

  transferPatient: async (sourceBedId, req) => {
    const destBed = await bedsApi.transferPatient(sourceBedId, req);
    mergeBed(set, get, destBed);
    // Source bed state changed too — refetch it so the UI shows CLEANING
    try {
      const source = await bedsApi.getBed(sourceBedId);
      mergeBed(set, get, source);
    } catch (e) {
      console.warn('[bedStore] transferPatient source refresh failed', e);
    }
    return destBed;
  },

  dischargePatient: async (bedId, reason) => {
    const bed = await bedsApi.dischargePatient(bedId, reason);
    mergeBed(set, get, bed);
    return bed;
  },

  markCleaned: async (bedId) => {
    const bed = await bedsApi.markCleaned(bedId);
    mergeBed(set, get, bed);
    return bed;
  },

  markOutOfService: async (bedId, reason) => {
    const bed = await bedsApi.markOutOfService(bedId, reason);
    mergeBed(set, get, bed);
    return bed;
  },

  markAvailable: async (bedId) => {
    const bed = await bedsApi.markAvailable(bedId);
    mergeBed(set, get, bed);
    return bed;
  },

  // ── Admin CRUD ──
  createBed: async (req) => {
    const bed = await bedsApi.createBed(req);
    mergeBed(set, get, bed);
    return bed;
  },

  updateBed: async (bedId, req) => {
    const bed = await bedsApi.updateBed(bedId, req);
    mergeBed(set, get, bed);
    return bed;
  },

  deleteBed: async (bedId) => {
    await bedsApi.deleteBed(bedId);
    const { beds } = get();
    const newBeds = new Map(beds);
    newBeds.delete(bedId);
    set({ beds: newBeds });
  },

  assignDevice: async (bedId, req) => {
    const bed = await bedsApi.assignDevice(bedId, req);
    mergeBed(set, get, bed);
    return bed;
  },

  // ── Selectors ──
  getBed: (bedId) => get().beds.get(bedId),

  getBedsByZone: (zone) => {
    return Array.from(get().beds.values())
      .filter((b) => b.zone === zone)
      .sort((a, b) => a.displayOrder - b.displayOrder || a.code.localeCompare(b.code));
  },

  getAvailableBedsByZone: (zone) => {
    return get().getBedsByZone(zone).filter((b) => b.status === 'AVAILABLE');
  },

  getBedByVisitId: (visitId) => {
    return Array.from(get().beds.values()).find((b) => b.currentVisitId === visitId);
  },

  countByStatus: (status) => {
    let n = 0;
    get().beds.forEach((b) => {
      if (b.status === status) n++;
    });
    return n;
  },
}));

/**
 * Merge a freshly-fetched bed into the store's bed map and, if the store
 * already has a cached occupancy snapshot for that bed's zone, replace
 * the stale bed inside it so the zone view renders the updated record.
 */
function mergeBed(
  set: (partial: Partial<BedState>) => void,
  get: () => BedState,
  bed: BedResponse
) {
  const { beds, zoneSnapshots } = get();

  const newBeds = new Map(beds);
  newBeds.set(bed.id, bed);

  const newSnapshots = new Map(zoneSnapshots);
  const snap = newSnapshots.get(bed.zone);
  if (snap) {
    const idx = snap.beds.findIndex((b) => b.id === bed.id);
    const nextBeds = idx >= 0
      ? snap.beds.map((b, i) => (i === idx ? bed : b))
      : [...snap.beds, bed];
    newSnapshots.set(bed.zone, { ...snap, beds: nextBeds });
  }

  set({ beds: newBeds, zoneSnapshots: newSnapshots });
}
