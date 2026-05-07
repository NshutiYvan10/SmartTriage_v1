/**
 * RwandaLocationPicker — cascading dropdowns for the 5-level
 * administrative hierarchy of Rwanda.
 *
 * Picks a Province → narrows District → narrows Sector → narrows
 * Cell → narrows Village. Selecting a higher level resets every
 * level below it (clearing both the local options and the value),
 * so a user changing province from Kigali City to Eastern can never
 * be left with a stale Kicukiro district selected.
 *
 * <p>Designed as a single controlled component so both Patient
 * Registration and Hospital Creation wire it the same way:
 *
 *   <RwandaLocationPicker
 *     value={location}
 *     onChange={setLocation}
 *     showVillage  // include the village level (default false)
 *   />
 *
 * Where `location` is the partial state:
 *   { provinceId, districtId, sectorId, cellId?, villageId? }
 *
 * <p>Backend gracefully accepts any subset of the IDs, so it's fine
 * for a clinician to leave deeper levels blank when they don't know
 * the patient's village. The `showVillage` flag controls whether the
 * 5th dropdown is rendered at all (some forms only need granularity
 * down to cell or sector).
 */

import { useEffect, useMemo, useRef, useState } from 'react';
import { MapPin, ChevronDown, Loader2 } from 'lucide-react';
import { locationApi, type LocationOption } from '@/api/locations';

export interface RwandaLocationValue {
  provinceId?: string;
  districtId?: string;
  sectorId?: string;
  cellId?: string;
  villageId?: string;
  // Display names — emitted by the picker as a convenience so consumers
  // can mirror them into legacy text fields (e.g. confirmation screens
  // built before the FK chain existed) without doing their own lookups.
  // Always reflect the currently-selected IDs in the same change.
  provinceName?: string;
  districtName?: string;
  sectorName?: string;
  cellName?: string;
  villageName?: string;
}

interface Props {
  value: RwandaLocationValue;
  onChange: (next: RwandaLocationValue) => void;
  /** When true, the village dropdown is rendered. Default true. */
  showVillage?: boolean;
  /** When true, the cell + village dropdowns are hidden (for forms
   *  where district/sector granularity is enough). Default false. */
  shallow?: boolean;
  /** When true, the picker labels its own header "Location". */
  showHeader?: boolean;
  /** Optional id prefix for accessibility / form-association. */
  idPrefix?: string;
}

/**
 * Module-level option cache.
 *
 * Some host forms (notably the wizard-style patient registration)
 * unmount the picker as the user navigates between steps, then mount
 * a fresh instance when they return. Without a cache, remount lands
 * with empty option arrays — the IDs in `value` are still set in the
 * parent's form state, but the dropdowns show their placeholder text
 * until the network round-trips finish, which looks like the user's
 * earlier picks were lost.
 *
 * This cache lives at module scope (not inside the component) so it
 * survives unmount/remount within the same browser session. Each level
 * keys on its parent id. The cache is purely additive: every successful
 * fetch overwrites its entry. There is no eviction — the data is small
 * (≤ 14k villages total, but only the cells the user has actually
 * navigated into are loaded), and the underlying reference data is
 * stable across a clinical session.
 *
 * On a fresh page load (full app reload) the cache is empty by design
 * — that's when we want to re-fetch so any backend updates land.
 */
const optionCache = {
  provinces: [] as LocationOption[],
  districtsByProvince: new Map<string, LocationOption[]>(),
  sectorsByDistrict: new Map<string, LocationOption[]>(),
  cellsBySector: new Map<string, LocationOption[]>(),
  villagesByCell: new Map<string, LocationOption[]>(),
};

