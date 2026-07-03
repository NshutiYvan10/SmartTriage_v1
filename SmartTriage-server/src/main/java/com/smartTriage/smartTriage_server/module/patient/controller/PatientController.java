package com.smartTriage.smartTriage_server.module.patient.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.patient.dto.CreatePatientRequest;
import com.smartTriage.smartTriage_server.module.patient.dto.PatientLookupCandidate;
import com.smartTriage.smartTriage_server.module.patient.dto.PatientLookupQuery;
import com.smartTriage.smartTriage_server.module.patient.dto.PatientResponse;
import com.smartTriage.smartTriage_server.module.patient.dto.RegisterPatientRequest;
import com.smartTriage.smartTriage_server.module.patient.dto.RegisterPatientResponse;
import com.smartTriage.smartTriage_server.module.patient.dto.UpdateAllergiesRequest;
import com.smartTriage.smartTriage_server.module.patient.dto.UpdateChronicConditionsRequest;
import com.smartTriage.smartTriage_server.module.patient.dto.UpdatePregnancyStatusRequest;
import com.smartTriage.smartTriage_server.module.patient.service.PatientLookupService;
import com.smartTriage.smartTriage_server.module.patient.service.PatientService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Patient registration and lookup endpoints.
 * Accessible by REGISTRAR, NURSE, DOCTOR roles.
 */
@RestController
@RequestMapping("/api/v1/patients")
@RequiredArgsConstructor
public class PatientController {

    private final PatientService patientService;
    private final PatientLookupService patientLookupService;
    private final com.smartTriage.smartTriage_server.module.visit.service.VisitService visitService;

