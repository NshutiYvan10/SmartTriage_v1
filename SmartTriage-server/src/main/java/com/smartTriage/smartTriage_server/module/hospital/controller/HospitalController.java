package com.smartTriage.smartTriage_server.module.hospital.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.hospital.dto.CreateHospitalRequest;
import com.smartTriage.smartTriage_server.module.hospital.dto.HospitalResponse;
import com.smartTriage.smartTriage_server.module.hospital.service.HospitalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Hospital management endpoints.
 * Hospital creation and management is restricted to SUPER_ADMIN role.
 */
@RestController
@RequestMapping("/api/v1/hospitals")
@RequiredArgsConstructor
public class HospitalController {

    private final HospitalService hospitalService;

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<HospitalResponse>> createHospital(
            @Valid @RequestBody CreateHospitalRequest request) {
        HospitalResponse response = hospitalService.createHospital(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Hospital created successfully", response));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<HospitalResponse>> getHospital(@PathVariable UUID id) {
        HospitalResponse response = hospitalService.getHospitalById(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/code/{code}")
    public ResponseEntity<ApiResponse<HospitalResponse>> getHospitalByCode(@PathVariable String code) {
        HospitalResponse response = hospitalService.getHospitalByCode(code);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Page<HospitalResponse>>> getAllHospitals(
            @PageableDefault(size = 20) Pageable pageable) {
        Page<HospitalResponse> response = hospitalService.getAllHospitals(pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deactivateHospital(@PathVariable UUID id) {
        hospitalService.deactivateHospital(id);
        return ResponseEntity.ok(ApiResponse.success("Hospital deactivated", null));
    }
}
