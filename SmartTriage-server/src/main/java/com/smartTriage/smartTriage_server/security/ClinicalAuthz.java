package com.smartTriage.smartTriage_server.security;

import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.module.alert.service.AlertScopeResolver;
import com.smartTriage.smartTriage_server.common.enums.Designation;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.ReportLevel;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.common.enums.ShiftFunction;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.clinical.repository.ClinicalNoteRepository;
import com.smartTriage.smartTriage_server.module.clinical.repository.DiagnosisRepository;
import com.smartTriage.smartTriage_server.module.clinical.repository.InvestigationRepository;
import com.smartTriage.smartTriage_server.module.consent.repository.InformedConsentRepository;
import com.smartTriage.smartTriage_server.module.documentation.repository.ClinicalDocumentRepository;
import com.smartTriage.smartTriage_server.module.governance.repository.ClinicalPolicyRepository;
import com.smartTriage.smartTriage_server.module.handover.repository.HandoverReportRepository;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientRepository;
import com.smartTriage.smartTriage_server.module.sepsis.repository.SepsisScreeningRepository;
import com.smartTriage.smartTriage_server.module.fasttrack.repository.FastTrackActivationRepository;
import com.smartTriage.smartTriage_server.module.hypoglycemia.repository.HypoglycemiaEventRepository;
import com.smartTriage.smartTriage_server.module.isolation.repository.InfectionScreeningRepository;
import com.smartTriage.smartTriage_server.module.pathway.repository.PathwayActivationRepository;
import com.smartTriage.smartTriage_server.module.referral.repository.ReferralRepository;
import com.smartTriage.smartTriage_server.module.reporting.repository.MohReportRepository;
import com.smartTriage.smartTriage_server.module.lab.repository.LabOrderRepository;
import com.smartTriage.smartTriage_server.module.medsafety.repository.MedicationSafetyCheckRepository;
import com.smartTriage.smartTriage_server.module.iot.repository.IoTDeviceRepository;
import com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentService;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Authorization helper for endpoints that read or write clinical data
 * (patient records, visits, vitals, alerts, medications, diagnoses, lab
 * results, clinical notes, ...).
 *
 * <p>Wired into Spring Security via SpEL — e.g.:
 *
 * <pre>{@code
 * @PreAuthorize("@clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
 * @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
 * @PreAuthorize("@clinicalAuthz.canSeeAllZonesAtHospital(authentication, #hospitalId)")
 * }</pre>
 *
 * <h2>Three orthogonal questions, three methods</h2>
 *
 * <ul>
 *   <li>{@link #canAccessHospital} — "is this user attached to this hospital?"
 *       The cross-hospital boundary. SUPER_ADMIN bypasses; everyone else must
 *       have {@code user.hospital_id == hospitalId}. Used on every endpoint
 *       that takes a {@code hospitalId} path parameter.</li>
 *
 *   <li>{@link #canAccessVisit} — "is this visit at the user's hospital?"
 *       Used on visit-keyed endpoints (vitals/{visitId}, medications/visit/...,
 *       triage/visit/..., etc.) where {@code hospitalId} is not in the URL
 *       but the visit row carries it. Resolves visit→hospital_id via a
 *       projection query and delegates to {@link #canAccessHospital}.</li>
 *
 *   <li>{@link #canSeeAllZonesAtHospital} — "may this user see patients
 *       across all zones?" Hospital-wide visibility is allowed for
 *       SUPER_ADMIN, the HOSPITAL_ADMIN at this hospital, the user holding
 *       the shift-lead badge, and any nurse with Designation.CHARGE_NURSE.
 *       Everyone else (regular doctor, regular nurse) must be filtered to
 *       their assigned zone — the controller falls back to a zone-scoped
 *       query when this returns false.</li>
 * </ul>
 *
 * <h2>Why a separate bean from {@link com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentAuthz}</h2>
 *
 * That bean models the question "may this user write the staffing roster?"
 * — a narrow administrative authority. This bean models "may this user read
 * a clinical record?" — a much broader question that applies to most of the
 * API surface. They share helpers (current shift-lead, charge-nurse
 * designation) but the policies diverge: read-access permits a much wider
 * set of actors than roster-write authority does, and read-access does not
 * suspend on approved leave (a CN at home reading the dashboard is fine;
 * a CN at home approving swap requests is not).
 *
 * <h2>Exception-safety</h2>
 *
 * Spring Security evaluates these methods in the SpEL phase, before the
 * controller transaction opens. A {@code LazyInitializationException} on
 * {@code User#hospital} would otherwise produce a 500 instead of a clean
 * 403, leaking implementation detail and confusing the UI. Every public
 * method therefore has a defensive try/catch that fails closed (returns
 * false). Hospital membership is resolved via the same primitive-projection
 * query {@link UserRepository#findHospitalIdByUserId} that
 * {@code ShiftAssignmentAuthz} uses, so we never dereference the lazy
 * association on a detached principal.
 */
@Slf4j
@Component("clinicalAuthz")
@RequiredArgsConstructor
public class ClinicalAuthz {

    private final UserRepository userRepository;
    private final VisitRepository visitRepository;
    private final PatientRepository patientRepository;
    private final ShiftAssignmentService shiftAssignmentService;
    private final ClinicalNoteRepository clinicalNoteRepository;
    private final DiagnosisRepository diagnosisRepository;
    private final InvestigationRepository investigationRepository;
    private final HandoverReportRepository handoverReportRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final SepsisScreeningRepository sepsisScreeningRepository;
    private final FastTrackActivationRepository fastTrackActivationRepository;
    private final HypoglycemiaEventRepository hypoglycemiaEventRepository;
    private final InfectionScreeningRepository infectionScreeningRepository;
    private final PathwayActivationRepository pathwayActivationRepository;
    private final LabOrderRepository labOrderRepository;
    private final ClinicalDocumentRepository clinicalDocumentRepository;
    private final InformedConsentRepository informedConsentRepository;
    private final ReferralRepository referralRepository;
    private final MohReportRepository mohReportRepository;
    private final MedicationSafetyCheckRepository medicationSafetyCheckRepository;
    private final com.smartTriage.smartTriage_server.module.medication.repository.MedicationAdministrationRepository medicationAdministrationRepository;
    private final com.smartTriage.smartTriage_server.module.medication.repository.MedicationDoseRepository medicationDoseRepository;
    private final com.smartTriage.smartTriage_server.module.icu.repository.IcuEscalationRepository icuEscalationRepository;
    private final com.smartTriage.smartTriage_server.module.zonetransfer.repository.ZoneTransferRepository zoneTransferRepository;
    private final IoTDeviceRepository ioTDeviceRepository;
    private final com.smartTriage.smartTriage_server.module.safety.repository.SafetyIncidentRepository safetyIncidentRepository;
    private final ClinicalPolicyRepository clinicalPolicyRepository;
    /**
     * V107 patient-centric audit: authorization is the one place that already
     * resolves WHICH VISIT every clinical request targets (all resource-keyed
     * canAccessX checks funnel into canAccessVisit) — so it notes that visit on
     * the request's audit context, and AuditInterceptor stamps it onto the
     * audit row. Note-only; it never influences the authorization decision.
     */
    private final com.smartTriage.smartTriage_server.module.audit.context.AuditContext auditContext;

    /**
     * @return true if the authenticated user is attached to {@code hospitalId}.
     *         SUPER_ADMIN always returns true; everyone else must match.
     */
    @Transactional(readOnly = true)
    public boolean canAccessHospital(Authentication authentication, UUID hospitalId) {
        try {
            User user = currentUser(authentication);
            if (user == null || hospitalId == null) {
                return false;
            }
            if (user.getRole() == Role.SUPER_ADMIN) {
                return true;
            }
            return belongsToHospital(user, hospitalId);
        } catch (Exception e) {
            log.error("canAccessHospital error for hospital {}: {}", hospitalId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Like {@link #canAccessVisit(Authentication, UUID)} but accepts a loose
     * visit REFERENCE: either the internal UUID or the human-readable visit
     * number (V-KFH-001-…) that users actually see on screens. Used by search
     * endpoints where users paste whatever identifier they have; previously a
     * pasted visit number 500-ed on UUID parsing.
     *
     * <p>An unresolvable reference returns {@code true} so the controller can
     * answer with a clear 404 ("no visit found") instead of a misleading 403 —
     * no data is exposed because the controller's own lookup fails first.
     */
    @Transactional(readOnly = true)
    public boolean canAccessVisitRef(Authentication authentication, String visitRef) {
        try {
            if (visitRef == null || visitRef.isBlank()) return false;
            String trimmed = visitRef.trim();
            UUID id;
            try {
                id = UUID.fromString(trimmed);
            } catch (IllegalArgumentException notUuid) {
                id = visitRepository.findByVisitNumberAndIsActiveTrue(trimmed)
                        .map(v -> v.getId())
                        .orElse(null);
            }
            if (id == null) return true; // controller 404s with a clear message
            return canAccessVisit(authentication, id);
        } catch (Exception e) {
            log.error("canAccessVisitRef error for ref {}: {}", visitRef, e.getMessage(), e);
            return false;
        }
    }

    /**
     * @return true if the visit identified by {@code visitId} belongs to the
     *         authenticated user's hospital. Returns false when the visit
     *         does not exist (no information leak about which ids exist).
     */
    @Transactional(readOnly = true)
    public boolean canAccessVisit(Authentication authentication, UUID visitId) {
        try {
            if (visitId == null) return false;
            // Audit attribution (V107): note the visit BEFORE deciding, so even a
            // DENIED attempt is recorded against the patient it targeted.
            auditContext.noteVisit(visitId);
            Optional<UUID> visitHospitalId = visitRepository.findHospitalIdByVisitId(visitId);
            if (visitHospitalId.isEmpty()) {
                // Don't reveal whether the id exists — deny.
                return false;
            }
            return canAccessHospital(authentication, visitHospitalId.get());
        } catch (Exception e) {
            log.error("canAccessVisit error for visit {}: {}", visitId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * @return true if the alert identified by {@code alertId} belongs to a visit at the
     *         authenticated user's hospital. Resolves alert→visit→hospital via projection.
     *         Used to hospital-scope the generic acknowledge endpoint so a clinician at one
     *         hospital cannot acknowledge another hospital's alert by id.
     */
    @Transactional(readOnly = true)
    public boolean canAccessAlert(Authentication authentication, UUID alertId) {
        try {
            if (alertId == null) return false;
            Optional<UUID> visitId = clinicalAlertRepository.findVisitIdById(alertId);
            if (visitId.isEmpty()) return false; // unknown id — deny, no existence leak
            return canAccessVisit(authentication, visitId.get());
        } catch (Exception e) {
            log.error("canAccessAlert error for alert {}: {}", alertId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * CATEGORY-AWARE acknowledge gate. Hospital scope ({@link #canAccessAlert}) alone is not enough:
     * a SCOPED desk/field role (REGISTRAR / LAB_TECHNICIAN / PARAMEDIC) must only be able to
     * acknowledge alerts inside its own {@link AlertScopeResolver} scope — otherwise a non-clinical
     * role could enumerate a visit's alerts and silence a life-critical clinical alert (e.g. a
     * CRITICAL_LAB_RESULT), dropping it out of the escalation re-page loop. Oversight/clinical roles
     * (SUPER_ADMIN / DOCTOR / NURSE and the like) keep the plain hospital-scoped behaviour.
     */
    @Transactional(readOnly = true)
    public boolean canAckAlertForRole(Authentication authentication, UUID alertId) {
        try {
            User user = currentUser(authentication);
            if (user == null || alertId == null) return false;
            // Must first be at the alert's hospital (closes cross-tenant).
            if (!canAccessAlert(authentication, alertId)) return false;

            Role role = user.getRole();
            // Scoped roles: restrict to their own alert scope.
            if (role == Role.LAB_TECHNICIAN) {
                return clinicalAlertRepository.findAlertTypeById(alertId)
                        .map(AlertScopeResolver::isLabScopedType).orElse(false);
            }
            if (role == Role.REGISTRAR) {
                return clinicalAlertRepository.findAlertTypeById(alertId)
                        .map(AlertScopeResolver::isRegistrarScopedType).orElse(false);
            }
            if (role == Role.PARAMEDIC) {
                // Personal scope: only alerts addressed to this crew member.
                return clinicalAlertRepository.findTargetDoctorIdById(alertId)
                        .map(id -> id.equals(user.getId())).orElse(false);
            }
            // Oversight / clinical roles keep hospital-scoped ack (already checked above).
            return true;
        } catch (Exception e) {
            log.error("canAckAlertForRole error for alert {}: {}", alertId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * @return true when the {@code targetUserId} is the caller themselves OR
     *         belongs to the caller's hospital. Used by endpoints keyed on a
     *         user id (e.g. {@code /alerts/doctor/{doctorId}}) so a DOCTOR
     *         can read their own queue but not a colleague's at another
     *         hospital.
     */
    @Transactional(readOnly = true)
    public boolean canAccessUser(Authentication authentication, UUID targetUserId) {
        try {
            User user = currentUser(authentication);
            if (user == null || targetUserId == null) return false;
            if (user.getRole() == Role.SUPER_ADMIN) return true;
            if (targetUserId.equals(user.getId())) return true;
            // Same hospital — resolve the target's hospital_id via projection.
            Optional<UUID> targetHospitalId = userRepository.findHospitalIdByUserId(targetUserId);
            if (targetHospitalId.isEmpty()) return false;
            return targetHospitalId.get().equals(
                    userRepository.findHospitalIdByUserId(user.getId()).orElse(null));
        } catch (Exception e) {
            log.error("canAccessUser error for user {}: {}", targetUserId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * @return true if the patient identified by {@code patientId} belongs to
     *         the authenticated user's hospital.
     */
    @Transactional(readOnly = true)
    public boolean canAccessPatient(Authentication authentication, UUID patientId) {
        try {
            if (patientId == null) return false;
            Optional<UUID> patientHospitalId = patientRepository.findHospitalIdByPatientId(patientId);
            if (patientHospitalId.isEmpty()) {
                return false;
            }
            return canAccessHospital(authentication, patientHospitalId.get());
        } catch (Exception e) {
            log.error("canAccessPatient error for patient {}: {}", patientId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Hospital-wide (cross-zone) visibility. True for:
     * <ul>
     *   <li>SUPER_ADMIN (always)</li>
     *   <li>HOSPITAL_ADMIN at this hospital</li>
     *   <li>The user currently holding the shift-lead badge at this hospital
     *       (Charge Nurse / acting CN)</li>
     *   <li>Any nurse with {@link Designation#CHARGE_NURSE} attached to this
     *       hospital — CN authority is part of the role, not just the badge,
     *       so they retain cross-zone visibility even when not the active
     *       shift-lead.</li>
     * </ul>
     *
     * <p>Used on hospital-wide list endpoints that have a parallel zone-scoped
     * variant: when this returns false, the controller routes to the
     * zone-filtered query. The full hospital-wide endpoint remains, but its
     * own {@code @PreAuthorize} requires this method — so a regular doctor
     * calling it directly gets a 403, not a leak.
     */
    @Transactional(readOnly = true)
    public boolean canSeeAllZonesAtHospital(Authentication authentication, UUID hospitalId) {
        try {
            User user = currentUser(authentication);
            if (user == null || hospitalId == null) {
                return false;
            }
            if (user.getRole() == Role.SUPER_ADMIN) {
                return true;
            }
            boolean sameHospital = belongsToHospital(user, hospitalId);
            if (!sameHospital) {
                return false;
            }
            if (user.getRole() == Role.HOSPITAL_ADMIN) {
                return true;
            }
            // Charge-nurse designation grants cross-zone read regardless of
            // current shift state. Defence-in-depth: also require role=NURSE.
            if (user.getRole() == Role.NURSE
                    && user.getDesignation() == Designation.CHARGE_NURSE) {
                return true;
            }
            // Anyone holding the shift-lead badge right now (acting CN, or a
            // doctor promoted by the materialiser when no nurse is on
            // shift). The badge is the canonical "you're in charge of the
            // floor right now" signal.
            return shiftAssignmentService.isUserCurrentShiftLead(user.getId(), hospitalId);
        } catch (Exception e) {
            log.error("canSeeAllZonesAtHospital error for hospital {}: {}",
                    hospitalId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Alert-center read gate. Kept in PARITY with the live alert stream:
     * {@code /topic/alerts/{hospitalId}} is gated by
     * {@link #canAccessHospital(Authentication, UUID)} in
     * {@code StompAuthChannelInterceptor}, so every clinical staff member at the
     * hospital already RECEIVES these alerts in real time. Gating the historical
     * REST read more strictly (formerly doctor / charge-nurse / shift-lead only)
     * left regular nurses, lab techs, registrars and paramedics denied here while
     * still getting the same alerts live — so the Alert Center flipped between
     * showing live pushes and a false "feed unavailable" whenever the live buffer
     * happened to be empty (visibility tracked recent pushes, not actual state).
     * Anyone who can receive the alerts live can read their history. The ONE
     * exception is HOSPITAL_ADMIN, who stays denied: the clinical alert queue is
     * a clinician surface and product policy (S6) keeps administrators out of it.
     */
    @Transactional(readOnly = true)
    public boolean canReadHospitalAlerts(Authentication authentication, UUID hospitalId) {
        try {
            User user = currentUser(authentication);
            if (user == null) {
                return false;
            }
            if (user.getRole() == Role.HOSPITAL_ADMIN) {
                return false;
            }
            return canAccessHospital(authentication, hospitalId);
        } catch (Exception e) {
            log.error("canReadHospitalAlerts error for hospital {}: {}",
                    hospitalId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * WebSocket SUBSCRIBE gate for a ZONE alert topic
     * {@code /topic/alerts/{hospitalId}/{zone}}. Allowed for cross-zone/oversight
     * roles (Charge Nurse, shift lead, Super-Admin, Read-Only) and for any clinician
     * currently assigned to that zone (primary or additional coverage). This is the
     * live-pop-up half of the alert-scoping policy and mirrors {@link AlertScopeResolver}
     * (the REST half): a General-zone nurse can subscribe to GENERAL alerts but not
     * ACUTE, and the hospital-wide firehose is gated separately by
     * {@link #canSeeAllZonesAtHospital}.
     */
    @Transactional(readOnly = true)
    public boolean canReceiveZoneAlerts(Authentication authentication, UUID hospitalId, EdZone zone) {
        try {
            if (zone == null || hospitalId == null) {
                return false;
            }
            if (canSeeAllZonesAtHospital(authentication, hospitalId)) {
                return true;
            }
            User user = currentUser(authentication);
            if (user == null || !belongsToHospital(user, hospitalId)) {
                return false;
            }
            return shiftAssignmentService.getCurrentShiftForUser(user.getId())
                    .map(sa -> hospitalId.equals(sa.getHospitalId())
                            && (zone.equals(sa.getZone())
                                || (sa.getAdditionalZones() != null
                                    && sa.getAdditionalZones().contains(zone))))
                    .orElse(false);
        } catch (Exception e) {
            log.error("canReceiveZoneAlerts error for hospital {} zone {}: {}",
                    hospitalId, zone, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Reporting/analytics read gate — for hospital-wide aggregate reports (Quality
     * KPIs, MoH statistics, operational analytics). Restricted to the governance /
     * management / audit readers: SUPER_ADMIN, and the HOSPITAL_ADMIN AT THIS
     * HOSPITAL. Deliberately NOT every clinician
     * — a bedside doctor/nurse/paramedic/lab-tech/registrar has no need for
     * hospital-wide mortality / LWBS / throughput aggregates, and the Quality
     * endpoints were previously gated only by hospital membership (any role).
     */
    @Transactional(readOnly = true)
    public boolean canViewHospitalReports(Authentication authentication, UUID hospitalId) {
        try {
            User user = currentUser(authentication);
            if (user == null || hospitalId == null) {
                return false;
            }
            if (user.getRole() == Role.SUPER_ADMIN) {
                return true;
            }
            if (!belongsToHospital(user, hospitalId)) {
                return false;
            }
            return user.getRole() == Role.HOSPITAL_ADMIN;
        } catch (Exception e) {
            log.error("canViewHospitalReports error for hospital {}: {}", hospitalId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Read gate for a single visit's AUDIT TRAIL (V107 incident timeline) — the
     * same governance/audit-reader tier as {@link #canViewHospitalReports}, scoped
     * via the visit's OWN hospital. Deliberately does NOT reuse canAccessVisit:
     * this is oversight tooling for admins/auditors, not bedside chart access —
     * and canAccessVisit's audit-attribution hook must not fire for the auditor
     * merely READING the trail.
     */
    @Transactional(readOnly = true)
    public boolean canViewAuditForVisit(Authentication authentication, UUID visitId) {
        try {
            if (visitId == null) return false;
            return visitRepository.findHospitalIdByVisitId(visitId)
                    .map(hospitalId -> canViewHospitalReports(authentication, hospitalId))
                    .orElse(false);
        } catch (Exception e) {
            log.error("canViewAuditForVisit error for visit {}: {}", visitId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Read gate for the REGISTRAR reporting pack (intake log, unidentified-patient reconciliation
     * queue, census). This is OPERATIONAL desk reporting — distinct from the governance-tier
     * {@link #canViewHospitalReports} (which deliberately excludes the registrar). Allowed:
     * SUPER_ADMIN (any hospital); REGISTRAR + HOSPITAL_ADMIN at their own hospital. Clinical roles
     * (DOCTOR/NURSE/LAB_TECHNICIAN/PARAMEDIC) are not a registration-desk reporting audience.
     */
    @Transactional(readOnly = true)
    public boolean canAccessRegistrarReports(Authentication authentication, UUID hospitalId) {
        try {
            User user = currentUser(authentication);
            if (user == null || hospitalId == null) {
                return false;
            }
            if (user.getRole() == Role.SUPER_ADMIN) {
                return true;
            }
            if (!belongsToHospital(user, hospitalId)) {
                return false;
            }
            return user.getRole() == Role.REGISTRAR || user.getRole() == Role.HOSPITAL_ADMIN;
        } catch (Exception e) {
            log.error("canAccessRegistrarReports error for hospital {}: {}", hospitalId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * The GLOBAL patient registry search is the ONE deliberately un-hospital-scoped patient
     * lookup: a REGISTRAR (or SUPER_ADMIN) may find a patient first registered at ANY hospital
     * so a returning patient is reused, not re-registered. NOT a clinical surface — doctors,
     * nurses, lab techs and paramedics keep the hospital-scoped searches (no cross-hospital
     * patient browsing for clinical staff). Hospital-Admin is a governance role, not a desk role.
     */
    @Transactional(readOnly = true)
    public boolean canSearchGlobalRegistry(Authentication authentication) {
        try {
            User user = currentUser(authentication);
            if (user == null) return false;
            return user.getRole() == Role.SUPER_ADMIN || user.getRole() == Role.REGISTRAR;
        } catch (Exception e) {
            log.error("canSearchGlobalRegistry error: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Object-level read gate for a single MoH report by id (JSON + PDF). NATIONAL rollups
     * are SUPER_ADMIN-only; a HOSPITAL report is readable only by the SUPER_ADMIN or the
     * HOSPITAL_ADMIN of THAT report's hospital. Closes the by-id IDOR hole where
     * the class-level role gate alone let any admin read national or other-hospital
     * reports by guessing/holding the UUID. Denies on unknown id (no existence leak).
     */
    @Transactional(readOnly = true)
    public boolean canViewMohReport(Authentication authentication, UUID reportId) {
        try {
            User user = currentUser(authentication);
            if (user == null || reportId == null) {
                return false;
            }
            if (user.getRole() == Role.SUPER_ADMIN) {
                return true;
            }
            ReportLevel level = mohReportRepository.findReportLevelById(reportId).orElse(null);
            if (level == null || level == ReportLevel.NATIONAL) {
                return false; // unknown/inactive, or national (super-admin only)
            }
            UUID hospitalId = mohReportRepository.findHospitalIdById(reportId).orElse(null);
            return hospitalId != null && canViewHospitalReports(authentication, hospitalId);
        } catch (Exception e) {
            log.error("canViewMohReport error for report {}: {}", reportId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Object-level gate for advancing a MoH report's lifecycle (submit). NATIONAL rollups
     * may only be submitted by SUPER_ADMIN (the national governance owner); a HOSPITAL report
     * may be submitted by the SUPER_ADMIN or the HOSPITAL_ADMIN of THAT hospital. Prevents a
     * hospital admin from submitting a national rollup or another hospital's report by id.
     */
    @Transactional(readOnly = true)
    public boolean canSubmitMohReport(Authentication authentication, UUID reportId) {
        try {
            User user = currentUser(authentication);
            if (user == null || reportId == null) {
                return false;
            }
            if (user.getRole() == Role.SUPER_ADMIN) {
                return true;
            }
            if (user.getRole() != Role.HOSPITAL_ADMIN) {
                return false;
            }
            ReportLevel level = mohReportRepository.findReportLevelById(reportId).orElse(null);
            if (level == null || level == ReportLevel.NATIONAL) {
                return false; // unknown/inactive, or national (super-admin only)
            }
            UUID hospitalId = mohReportRepository.findHospitalIdById(reportId).orElse(null);
            return hospitalId != null && belongsToHospital(user, hospitalId);
        } catch (Exception e) {
            log.error("canSubmitMohReport error for report {}: {}", reportId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Cross-hospital patient SAFETY-SUMMARY read gate (Phase 1 — federated identity).
     *
     * <p>This is the deliberate "safety floor": demographics + allergies + blood type + active
     * meds + chronic problems + emergency contacts, shared across SmartTriage hospitals so a
     * returning patient is recognised instead of re-registered. It therefore DELIBERATELY does
     * NOT scope to the caller's hospital ({@code canAccessHospital}) — cross-hospital read is the
     * whole point. It is role-gated to the clinical/registration roles that register or treat
     * patients (SUPER_ADMIN, DOCTOR, NURSE, REGISTRAR, PARAMEDIC); admins/lab are denied.
     * It does NOT open the deep clinical record — that stays hospital-owned (a later phase).
     * Every read is separately written to the audit log.
     */
    @Transactional(readOnly = true)
    public boolean canReadCrossHospitalSafetySummary(Authentication authentication) {
        try {
            User user = currentUser(authentication);
            if (user == null) {
                return false;
            }
            return switch (user.getRole()) {
                case SUPER_ADMIN, DOCTOR, NURSE, REGISTRAR, PARAMEDIC -> true;
                default -> false;
            };
        } catch (Exception e) {
            log.error("canReadCrossHospitalSafetySummary error: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Who may RECORD/withdraw a cross-hospital data-sharing consent (Phase 2). Captured at the desk
     * by the registrar/clinician with the patient, so the registration-capable clinical roles
     * (SUPER_ADMIN, DOCTOR, NURSE, REGISTRAR). Role-only (consent is keyed on the cross-hospital
     * identity, so hospital scoping doesn't apply); each write is separately audited.
     */
    @Transactional(readOnly = true)
    public boolean canManageDataSharingConsent(Authentication authentication) {
        try {
            User user = currentUser(authentication);
            if (user == null) {
                return false;
            }
            return switch (user.getRole()) {
                case SUPER_ADMIN, DOCTOR, NURSE, REGISTRAR -> true;
                default -> false;
            };
        } catch (Exception e) {
            log.error("canManageDataSharingConsent error: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Who may ATTEMPT a cross-hospital DEEP-record read (Phase 2). Treating clinicians only —
     * SUPER_ADMIN, DOCTOR, NURSE, PARAMEDIC (REGISTRAR excluded: the deep clinical record is not a
     * registration need; paramedic kept for pre-arrival/emergency lookup). This is only the ROLE
     * gate — the actual disclosure is gated DATA-side in the service by consent OR break-the-glass,
     * and the read deliberately bypasses hospital scope (cross-hospital is the point).
     */
    @Transactional(readOnly = true)
    public boolean canAccessCrossHospitalDeepRecord(Authentication authentication) {
        try {
            User user = currentUser(authentication);
            if (user == null) {
                return false;
            }
            return switch (user.getRole()) {
                case SUPER_ADMIN, DOCTOR, NURSE, PARAMEDIC -> true;
                default -> false;
            };
        } catch (Exception e) {
            log.error("canAccessCrossHospitalDeepRecord error: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * May this user INVOKE the break-the-glass emergency override on the
     * cross-hospital deep record? Deliberately STRICTER than the deep-record
     * ATTEMPT gate above: overriding an absent consent is a senior clinical
     * act with legal weight. Doctors and paramedics (pre-arrival emergency
     * lookup) qualify; a nurse qualifies only with senior authority — a
     * Charge / Senior Nurse designation or the current shift-lead badge.
     * Staff and student nurses still read consented records normally; they
     * just cannot bypass a missing consent on their own.
     */
    @Transactional(readOnly = true)
    public boolean canInvokeBreakTheGlass(Authentication authentication) {
        try {
            User user = currentUser(authentication);
            if (user == null) {
                return false;
            }
            return switch (user.getRole()) {
                case SUPER_ADMIN, DOCTOR, PARAMEDIC -> true;
                case NURSE -> hasSeniorNurseAuthority(user);
                default -> false;
            };
        } catch (Exception e) {
            log.error("canInvokeBreakTheGlass error: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * May this user ACTIVATE a clinical pathway on a visit? Activation
     * commits the care team to protocol compliance timers, so it is a
     * senior clinical decision: doctors always; nurses only with senior
     * authority (Charge / Senior Nurse designation or the shift-lead
     * badge). Every nurse can still SEE active pathways and work them —
     * completing and skipping steps stays open to the whole care team.
     */
    @Transactional(readOnly = true)
    public boolean canActivateClinicalPathway(Authentication authentication) {
        try {
            User user = currentUser(authentication);
            if (user == null) {
                return false;
            }
            return switch (user.getRole()) {
                case SUPER_ADMIN, DOCTOR -> true;
                case NURSE -> hasSeniorNurseAuthority(user);
                default -> false;
            };
        } catch (Exception e) {
            log.error("canActivateClinicalPathway error: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Senior nursing authority: a Charge / Senior Nurse designation, or the
     * current transferable shift-lead badge. Used by the break-the-glass and
     * pathway-activation gates; NOT a general privilege tier — each gate that
     * needs it opts in explicitly.
     */
    public boolean hasSeniorNurseAuthority(User user) {
        if (user == null || user.getRole() != Role.NURSE) {
            return false;
        }
        if (user.getDesignation() == Designation.CHARGE_NURSE
                || user.getDesignation() == Designation.SENIOR_NURSE) {
            return true;
        }
        return userRepository.findHospitalIdByUserId(user.getId())
                .map(hid -> shiftAssignmentService.isUserCurrentShiftLead(user.getId(), hid))
                .orElse(false);
    }

    /**
     * May this user view the FORENSIC medication-safety override audit?
     *
     * <p>This is a governance / quality surface (hospital safety officer,
     * clinical lead, M&amp;M committee, administrator) — deliberately distinct
     * from {@link #canReadHospitalAlerts}, which gates the OPERATIONAL alert
     * stream to cross-zone clinical floor leads and denies HOSPITAL_ADMIN. The
     * override audit is retrospective, scoped to medication-safety override
     * rows, and its intended readers do not hold a clinical shift badge — so it
     * needs its own, broader-but-still-bounded authority:
     * <ul>
     *   <li>SUPER_ADMIN — always;</li>
     *   <li>at the SAME hospital: HOSPITAL_ADMIN (governance), DOCTOR (peer
     *       review), and a Charge Nurse / current shift-lead nurse (floor
     *       oversight).</li>
     * </ul>
     * These are exactly the roles the frontend surfaces the Override Audit page
     * to, so "can see the page" now implies "can load it" — closing the blank-
     * page gap without loosening the operational alert stream.
     */
    @Transactional(readOnly = true)
    public boolean canAuditSafetyOverrides(Authentication authentication, UUID hospitalId) {
        try {
            User user = currentUser(authentication);
            if (user == null || hospitalId == null) {
                return false;
            }
            if (user.getRole() == Role.SUPER_ADMIN) {
                return true;
            }
            if (!belongsToHospital(user, hospitalId)) {
                return false;
            }
            return switch (user.getRole()) {
                case HOSPITAL_ADMIN, DOCTOR -> true;
                case NURSE -> user.getDesignation() == Designation.CHARGE_NURSE
                        || shiftAssignmentService.isUserCurrentShiftLead(user.getId(), hospitalId);
                default -> false;
            };
        } catch (Exception e) {
            log.error("canAuditSafetyOverrides error for hospital {}: {}",
                    hospitalId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * May this user ACKNOWLEDGE / sign off a specific medication-safety override
     * alert? Loads the alert, confirms it is an override row (not an operational
     * clinical alert), and applies {@link #canAuditSafetyOverrides} for that
     * alert's hospital. This lets the governance audience (admin, safety
     * officer, doctor, charge nurse) mark an override reviewed WITHOUT being
     * able to acknowledge operational clinical alerts through the generic path.
     */
    @Transactional(readOnly = true)
    public boolean canAcknowledgeSafetyOverride(Authentication authentication, UUID alertId) {
        try {
            if (alertId == null) return false;
            return clinicalAlertRepository.findByIdAndIsActiveTrue(alertId)
                    .filter(a -> a.getAlertType() == AlertType.MEDICATION_SAFETY_WARNING
                            || a.getAlertType() == AlertType.MEDICATION_EMERGENCY_OVERRIDE)
                    .map(a -> a.getVisit() != null && a.getVisit().getHospital() != null
                            && canAuditSafetyOverrides(authentication, a.getVisit().getHospital().getId()))
                    .orElse(false);
        } catch (Exception e) {
            log.error("canAcknowledgeSafetyOverride error for alert {}: {}", alertId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * RBAC fix (Critical) — does the caller hold today's TRIAGE_NURSE shift
     * function? Returns true only for a clinician who:
     * <ul>
     *   <li>has an active shift assignment for the current shift date + period</li>
     *   <li>whose {@code shiftFunction == TRIAGE_NURSE}</li>
     * </ul>
     * SUPER_ADMIN and HOSPITAL_ADMIN are <strong>not</strong> auto-true —
     * admins do not perform clinical work and must not appear in triage flows.
     */
    @Transactional(readOnly = true)
    public boolean callerIsTodaysTriageNurse(Authentication authentication) {
        try {
            User user = currentUser(authentication);
            if (user == null) return false;
            // Admins are NOT triage nurses.
            if (user.getRole() == Role.SUPER_ADMIN || user.getRole() == Role.HOSPITAL_ADMIN) {
                return false;
            }
            return shiftAssignmentService.getCurrentShiftForUser(user.getId())
                    .map(sa -> sa.getShiftFunction() == ShiftFunction.TRIAGE_NURSE)
                    .orElse(false);
        } catch (Exception e) {
            log.error("callerIsTodaysTriageNurse error: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * The caller's current ED zone, taken from their active shift assignment —
     * or {@code null} when they have no current shift or it carries no zone.
     * Used to scope zone-restricted list endpoints server-side (e.g. the
     * Constant Monitoring sessions list, B8). Cross-zone authority must be
     * checked separately via {@link #canSeeAllZonesAtHospital}.
     */
    @Transactional(readOnly = true)
    public EdZone callerCurrentZone(Authentication authentication) {
        try {
            User user = currentUser(authentication);
            if (user == null) return null;
            return shiftAssignmentService.getCurrentShiftForUser(user.getId())
                    .map(sa -> sa.getZone())
                    .orElse(null);
        } catch (Exception e) {
            log.error("callerCurrentZone error: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Whether the caller may perform a triage write right now.
     *
     * <p>Authority follows the <strong>daily shift assignment</strong>, not
     * permanent designation. "Once a Charge Nurse" is not a free pass to
     * triage on a day when you're rostered as a Zone Nurse — your job
     * today is what your shift function says today.
     *
     * <p>Allowed:
     * <ul>
     *   <li>Today's TRIAGE_NURSE — the canonical authority.</li>
     *   <li>Today's CHARGE_NURSE shift function — actual CN on duty.</li>
     *   <li>Today's shift-lead badge holder — the badge is the canonical
     *       "you're in charge of the floor right now" signal and can be
     *       transferred mid-shift if a senior nurse needs to step in.</li>
     * </ul>
     *
     * <p>Denied (previously allowed via designation backdoor):
     * <ul>
     *   <li>Nurse with {@code Designation.CHARGE_NURSE} working a
     *       non-CN shift today (e.g. rostered as ZONE_NURSE in ACUTE).
     *       For emergencies they need the shift-lead badge transferred,
     *       not just their permanent title.</li>
     *   <li>Admins (SUPER_ADMIN / HOSPITAL_ADMIN) — never clinical.</li>
     *   <li>Doctors — triage is a nurse function in this system.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public boolean callerCanPerformTriage(Authentication authentication) {
        try {
            User user = currentUser(authentication);
            if (user == null) return false;
            if (user.getRole() == Role.SUPER_ADMIN || user.getRole() == Role.HOSPITAL_ADMIN) {
                return false;
            }
            if (callerIsTodaysTriageNurse(authentication)) {
                return true;
            }
            // Shift-lead badge — daily, transferable, designed exactly for
            // "this person is acting in charge right now". This is the
            // override path for emergencies (Triage Nurse called out sick,
            // a senior nurse picks up the badge and the duty with it).
            Optional<UUID> hospitalIdOpt = userRepository.findHospitalIdByUserId(user.getId());
            if (hospitalIdOpt.isPresent()
                    && shiftAssignmentService.isUserCurrentShiftLead(user.getId(), hospitalIdOpt.get())) {
                return true;
            }
            // Today's shift function == CHARGE_NURSE. Same idea: the
            // person actually rostered as CN today, not a permanent title.
            return shiftAssignmentService.getCurrentShiftForUser(user.getId())
                    .map(sa -> sa.getShiftFunction() == ShiftFunction.CHARGE_NURSE)
                    .orElse(false);
        } catch (Exception e) {
            log.error("callerCanPerformTriage error: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Who may CONFIRM a paramedic's field triage for a placed high-acuity ambulance arrival —
     * accepting the field category on sight to flip the visit to TRIAGED WITHOUT waiting for the
     * triage-desk nurse. This is deliberately BROADER than {@link #callerCanPerformTriage}: it
     * removes the single-point bottleneck when the triage/charge nurse is occupied elsewhere by
     * also empowering the clinician actually RECEIVING the patient in the zone. It stays controlled
     * — NOT "anyone with an account". Allowed:
     * <ul>
     *   <li>the triage authorities (triage nurse / charge nurse / shift-lead) — the full
     *       {@link #callerCanPerformTriage} set; OR</li>
     *   <li>a DOCTOR or NURSE whose current shift covers the patient's CURRENT ed-zone (the
     *       receiving Resus/Acute team), resolved via the same shift-coverage check that gates
     *       zone alerts ({@link #canReceiveZoneAlerts}).</li>
     * </ul>
     * Admins, registrars and paramedics are excluded. A clinician who
     * DISAGREES with the field category does not confirm — they re-run the full triage form
     * (gated by {@link #callerCanPerformTriage}), so widening confirmation never widens the
     * ability to author an arbitrary triage from scratch.
     */
    @Transactional(readOnly = true)
    public boolean callerCanConfirmFieldTriage(Authentication authentication, UUID visitId) {
        try {
            if (visitId == null) return false;
            auditContext.noteVisit(visitId); // V107 audit attribution (note-only)
            // Triage trio always may.
            if (callerCanPerformTriage(authentication)) return true;
            User user = currentUser(authentication);
            if (user == null) return false;
            // Beyond the trio, only bedside clinicians — never admins/registrars/paramedics.
            if (user.getRole() != Role.DOCTOR && user.getRole() != Role.NURSE) return false;
            UUID hospitalId = visitRepository.findHospitalIdByVisitId(visitId).orElse(null);
            EdZone zone = visitRepository.findCurrentEdZoneByVisitId(visitId).orElse(null);
            // A placed high-acuity arrival has a concrete zone (RESUS/ACUTE); if it is still
            // unplaced (zone null) there is no "receiving team" to attest — fall back to the trio,
            // which already returned above, so deny here.
            if (hospitalId == null || zone == null) return false;
            return canReceiveZoneAlerts(authentication, hospitalId, zone);
        } catch (Exception e) {
            log.error("callerCanConfirmFieldTriage error for visit {}: {}", visitId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * RBAC fix — true when the caller's currently-assigned ED zone matches
     * the visit's {@code currentEdZone}. Used to gate clinical writes
     * (vital signs, clinical signs, status changes) so a NURSE assigned to
     * GENERAL can't mutate a RESUS patient's record.
     *
     * <p>Returns true for cross-zone authorities (canSeeAllZonesAtHospital).
     * Returns true for the TRIAGE_NURSE when the visit is pre-triage
     * (currentEdZone IS NULL) — they need to write vitals during triage.
     */
    @Transactional(readOnly = true)
    public boolean callerCanWriteToVisit(Authentication authentication, UUID visitId) {
        try {
            if (visitId == null) return false;
            auditContext.noteVisit(visitId); // V107 audit attribution (note-only)
            User user = currentUser(authentication);
            if (user == null) return false;

            // Cross-hospital boundary first.
            Optional<UUID> visitHospitalId = visitRepository.findHospitalIdByVisitId(visitId);
            if (visitHospitalId.isEmpty()) return false;
            UUID hospitalId = visitHospitalId.get();
            if (!canAccessHospital(authentication, hospitalId)) return false;

            // Cross-zone authorities (admins/CN/shift-lead) bypass zone check.
            if (canSeeAllZonesAtHospital(authentication, hospitalId)) return true;

            // Operational non-zone roles can write where their role admits
            // (e.g. PARAMEDIC handoff vitals). Read paths already permit them.
            Role role = user.getRole();
            if (role == Role.REGISTRAR || role == Role.LAB_TECHNICIAN
                    || role == Role.PARAMEDIC) {
                // Non-zone-bound operational roles; whether they may write
                // depends on the specific endpoint's role gate.
                return true;
            }

            // Resolve the visit's current ED zone.
            EdZone visitZone = visitRepository.findCurrentEdZoneByVisitId(visitId).orElse(null);

            // Today's TRIAGE_NURSE can write to pre-triage visits.
            if (visitZone == null && callerIsTodaysTriageNurse(authentication)) {
                return true;
            }

            // Zone-bound clinicians: their current shift zone must equal visit zone.
            return shiftAssignmentService.getCurrentShiftForUser(user.getId())
                    .map(sa -> sa.getZone() != null && sa.getZone() == visitZone)
                    .orElse(false);
        } catch (Exception e) {
            log.error("callerCanWriteToVisit error for visit {}: {}", visitId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * RBAC fix — gate the GET-by-id endpoint on a clinical note. The note
     * id resolves to a visit id via projection; access is then delegated
     * to {@link #canAccessVisit}. Returns false (deny) for unknown ids
     * rather than leaking which ids exist.
     */
    @Transactional(readOnly = true)
    public boolean canAccessClinicalNote(Authentication authentication, UUID noteId) {
        try {
            if (noteId == null) return false;
            return clinicalNoteRepository.findVisitIdByNoteId(noteId)
                    .map(visitId -> canAccessVisit(authentication, visitId))
                    .orElse(false);
        } catch (Exception e) {
            log.error("canAccessClinicalNote error for note {}: {}", noteId, e.getMessage(), e);
            return false;
        }
    }

    /** RBAC fix — same pattern as canAccessClinicalNote, for diagnoses. */
    @Transactional(readOnly = true)
    public boolean canAccessDiagnosis(Authentication authentication, UUID diagnosisId) {
        try {
            if (diagnosisId == null) return false;
            return diagnosisRepository.findVisitIdByDiagnosisId(diagnosisId)
                    .map(visitId -> canAccessVisit(authentication, visitId))
                    .orElse(false);
        } catch (Exception e) {
            log.error("canAccessDiagnosis error for diagnosis {}: {}", diagnosisId, e.getMessage(), e);
            return false;
        }
    }

    /** Same pattern as canAccessClinicalNote, for sepsis screenings — scopes the
     *  bundle endpoints (start / complete item) to the screening's own hospital so
     *  a clinician cannot advance another hospital's bundle by enumerating a UUID. */
    @Transactional(readOnly = true)
    public boolean canAccessSepsisScreening(Authentication authentication, UUID screeningId) {
        try {
            if (screeningId == null) return false;
            return sepsisScreeningRepository.findVisitIdById(screeningId)
                    .map(visitId -> canAccessVisit(authentication, visitId))
                    .orElse(false);
        } catch (Exception e) {
            log.error("canAccessSepsisScreening error for screening {}: {}", screeningId, e.getMessage(), e);
            return false;
        }
    }

    /** Nurse-scope RBAC fix — hospital-scope authz for a MEDICATION ORDER by id: order → visit →
     *  {@link #canAccessVisit}. The id-keyed MAR endpoints (administer / countersign / hold /
     *  refuse / approve / resume / discontinue / modify / prn-dose / infusion events) were
     *  previously role-gated only, letting a clinician at hospital B drive hospital A's
     *  medication order by enumerating a UUID. Denies unknown ids (no existence leak). */
    @Transactional(readOnly = true)
    public boolean canAccessMedication(Authentication authentication, UUID medicationId) {
        try {
            if (medicationId == null) return false;
            return medicationAdministrationRepository.findVisitIdById(medicationId)
                    .map(visitId -> canAccessVisit(authentication, visitId))
                    .orElse(false);
        } catch (Exception e) {
            log.error("canAccessMedication error for medication {}: {}", medicationId, e.getMessage(), e);
            return false;
        }
    }

    /** Same pattern as {@link #canAccessMedication}, for a single DOSE row (the
     *  /doses/{doseId}/administer|delay|refuse endpoints). */
    @Transactional(readOnly = true)
    public boolean canAccessMedicationDose(Authentication authentication, UUID doseId) {
        try {
            if (doseId == null) return false;
            return medicationDoseRepository.findVisitIdById(doseId)
                    .map(visitId -> canAccessVisit(authentication, visitId))
                    .orElse(false);
        } catch (Exception e) {
            log.error("canAccessMedicationDose error for dose {}: {}", doseId, e.getMessage(), e);
            return false;
        }
    }

    /** Scopes the ICU-escalation lifecycle endpoints (notify-team / response /
     *  assign-bed / transfer / cancel) to the escalation's own hospital. */
    @Transactional(readOnly = true)
    public boolean canAccessIcuEscalation(Authentication authentication, UUID escalationId) {
        try {
            if (escalationId == null) return false;
            return icuEscalationRepository.findVisitIdById(escalationId)
                    .map(visitId -> canAccessVisit(authentication, visitId))
                    .orElse(false);
        } catch (Exception e) {
            log.error("canAccessIcuEscalation error for escalation {}: {}", escalationId, e.getMessage(), e);
            return false;
        }
    }

    /** Scopes the zone-transfer lifecycle endpoints (accept / decline /
     *  resus-in-place / cancel) to the transfer's own hospital — the role
     *  gate alone would let a doctor act on another hospital's transfer
     *  by guessing its id. */
    @Transactional(readOnly = true)
    public boolean canAccessZoneTransfer(Authentication authentication, UUID transferId) {
        try {
            if (transferId == null) return false;
            return zoneTransferRepository.findVisitIdById(transferId)
                    .map(visitId -> canAccessVisit(authentication, visitId))
                    .orElse(false);
        } catch (Exception e) {
            log.error("canAccessZoneTransfer error for transfer {}: {}", transferId, e.getMessage(), e);
            return false;
        }
    }

    /** Receiving-side gate for a zone transfer's accept / decline / treat-in-place
     *  actions. Unlike {@link #canAccessZoneTransfer} (hospital-wide read scope),
     *  taking or resolving a transfer REASSIGNS primary clinical responsibility, so
     *  it is restricted to a clinician who actually covers the TARGET zone on their
     *  current shift — or a charge nurse / shift lead / admin with hospital-wide
     *  oversight ({@link #canReceiveZoneAlerts} folds in both). Denies an unknown or
     *  inactive transfer (no existence leak). */
    @Transactional(readOnly = true)
    public boolean canAcceptZoneTransfer(Authentication authentication, UUID transferId) {
        try {
            if (transferId == null) return false;
            return zoneTransferRepository.findAcceptTargetById(transferId)
                    .map(t -> canReceiveZoneAlerts(authentication, t.getHospitalId(), t.getToZone()))
                    .orElse(false);
        } catch (Exception e) {
            log.error("canAcceptZoneTransfer error for transfer {}: {}", transferId, e.getMessage(), e);
            return false;
        }
    }

    /** Gate for MANUAL zone-transfer initiation (an operational move or a
     *  clinical step-down) on a visit. Allowed for a clinician whose current
     *  shift covers the patient's CURRENT zone, or a charge nurse / shift lead /
     *  admin with hospital-wide oversight ({@link #canReceiveZoneAlerts} folds in
     *  both). A pre-triage visit has no zone yet, so only hospital-wide oversight
     *  may initiate one. Denies an unknown visit (no existence leak). */
    @Transactional(readOnly = true)
    public boolean canInitiateZoneTransfer(Authentication authentication, UUID visitId) {
        try {
            if (visitId == null) return false;
            UUID hospitalId = visitRepository.findHospitalIdByVisitId(visitId).orElse(null);
            if (hospitalId == null) return false; // unknown / inactive visit
            EdZone currentZone = visitRepository.findCurrentEdZoneByVisitId(visitId).orElse(null);
            return currentZone != null
                    ? canReceiveZoneAlerts(authentication, hospitalId, currentZone)
                    : canSeeAllZonesAtHospital(authentication, hospitalId);
        } catch (Exception e) {
            log.error("canInitiateZoneTransfer error for visit {}: {}", visitId, e.getMessage(), e);
            return false;
        }
    }

    /** Scopes the medication-safety OVERRIDE endpoint to the check's own hospital, so a doctor
     *  at hospital B cannot clear a safety block (allergy / interaction / dose) belonging to
     *  hospital A's patient by enumerating a checkId. Resolves checkId → visit → hospital and
     *  reuses {@link #canAccessVisit}; denies an unknown/inactive check (no existence leak). */
    @Transactional(readOnly = true)
    public boolean canAccessMedicationSafetyCheck(Authentication authentication, UUID checkId) {
        try {
            if (checkId == null) return false;
            return medicationSafetyCheckRepository.findVisitIdById(checkId)
                    .map(visitId -> canAccessVisit(authentication, visitId))
                    .orElse(false);
        } catch (Exception e) {
            log.error("canAccessMedicationSafetyCheck error for check {}: {}", checkId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Who may operate an RFID registration reader (arm tap-to-capture bind mode) at the registration
     * desk: SUPER_ADMIN anywhere; REGISTRAR or HOSPITAL_ADMIN at the DEVICE's own hospital. Resolves
     * deviceId → hospital so a registrar at hospital A cannot drive hospital B's reader by id; denies
     * an unknown device (no existence leak). The /tap ingest itself is device-API-key authed, not here.
     */
    @Transactional(readOnly = true)
    public boolean canOperateRfidDevice(Authentication authentication, UUID deviceId) {
        try {
            User user = currentUser(authentication);
            if (user == null || deviceId == null) {
                return false;
            }
            if (user.getRole() == Role.SUPER_ADMIN) {
                return true;
            }
            UUID hospitalId = ioTDeviceRepository.findHospitalIdById(deviceId).orElse(null);
            if (hospitalId == null || !belongsToHospital(user, hospitalId)) {
                return false;
            }
            return user.getRole() == Role.REGISTRAR || user.getRole() == Role.HOSPITAL_ADMIN;
        } catch (Exception e) {
            log.error("canOperateRfidDevice error for device {}: {}", deviceId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Who may operate a self-registered device (read its latest-vitals snapshot, manage it): the
     * OWNING user (a paramedic and THEIR own field monitor — user-owned / hospital-agnostic, so it
     * works at any destination hospital), SUPER_ADMIN anywhere, or a HOSPITAL_ADMIN at the device's
     * own hospital. Ownership (registeredByUserId) is checked first so a paramedic's monitor is theirs
     * regardless of which hospital they've transported into. Denies an unknown device. The telemetry
     * ingest itself is device-API-key authed, not here.
     */
    @Transactional(readOnly = true)
    public boolean canOperateDevice(Authentication authentication, UUID deviceId) {
        try {
            User user = currentUser(authentication);
            if (user == null || deviceId == null) {
                return false;
            }
            if (user.getRole() == Role.SUPER_ADMIN) {
                return true;
            }
            // Owner path — user-owned, hospital-agnostic.
            UUID owner = ioTDeviceRepository.findRegisteredByUserIdById(deviceId).orElse(null);
            if (owner != null && owner.equals(user.getId())) {
                return true;
            }
            // Admin path — HOSPITAL_ADMIN at the device's own hospital.
            UUID hospitalId = ioTDeviceRepository.findHospitalIdById(deviceId).orElse(null);
            if (hospitalId == null || !belongsToHospital(user, hospitalId)) {
                return false;
            }
            return user.getRole() == Role.HOSPITAL_ADMIN;
        } catch (Exception e) {
            log.error("canOperateDevice error for device {}: {}", deviceId, e.getMessage(), e);
            return false;
        }
    }

    /** Scopes the fast-track mutating endpoints (status / ecg / ct / complete /
     *  cancel / acknowledge) to the activation's own hospital, so a clinician at
     *  hospital B cannot record an ECG/CT result or drive the status of hospital
     *  A's stroke/STEMI activation by enumerating a UUID. */
    @Transactional(readOnly = true)
    public boolean canAccessFastTrack(Authentication authentication, UUID activationId) {
        try {
            if (activationId == null) return false;
            return fastTrackActivationRepository.findVisitIdById(activationId)
                    .map(visitId -> canAccessVisit(authentication, visitId))
                    .orElse(false);
        } catch (Exception e) {
            log.error("canAccessFastTrack error for activation {}: {}", activationId, e.getMessage(), e);
            return false;
        }
    }

    /** Scopes the hypoglycemia mutating endpoints (treatment / repeat-glucose /
     *  resolve) to the event's own hospital, so a clinician cannot write a
     *  treatment/recheck/resolution to another hospital's event by enumerating
     *  an eventId. */
    @Transactional(readOnly = true)
    public boolean canAccessHypoglycemiaEvent(Authentication authentication, UUID eventId) {
        try {
            if (eventId == null) return false;
            return hypoglycemiaEventRepository.findVisitIdById(eventId)
                    .map(visitId -> canAccessVisit(authentication, visitId))
                    .orElse(false);
        } catch (Exception e) {
            log.error("canAccessHypoglycemiaEvent error for event {}: {}", eventId, e.getMessage(), e);
            return false;
        }
    }

    /** Scopes the isolation mutating endpoints (assign-room / end / notify-public-health)
     *  to the screening's own hospital, so a clinician cannot drive another hospital's
     *  isolation/de-isolation/notification by enumerating a screeningId. */
    @Transactional(readOnly = true)
    public boolean canAccessInfectionScreening(Authentication authentication, UUID screeningId) {
        try {
            if (screeningId == null) return false;
            return infectionScreeningRepository.findVisitIdById(screeningId)
                    .map(visitId -> canAccessVisit(authentication, visitId))
                    .orElse(false);
        } catch (Exception e) {
            log.error("canAccessInfectionScreening error for screening {}: {}", screeningId, e.getMessage(), e);
            return false;
        }
    }

    /** Scopes safety-incident reads + lifecycle mutations (get / pdf / investigate /
     *  root-cause / corrective-action / complete / close / update) to the incident's own
     *  hospital, so staff cannot read or drive another hospital's incident register by
     *  enumerating incident ids. Hospital-keyed (not visit-keyed) because incidents may
     *  legitimately have NO visit (equipment failure in a corridor, etc.). */
    @Transactional(readOnly = true)
    public boolean canAccessSafetyIncident(Authentication authentication, UUID incidentId) {
        try {
            if (incidentId == null) return false;
            return safetyIncidentRepository.findHospitalIdById(incidentId)
                    .map(hospitalId -> canAccessHospital(authentication, hospitalId))
                    .orElse(false);
        } catch (Exception e) {
            log.error("canAccessSafetyIncident error for incident {}: {}", incidentId, e.getMessage(), e);
            return false;
        }
    }

    /** Scopes the pathway mutating endpoints (step complete/skip, complete, abandon, progress)
     *  to the activation's own hospital, so a clinician cannot drive another hospital's
     *  pathway activation by enumerating an activationId. */
    @Transactional(readOnly = true)
    public boolean canAccessPathwayActivation(Authentication authentication, UUID activationId) {
        try {
            if (activationId == null) return false;
            return pathwayActivationRepository.findVisitIdById(activationId)
                    .map(visitId -> canAccessVisit(authentication, visitId))
                    .orElse(false);
        } catch (Exception e) {
            log.error("canAccessPathwayActivation error for activation {}: {}", activationId, e.getMessage(), e);
            return false;
        }
    }

    /** Hospital-scope authz for a lab order — maps order → visit → canAccessVisit, so a
     *  lab order can only be acted on within its own hospital. */
    @Transactional(readOnly = true)
    public boolean canAccessLabOrder(Authentication authentication, UUID orderId) {
        try {
            if (orderId == null) return false;
            return labOrderRepository.findVisitIdById(orderId)
                    .map(visitId -> canAccessVisit(authentication, visitId))
                    .orElse(false);
        } catch (Exception e) {
            log.error("canAccessLabOrder error for order {}: {}", orderId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Hospital-scope authz for a single clinical document — maps document → visit
     * → {@link #canAccessVisit}, so the GET-by-id endpoint cannot be used to read
     * another hospital's discharge summary, consent form or death certificate by
     * enumerating a UUID. Closes the cross-tenant PHI read on GET /documents/{id}
     * (previously gated only by {@code isAuthenticated()}).
     */
    @Transactional(readOnly = true)
    public boolean canAccessDocument(Authentication authentication, UUID documentId) {
        try {
            if (documentId == null) return false;
            return clinicalDocumentRepository.findVisitIdById(documentId)
                    .map(visitId -> canAccessVisit(authentication, visitId))
                    .orElse(false);
        } catch (Exception e) {
            log.error("canAccessDocument error for document {}: {}", documentId, e.getMessage(), e);
            return false;
        }
    }

    /** Hospital-scope authz for an informed-consent record — consent → visit →
     *  canAccessVisit, so a consent cannot be read or withdrawn outside its own
     *  hospital by enumerating a UUID. */
    @Transactional(readOnly = true)
    public boolean canAccessConsent(Authentication authentication, UUID consentId) {
        try {
            if (consentId == null) return false;
            return informedConsentRepository.findVisitIdById(consentId)
                    .map(visitId -> canAccessVisit(authentication, visitId))
                    .orElse(false);
        } catch (Exception e) {
            log.error("canAccessConsent error for consent {}: {}", consentId, e.getMessage(), e);
            return false;
        }
    }

    /** Hospital-scope authz for a referral / consultation — referral → visit →
     *  canAccessVisit, so a referral cannot be read, responded to or cancelled
     *  outside its own hospital by enumerating a UUID. */
    @Transactional(readOnly = true)
    public boolean canAccessReferral(Authentication authentication, UUID referralId) {
        try {
            if (referralId == null) return false;
            return referralRepository.findVisitIdById(referralId)
                    .map(visitId -> canAccessVisit(authentication, visitId))
                    .orElse(false);
        } catch (Exception e) {
            log.error("canAccessReferral error for referral {}: {}", referralId, e.getMessage(), e);
            return false;
        }
    }

    /** RBAC fix — same pattern as canAccessClinicalNote, for handover reports. */
    @Transactional(readOnly = true)
    public boolean canAccessHandoverReport(Authentication authentication, UUID reportId) {
        try {
            if (reportId == null) return false;
            return handoverReportRepository.findHospitalIdByReportId(reportId)
                    .map(hospitalId -> canAccessHospital(authentication, hospitalId))
                    .orElse(false);
        } catch (Exception e) {
            log.error("canAccessHandoverReport error for report {}: {}", reportId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * READ gate for a handover/SBAR report and its PDF. A handover report is a full
     * clinical patient summary (diagnoses, meds, labs, isolation, disposition), so
     * reading it requires a CLINICAL role in addition to hospital scope — the
     * previous {@link #canAccessHandoverReport} was hospital-membership only, which
     * let a REGISTRAR / LAB_TECHNICIAN token enumerate any patient's SBAR
     * PDF by id. Permitted: SUPER_ADMIN, DOCTOR, NURSE, PARAMEDIC (the same clinical
     * set allowed to GENERATE a handover), still bounded to the report's hospital.
     */
    @Transactional(readOnly = true)
    public boolean canReadHandoverReport(Authentication authentication, UUID reportId) {
        try {
            User user = currentUser(authentication);
            if (user == null) return false;
            Role role = user.getRole();
            boolean clinical = role == Role.SUPER_ADMIN || role == Role.DOCTOR
                    || role == Role.NURSE || role == Role.PARAMEDIC;
            if (!clinical) return false;
            return canAccessHandoverReport(authentication, reportId);
        } catch (Exception e) {
            log.error("canReadHandoverReport error for report {}: {}", reportId, e.getMessage(), e);
            return false;
        }
    }

    /** RBAC fix — same pattern as canAccessClinicalNote, for investigations. */
    @Transactional(readOnly = true)
    public boolean canAccessInvestigation(Authentication authentication, UUID investigationId) {
        try {
            if (investigationId == null) return false;
            return investigationRepository.findVisitIdByInvestigationId(investigationId)
                    .map(visitId -> canAccessVisit(authentication, visitId))
                    .orElse(false);
        } catch (Exception e) {
            log.error("canAccessInvestigation error for investigation {}: {}",
                    investigationId, e.getMessage(), e);
            return false;
        }
    }

    /* ──────────────────────── governance policies ─────────────────── */

    /**
     * Object-level READ gate for a clinical policy by id. SUPER_ADMIN sees all;
     * a system-wide (NULL-hospital) policy is readable by any governance role;
     * a hospital-scoped policy requires membership of that hospital. Denies
     * unknown/inactive ids (no cross-tenant existence leak).
     */
    @Transactional(readOnly = true)
    public boolean canAccessPolicy(Authentication authentication, UUID policyId) {
        try {
            User user = currentUser(authentication);
            if (user == null || policyId == null) {
                return false;
            }
            if (user.getRole() == Role.SUPER_ADMIN) {
                return true;
            }
            java.util.List<UUID> rows = clinicalPolicyRepository.findHospitalIdByPolicyId(policyId);
            if (rows.isEmpty()) {
                return false; // no such active policy
            }
            UUID hospitalId = rows.get(0);
            if (hospitalId == null) {
                return true;  // system-wide default — readable by governance roles
            }
            return belongsToHospital(user, hospitalId);
        } catch (Exception e) {
            log.error("canAccessPolicy error for policy {}: {}", policyId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Object-level WRITE gate for a clinical policy by id. SUPER_ADMIN manages all;
     * a system-wide (NULL-hospital) policy is SUPER_ADMIN-only (a hospital admin must
     * not edit a national default); a hospital-scoped policy requires membership of
     * that hospital. (The role floor — SUPER_ADMIN/HOSPITAL_ADMIN — is applied
     * alongside this in the controller's @PreAuthorize.)
     */
    @Transactional(readOnly = true)
    public boolean canManagePolicy(Authentication authentication, UUID policyId) {
        try {
            User user = currentUser(authentication);
            if (user == null || policyId == null) {
                return false;
            }
            if (user.getRole() == Role.SUPER_ADMIN) {
                return true;
            }
            java.util.List<UUID> rows = clinicalPolicyRepository.findHospitalIdByPolicyId(policyId);
            if (rows.isEmpty()) {
                return false;
            }
            UUID hospitalId = rows.get(0);
            if (hospitalId == null) {
                return false; // system-wide default: SUPER_ADMIN only (already returned true above)
            }
            return belongsToHospital(user, hospitalId);
        } catch (Exception e) {
            log.error("canManagePolicy error for policy {}: {}", policyId, e.getMessage(), e);
            return false;
        }
    }

    /* ─────────────────────────── helpers ─────────────────────────── */

    private User currentUser(Authentication authentication) {
        if (authentication == null) return null;
        Object principal = authentication.getPrincipal();
        return (principal instanceof User user) ? user : null;
    }

    private boolean belongsToHospital(User user, UUID hospitalId) {
        if (user == null || user.getId() == null || hospitalId == null) {
            return false;
        }
        Optional<UUID> resolved = userRepository.findHospitalIdByUserId(user.getId());
        return resolved.map(hospitalId::equals).orElse(false);
    }
}
