package com.smartTriage.smartTriage_server.module.reporting.service;

import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.common.report.PdfReport;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.quality.entity.QualityMetricSnapshot;
import com.smartTriage.smartTriage_server.module.quality.repository.QualityMetricSnapshotRepository;
import com.smartTriage.smartTriage_server.module.reporting.engine.MohIndicatorQueries;
import com.smartTriage.smartTriage_server.module.reporting.engine.OperationalReportQueries;
import com.smartTriage.smartTriage_server.module.reporting.engine.OperationalReportQueries.ActivityStats;
import com.smartTriage.smartTriage_server.module.reporting.engine.OperationalReportQueries.ModuleActivity;
import com.smartTriage.smartTriage_server.module.reporting.engine.OperationalReportQueries.MyActivity;
import com.smartTriage.smartTriage_server.module.reporting.engine.OperationalReportQueries.OpenWork;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OperationalReportService — the server-side report catalog. Every report here is
 * generated from authoritative aggregate queries and rendered through the shared
 * branded {@link PdfReport} kit: masthead, report parameters, requested-by line,
 * generated-at timestamp, page numbering — never a snapshot of a screen.
 *
 * <p>Catalog: DAILY ED ACTIVITY (one day's operational return), SHIFT HANDOVER
 * SUMMARY (department state + open work for a shift change, with signature block),
 * PERIOD ACTIVITY (date-range totals + per-day trend table), MY CLINICAL ACTIVITY
 * (the authenticated clinician's own workload — self-scoped by construction), and
 * QUALITY METRICS (KPI snapshots trend).
 *
 * <p>Ranges are hard-capped ({@value #MAX_RANGE_DAYS} days) so a mistyped range can
 * never turn into a table-scan marathon; the my-activity visit list is capped at
 * {@value #MY_VISITS_CAP} rows with a visible truncation note.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OperationalReportService {

    static final int MAX_RANGE_DAYS = 92;
    static final int MY_VISITS_CAP = 200;
    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(KIGALI);
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(KIGALI);

    private final OperationalReportQueries queries;
    private final MohIndicatorQueries mohIndicatorQueries;
    private final HospitalRepository hospitalRepository;
    private final QualityMetricSnapshotRepository qualitySnapshotRepository;

    public record RenderedPdf(byte[] bytes, String filename) {}

    // ─────────────────────────────────────────────────────────────────
    // 1. DAILY ED ACTIVITY
    // ─────────────────────────────────────────────────────────────────

    public RenderedPdf dailyActivity(UUID hospitalId, LocalDate date, String requestedBy) {
        Hospital hospital = hospital(hospitalId);
        Instant from = date.atStartOfDay(KIGALI).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(KIGALI).toInstant();

        PdfReport r = begin("DAILY ED ACTIVITY REPORT", "Daily Activity", hospital,
                List.of("Report date: " + date, "Scope: whole department, all zones"), requestedBy);

        ActivityStats stats = queries.activityStats(hospitalId, from, to);
        r.sectionHeader("Activity");
        r.statTiles(List.of(
                PdfReport.kv("Arrivals", String.valueOf(stats.arrivals())),
                PdfReport.kv("Triaged", String.valueOf(stats.triaged())),
                PdfReport.kv("Avg wait (min)", dec(stats.avgWaitMinutes())),
                PdfReport.kv("Avg LOS (min)", dec(stats.avgLosMinutes()))));
        r.keyValues(kvsOf(queries.categoryBreakdown(hospitalId, from, to), "Triage category — "));

        r.sectionHeader("Dispositions");
        Map<String, Long> disp = queries.dispositions(hospitalId, from, to);
        r.keyValues(disp.isEmpty()
                ? List.of(PdfReport.kv("Dispositions", "None recorded on this date"))
                : kvsOf(disp, ""));

        r.sectionHeader("Clinical module activity");
        ModuleActivity mod = queries.moduleActivity(hospitalId, from, to);
        int sepsis = mohIndicatorQueries.countSepsisScreened(hospitalId, from, to);
        int isolation = mohIndicatorQueries.countIsolationActivated(hospitalId, from, to);
        r.statTiles(List.of(
                PdfReport.kv("Sepsis screens", String.valueOf(sepsis)),
                PdfReport.kv("Isolations activated", String.valueOf(isolation)),
                PdfReport.kv("Hypoglycemia events", String.valueOf(mod.hypoglycemiaEvents())),
                PdfReport.kv("Fast-track activations", String.valueOf(mod.fastTrackActivations()))));
        r.keyValues(List.of(
                PdfReport.kv("Safety incidents reported", String.valueOf(mod.incidentsReported())),
                PdfReport.kv("Severe-harm / death incidents", String.valueOf(mod.severeIncidents()))));

        r.sectionHeader("Department census at generation time");
        r.keyValues(kvsOf(queries.censusByZone(hospitalId), "Zone — "));

        footerNote(r);
        return new RenderedPdf(r.finish(), "daily-activity-" + date + ".pdf");
    }

    // ─────────────────────────────────────────────────────────────────
    // 2. SHIFT HANDOVER SUMMARY
    // ─────────────────────────────────────────────────────────────────

    public RenderedPdf shiftHandoverSummary(UUID hospitalId, LocalDate date, String period, String requestedBy) {
        Hospital hospital = hospital(hospitalId);
        String p = "NIGHT".equalsIgnoreCase(period) ? "NIGHT" : "DAY";
        // DAY = 07:00–19:00; NIGHT = 19:00–07:00 (+1d) — the roster's shift windows.
        Instant from = "DAY".equals(p)
                ? date.atTime(7, 0).atZone(KIGALI).toInstant()
                : date.atTime(19, 0).atZone(KIGALI).toInstant();
        Instant to = "DAY".equals(p)
                ? date.atTime(19, 0).atZone(KIGALI).toInstant()
                : date.plusDays(1).atTime(7, 0).atZone(KIGALI).toInstant();

        PdfReport r = begin("SHIFT HANDOVER SUMMARY", "Shift Handover", hospital,
                List.of("Shift: " + p + " " + date, "Window: " + DT.format(from) + " → " + DT.format(to)),
                requestedBy);

        r.sectionHeader("Department state at generation time");
        r.keyValues(kvsOf(queries.censusByZone(hospitalId), "Zone — "));
        r.keyValues(kvsOf(queries.acuityNow(hospitalId), "Acuity — "));

        r.sectionHeader("Open work being handed over");
        OpenWork w = queries.openWork(hospitalId);
        if (w.criticalAlertsUnacked() > 0) {
            r.alertBanner(w.criticalAlertsUnacked() + " CRITICAL alert(s) UNACKNOWLEDGED — review before accepting handover");
        }
        r.keyValues(List.of(
                PdfReport.kv("Unacknowledged CRITICAL alerts", String.valueOf(w.criticalAlertsUnacked())),
                PdfReport.kv("Sepsis bundles in progress", String.valueOf(w.sepsisBundlesOpen())),
                PdfReport.kv("Active isolations", String.valueOf(w.isolationsActive())),
                PdfReport.kv("Isolations awaiting a room", String.valueOf(w.isolationsUnroomed())),
                PdfReport.kv("Unresolved hypoglycemia events", String.valueOf(w.hypoUnresolved())),
                PdfReport.kv("… of which recheck OVERDUE", String.valueOf(w.hypoRecheckOverdue())),
                PdfReport.kv("Open safety incidents", String.valueOf(w.incidentsOpen()))));

        r.sectionHeader("Activity during the shift window");
        ActivityStats stats = queries.activityStats(hospitalId, from, to);
        r.statTiles(List.of(
                PdfReport.kv("Arrivals", String.valueOf(stats.arrivals())),
                PdfReport.kv("Triaged", String.valueOf(stats.triaged())),
                PdfReport.kv("Avg wait (min)", dec(stats.avgWaitMinutes()))));
        Map<String, Long> disp = queries.dispositions(hospitalId, from, to);
        if (!disp.isEmpty()) r.keyValues(kvsOf(disp, ""));

        r.sectionHeader("Rostered staff — " + p + " shift");
        List<Object[]> roster = queries.staffing(hospitalId, date, p);
        List<String[]> rows = new ArrayList<>();
        for (Object[] s : roster) {
            rows.add(new String[]{
                    str(s[0]), str(s[1]).replace('_', ' '),
                    (str(s[2]) + " " + str(s[3])).trim() + (Boolean.TRUE.equals(s[4]) ? "  (SHIFT LEAD)" : "")});
        }
        r.dataTable(new String[]{"Zone", "Function", "Staff member"}, new float[]{20, 30, 50}, rows, 3);

        r.spacer(4f);
        r.paragraph("Handover is complete only when both leads have reviewed the open work above and signed.",
                PdfReport.F_META);
        r.signatureBlock("Outgoing shift lead", "Incoming shift lead");

        footerNote(r);
        return new RenderedPdf(r.finish(), "shift-handover-" + date + "-" + p.toLowerCase() + ".pdf");
    }

    // ─────────────────────────────────────────────────────────────────
    // 3. PERIOD ACTIVITY (trend)
    // ─────────────────────────────────────────────────────────────────

    public RenderedPdf periodActivity(UUID hospitalId, LocalDate from, LocalDate to, String requestedBy) {
        validateRange(from, to);
        Hospital hospital = hospital(hospitalId);
        Instant f = from.atStartOfDay(KIGALI).toInstant();
        Instant t = to.plusDays(1).atStartOfDay(KIGALI).toInstant();

        PdfReport r = begin("ED ACTIVITY REPORT — PERIOD", "Period Activity", hospital,
                List.of("Period: " + from + " → " + to + " (inclusive)"), requestedBy);

        ActivityStats stats = queries.activityStats(hospitalId, f, t);
        r.sectionHeader("Period totals");
        r.statTiles(List.of(
                PdfReport.kv("Arrivals", String.valueOf(stats.arrivals())),
                PdfReport.kv("Triaged", String.valueOf(stats.triaged())),
                PdfReport.kv("Avg wait (min)", dec(stats.avgWaitMinutes())),
                PdfReport.kv("Avg LOS (min)", dec(stats.avgLosMinutes()))));
        r.keyValues(kvsOf(queries.categoryBreakdown(hospitalId, f, t), "Triage category — "));
        Map<String, Long> disp = queries.dispositions(hospitalId, f, t);
        if (!disp.isEmpty()) {
            r.sectionHeader("Dispositions");
            r.keyValues(kvsOf(disp, ""));
        }

        r.sectionHeader("Per-day trend");
        List<String[]> rows = new ArrayList<>();
        for (Object[] d : queries.dailyTrend(hospitalId, f, t)) {
            rows.add(new String[]{
                    String.valueOf(d[0]), String.valueOf(d[1]), String.valueOf(d[2]),
                    String.valueOf(d[3]), String.valueOf(d[4]), String.valueOf(d[5]),
                    String.valueOf(d[6]), String.valueOf(d[7]), dec(asDouble(d[8]))});
        }
        r.dataTable(
                new String[]{"Date", "Arrivals", "Red", "Orange", "Yellow", "Green", "Admitted", "LWBS", "Avg wait"},
                new float[]{16, 11, 9, 10, 10, 10, 12, 9, 13}, rows, 1);

        footerNote(r);
        return new RenderedPdf(r.finish(), "period-activity-" + from + "-to-" + to + ".pdf");
    }

    // ─────────────────────────────────────────────────────────────────
    // 4. MY CLINICAL ACTIVITY (self-scoped)
    // ─────────────────────────────────────────────────────────────────

    public RenderedPdf myActivity(User caller, LocalDate from, LocalDate to) {
        validateRange(from, to);
        if (caller == null || caller.getHospital() == null) {
            throw new IllegalArgumentException("No hospital is associated with your account.");
        }
        UUID hospitalId = caller.getHospital().getId();
        Hospital hospital = hospital(hospitalId);
        Instant f = from.atStartOfDay(KIGALI).toInstant();
        Instant t = to.plusDays(1).atStartOfDay(KIGALI).toInstant();
        String name = (caller.getFirstName() + " " + caller.getLastName()).trim();

        PdfReport r = begin("MY CLINICAL ACTIVITY", "My Activity", hospital,
                List.of("Clinician: " + name + " (" + caller.getRole() + ")",
                        "Period: " + from + " → " + to + " (inclusive)"), name);

        MyActivity a = queries.myActivity(caller.getId(), hospitalId, f, t);
        r.sectionHeader("Workload");
        r.statTiles(List.of(
                PdfReport.kv("Patients (primary clinician)", String.valueOf(a.primaryVisits())),
                PdfReport.kv("Clinical notes authored", String.valueOf(a.notesAuthored())),
                PdfReport.kv("Medications prescribed", String.valueOf(a.prescriptions()))));

        r.sectionHeader("Patients seen as primary clinician");
        List<Object[]> visitRows = queries.myVisitRows(caller.getId(), hospitalId, f, t, MY_VISITS_CAP);
        List<String[]> rows = new ArrayList<>();
        for (Object[] v : visitRows) {
            rows.add(new String[]{
                    str(v[0]), str(v[1]),
                    v[2] != null ? DT.format(((java.sql.Timestamp) v[2]).toInstant()) : "—",
                    str(v[3]), str(v[4]).replace('_', ' ')});
        }
        r.dataTable(new String[]{"Visit", "Patient", "Arrival", "Category", "Disposition"},
                new float[]{22, 28, 20, 12, 18}, rows, 5);
        if (a.primaryVisits() > MY_VISITS_CAP) {
            r.paragraph("Showing the most recent " + MY_VISITS_CAP + " of " + a.primaryVisits()
                    + " visits.", PdfReport.F_META);
        }

        footerNote(r);
        return new RenderedPdf(r.finish(), "my-activity-" + from + "-to-" + to + ".pdf");
    }

    // ─────────────────────────────────────────────────────────────────
    // 5. QUALITY METRICS
    // ─────────────────────────────────────────────────────────────────

    public RenderedPdf qualityMetrics(UUID hospitalId, LocalDate from, LocalDate to, String requestedBy) {
        validateRange(from, to);
        Hospital hospital = hospital(hospitalId);

        PdfReport r = begin("ED QUALITY METRICS REPORT", "Quality Metrics", hospital,
                List.of("Period: " + from + " → " + to + " (inclusive)",
                        "Source: scheduled daily quality snapshots"), requestedBy);

        // findByHospitalAndDateRange + Java filter rather than findDailySnapshotsInRange:
        // that finder compares the enum path to a string literal ('DAILY') and returned
        // empty here against rows SQL can see — filtering on the typed enum is unambiguous.
        List<QualityMetricSnapshot> snaps = qualitySnapshotRepository
                .findByHospitalAndDateRange(hospitalId, from, to).stream()
                .filter(s -> s.getSnapshotPeriod() == com.smartTriage.smartTriage_server.common.enums.MetricPeriod.DAILY)
                .toList();

        if (snaps.isEmpty()) {
            r.paragraph("No quality snapshots exist for this period. Snapshots are captured daily by the "
                    + "quality scheduler; pick a period that includes captured days.", PdfReport.F_BODY);
        } else {
            QualityMetricSnapshot latest = snaps.get(snaps.size() - 1);
            r.sectionHeader("Latest captured day — " + latest.getSnapshotDate());
            r.statTiles(List.of(
                    PdfReport.kv("Patients", n(latest.getTotalPatients())),
                    PdfReport.kv("Admissions", n(latest.getTotalAdmissions())),
                    PdfReport.kv("LWBS", n(latest.getTotalLeftWithoutBeingSeen())),
                    PdfReport.kv("Avg wait (min)", dec(latest.getAverageWaitTimeMinutes())),
                    PdfReport.kv("Door→triage (min)", dec(latest.getAverageDoorToTriageMinutes())),
                    PdfReport.kv("Avg TEWS", dec(latest.getAverageTewsScore()))));

            r.sectionHeader("Daily snapshots");
            List<String[]> rows = new ArrayList<>();
            for (QualityMetricSnapshot s : snaps) {
                rows.add(new String[]{
                        String.valueOf(s.getSnapshotDate()), n(s.getTotalPatients()),
                        n(s.getRedPatients()), n(s.getOrangePatients()),
                        n(s.getTotalAdmissions()), n(s.getTotalDeaths()),
                        n(s.getTotalLeftWithoutBeingSeen()), dec(s.getAverageWaitTimeMinutes()),
                        n(s.getRetriageCount())});
            }
            r.dataTable(
                    new String[]{"Date", "Patients", "Red", "Orange", "Admitted", "Deaths", "LWBS", "Avg wait", "Re-triages"},
                    new float[]{15, 11, 8, 10, 12, 10, 9, 12, 13}, rows, 1);
        }

        footerNote(r);
        return new RenderedPdf(r.finish(), "quality-metrics-" + from + "-to-" + to + ".pdf");
    }

    // ─────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────

    private Hospital hospital(UUID hospitalId) {
        return hospitalRepository.findByIdAndIsActiveTrue(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital", "id", hospitalId));
    }

    private PdfReport begin(String title, String docType, Hospital hospital,
                            List<String> paramLines, String requestedBy) {
        List<String> meta = new ArrayList<>();
        if (hospital.getHospitalCode() != null) meta.add("Facility code: " + hospital.getHospitalCode());
        meta.addAll(paramLines);
        return PdfReport.begin(new PdfReport.Spec(
                title, docType, hospital.getName(), meta, requestedBy,
                "Operational report — generated from live clinical data"));
    }

    private void footerNote(PdfReport r) {
        r.spacer(6f);
        r.paragraph("Generated by SmartTriage from authoritative clinical records. Figures reflect the data "
                + "at generation time; the generation itself is recorded in the hospital audit trail.",
                PdfReport.F_META);
    }

    private static void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("Invalid period: 'from' must be on or before 'to'.");
        }
        if (from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw new IllegalArgumentException(
                    "Period too large: reports cover at most " + MAX_RANGE_DAYS + " days per generation.");
        }
    }

    private static List<PdfReport.KeyVal> kvsOf(Map<String, Long> counts, String prefix) {
        List<PdfReport.KeyVal> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            out.add(PdfReport.kv(prefix + e.getKey().replace('_', ' '), String.valueOf(e.getValue())));
        }
        return out;
    }

    private static String str(Object v) { return v != null ? v.toString() : "—"; }
    private static String n(Integer v) { return v != null ? String.valueOf(v) : "0"; }
    private static String dec(Double v) { return v != null ? String.format("%.1f", v) : "—"; }
    private static Double asDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number num) return num.doubleValue();
        return null;
    }
}
