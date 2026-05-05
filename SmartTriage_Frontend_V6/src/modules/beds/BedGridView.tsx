/* ── BedGridView ──────────────────────────────────────────────────────
 *
 * The primary bed-management surface for clinical staff. A zone-tabbed
 * dashboard showing every bed in the selected zone as a tile, with:
 *   - headline capacity metrics (occupied / available / cleaning / OOS)
 *   - clickable tiles that open a contextual action sheet
 *   - real-time updates via /topic/beds/{hospitalId}
 *
 * Navigate here from the sidebar ("Beds") or deep-link via /beds?zone=RESUS.
 */
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useAuthStore } from '@/store/authStore';
import { useBedStore } from '@/store/bedStore';
import { subscribeToBedChanges } from '@/api/websocket';
import type { BedResponse, EdZone } from '@/api/types';
import { useTheme } from '@/hooks/useTheme';
import { BedTile } from './BedTile';
import { BedActionSheet } from './BedActionSheet';

const ZONES: { key: EdZone; label: string; hint: string }[] = [
  { key: 'RESUS', label: 'Resuscitation', hint: 'Critical RED patients' },
  { key: 'ACUTE', label: 'Acute Care', hint: 'ORANGE — urgent treatment' },
  { key: 'GENERAL', label: 'General', hint: 'YELLOW & GREEN — sub-acute care' },
  { key: 'PEDIATRIC', label: 'Pediatric', hint: 'Children under 18' },
  { key: 'ISOLATION', label: 'Isolation', hint: 'Infection control' },
  { key: 'OBSERVATION', label: 'Observation', hint: 'Short-stay monitoring' },
];

interface BedGridViewProps {
  /** Optional initial zone. Defaults to RESUS. */
  initialZone?: EdZone;
}

export function BedGridView({ initialZone = 'RESUS' }: BedGridViewProps) {
  const { isDark } = useTheme();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  const { zoneSnapshots, loadZone } = useBedStore();

  const [zone, setZone] = useState<EdZone>(initialZone);
  const [selectedBed, setSelectedBed] = useState<BedResponse | null>(null);
  const [loading, setLoading] = useState(false);

  const snap = zoneSnapshots.get(zone);
  const beds = snap?.beds ?? [];

  const refresh = useCallback(async () => {
    if (!hospitalId) return;
    setLoading(true);
    await loadZone(hospitalId, zone);
    setLoading(false);
  }, [hospitalId, zone, loadZone]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  // Subscribe to real-time bed updates
  useEffect(() => {
    if (!hospitalId) return;
    const unsub = subscribeToBedChanges(hospitalId, (event) => {
      // Only refresh the zone if the event concerns it — cheap heuristic
      if (event.zone === zone) {
        loadZone(hospitalId, zone);
      }
    });
    return () => unsub();
  }, [hospitalId, zone, loadZone]);

  const sortedBeds = useMemo(() => {
    return [...beds].sort((a, b) => a.displayOrder - b.displayOrder || a.code.localeCompare(b.code));
  }, [beds]);

  const handleActionDone = () => {
    setSelectedBed(null);
    refresh();
  };

  return (
    <div className="p-6">
      <header className="mb-4">
        <h1 className={`text-2xl font-bold ${isDark ? 'text-slate-100' : 'text-slate-900'}`}>
          Bed Management
        </h1>
        <p className={`text-sm ${isDark ? 'text-slate-400' : 'text-slate-500'}`}>
          Live view of every bed in the ED. Click a bed to place, transfer, or discharge.
        </p>
      </header>

      {/* Zone tabs */}
      <div className={`mb-4 flex flex-wrap gap-1 rounded-lg border p-1 ${isDark ? 'border-slate-700 bg-slate-900/40' : 'border-slate-200 bg-slate-50'}`}>
        {ZONES.map((z) => {
          const active = z.key === zone;
          return (
            <button
              key={z.key}
              type="button"
              onClick={() => setZone(z.key)}
              className={`rounded-md px-3 py-1.5 text-sm font-medium transition ${
                active
                  ? isDark ? 'bg-cyan-500 text-white' : 'bg-cyan-600 text-white'
                  : isDark ? 'text-slate-300 hover:bg-slate-800' : 'text-slate-700 hover:bg-white'
              }`}
              title={z.hint}
            >
              {z.label}
            </button>
          );
        })}
      </div>

      {/* Header metrics */}
      {snap && (
        <div className="mb-4 grid grid-cols-2 gap-2 sm:grid-cols-4">
          <Metric label="Total" value={snap.totalBeds} isDark={isDark} />
          <Metric label="Occupied" value={snap.occupied} tone="occupied" isDark={isDark} />
          <Metric label="Available" value={snap.available} tone="available" isDark={isDark} />
          <Metric label="Cleaning / OOS" value={snap.cleaning + snap.outOfService} tone="transition" isDark={isDark} />
        </div>
      )}

      {loading && sortedBeds.length === 0 ? (
        <div className={`rounded-lg border px-6 py-12 text-center text-sm ${isDark ? 'border-slate-700 text-slate-400' : 'border-slate-200 text-slate-500'}`}>
          Loading beds…
        </div>
      ) : sortedBeds.length === 0 ? (
        <div className={`rounded-lg border border-dashed px-6 py-12 text-center ${isDark ? 'border-slate-700 text-slate-400' : 'border-slate-200 text-slate-500'}`}>
          <p className="text-sm font-medium">No beds configured in {zone}</p>
          <p className={`mt-1 text-xs ${isDark ? 'text-slate-500' : 'text-slate-400'}`}>
            Ask your hospital admin to add beds to this zone from the admin panel.
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6">
          {sortedBeds.map((b) => (
            <BedTile
              key={b.id}
              bed={b}
              onClick={() => setSelectedBed(b)}
              selected={selectedBed?.id === b.id}
            />
          ))}
        </div>
      )}

      {selectedBed && (
        <BedActionSheet
          bed={selectedBed}
          onClose={() => setSelectedBed(null)}
          onActionComplete={handleActionDone}
        />
      )}
    </div>
  );
}

function Metric({
  label,
  value,
  tone,
  isDark,
}: {
  label: string;
  value: number;
  tone?: 'occupied' | 'available' | 'transition';
  isDark: boolean;
}) {
  const toneClass =
    tone === 'occupied'
      ? isDark ? 'text-slate-200' : 'text-slate-800'
      : tone === 'available'
        ? isDark ? 'text-emerald-300' : 'text-emerald-700'
        : tone === 'transition'
          ? isDark ? 'text-amber-300' : 'text-amber-700'
          : isDark ? 'text-slate-100' : 'text-slate-900';

  return (
    <div className={`rounded-md border px-3 py-2 ${isDark ? 'border-slate-700 bg-slate-900/60' : 'border-slate-200 bg-white'}`}>
      <div className={`text-[11px] uppercase tracking-wide ${isDark ? 'text-slate-500' : 'text-slate-500'}`}>
        {label}
      </div>
      <div className={`text-xl font-bold ${toneClass}`}>{value}</div>
    </div>
  );
}
