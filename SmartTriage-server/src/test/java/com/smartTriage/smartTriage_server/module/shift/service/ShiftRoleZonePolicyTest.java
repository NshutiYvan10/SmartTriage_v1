package com.smartTriage.smartTriage_server.module.shift.service;

import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.common.enums.ShiftFunction;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ED roster is for clinical staff only: nurse stations require a NURSE
 * account, doctor stations a DOCTOR account, and every other role (admins,
 * registrar, paramedic, lab technician, read-only) is refused outright.
 * Regression cover for the hole where a HOSPITAL_ADMIN could be rostered
 * as ZONE_NURSE — which faked zone coverage and granted zone-scoped
 * clinical visibility to a non-clinical actor.
 */
class ShiftRoleZonePolicyTest {

    // ── Allowed pairs ────────────────────────────────────────────────

    @Test
    void nurseMayHoldEveryNurseStation() {
        assertDoesNotThrow(() -> ShiftRoleZonePolicy.validateRole(Role.NURSE, ShiftFunction.CHARGE_NURSE));
        assertDoesNotThrow(() -> ShiftRoleZonePolicy.validateRole(Role.NURSE, ShiftFunction.TRIAGE_NURSE));
        assertDoesNotThrow(() -> ShiftRoleZonePolicy.validateRole(Role.NURSE, ShiftFunction.ZONE_NURSE));
    }

    @Test
    void doctorMayHoldEveryDoctorStation() {
        assertDoesNotThrow(() -> ShiftRoleZonePolicy.validateRole(Role.DOCTOR, ShiftFunction.PRIMARY_DOCTOR));
        assertDoesNotThrow(() -> ShiftRoleZonePolicy.validateRole(Role.DOCTOR, ShiftFunction.SUPERVISING_DOCTOR));
        assertDoesNotThrow(() -> ShiftRoleZonePolicy.validateRole(Role.DOCTOR, ShiftFunction.RESIDENT));
    }

    // ── Cross-family mismatches ──────────────────────────────────────

    @Test
    void nurseCannotHoldDoctorStations() {
        assertThrows(ClinicalBusinessException.class,
                () -> ShiftRoleZonePolicy.validateRole(Role.NURSE, ShiftFunction.PRIMARY_DOCTOR));
        assertThrows(ClinicalBusinessException.class,
                () -> ShiftRoleZonePolicy.validateRole(Role.NURSE, ShiftFunction.RESIDENT));
    }

    @Test
    void doctorCannotHoldNurseStations() {
        assertThrows(ClinicalBusinessException.class,
                () -> ShiftRoleZonePolicy.validateRole(Role.DOCTOR, ShiftFunction.TRIAGE_NURSE));
        assertThrows(ClinicalBusinessException.class,
                () -> ShiftRoleZonePolicy.validateRole(Role.DOCTOR, ShiftFunction.ZONE_NURSE));
    }

    // ── Non-clinical roles are refused everywhere ────────────────────

    @Test
    void nonClinicalRolesAreRefusedForEveryStation() {
        Role[] nonClinical = {
                Role.SUPER_ADMIN, Role.HOSPITAL_ADMIN, Role.REGISTRAR,
                Role.PARAMEDIC, Role.LAB_TECHNICIAN,
        };
        for (Role role : nonClinical) {
            for (ShiftFunction fn : ShiftFunction.values()) {
                assertThrows(ClinicalBusinessException.class,
                        () -> ShiftRoleZonePolicy.validateRole(role, fn),
                        role + " must not hold " + fn);
            }
        }
    }

    @Test
    void hospitalAdminAsZoneNurseIsRefusedWithAdminSpecificMessage() {
        ClinicalBusinessException ex = assertThrows(ClinicalBusinessException.class,
                () -> ShiftRoleZonePolicy.validateRole(Role.HOSPITAL_ADMIN, ShiftFunction.ZONE_NURSE));
        assertTrue(ex.getMessage().contains("never rostered clinicians"),
                "admin refusal should explain admins are never rostered clinicians, got: " + ex.getMessage());
    }

    // ── Null guards (fail closed) ────────────────────────────────────

    @Test
    void nullRoleOrFunctionIsRefused() {
        assertThrows(ClinicalBusinessException.class,
                () -> ShiftRoleZonePolicy.validateRole(null, ShiftFunction.ZONE_NURSE));
        assertThrows(ClinicalBusinessException.class,
                () -> ShiftRoleZonePolicy.validateRole(Role.NURSE, null));
    }
}
