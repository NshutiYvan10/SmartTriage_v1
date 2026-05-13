import { create } from 'zustand';
import { VitalSigns, VitalReading } from '@/types';
import { vitalApi } from '@/api/vitals';
import type { VitalSignsResponse } from '@/api/types';

/**
 * True when the fetched DB row is at least as fresh as what we already
 * have in memory.
 *
 * The slow (30s) HTTP poll path runs alongside the high-frequency
 * WebSocket stream. While monitoring is live, the WebSocket has
 * already written the most recent reading to {@link VitalState.vitalsByPatient}.
 * The clinical {@link VitalSigns} table only gets a row every ~60s
 * (the periodic IoT snapshot) or once at triage submission — so the
 * "latest DB row" returned by {@code vitalApi.getLatest} can easily
 * be OLDER than the live in-memory value.
 *
 * After a clinician presses Stop the WebSocket goes silent. The next
 * HTTP poll would then overwrite the live in-memory vitals with an
 * older row — typically the triage form's entry if monitoring was
 * brief — which presented to the user as "Stop reverts vitals to the
 * original triage values." Returning false here when the in-memory
 * timestamp is fresher keeps the last live reading frozen on screen,
 * which is the clinically expected behaviour.
 */
function fetchedIsFresher(fetchedRecordedAt: string, prev: VitalSigns | undefined): boolean {
  if (!prev) return true;
  const fetchedAt = new Date(fetchedRecordedAt).getTime();
  const inMemoryAt = prev.timestamp instanceof Date
    ? prev.timestamp.getTime()
    : new Date(prev.timestamp as unknown as string).getTime();
  if (!Number.isFinite(fetchedAt) || !Number.isFinite(inMemoryAt)) return true;
  return fetchedAt > inMemoryAt;
}

// ── Map backend → frontend VitalSigns ──
//
// IMPORTANT — ECG handling
// ------------------------
// The clinical `VitalSigns` table on the backend does NOT store ECG data
// (no ST deviation, no rhythm, no QRS duration). ECG lives only in the
// high-frequency `VitalStream` table, which is delivered to the client
// exclusively via the `/topic/vitals/{visitId}` WebSocket push.
//
// This mapper is called by `fetchLatestVitals` / `fetchVitalHistory` —
// i.e. the slow (~30s) HTTP poll path. If we naively hardcoded `ecg: 0`
// here, every poll would clobber the live ECG value that WebSocket just
// wrote, and the UI would show "0 mV" forever even while the stream is
// working correctly. The caller therefore passes the previous in-store
// vitals so we can preserve ECG/rhythm/QRS across the refresh. If there
// is no prior value yet (first load), ecg defaults to 0 — which matches
// the clinical default until the stream begins.
function mapToVitalSigns(v: VitalSignsResponse, prev?: VitalSigns): VitalSigns {
  return {
    heartRate: v.heartRate,
    respiratoryRate: v.respiratoryRate,
    spo2: v.spo2,
    systolicBP: v.systolicBp,
    diastolicBP: v.diastolicBp,
    temperature: v.temperature,
    // Preserve live ECG from the WebSocket-populated previous value.
    ecg: prev?.ecg ?? 0,
    ecgRhythm: prev?.ecgRhythm,
    ecgQrsDuration: prev?.ecgQrsDuration,
    glucose: v.bloodGlucose,
    timestamp: new Date(v.recordedAt),
    deviceConnected: v.source === 'IOT_DEVICE',
  };
}

interface VitalState {
  vitalsByPatient: Map<string, VitalSigns>;
  vitalHistory: Map<string, Map<string, VitalReading[]>>; // patientId -> vitalType -> readings
  /** Fetch latest vitals for a visit from the API */
  fetchLatestVitals: (visitId: string) => Promise<void>;
  /** Fetch full vital history for a visit from the API */
  fetchVitalHistory: (visitId: string) => Promise<void>;
  updateVitals: (patientId: string, vitals: VitalSigns) => void;
  getVitals: (patientId: string) => VitalSigns | undefined;
  getVitalHistory: (patientId: string, vitalType: string) => VitalReading[];
  addVitalReading: (patientId: string, vitalType: string, reading: VitalReading) => void;
  clearVitals: (patientId: string) => void;
}

