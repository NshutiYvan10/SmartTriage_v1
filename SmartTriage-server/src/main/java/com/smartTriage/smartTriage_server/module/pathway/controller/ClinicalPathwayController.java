package com.smartTriage.smartTriage_server.module.pathway.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.pathway.dto.AbandonPathwayRequest;
import com.smartTriage.smartTriage_server.module.pathway.dto.ActivatePathwayRequest;
import com.smartTriage.smartTriage_server.module.pathway.dto.ClinicalPathwayResponse;
import com.smartTriage.smartTriage_server.module.pathway.dto.CompleteStepRequest;
import com.smartTriage.smartTriage_server.module.pathway.dto.CreatePathwayRequest;
import com.smartTriage.smartTriage_server.module.pathway.dto.PathwayActivationResponse;
import com.smartTriage.smartTriage_server.module.pathway.dto.PathwayProgressResponse;
import com.smartTriage.smartTriage_server.module.pathway.dto.PathwayRecommendation;
import com.smartTriage.smartTriage_server.module.pathway.dto.PathwayStepCompletionResponse;
import com.smartTriage.smartTriage_server.module.pathway.dto.PathwayStepResponse;
import com.smartTriage.smartTriage_server.module.pathway.dto.SkipStepRequest;
import com.smartTriage.smartTriage_server.module.pathway.service.ClinicalPathwayService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * ClinicalPathwayController — REST surface for evidence-based clinical pathways
 * (catalog browse, engine recommendation, activation, step completion/skip,
 * complete/abandon, and progress). Previously MISSING entirely, which left the
 * whole module unreachable (every frontend call 404'd). Catalog reads are open to
 * clinical roles; recommend/activate/active are visit-scoped; per-activation
 * mutations are activation-scoped (hospital isolation); create is admin-only.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/pathways")
@RequiredArgsConstructor
public class ClinicalPathwayController {

    private final ClinicalPathwayService pathwayService;

    // ── Catalog ─────────────────────────────────────────────────────────
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<List<ClinicalPathwayResponse>>> getAllPathways() {
        return ResponseEntity.ok(ApiResponse.success(pathwayService.getAllPathways()));
    }

    @GetMapping("/{pathwayId}/steps")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<List<PathwayStepResponse>>> getSteps(@PathVariable UUID pathwayId) {
        return ResponseEntity.ok(ApiResponse.success(pathwayService.getStepsForPathway(pathwayId)));
    }

    // ── Recommendation (advisory) ───────────────────────────────────────
    @PostMapping("/recommend/{visitId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<List<PathwayRecommendation>>> recommend(@PathVariable UUID visitId) {
        return ResponseEntity.ok(ApiResponse.success(pathwayService.recommendPathways(visitId)));
    }

    // ── Activation ──────────────────────────────────────────────────────
    @PostMapping("/activate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessVisit(authentication, #request.visitId)")
    public ResponseEntity<ApiResponse<PathwayActivationResponse>> activate(
            @Valid @RequestBody ActivatePathwayRequest request) {
        PathwayActivationResponse response = pathwayService.activatePathway(
                request.getVisitId(), request.getPathwayId(), request.getActivatedByName(), request.getNotes());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Pathway activated", response));
    }

    @GetMapping("/visit/{visitId}/active")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<List<PathwayActivationResponse>>> getActive(@PathVariable UUID visitId) {
        return ResponseEntity.ok(ApiResponse.success(pathwayService.getActivePathways(visitId)));
    }

    @GetMapping("/activation/{activationId}/progress")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessPathwayActivation(authentication, #activationId)")
    public ResponseEntity<ApiResponse<PathwayProgressResponse>> getProgress(@PathVariable UUID activationId) {
        return ResponseEntity.ok(ApiResponse.success(pathwayService.getPathwayProgress(activationId)));
    }

    // ── Step actions ────────────────────────────────────────────────────
    @PutMapping("/activation/{activationId}/step/{stepId}/complete")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessPathwayActivation(authentication, #activationId)")
    public ResponseEntity<ApiResponse<PathwayStepCompletionResponse>> completeStep(
            @PathVariable UUID activationId, @PathVariable UUID stepId,
            @RequestBody(required = false) CompleteStepRequest request) {
        CompleteStepRequest req = request != null ? request : new CompleteStepRequest();
        return ResponseEntity.ok(ApiResponse.success("Step completed",
                pathwayService.completeStep(activationId, stepId, req)));
    }

    @PutMapping("/activation/{activationId}/step/{stepId}/skip")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessPathwayActivation(authentication, #activationId)")
    public ResponseEntity<ApiResponse<PathwayStepCompletionResponse>> skipStep(
            @PathVariable UUID activationId, @PathVariable UUID stepId,
            @Valid @RequestBody SkipStepRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Step skipped",
                pathwayService.skipStep(activationId, stepId, request.getReason(), request.getCompletedByName())));
    }

    // ── Pathway lifecycle ───────────────────────────────────────────────
    @PutMapping("/activation/{activationId}/complete")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessPathwayActivation(authentication, #activationId)")
    public ResponseEntity<ApiResponse<PathwayActivationResponse>> completePathway(@PathVariable UUID activationId) {
        return ResponseEntity.ok(ApiResponse.success("Pathway completed",
                pathwayService.completePathway(activationId)));
    }

    @PutMapping("/activation/{activationId}/abandon")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessPathwayActivation(authentication, #activationId)")
    public ResponseEntity<ApiResponse<PathwayActivationResponse>> abandonPathway(
            @PathVariable UUID activationId, @Valid @RequestBody AbandonPathwayRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Pathway abandoned",
                pathwayService.abandonPathway(activationId, request.getReason())));
    }

    // ── Admin: create a pathway ─────────────────────────────────────────
    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<ClinicalPathwayResponse>> createPathway(
            @Valid @RequestBody CreatePathwayRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Pathway created", pathwayService.createPathway(request)));
    }
}
