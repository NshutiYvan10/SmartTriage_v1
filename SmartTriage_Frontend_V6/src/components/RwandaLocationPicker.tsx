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

export function RwandaLocationPicker({
  value, onChange,
  showVillage = true,
  shallow = false,
  showHeader = true,
  idPrefix = 'rw-loc',
}: Props) {
  const [provinces, setProvinces] = useState<LocationOption[]>([]);
  const [districts, setDistricts] = useState<LocationOption[]>([]);
  const [sectors, setSectors] = useState<LocationOption[]>([]);
  const [cells, setCells] = useState<LocationOption[]>([]);
  const [villages, setVillages] = useState<LocationOption[]>([]);

  const [loading, setLoading] = useState({
    provinces: true, districts: false, sectors: false, cells: false, villages: false,
  });

  // Track the parent ids the dependent levels were last loaded for.
  // Comparing against value.* on each render lets us avoid redundant
  // fetches AND lets us clear stale child options when the parent
  // changes externally (e.g. the parent form resets the value).
  const lastParents = useRef({
    province: '', district: '', sector: '', cell: '',
  });

  // ── Provinces — fetch once on mount. ──
  useEffect(() => {
    let cancelled = false;
    setLoading((s) => ({ ...s, provinces: true }));
    locationApi.provinces()
      .then((rows) => { if (!cancelled) setProvinces(rows ?? []); })
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
    if (lastParents.current.province === value.provinceId) return;
    lastParents.current.province = value.provinceId;
    let cancelled = false;
    setLoading((s) => ({ ...s, districts: true }));
    locationApi.districts(value.provinceId)
      .then((rows) => { if (!cancelled) setDistricts(rows ?? []); })
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
    if (lastParents.current.district === value.districtId) return;
    lastParents.current.district = value.districtId;
    let cancelled = false;
    setLoading((s) => ({ ...s, sectors: true }));
    locationApi.sectors(value.districtId)
      .then((rows) => { if (!cancelled) setSectors(rows ?? []); })
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
    if (lastParents.current.sector === value.sectorId) return;
    lastParents.current.sector = value.sectorId;
    let cancelled = false;
    setLoading((s) => ({ ...s, cells: true }));
    locationApi.cells(value.sectorId)
      .then((rows) => { if (!cancelled) setCells(rows ?? []); })
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
    if (lastParents.current.cell === value.cellId) return;
    lastParents.current.cell = value.cellId;
    let cancelled = false;
    setLoading((s) => ({ ...s, villages: true }));
    locationApi.villages(value.cellId)
      .then((rows) => { if (!cancelled) setVillages(rows ?? []); })
      .catch(() => { if (!cancelled) setVillages([]); })
      .finally(() => { if (!cancelled) setLoading((s) => ({ ...s, villages: false })); });
    return () => { cancelled = true; };
  }, [value.cellId, shallow, showVillage]);

  // ── Cascade reset helpers — selecting a higher level CLEARS
  //    everything below to prevent stale (province, district)
  //    combinations like (Kigali, Kayonza). ──
  const onProvince = (id: string) => onChange({
    provinceId: id || undefined,
    districtId: undefined, sectorId: undefined, cellId: undefined, villageId: undefined,
  });
  const onDistrict = (id: string) => onChange({
    ...value, districtId: id || undefined,
    sectorId: undefined, cellId: undefined, villageId: undefined,
  });
  const onSector = (id: string) => onChange({
    ...value, sectorId: id || undefined,
    cellId: undefined, villageId: undefined,
  });
  const onCell = (id: string) => onChange({
    ...value, cellId: id || undefined,
    villageId: undefined,
  });
  const onVillage = (id: string) => onChange({
    ...value, villageId: id || undefined,
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
