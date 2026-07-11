package com.smartTriage.smartTriage_server.common.report;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke-renders the redesigned report kit end-to-end: every new component
 * (meta strip, KPI tiles, stacked acuity bar + legend, disposition bar list,
 * metric grid, census tiles, workload rows, chips, trend chart, patient
 * banner, SBAR groups, banners/callouts, totals table) in one document each
 * for the "operational" and "per-patient" shapes. Guards against a component
 * throwing at render time; the PDFs land in target/pdf-kit-preview/ so a
 * human can eyeball the design after changes.
 */
class PdfReportKitShowcaseTest {

    private static final Path OUT_DIR = Path.of("target", "pdf-kit-preview");

    private static byte[] writeOut(String name, byte[] pdf) throws Exception {
        Files.createDirectories(OUT_DIR);
        Files.write(OUT_DIR.resolve(name), pdf);
        return pdf;
    }

    private static void assertIsPdf(byte[] pdf) {
        assertThat(pdf).isNotNull();
        assertThat(pdf.length).isGreaterThan(5_000);
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    void rendersOperationalShowcase() throws Exception {
        PdfReport r = PdfReport.begin(new PdfReport.Spec(
                "Daily ED Activity", "Daily Activity", "King Faisal Hospital Kigali",
                List.of("Facility code · KFH-KGL-001"),
                "Y. Nshuti · Hospital Admin", "Operational report", "Operational report"));

        r.metaStrip(List.of(
                PdfReport.kv("Report date", "2026-07-07"),
                PdfReport.kv("Scope", "Whole department · all zones"),
                PdfReport.kv("Requested by", "Y. Nshuti · Hospital Admin"),
                PdfReport.kv("Source", "Live clinical records")));

        r.sectionHeader("Activity at a glance");
        r.kpiTiles(List.of(
                PdfReport.kpi("148", null, "Arrivals"),
                PdfReport.kpi("142", null, "Triaged"),
                PdfReport.kpi("27.4", "min", "Avg wait"),
                PdfReport.kpi("214", "min", "Avg length of stay")));

        r.sectionHeader("Triage acuity mix", "142 triaged · SATS");
        r.stackedBar(List.of(
                new PdfReport.Segment("6", 6, PdfReport.SATS_RED, false),
                new PdfReport.Segment("24", 24, PdfReport.SATS_ORANGE, false),
                new PdfReport.Segment("63", 63, PdfReport.SATS_YELLOW, true),
                new PdfReport.Segment("49", 49, PdfReport.SATS_GREEN, false)));
        r.legend(List.of(
                new PdfReport.LegendItem(PdfReport.SATS_RED, "Red", "Immediate", "6", "4.2%"),
                new PdfReport.LegendItem(PdfReport.SATS_ORANGE, "Orange", "Very urgent", "24", "16.9%"),
                new PdfReport.LegendItem(PdfReport.SATS_YELLOW, "Yellow", "Urgent", "63", "44.4%"),
                new PdfReport.LegendItem(PdfReport.SATS_GREEN, "Green", "Routine", "49", "34.5%")));

        r.sectionHeader("Dispositions");
        r.barList(List.of(
                new PdfReport.BarRow("Discharged", 1.0, PdfReport.SATS_GREEN, "79", "55.6%"),
                new PdfReport.BarRow("Admitted", 0.481, PdfReport.BRAND, "38", "26.8%"),
                new PdfReport.BarRow("Referred out", 0.152, PdfReport.SLATE_400, "12", "8.5%"),
                new PdfReport.BarRow("Left without being seen", 0.089, PdfReport.ACCENT, "7", "4.9%"),
                new PdfReport.BarRow("Deceased", 0.013, PdfReport.DANGER, "1", "0.7%")));

        r.sectionHeader("Clinical module activity");
        r.metricGrid(List.of(
                new PdfReport.Metric("14", "Sepsis screens", PdfReport.MetricTone.NEUTRAL),
                new PdfReport.Metric("3", "Isolations activated", PdfReport.MetricTone.NEUTRAL),
                new PdfReport.Metric("2", "Safety incidents reported", PdfReport.MetricTone.ATTENTION),
                new PdfReport.Metric("0", "Severe-harm / death incidents", PdfReport.MetricTone.GOOD)));

        r.sectionHeader("Department census", "at generation time");
        r.censusTiles(List.of(
                PdfReport.kv("Resus", "4"), PdfReport.kv("Acute", "12"),
                PdfReport.kv("General", "19"), PdfReport.kv("Observation", "6"),
                PdfReport.kv("Waiting room", "8")), PdfReport.kv("In department", "49"));

        r.alertBanner("1 CRITICAL alert UNACKNOWLEDGED — review before accepting handover");
        r.sectionHeader("Open work being handed over");
        r.workRows(List.of(
                new PdfReport.WorkRow("Unacknowledged CRITICAL alerts", "1", PdfReport.WorkTone.CRIT, false, "Act now"),
                new PdfReport.WorkRow("Sepsis bundles in progress", "2", PdfReport.WorkTone.INFO, false, "In progress"),
                new PdfReport.WorkRow("Isolations awaiting a room", "1", PdfReport.WorkTone.WARN, false, "Attention"),
                new PdfReport.WorkRow("… of which recheck OVERDUE", "1", PdfReport.WorkTone.OVER, true, "Overdue"),
                new PdfReport.WorkRow("Open safety incidents", "0", PdfReport.WorkTone.OK, false, "None")));
        r.chipRow(List.of(
                new PdfReport.Chip(PdfReport.SATS_RED, "Red", "2"),
                new PdfReport.Chip(PdfReport.SATS_ORANGE, "Orange", "7"),
                new PdfReport.Chip(PdfReport.SATS_YELLOW, "Yellow", "14"),
                new PdfReport.Chip(PdfReport.SATS_GREEN, "Green", "19")));

        r.sectionHeader("Daily arrivals — trend", "30 days · peak 159 · low 122");
        r.trendChart(new double[]{128, 141, 133, 150, 145, 119, 126, 148, 137, 152, 144, 130,
                139, 148, 155, 129, 122, 141, 159, 135, 127, 149, 143, 131, 138, 151, 128, 133, 146, 148},
                "08 Jun", "22 Jun", "07 Jul");

        r.sectionHeader("Per-day breakdown");
        r.dataTable(
                new String[]{"Date", "Arrivals", "Red", "Orange", "Yellow", "Green", "Admitted", "LWBS", "Avg wait"},
                new java.awt.Color[]{null, null, PdfReport.SATS_RED, PdfReport.SATS_ORANGE,
                        PdfReport.SATS_YELLOW, PdfReport.SATS_GREEN, null, null, null},
                new float[]{16, 11, 9, 10, 10, 10, 12, 9, 13},
                List.of(
                        new String[]{"24 Jun", "128", "5", "21", "58", "41", "34", "6", "28.4"},
                        new String[]{"25 Jun", "141", "7", "24", "63", "44", "38", "8", "31.2"},
                        new String[]{"26 Jun", "133", "4", "19", "61", "46", "33", "5", "26.9"}),
                new String[]{"Period total", "3,842", "154", "612", "1,689", "1,246", "1,004", "214", "29.1"},
                1);
        r.tableNote("Showing the most recent 3 of 30 days; totals are for all 30 days.");

        assertIsPdf(writeOut("showcase-operational.pdf", r.finish()));
    }

    @Test
    void rendersSafetyIncidentThroughRealService() throws Exception {
        // The REAL SafetyIncidentPdfService (not kit primitives) — proves the
        // redesigned incident document end-to-end and drops it in the preview dir.
        var h = new com.smartTriage.smartTriage_server.module.hospital.entity.Hospital();
        h.setName("King Faisal Hospital Kigali");
        h.setHospitalCode("KFH-KGL-001");
        h.setCity("Kigali");
        var i = com.smartTriage.smartTriage_server.module.safety.entity.SafetyIncident.builder()
                .hospital(h)
                .incidentNumber("SI-20260709-00042")
                .incidentType(com.smartTriage.smartTriage_server.common.enums.IncidentType.FALL)
                .severity(com.smartTriage.smartTriage_server.common.enums.IncidentSeverity.SEVERE_HARM)
                .status(com.smartTriage.smartTriage_server.common.enums.IncidentStatus.INVESTIGATION_STARTED)
                .incidentDateTime(java.time.Instant.now())
                .locationInHospital("Zone GENERAL · Bed A1")
                .description("Patient fall from trolley during transfer to imaging; **side rail not raised**.")
                .contributingFactors("Transfer during shift change; single porter; trolley rail latch worn.")
                .immediateActions("Patient assessed by duty doctor; CT head ordered; family informed.")
                .reportedByName("RN Keza")
                .reportedByRole("NURSE")
                .reportedAt(java.time.Instant.now())
                .patientHarmed(true)
                .investigatorName("Safety Officer M. Uwera")
                .investigationStartedAt(java.time.Instant.now())
                .rootCauseCategory("Equipment / Process")
                .build();
        byte[] pdf = new com.smartTriage.smartTriage_server.module.safety.service.SafetyIncidentPdfService()
                .render(i, "Y. Nshuti · Hospital Admin");
        assertIsPdf(writeOut("showcase-safety-incident.pdf", pdf));
    }

    @Test
    void rendersPerPatientShowcase() throws Exception {
        PdfReport r = PdfReport.begin(new PdfReport.Spec(
                "Handover · SBAR", "Handover / SBAR", "King Faisal Hospital Kigali",
                List.of("Facility code · KFH-KGL-001"),
                "Dr. J. Bosco", "clinical handover report", "Emergency department"));

        r.patientBanner("UWIMANA, Claudine", List.of(
                PdfReport.kv("Visit", "V-2026-0713"),
                PdfReport.kv("MRN", "KFH-0044821"),
                PdfReport.kv("Age / Sex", "54 · Female"),
                PdfReport.kv("Arrived", "08 Jul · 06:42")),
                "ORANGE", "Very Urgent", PdfReport.SATS_ORANGE);

        r.statusBanner("Awaiting acknowledgement by the receiving clinician");
        r.metaStrip(List.of(
                PdfReport.kv("Report type", "Transfer of care"),
                PdfReport.kv("Generated", "2026-07-08 07:10"),
                PdfReport.kv("Handing over", "Dr. J. Bosco"),
                PdfReport.kv("Acknowledgement", "Pending")));

        r.sbarGroup("S", "Situation");
        r.subHeader("Presenting complaint");
        r.narrative("Central **chest pain**, sudden onset at rest ~3 hours ago, 8/10, radiating to the "
                + "left arm, with diaphoresis and nausea. Not relieved by rest.");
        r.subHeader("Patient summary");
        r.narrative("54-year-old female, known **hypertensive** (on amlodipine), ex-smoker. "
                + "Independent at baseline. No known drug allergies.");

        r.sbarGroup("B", "Background");
        r.subHeader("Investigations & results");
        r.narrative("**ECG:** ST depression V4–V6, no ST elevation.\n**Troponin-I:** 0.09 ng/mL (elevated) "
                + "— repeat due 09:00.\n**CXR:** no acute changes.");

        r.sbarGroup("A", "Assessment");
        r.subHeader("Active clinical alerts");
        r.alertCallout("CRITICAL — rising troponin.", "Cardiology referral raised 06:58, awaiting review.\n"
                + "Continuous cardiac monitoring in place; watch for arrhythmia and haemodynamic change.");

        r.sbarGroup("R", "Recommendation");
        r.subHeader("Assessment & plan");
        r.narrative("Likely NSTEMI. Continue ACS protocol, serial troponin and ECG, admit to CCU under "
                + "cardiology. Escalate for any dynamic ECG change, ongoing pain, or instability.");

        r.spacer(4f);
        r.subHeader("Acknowledgement — handover is complete only when the receiving clinician signs");
        r.signatureBlock("Handing over — Dr. J. Bosco", "Receiving clinician");

        assertIsPdf(writeOut("showcase-sbar.pdf", r.finish()));
    }
}
