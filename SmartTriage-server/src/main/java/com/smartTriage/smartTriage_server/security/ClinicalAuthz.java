package com.smartTriage.smartTriage_server.security;

import com.smartTriage.smartTriage_server.common.enums.AlertType;
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
    private final IoTDeviceRepository ioTDeviceRepository;

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
     * @return true if the visit identified by {@code visitId} belongs to the
     *         authenticated user's hospital. Returns false when the visit
     *         does not exist (no information leak about which ids exist).
     */
    @Transactional(readOnly = true)
    public boolean canAccessVisit(Authentication authentication, UUID visitId) {
        try {
            if (visitId == null) return false;
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
     * Alert-center read gate. Identical cross-zone visibility to
     * {@link #canSeeAllZonesAtHospital(Authentication, UUID)} EXCEPT that
     * HOSPITAL_ADMIN is denied: the clinical alert queue is a clinician
     * surface and product policy keeps hospital administrators out of it.
     * The UI already hides the Alerts page from HA; this closes the
     * matching API hole (a HA token could otherwise GET the hospital-wide
     * alert endpoints directly and read every clinical alert).
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
            // Doctors are not zone-bound the way nurses are — they roam the whole
            // floor, so the operational alert feed is theirs to read, scoped to
            // their own hospital. (A regular zone nurse still goes through the
            // cross-zone authority below; when denied the hospital-wide list the
            // Alert Center surfaces an honest "no access" state rather than a
            // falsely-reassuring "all clear".)
            if (user.getRole() == Role.DOCTOR) {
                return belongsToHospital(user, hospitalId);
            }
            return canSeeAllZonesAtHospital(authentication, hospitalId);
        } catch (Exception e) {
            log.error("canReadHospitalAlerts error for hospital {}: {}",
                    hospitalId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Reporting/analytics read gate — for hospital-wide aggregate reports (Quality
     * KPIs, MoH statistics, operational analytics). Restricted to the governance /
     * management / audit readers: SUPER_ADMIN, and the HOSPITAL_ADMIN or READ_ONLY
     * (safety-officer / auditor) AT THIS HOSPITAL. Deliberately NOT every clinician
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
            return user.getRole() == Role.HOSPITAL_ADMIN || user.getRole() == Role.READ_ONLY;
        } catch (Exception e) {
            log.error("canViewHospitalReports error for hospital {}: {}", hospitalId, e.getMessage(), e);
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
     * Object-level read gate for a single MoH report by id (JSON + PDF). NATIONAL rollups
     * are SUPER_ADMIN-only; a HOSPITAL report is readable only by the SUPER_ADMIN or the
     * HOSPITAL_ADMIN/READ_ONLY of THAT report's hospital. Closes the by-id IDOR hole where
     * the class-level role gate alone let any admin/read-only read national or other-hospital
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
     * patients (SUPER_ADMIN, DOCTOR, NURSE, REGISTRAR, PARAMEDIC); admins/lab/read-only are denied.
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
     *   <li>at the SAME hospital: HOSPITAL_ADMIN (governance), READ_ONLY (the
     *       safety-officer / auditor persona), DOCTOR (peer review), and a
     *       Charge Nurse / current shift-lead nurse (floor oversight).</li>
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
                case HOSPITAL_ADMIN, READ_ONLY, DOCTOR -> true;
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
                    || role == Role.PARAMEDIC || role == Role.READ_ONLY) {
                // READ_ONLY may NOT write; others depend on the specific endpoint's role gate.
                return role != Role.READ_ONLY;
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
     * let a REGISTRAR / LAB_TECHNICIAN / READ_ONLY token enumerate any patient's SBAR
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
