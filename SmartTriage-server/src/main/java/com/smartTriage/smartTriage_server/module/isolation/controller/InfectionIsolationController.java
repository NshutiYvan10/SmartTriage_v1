package com.smartTriage.smartTriage_server.module.isolation.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.isolation.dto.AssignRoomRequest;
import com.smartTriage.smartTriage_server.module.isolation.dto.InfectionScreeningRequest;
import com.smartTriage.smartTriage_server.module.isolation.dto.InfectionScreeningResponse;
import com.smartTriage.smartTriage_server.module.isolation.dto.PublicHealthNotificationRequest;
import com.smartTriage.smartTriage_server.module.isolation.mapper.InfectionScreeningMapper;
import com.smartTriage.smartTriage_server.module.isolation.service.InfectionIsolationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * InfectionIsolationController — endpoints for infection screening, isolation management,
 * and public health notification.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/isolation")
@RequiredArgsConstructor
public class InfectionIsolationController {

    private final InfectionIsolationService isolationService;

    @PostMapping("/screen/{visitId}")
    public ResponseEntity<ApiResponse<InfectionScreeningResponse>> screenPatient(
            @PathVariable UUID visitId,
            @Valid @RequestBody InfectionScreeningRequest request) {
        InfectionScreeningResponse response = isolationService.screenPatient(visitId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Infection screening completed", response));
    }

    @GetMapping("/visit/{visitId}")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<List<InfectionScreeningResponse>>> getScreeningsForVisit(
            @PathVariable UUID visitId) {
        List<InfectionScreeningResponse> responses = isolationService.getScreeningsForVisit(visitId)
                .stream()
                .map(InfectionScreeningMapper::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @GetMapping("/hospital/{hospitalId}/active")
    @PreAuthorize("@clinicalAuthz.canSeeAllZonesAtHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<InfectionScreeningResponse>>> getActiveIsolations(
            @PathVariable UUID hospitalId) {
        List<InfectionScreeningResponse> responses = isolationService.getActiveIsolations(hospitalId)
                .stream()
                .map(InfectionScreeningMapper::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @PutMapping("/{screeningId}/assign-room")
    public ResponseEntity<ApiResponse<InfectionScreeningResponse>> assignIsolationRoom(
            @PathVariable UUID screeningId,
            @Valid @RequestBody AssignRoomRequest request) {
        InfectionScreeningResponse response = InfectionScreeningMapper.toResponse(
                isolationService.assignIsolationRoom(screeningId, request.getRoomNumber()));
        return ResponseEntity.ok(ApiResponse.success("Isolation room assigned", response));
    }

    @PutMapping("/{screeningId}/end")
    public ResponseEntity<ApiResponse<InfectionScreeningResponse>> endIsolation(
            @PathVariable UUID screeningId) {
        InfectionScreeningResponse response = InfectionScreeningMapper.toResponse(
                isolationService.endIsolation(screeningId));
        return ResponseEntity.ok(ApiResponse.success("Isolation ended", response));
    }

    @PutMapping("/{screeningId}/notify-public-health")
    public ResponseEntity<ApiResponse<InfectionScreeningResponse>> notifyPublicHealth(
            @PathVariable UUID screeningId,
            @RequestBody(required = false) PublicHealthNotificationRequest request) {
        String referenceNumber = request != null ? request.getReferenceNumber() : null;
        InfectionScreeningResponse response = InfectionScreeningMapper.toResponse(
                isolationService.notifyPublicHealth(screeningId, referenceNumber));
        return ResponseEntity.ok(ApiResponse.success("Public health notification recorded", response));
    }

    @GetMapping("/hospital/{hospitalId}/notifiable-diseases")
    @PreAuthorize("@clinicalAuthz.canSeeAllZonesAtHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<InfectionScreeningResponse>>> getNotifiableDiseases(
            @PathVariable UUID hospitalId) {
        List<InfectionScreeningResponse> responses = isolationService.getNotifiableDiseases(hospitalId)
                .stream()
                .map(InfectionScreeningMapper::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(responses));
    }
}
