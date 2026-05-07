package com.smartTriage.smartTriage_server.module.location.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.location.dto.LocationDtos.LocationOption;
import com.smartTriage.smartTriage_server.module.location.service.RwLocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Cascading lookup endpoints for Rwanda's 5-level administrative
 * hierarchy. Used by the patient-registration and hospital-creation
 * forms to populate dependent dropdowns: pick a province → fetch its
 * districts; pick a district → fetch its sectors; etc.
 *
 * <p>Reference data, hospital-agnostic. Authenticated only — no
 * hospital-scope check needed because the data is identical for every
 * tenant.
 */
@RestController
@RequestMapping("/api/v1/locations/rw")
@RequiredArgsConstructor
public class RwLocationController {

    private final RwLocationService service;

    @GetMapping("/provinces")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<LocationOption>>> provinces() {
        return ResponseEntity.ok(ApiResponse.success(service.listProvinces()));
    }

    @GetMapping("/districts")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<LocationOption>>> districts(
            @RequestParam("provinceId") UUID provinceId) {
        return ResponseEntity.ok(ApiResponse.success(service.listDistricts(provinceId)));
    }

    @GetMapping("/sectors")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<LocationOption>>> sectors(
            @RequestParam("districtId") UUID districtId) {
        return ResponseEntity.ok(ApiResponse.success(service.listSectors(districtId)));
    }

    @GetMapping("/cells")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<LocationOption>>> cells(
            @RequestParam("sectorId") UUID sectorId) {
        return ResponseEntity.ok(ApiResponse.success(service.listCells(sectorId)));
    }

    @GetMapping("/villages")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<LocationOption>>> villages(
            @RequestParam("cellId") UUID cellId) {
        return ResponseEntity.ok(ApiResponse.success(service.listVillages(cellId)));
    }
}
