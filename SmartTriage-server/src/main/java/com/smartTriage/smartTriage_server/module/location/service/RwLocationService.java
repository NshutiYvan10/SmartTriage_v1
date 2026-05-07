package com.smartTriage.smartTriage_server.module.location.service;

import com.smartTriage.smartTriage_server.module.location.dto.LocationDtos.LocationOption;
import com.smartTriage.smartTriage_server.module.location.repository.RwProvinceRepository;
import com.smartTriage.smartTriage_server.module.location.repository.RwDistrictRepository;
import com.smartTriage.smartTriage_server.module.location.repository.RwSectorRepository;
import com.smartTriage.smartTriage_server.module.location.repository.RwCellRepository;
import com.smartTriage.smartTriage_server.module.location.repository.RwVillageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Cascading lookup service for Rwanda's 5-level administrative hierarchy.
 *
 * <p>Each method returns the children of one level, ordered for
 * dropdown display. The frontend's RwandaLocationPicker calls these in
 * sequence as the user picks each level. All five methods are pure
 * read-only lookups against reference tables that change at most once
 * per administrative reform — safe to memoise client-side, but kept
 * server-trip per call here for simplicity since the result sets are
 * small (≤ 30 districts per province, ≤ ~25 sectors per district,
 * etc.).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RwLocationService {

    private final RwProvinceRepository provinces;
    private final RwDistrictRepository districts;
    private final RwSectorRepository sectors;
    private final RwCellRepository cells;
    private final RwVillageRepository villages;

    public List<LocationOption> listProvinces() {
        return provinces.findAllByOrderByDisplayOrderAscNameAsc().stream()
                .map(p -> LocationOption.builder()
                        .id(p.getId()).code(p.getCode()).name(p.getName()).build())
                .toList();
    }

    public List<LocationOption> listDistricts(UUID provinceId) {
        if (provinceId == null) return List.of();
        return districts.findByProvinceIdOrderByNameAsc(provinceId).stream()
                .map(d -> LocationOption.builder()
                        .id(d.getId()).code(d.getCode()).name(d.getName()).build())
                .toList();
    }

    public List<LocationOption> listSectors(UUID districtId) {
        if (districtId == null) return List.of();
        return sectors.findByDistrictIdOrderByNameAsc(districtId).stream()
                .map(s -> LocationOption.builder()
                        .id(s.getId()).code(s.getCode()).name(s.getName()).build())
                .toList();
    }

    public List<LocationOption> listCells(UUID sectorId) {
        if (sectorId == null) return List.of();
        return cells.findBySectorIdOrderByNameAsc(sectorId).stream()
                .map(c -> LocationOption.builder()
                        .id(c.getId()).code(c.getCode()).name(c.getName()).build())
                .toList();
    }

    public List<LocationOption> listVillages(UUID cellId) {
        if (cellId == null) return List.of();
        return villages.findByCellIdOrderByNameAsc(cellId).stream()
                .map(v -> LocationOption.builder()
                        .id(v.getId()).code(v.getCode()).name(v.getName()).build())
                .toList();
    }
}
