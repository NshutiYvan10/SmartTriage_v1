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
import type { CSSProperties } from 'react';
import { useAuthStore } from '@/store/authStore';
import { useBedStore } from '@/store/bedStore';
import { subscribeToBedChanges } from '@/api/websocket';
import type { BedResponse, EdZone } from '@/api/types';
import { useTheme } from '@/hooks/useTheme';
import type { ThemeStyles } from '@/hooks/useTheme';
import { BedDouble } from 'lucide-react';
import { BedTile } from './BedTile';
import { BedActionSheet } from './BedActionSheet';
import { useScopedView } from '@/hooks/useScopedView';
import { CrossZoneRestrictedPanel } from '@/components/CrossZoneRestrictedPanel';

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
  const { glassCard, glassInner, isDark, text } = useTheme();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  const { zoneSnapshots, loadZone } = useBedStore();

  const [zone, setZone] = useState<EdZone>(initialZone);
  const [selectedBed, setSelectedBed] = useState<BedResponse | null>(null);
  const [loading, setLoading] = useState(false);

  // Zone-scope the board to what the caller may actually see. The backend now
  // gates the per-zone bed endpoints with canReceiveZoneAlerts (occupancy is
  // patient PHI), so a zone clinician selecting a zone they don't cover would
  // get a 403. Oversight (admin / charge nurse / shift-lead → HOSPITAL_WIDE)
  // sees every zone; an on-shift clinician sees only their covered zones
  // (current ∪ additional); an off-shift clinician gets the restriction card.
  const scope = useScopedView();
  const coveredZones = useMemo<EdZone[]>(() => {
    if (scope.mode === 'HOSPITAL_WIDE') return ZONES.map((z) => z.key);
    const set = new Set<EdZone>();
    if (user?.currentZone) set.add(user.currentZone);
    (user?.additionalZones ?? []).forEach((z) => set.add(z));
    return ZONES.map((z) => z.key).filter((k) => set.has(k));
  }, [scope.mode, user?.currentZone, user?.additionalZones]);
  const visibleZones = useMemo(
    () => ZONES.filter((z) => coveredZones.includes(z.key)),
    [coveredZones],
  );

  const snap = zoneSnapshots.get(zone);
  const beds = snap?.beds ?? [];

  const refresh = useCallback(async () => {
    // Never call a zone the caller can't cover — it would 403. The clamp
    // effect below re-points `zone` to a covered zone, then this refires.
    if (!hospitalId || !coveredZones.includes(zone)) return;
    setLoading(true);
    await loadZone(hospitalId, zone);
    setLoading(false);
  }, [hospitalId, zone, loadZone, coveredZones]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  // Keep the active zone within the caller's covered set (e.g. a GENERAL nurse
  // must not sit on the default RESUS tab, which would 403).
  useEffect(() => {
    if (coveredZones.length && !coveredZones.includes(zone)) {
      setZone(coveredZones[0]);
    }
  }, [coveredZones, zone]);

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

  // Off-shift clinician with no covered zone → show the restriction card
  // instead of an empty board that would 403 on every zone.
  if (!scope.isLoading && visibleZones.length === 0) {
    return (
      <div className="min-h-full animate-fade-in">
        <div className="p-4 lg:p-6 max-w-7xl mx-auto">
          <CrossZoneRestrictedPanel pageTitle="Bed Management" zone={null} reason="OFF_SHIFT" />
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-full animate-fade-in">
      <div className="p-4 lg:p-6 max-w-7xl mx-auto space-y-4">

        {/* ── Header Banner ── */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5 flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-cyan-500/20 flex items-center justify-center">
              <BedDouble className="w-5 h-5 text-cyan-300" />
            </div>
            <div>
              <h1 className="text-lg font-bold text-white tracking-tight leading-tight">
                Bed Management
              </h1>
              <p className="text-sm text-white/50 mt-0.5 font-medium">
                Live view of every bed in the ED. Click a bed to place, transfer, or discharge.
              </p>
            </div>
          </div>
        </div>

        {/* Zone tabs */}
        <div className="flex flex-wrap gap-1 rounded-2xl p-1 animate-fade-up" style={glassInner}>
          {visibleZones.map((z) => {
            const active = z.key === zone;
            return (
              <button
                key={z.key}
                type="button"
                onClick={() => setZone(z.key)}
                className={`rounded-xl px-3 py-1.5 text-sm font-medium transition border ${
                  active
                    ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md border-transparent'
                    : `border-transparent ${text.body} hover:bg-white/5`
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
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4 animate-fade-up">
            <Metric label="Total" value={snap.totalBeds} glassInner={glassInner} text={text} />
            <Metric label="Occupied" value={snap.occupied} tone="occupied" glassInner={glassInner} text={text} />
            <Metric label="Available" value={snap.available} tone="available" glassInner={glassInner} text={text} />
            <Metric label="Cleaning / OOS" value={snap.cleaning + snap.outOfService} tone="transition" glassInner={glassInner} text={text} />
          </div>
        )}

        {loading && sortedBeds.length === 0 ? (
          <div className={`rounded-2xl px-6 py-12 text-center text-sm animate-fade-up ${text.muted}`} style={glassCard}>
            Loading beds…
          </div>
        ) : sortedBeds.length === 0 ? (
          <div className={`rounded-2xl px-6 py-12 text-center animate-fade-up ${text.muted}`} style={glassCard}>
            <p className={`text-sm font-medium ${text.body}`}>No beds configured in {zone}</p>
            <p className={`mt-1 text-xs ${text.muted}`}>
              Ask your hospital admin to add beds to this zone from the admin panel.
            </p>
          </div>
        ) : (
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 animate-fade-up">
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
    </div>
  );
}

function Metric({
  label,
  value,
  tone,
  glassInner,
  text,
}: {
  label: string;
  value: number;
  tone?: 'occupied' | 'available' | 'transition';
  glassInner: CSSProperties;
  text: ThemeStyles['text'];
}) {
  const toneClass =
    tone === 'occupied'
      ? text.heading
      : tone === 'available'
        ? 'text-emerald-400'
        : tone === 'transition'
          ? 'text-amber-400'
          : text.heading;

  return (
    <div className="rounded-xl px-3 py-2" style={glassInner}>
      <div className={`text-[11px] uppercase tracking-wide ${text.muted}`}>
        {label}
      </div>
      <div className={`text-xl font-bold ${toneClass}`}>{value}</div>
    </div>
  );
}