    @PostMapping
    // B9 — exclude the on-shift TRIAGE_NURSE from registration: their job is
    // triage, not reception. Non-triage nurses and registrars may still
    // register. Exclusion is by TODAY'S shift function, not permanent
    // designation, so a nurse not rostered to triage today is unaffected.
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'REGISTRAR', 'NURSE', 'DOCTOR') "
            + "and !@clinicalAuthz.callerIsTodaysTriageNurse(authentication)")
    public ResponseEntity<ApiResponse<PatientResponse>> createPatient(
            @Valid @RequestBody CreatePatientRequest request) {
        PatientResponse response = patientService.createPatient(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Patient registered successfully", response));
    }

    /**
     * Combined registration — creates Patient + Visit in one atomic transaction.
     * Prevents the issue where a patient record exists but no matching visit.
     */
    @PostMapping("/register")
    // B9 — exclude the on-shift TRIAGE_NURSE from registration (see createPatient).
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'REGISTRAR', 'NURSE', 'DOCTOR') "
            + "and !@clinicalAuthz.callerIsTodaysTriageNurse(authentication)")
    public ResponseEntity<ApiResponse<RegisterPatientResponse>> registerPatient(
            @Valid @RequestBody RegisterPatientRequest request) {
        RegisterPatientResponse response = patientService.registerPatientWithVisit(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Patient and visit created successfully", response));
    }

    // Full patient record (national ID, phone, address, emergency contacts…). Role-
    // gated to EXCLUDE LAB_TECHNICIAN + PARAMEDIC — they get scoped surfaces (Lab
    // Patients / EMS runs), not full-registry PHI. Mirrors the list/search allowlist,
    // closing the enumerate-then-read IDOR (scoped lists must never become a PHI key).
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'REGISTRAR', 'NURSE', 'DOCTOR', 'READ_ONLY') "
            + "and @clinicalAuthz.canAccessPatient(authentication, #id)")
    public ResponseEntity<ApiResponse<PatientResponse>> getPatient(@PathVariable UUID id) {
        PatientResponse response = patientService.getPatientById(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Phase 13b — set or clear the structured pregnancy status. This is
     * the producer for the column that the teratogen safety check reads
     * at prescribe time. Restricted to clinician roles because changing
     * a patient between PREGNANT and NOT_PREGNANT directly affects
     * which warnings the prescribe dialog will fire — registrars
     * shouldn't have that lever.
     *
     * To clear a previously-set value pass `UNKNOWN` rather than null —
     * the column then signals "we asked, we don't know" instead of
     * "we never asked", and the safety check falls back to free-text
     * scan correctly.
     */
    @PatchMapping("/{id}/pregnancy-status")
    @PreAuthorize("hasAnyRole('NURSE', 'DOCTOR') "
            + "and @clinicalAuthz.canAccessPatient(authentication, #id)")
    public ResponseEntity<ApiResponse<PatientResponse>> updatePregnancyStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePregnancyStatusRequest request) {
        PatientResponse response = patientService.updatePregnancyStatus(id, request);
        return ResponseEntity.ok(ApiResponse.success("Pregnancy status updated", response));
    }

    /**
     * Update the patient's free-text known allergies. Drives the medication
     * safety engine's cross-reactivity check on every prescribe — a stale
     * or missing allergy here is a real safety risk, which is why mid-visit
     * edit needs to be possible. REGISTRAR is excluded; updating allergies
     * is a clinical decision.
     *
     * The new value REPLACES the existing free-text. Pass null to clear
     * (e.g. when a previously-recorded allergy turns out to be wrong).
     */
    @PatchMapping("/{id}/allergies")
    @PreAuthorize("hasAnyRole('NURSE', 'DOCTOR') "
            + "and @clinicalAuthz.canAccessPatient(authentication, #id)")
    public ResponseEntity<ApiResponse<PatientResponse>> updateAllergies(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAllergiesRequest request) {
        PatientResponse response = patientService.updateKnownAllergies(id, request.getKnownAllergies());
        return ResponseEntity.ok(ApiResponse.success("Allergies updated", response));
    }

    /** Update the patient's free-text chronic conditions. Same semantics
     *  as updateAllergies — full replacement, null clears. */
    @PatchMapping("/{id}/chronic-conditions")
    @PreAuthorize("hasAnyRole('NURSE', 'DOCTOR') "
            + "and @clinicalAuthz.canAccessPatient(authentication, #id)")
    public ResponseEntity<ApiResponse<PatientResponse>> updateChronicConditions(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateChronicConditionsRequest request) {
        PatientResponse response = patientService.updateChronicConditions(id, request.getChronicConditions());
        return ResponseEntity.ok(ApiResponse.success("Chronic conditions updated", response));
    }

    // Registry list + search expose the whole hospital patient population with
    // full demographics (national ID, phone, address, allergies…), so they are
    // NOT for every clinical role. EXCLUDED:
    //   - PARAMEDIC — sees only patients they transported (their own EMS runs).
    //   - LAB_TECHNICIAN — sees only patients they have lab/diagnostic orders
    //     for, via the scoped Lab Patients view (GET /lab/hospital/{id}/patients);
    //     the full registry with full PHI is not the lab's business.
    // Allowlist = the roles that legitimately need the full registry.
    @GetMapping("/hospital/{hospitalId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'REGISTRAR', 'NURSE', 'DOCTOR', 'READ_ONLY') "
            + "and @clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<Page<PatientResponse>>> getPatientsByHospital(
            @PathVariable UUID hospitalId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<PatientResponse> response = patientService.getPatientsByHospital(hospitalId, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/hospital/{hospitalId}/search")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'REGISTRAR', 'NURSE', 'DOCTOR', 'READ_ONLY') "
            + "and @clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<Page<PatientResponse>>> searchPatients(
            @PathVariable UUID hospitalId,
            @RequestParam String query,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<PatientResponse> response = patientService.searchPatients(hospitalId, query, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Federated patient lookup — supply any combination of identifiers
     * (NID, passport, birth-cert, MRN, phone+DOB, guardian NID/phone,
     * name+DOB) and get back ranked candidates with confidence scores.
     *
     * Hospital scope is taken from the path; it is not derived from the
     * request body so a triage nurse cannot exfiltrate patients from
     * another hospital by tampering with the query.
     *
     * Returns an empty list if no identifiers are supplied.
     */
    @GetMapping("/hospital/{hospitalId}/lookup")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'REGISTRAR', 'NURSE', 'DOCTOR') "
            + "and @clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<PatientLookupCandidate>>> lookupPatients(
            @PathVariable UUID hospitalId,
            @RequestParam(required = false) String nationalId,
            @RequestParam(required = false) String passport,
            @RequestParam(required = false) String birthCertificate,
            @RequestParam(required = false) String mrn,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String guardianNationalId,
            @RequestParam(required = false) String guardianPhone,
            @RequestParam(required = false) String firstName,
            @RequestParam(required = false) String lastName,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dob) {
        PatientLookupQuery q = PatientLookupQuery.builder()
                .nationalId(nationalId)
                .passport(passport)
                .birthCertificate(birthCertificate)
                .mrn(mrn)
                .phone(phone)
                .guardianNationalId(guardianNationalId)
                .guardianPhone(guardianPhone)
                .firstName(firstName)
                .lastName(lastName)
                .dob(dob)
                .build();
        List<PatientLookupCandidate> candidates = patientLookupService.lookup(hospitalId, q);
        return ResponseEntity.ok(ApiResponse.success(candidates));
    }

    /**
     * GLOBAL patient registry search — REGISTRAR-only, system-wide across ALL hospitals.
     * The deliberate exception to hospital scoping (a King Faisal registrar finds a
     * CHUK-registered patient). {@code myHospitalId} is the registrar's own hospital,
     * used only to flag which rows are local / already have an open visit here — never
     * to filter. Clinical roles are excluded: they use /hospital/{id}/search.
     */
    @GetMapping("/registry/search")
    @PreAuthorize("@clinicalAuthz.canSearchGlobalRegistry(authentication)")
    public ResponseEntity<ApiResponse<Page<com.smartTriage.smartTriage_server.module.patient.dto.GlobalPatientRow>>> globalRegistrySearch(
            @RequestParam String query,
            @RequestParam(required = false) UUID myHospitalId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<com.smartTriage.smartTriage_server.module.patient.dto.GlobalPatientRow> response =
                patientService.globalRegistrySearch(myHospitalId, query, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Registrar "start a visit HERE" for a patient found in the global registry — the
     * manual-search twin of the RFID open-visit flow. Reuses / registers-a-local-copy of
     * a cross-hospital patient (linked by shared identity) and opens a fresh visit; the
     * duplicate-visit guard rejects a second open visit for the same patient here.
     */
    // Cross-hospital reuse (registering a local copy of a patient first seen elsewhere) is a
    // REGISTRATION-DESK action — same gate as the global registry search that yields these
    // patient ids (canSearchGlobalRegistry = SUPER_ADMIN/REGISTRAR). Clinical roles are
    // deliberately NOT here: the source patient is loaded system-wide (not hospital-scoped),
    // so admitting a DOCTOR/NURSE would leak another hospital's demographics into their tenant.
    @PostMapping("/{patientId}/open-visit-here")
    @PreAuthorize("@clinicalAuthz.canSearchGlobalRegistry(authentication) "
            + "and @clinicalAuthz.canAccessHospital(authentication, #request.hospitalId)")
    public ResponseEntity<ApiResponse<RegisterPatientResponse>> openVisitHere(
            @PathVariable UUID patientId,
            @Valid @RequestBody com.smartTriage.smartTriage_server.module.patient.dto.OpenVisitHereRequest request) {
        RegisterPatientResponse response = visitService.openVisitHereForPatient(
                patientId, request.getHospitalId(), request.getArrivalMode(), request.getChiefComplaint());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Visit opened", response));
    }
}