export function RwandaLocationPicker({
  value, onChange,
  showVillage = true,
  shallow = false,
  showHeader = true,
  idPrefix = 'rw-loc',
}: Props) {
  // Seed each list from the module-level cache so a remount with
  // already-known IDs renders instantly with the right options
  // selected, instead of flashing placeholders while the network
  // round-trips run.
  const [provinces, setProvinces] = useState<LocationOption[]>(
    () => optionCache.provinces);
  const [districts, setDistricts] = useState<LocationOption[]>(
    () => value.provinceId ? optionCache.districtsByProvince.get(value.provinceId) ?? [] : []);
  const [sectors, setSectors] = useState<LocationOption[]>(
    () => value.districtId ? optionCache.sectorsByDistrict.get(value.districtId) ?? [] : []);
  const [cells, setCells] = useState<LocationOption[]>(
    () => value.sectorId ? optionCache.cellsBySector.get(value.sectorId) ?? [] : []);
  const [villages, setVillages] = useState<LocationOption[]>(
    () => value.cellId ? optionCache.villagesByCell.get(value.cellId) ?? [] : []);

  // Loading flags: only "true" if the corresponding cache entry was
  // empty (we'll need to fetch); cached levels skip the loading state.
  const [loading, setLoading] = useState({
    provinces: optionCache.provinces.length === 0,
    districts: !!value.provinceId && !optionCache.districtsByProvince.has(value.provinceId),
    sectors: !!value.districtId && !optionCache.sectorsByDistrict.has(value.districtId),
    cells: !!value.sectorId && !optionCache.cellsBySector.has(value.sectorId),
    villages: !!value.cellId && !optionCache.villagesByCell.has(value.cellId),
  });

  // Seed lastParents from the IDs the picker already has on mount —
  // critical when remounting from cache: without this, the child
  // useEffects would treat the cached parent IDs as "new" and fetch
  // again, defeating the cache.
  const lastParents = useRef({
    province: value.provinceId ?? '',
    district: value.districtId ?? '',
    sector: value.sectorId ?? '',
    cell: value.cellId ?? '',
  });

  // ── Provinces — fetch on mount only when the cache is cold. ──
  useEffect(() => {
    if (optionCache.provinces.length > 0) return; // cache hot → skip
    let cancelled = false;
    setLoading((s) => ({ ...s, provinces: true }));
    locationApi.provinces()
      .then((rows) => {
        if (cancelled) return;
        const list = rows ?? [];
        setProvinces(list);
        optionCache.provinces = list;
      })
      .catch(() => { if (!cancelled) setProvinces([]); })
      .finally(() => { if (!cancelled) setLoading((s) => ({ ...s, provinces: false })); });
    return () => { cancelled = true; };
  }, []);

  // ── Districts — depend on provinceId. ──
  useEffect(() => {
    if (!value.provinceId) {
      setDistricts([]);
      lastParents.current.province = '';
      return;
    }
    if (lastParents.current.province === value.provinceId
        && optionCache.districtsByProvince.has(value.provinceId)) {
      // Cache hot for this parent and the lastParents check passes —
      // nothing to do; districts state was seeded from cache at mount.
      return;
    }
    lastParents.current.province = value.provinceId;
    const cached = optionCache.districtsByProvince.get(value.provinceId);
    if (cached) { setDistricts(cached); return; }
    let cancelled = false;
    setLoading((s) => ({ ...s, districts: true }));
    locationApi.districts(value.provinceId)
      .then((rows) => {
        if (cancelled) return;
        const list = rows ?? [];
        setDistricts(list);
        optionCache.districtsByProvince.set(value.provinceId!, list);
      })
      .catch(() => { if (!cancelled) setDistricts([]); })
      .finally(() => { if (!cancelled) setLoading((s) => ({ ...s, districts: false })); });
    return () => { cancelled = true; };
  }, [value.provinceId]);

  // ── Sectors — depend on districtId. ──
  useEffect(() => {
    if (!value.districtId) {
      setSectors([]);
      lastParents.current.district = '';
      return;
    }
    if (lastParents.current.district === value.districtId
        && optionCache.sectorsByDistrict.has(value.districtId)) {
      return;
    }
    lastParents.current.district = value.districtId;
    const cached = optionCache.sectorsByDistrict.get(value.districtId);
    if (cached) { setSectors(cached); return; }
    let cancelled = false;
    setLoading((s) => ({ ...s, sectors: true }));
    locationApi.sectors(value.districtId)
      .then((rows) => {
        if (cancelled) return;
        const list = rows ?? [];
        setSectors(list);
        optionCache.sectorsByDistrict.set(value.districtId!, list);
      })
      .catch(() => { if (!cancelled) setSectors([]); })
      .finally(() => { if (!cancelled) setLoading((s) => ({ ...s, sectors: false })); });
    return () => { cancelled = true; };
  }, [value.districtId]);

  // ── Cells — depend on sectorId. ──
  useEffect(() => {
    if (shallow || !value.sectorId) {
      setCells([]);
      lastParents.current.sector = '';
      return;
    }
    if (lastParents.current.sector === value.sectorId
        && optionCache.cellsBySector.has(value.sectorId)) {
      return;
    }
    lastParents.current.sector = value.sectorId;
    const cached = optionCache.cellsBySector.get(value.sectorId);
    if (cached) { setCells(cached); return; }
    let cancelled = false;
    setLoading((s) => ({ ...s, cells: true }));
    locationApi.cells(value.sectorId)
      .then((rows) => {
        if (cancelled) return;
        const list = rows ?? [];
        setCells(list);
        optionCache.cellsBySector.set(value.sectorId!, list);
      })
      .catch(() => { if (!cancelled) setCells([]); })
      .finally(() => { if (!cancelled) setLoading((s) => ({ ...s, cells: false })); });
    return () => { cancelled = true; };
  }, [value.sectorId, shallow]);

  // ── Villages — depend on cellId. ──
  useEffect(() => {
    if (shallow || !showVillage || !value.cellId) {
      setVillages([]);
      lastParents.current.cell = '';
      return;
    }
    if (lastParents.current.cell === value.cellId
        && optionCache.villagesByCell.has(value.cellId)) {
      return;
    }
    lastParents.current.cell = value.cellId;
    const cached = optionCache.villagesByCell.get(value.cellId);
    if (cached) { setVillages(cached); return; }
    let cancelled = false;
    setLoading((s) => ({ ...s, villages: true }));
    locationApi.villages(value.cellId)
      .then((rows) => {
        if (cancelled) return;
        const list = rows ?? [];
        setVillages(list);
        optionCache.villagesByCell.set(value.cellId!, list);
      })
      .catch(() => { if (!cancelled) setVillages([]); })
      .finally(() => { if (!cancelled) setLoading((s) => ({ ...s, villages: false })); });
    return () => { cancelled = true; };
  }, [value.cellId, shallow, showVillage]);

  // ── Cascade reset helpers — selecting a higher level CLEARS
  //    everything below to prevent stale (province, district)
  //    combinations like (Kigali, Kayonza). Each handler also
  //    emits the display name of the newly-selected level so the
  //    consumer doesn't need to keep its own id→name lookup just
  //    to render a confirmation screen. ──
  const nameOf = (opts: LocationOption[], id?: string) =>
    opts.find((o) => o.id === id)?.name;

  const onProvince = (id: string) => onChange({
    provinceId: id || undefined,
    provinceName: nameOf(provinces, id),
    districtId: undefined, districtName: undefined,
    sectorId: undefined, sectorName: undefined,
    cellId: undefined, cellName: undefined,
    villageId: undefined, villageName: undefined,
  });
  const onDistrict = (id: string) => onChange({
    ...value,
    districtId: id || undefined,
    districtName: nameOf(districts, id),
    sectorId: undefined, sectorName: undefined,
    cellId: undefined, cellName: undefined,
    villageId: undefined, villageName: undefined,
  });
  const onSector = (id: string) => onChange({
    ...value,
    sectorId: id || undefined,
    sectorName: nameOf(sectors, id),
    cellId: undefined, cellName: undefined,
    villageId: undefined, villageName: undefined,
  });
  const onCell = (id: string) => onChange({
    ...value,
    cellId: id || undefined,
    cellName: nameOf(cells, id),
    villageId: undefined, villageName: undefined,
  });
  const onVillage = (id: string) => onChange({
    ...value,
    villageId: id || undefined,
    villageName: nameOf(villages, id),
  });

  const showCells = !shallow;
  const showVillagesRow = !shallow && showVillage;

  return (
    <div className="space-y-2">
      {showHeader && (
        <div className="flex items-center gap-2 text-[11px] font-bold uppercase tracking-wider text-slate-500">
          <MapPin className="w-3.5 h-3.5" />
          Location
        </div>
      )}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-2.5">
        <Picker
          id={`${idPrefix}-province`}
          label="Province"
          options={provinces}
          value={value.provinceId}
          loading={loading.provinces}
          onChange={onProvince}
          placeholder="Select province…"
        />
        <Picker
          id={`${idPrefix}-district`}
          label="District"
          options={districts}
          value={value.districtId}
          loading={loading.districts}
          onChange={onDistrict}
          disabled={!value.provinceId}
          placeholder={value.provinceId ? 'Select district…' : 'Pick a province first'}
        />
        <Picker
          id={`${idPrefix}-sector`}
          label="Sector"
          options={sectors}
          value={value.sectorId}
          loading={loading.sectors}
          onChange={onSector}
          disabled={!value.districtId}
          placeholder={
            !value.districtId ? 'Pick a district first'
            : sectors.length === 0 && !loading.sectors
              ? 'No sectors loaded — see rw-locations CSV'
              : 'Select sector…'
          }
        />
        {showCells && (
          <Picker
            id={`${idPrefix}-cell`}
            label="Cell"
            options={cells}
            value={value.cellId}
            loading={loading.cells}
            onChange={onCell}
            disabled={!value.sectorId}
            placeholder={
              !value.sectorId ? 'Pick a sector first'
              : cells.length === 0 && !loading.cells
                ? 'No cells loaded — see rw-locations CSV'
                : 'Select cell…'
            }
          />
        )}
        {showVillagesRow && (
          <Picker
            id={`${idPrefix}-village`}
            label="Village"
            options={villages}
            value={value.villageId}
            loading={loading.villages}
            onChange={onVillage}
            disabled={!value.cellId}
            placeholder={
              !value.cellId ? 'Pick a cell first'
              : villages.length === 0 && !loading.villages
                ? 'No villages loaded — see rw-locations CSV'
                : 'Select village…'
            }
          />
        )}
      </div>
    </div>
  );
}

