package com.smartTriage.smartTriage_server.common.enums;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the investigation-routing invariants that decide WHERE a doctor's order
 * goes. A regression here is a clinical-safety hazard: if an imaging type stops
 * being on the diagnostics worklist it reaches no technician (the "silent
 * failure" this module fixes); if a type became BOTH lab-routable and
 * diagnostics-worklisted it would be double-owned.
 */
class InvestigationTypeRoutingTest {

    private static final Set<InvestigationType> LAB =
            EnumSet.of(InvestigationType.LABORATORY, InvestigationType.BLOOD_GAS,
                    InvestigationType.URINALYSIS, InvestigationType.RAPID_TEST);

    private static final Set<InvestigationType> IMAGING =
            EnumSet.of(InvestigationType.XRAY, InvestigationType.CT_SCAN, InvestigationType.MRI,
                    InvestigationType.ULTRASOUND, InvestigationType.RADIOLOGY, InvestigationType.ECG);

    @Test
    void labRoutableTypesAreExactlyTheLabSet() {
        for (InvestigationType t : InvestigationType.values()) {
            assertThat(t.isLabRoutable())
                    .as("isLabRoutable for %s", t)
                    .isEqualTo(LAB.contains(t));
        }
    }

    @Test
    void diagnosticsWorklistTypesAreExactlyTheImagingSet() {
        for (InvestigationType t : InvestigationType.values()) {
            assertThat(t.needsDiagnosticsWorklist())
                    .as("needsDiagnosticsWorklist for %s", t)
                    .isEqualTo(IMAGING.contains(t));
        }
    }

    @Test
    void noTypeIsBothLabRoutableAndDiagnosticsWorklisted() {
        for (InvestigationType t : InvestigationType.values()) {
            assertThat(t.isLabRoutable() && t.needsDiagnosticsWorklist())
                    .as("%s must not be double-owned by lab AND imaging", t)
                    .isFalse();
        }
    }

    @Test
    void bedsideAndMiscTypesReachNeitherWorklist() {
        // POINT_OF_CARE is bedside (ordering clinician performs it); OTHER is an
        // undefined modality tracked on the chart — neither belongs on a queue.
        assertThat(InvestigationType.POINT_OF_CARE.isLabRoutable()).isFalse();
        assertThat(InvestigationType.POINT_OF_CARE.needsDiagnosticsWorklist()).isFalse();
        assertThat(InvestigationType.OTHER.isLabRoutable()).isFalse();
        assertThat(InvestigationType.OTHER.needsDiagnosticsWorklist()).isFalse();
    }
}
