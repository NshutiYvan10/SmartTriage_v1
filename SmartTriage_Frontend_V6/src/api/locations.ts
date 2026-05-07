/* ── Locations API — Rwanda administrative hierarchy ── */
import { get } from './client';

export interface LocationOption {
  id: string;
  code: string;
  name: string;
}

/**
 * Cascading lookup endpoints. Each level returns only the children of
 * the supplied parent — picking a province narrows the district list,
 * picking a district narrows the sector list, and so on. The five
 * endpoints are intentionally symmetric so the frontend picker can be
 * built as a single generic component driven by an array of levels.
 */
export const locationApi = {
  provinces: () =>
    get<LocationOption[]>('/locations/rw/provinces'),
  districts: (provinceId: string) =>
    get<LocationOption[]>(`/locations/rw/districts?provinceId=${encodeURIComponent(provinceId)}`),
  sectors: (districtId: string) =>
    get<LocationOption[]>(`/locations/rw/sectors?districtId=${encodeURIComponent(districtId)}`),
  cells: (sectorId: string) =>
    get<LocationOption[]>(`/locations/rw/cells?sectorId=${encodeURIComponent(sectorId)}`),
  villages: (cellId: string) =>
    get<LocationOption[]>(`/locations/rw/villages?cellId=${encodeURIComponent(cellId)}`),
};