/* ─── Single dropdown — shared by every level ─── */

function Picker({
  id, label, options, value, onChange, loading, disabled, placeholder,
}: {
  id: string;
  label: string;
  options: LocationOption[];
  value?: string;
  onChange: (id: string) => void;
  loading: boolean;
  disabled?: boolean;
  placeholder: string;
}) {
  // Useful local memo: an alphabetised options list. Backend already
  // orders by name; we re-sort client-side too as a defensive measure
  // since downstream callers might pass in pre-filtered subsets.
  const sortedOptions = useMemo(
    () => [...options].sort((a, b) => a.name.localeCompare(b.name)),
    [options],
  );

  return (
    <label htmlFor={id} className="block">
      <div className="text-[10px] uppercase font-bold text-slate-500 tracking-wide mb-1">
        {label}
      </div>
      <div className="relative">
        <select
          id={id}
          value={value ?? ''}
          onChange={(e) => onChange(e.target.value)}
          disabled={disabled || loading}
          className={`w-full appearance-none border rounded-lg pl-3 pr-9 py-2 text-sm bg-white transition-colors ${
            disabled
              ? 'border-slate-200 text-slate-400 bg-slate-50 cursor-not-allowed'
              : 'border-slate-300 text-slate-800 hover:border-slate-400 focus:border-cyan-500 focus:ring-2 focus:ring-cyan-500/15 focus:outline-none'
          }`}
        >
          <option value="">{placeholder}</option>
          {sortedOptions.map((opt) => (
            <option key={opt.id} value={opt.id}>{opt.name}</option>
          ))}
        </select>
        <div className="pointer-events-none absolute inset-y-0 right-2 flex items-center text-slate-400">
          {loading ? <Loader2 className="w-4 h-4 animate-spin" /> : <ChevronDown className="w-4 h-4" />}
        </div>
      </div>
    </label>
  );
}