export const useVitalStore = create<VitalState>((set, get) => ({
  vitalsByPatient: new Map(),
  vitalHistory: new Map(),

  fetchLatestVitals: async (visitId: string) => {
    try {
      const latest = await vitalApi.getLatest(visitId);
      const prev = get().vitalsByPatient.get(visitId);
      // Don't clobber a fresher WebSocket-delivered reading with an
      // older DB row — see fetchedIsFresher for the full rationale.
      if (!fetchedIsFresher(latest.recordedAt, prev)) return;
      get().updateVitals(visitId, mapToVitalSigns(latest, prev));
    } catch {
      // No vitals recorded yet — silently ignore
    }
  },

  fetchVitalHistory: async (visitId: string) => {
    try {
      const page = await vitalApi.getByVisit(visitId, 0, 100);
      const records = page.content;
      if (records.length === 0) return;

      // Set latest as current vitals — pass the previous vitals so the
      // live WebSocket-supplied ECG is not overwritten by the clinical
      // snapshot (which has no ECG column). Same freshness guard as
      // fetchLatestVitals so the line-chart history below still gets
      // populated even when we skip the current-vitals overwrite.
      const latest = records[0]; // Most recent first
      const prev = get().vitalsByPatient.get(visitId);
      if (fetchedIsFresher(latest.recordedAt, prev)) {
        get().updateVitals(visitId, mapToVitalSigns(latest, prev));
      }

      // Build history from all records
      const { vitalHistory } = get();
      const newVitalHistory = new Map(vitalHistory);
      const patientHistory = new Map<string, VitalReading[]>();

      const vitalKeys: Array<{ type: string; extract: (v: VitalSignsResponse) => number }> = [
        { type: 'heartRate', extract: (v) => v.heartRate },
        { type: 'respiratoryRate', extract: (v) => v.respiratoryRate },
        { type: 'spo2', extract: (v) => v.spo2 },
        { type: 'systolicBP', extract: (v) => v.systolicBp },
        { type: 'temperature', extract: (v) => v.temperature },
        { type: 'glucose', extract: (v) => v.bloodGlucose },
      ];

      for (const { type, extract } of vitalKeys) {
        patientHistory.set(
          type,
          records.map((r: VitalSignsResponse) => ({ timestamp: new Date(r.recordedAt), value: extract(r) })).reverse()
        );
      }

      newVitalHistory.set(visitId, patientHistory);
      set({ vitalHistory: newVitalHistory });
    } catch (err) {
      console.error('[vitalStore] fetchVitalHistory failed:', err);
    }
  },

  updateVitals: (patientId, vitals) => {
    const { vitalsByPatient, vitalHistory } = get();
    const newVitalsByPatient = new Map(vitalsByPatient);
    newVitalsByPatient.set(patientId, vitals);

    // Update history for each vital
    const newVitalHistory = new Map(vitalHistory);
    if (!newVitalHistory.has(patientId)) {
      newVitalHistory.set(patientId, new Map());
    }
    const patientHistory = newVitalHistory.get(patientId)!;

    const vitalTypes = [
      { type: 'heartRate', value: vitals.heartRate },
      { type: 'respiratoryRate', value: vitals.respiratoryRate },
      { type: 'spo2', value: vitals.spo2 },
      { type: 'systolicBP', value: vitals.systolicBP },
      { type: 'temperature', value: vitals.temperature },
      { type: 'ecg', value: vitals.ecg },
      { type: 'glucose', value: vitals.glucose },
    ];

    vitalTypes?.forEach(({ type, value }) => {
      const readings = patientHistory.get(type) || [];
      const newReadings = [
        ...readings,
        { timestamp: vitals.timestamp, value },
      ].slice(-10); // Keep last 10 readings
      patientHistory.set(type, newReadings);
    });

    set({
      vitalsByPatient: newVitalsByPatient,
      vitalHistory: newVitalHistory,
    });
  },

  getVitals: (patientId) => {
    return get().vitalsByPatient.get(patientId);
  },

  getVitalHistory: (patientId, vitalType) => {
    const patientHistory = get().vitalHistory.get(patientId);
    if (!patientHistory) return [];
    return patientHistory.get(vitalType) || [];
  },

  addVitalReading: (patientId, vitalType, reading) => {
    const { vitalHistory } = get();
    const newVitalHistory = new Map(vitalHistory);
    
    if (!newVitalHistory.has(patientId)) {
      newVitalHistory.set(patientId, new Map());
    }
    const patientHistory = newVitalHistory.get(patientId)!;
    
    const readings = patientHistory.get(vitalType) || [];
    const newReadings = [...readings, reading].slice(-10);
    patientHistory.set(vitalType, newReadings);

    set({ vitalHistory: newVitalHistory });
  },

  clearVitals: (patientId) => {
    const { vitalsByPatient, vitalHistory } = get();
    const newVitalsByPatient = new Map(vitalsByPatient);
    const newVitalHistory = new Map(vitalHistory);
    
    newVitalsByPatient.delete(patientId);
    newVitalHistory.delete(patientId);

    set({
      vitalsByPatient: newVitalsByPatient,
      vitalHistory: newVitalHistory,
    });
  },
}));
