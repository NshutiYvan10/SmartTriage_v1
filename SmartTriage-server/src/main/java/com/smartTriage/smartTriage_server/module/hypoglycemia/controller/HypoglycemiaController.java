package com.smartTriage.smartTriage_server.module.hypoglycemia.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.hypoglycemia.dto.*;
import com.smartTriage.smartTriage_server.module.hypoglycemia.mapper.HypoglycemiaEventMapper;
import com.smartTriage.smartTriage_server.module.hypoglycemia.service.HypoglycemiaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * HypoglycemiaController — endpoints for hypoglycemia enforcement and event management.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/hypoglycemia")
@RequiredArgsConstructor
public class HypoglycemiaController {

    private final HypoglycemiaService hypoglycemiaService;

    @PostMapping("/check/{visitId}")
    public ResponseEntity<ApiResponse<HypoglycemiaCheckResponse>> checkAndEnforce(
            @PathVariable UUID visitId) {
        HypoglycemiaCheckResponse response = hypoglycemiaService.checkAndEnforce(visitId);
        return ResponseEntity.ok(ApiResponse.success("Hypoglycemia check completed", response));
    }

    @PutMapping("/{eventId}/treatment")
    public ResponseEntity<ApiResponse<HypoglycemiaEventResponse>> recordTreatment(
            @PathVariable UUID eventId,
            @Valid @RequestBody RecordTreatmentRequest request) {
        HypoglycemiaEventResponse response = HypoglycemiaEventMapper.toResponse(
                hypoglycemiaService.recordTreatment(eventId, request));
        return ResponseEntity.ok(ApiResponse.success("Treatment recorded", response));
    }

    @PutMapping("/{eventId}/repeat-glucose")
    public ResponseEntity<ApiResponse<HypoglycemiaEventResponse>> recordRepeatGlucose(
            @PathVariable UUID eventId,
            @Valid @RequestBody RepeatGlucoseRequest request) {
        HypoglycemiaEventResponse response = HypoglycemiaEventMapper.toResponse(
                hypoglycemiaService.recordRepeatGlucose(eventId, request));
        return ResponseEntity.ok(ApiResponse.success("Repeat glucose recorded", response));
    }

    @PutMapping("/{eventId}/resolve")
    public ResponseEntity<ApiResponse<HypoglycemiaEventResponse>> resolveEvent(
            @PathVariable UUID eventId) {
        HypoglycemiaEventResponse response = HypoglycemiaEventMapper.toResponse(
                hypoglycemiaService.resolveEvent(eventId));
        return ResponseEntity.ok(ApiResponse.success("Event resolved", response));
    }

    @GetMapping("/visit/{visitId}")
    public ResponseEntity<ApiResponse<List<HypoglycemiaEventResponse>>> getEventsForVisit(
            @PathVariable UUID visitId) {
        List<HypoglycemiaEventResponse> responses = hypoglycemiaService.getEventsForVisit(visitId)
                .stream()
                .map(HypoglycemiaEventMapper::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @GetMapping("/hospital/{hospitalId}/active")
    public ResponseEntity<ApiResponse<List<HypoglycemiaEventResponse>>> getActiveEvents(
            @PathVariable UUID hospitalId) {
        List<HypoglycemiaEventResponse> responses = hypoglycemiaService.getActiveEvents(hospitalId)
                .stream()
                .map(HypoglycemiaEventMapper::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(responses));
    }
}
