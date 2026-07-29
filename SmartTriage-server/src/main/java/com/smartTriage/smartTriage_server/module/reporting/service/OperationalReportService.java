package com.smartTriage.smartTriage_server.module.reporting.service;

import com.smartTriage.smartTriage_server.common.enums.MetricPeriod;
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

import java.awt.Color;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OperationalReportService — the server-side report catalog. Every report here is
 * generated from authoritative aggregate queries and rendered through the shared
 * branded {@link PdfReport} kit: masthead, labelled parameter strip, KPI tiles,
 * SATS acuity stacked bar + legend, proportional disposition bars, workload rows,
 * a vector trend chart, data tables, requested-by attribution and page numbering
 * — never a snapshot of a screen.
 *
 * <p>Catalog: DAILY ED ACTIVITY (one day's operational return), SHIFT HANDOVER
 * SUMMARY (department state + open work for a shift change, with signature block),
 * PERIOD ACTIVITY (date-range totals + per-day trend), MY CLINICAL ACTIVITY
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
    private static final DateTimeFormatter D_SHORT = DateTimeFormatter.ofPattern("dd MMM");

    private final OperationalReportQueries queries;
    private final MohIndicatorQueries mohIndicatorQueries;
    private final HospitalRepository hospitalRepository;
    private final QualityMetricSnapshotRepository qualitySnapshotRepository;

    public record RenderedPdf(byte[] bytes, String filename) {}
    public record RenderedCsv(String csv, String filename) {}

    // ─────────────────────────────────────────────────────────────────
    // 1. DAILY ED ACTIVITY
    // ─────────────────────────────────────────────────────────────────

    public RenderedPdf dailyActivity(UUID hospitalId, LocalDate date, String requestedBy) {
        Hospital hospital = hospital(hospitalId);
        Instant from = date.atStartOfDay(KIGALI).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(KIGALI).toInstant();

        PdfReport r = begin("Daily ED Activity", "Daily Activity", "Operational report", hospital, requestedBy);
        r.metaStrip(List.of(
                PdfReport.kv("Report date", date.toString()),
                PdfReport.kv("Scope", "Whole department · all zones"),
                PdfReport.kv("Requested by", requestedBy),
                PdfReport.kv("Source", "Live clinical records")));

        ActivityStats stats = queries.activityStats(hospitalId, from, to);
        r.sectionHeader("Activity at a glance");
        r.kpiTiles(List.of(
                PdfReport.kpi(grp(stats.arrivals()), null, "Arrivals"),
                PdfReport.kpi(grp(stats.triaged()), null, "Triaged"),
                PdfReport.kpi(dec(stats.avgWaitMinutes()), "min", "Avg wait"),
                PdfReport.kpi(dec(stats.avgLosMinutes()), "min", "Avg length of stay")));

        acuityMix(r, queries.categoryBreakdown(hospitalId, from, to), stats.triaged());

        r.sectionHeader("Dispositions");
        Map<String, Long> disp = queries.dispositions(hospitalId, from, to);
        if (disp.isEmpty()) {
            r.paragraph("None recorded on this date.", PdfReport.F_META);
        } else {
            r.barList(dispositionBars(disp));
        }

        r.sectionHeader("Clinical module activity");
        ModuleActivity mod = queries.moduleActivity(hospitalId, from, to);
        int sepsis = mohIndicatorQueries.countSepsisScreened(hospitalId, from, to);
        int isolation = mohIndicatorQueries.countIsolationActivated(hospitalId, from, to);
        r.metricGrid(List.of(
                new PdfReport.Metric(String.valueOf(sepsis), "Sepsis screens", PdfReport.MetricTone.NEUTRAL),
                new PdfReport.Metric(String.valueOf(isolation), "Isolations activated", PdfReport.MetricTone.NEUTRAL),
                new PdfReport.Metric(String.valueOf(mod.hypoglycemiaEvents()), "Hypoglycemia events", PdfReport.MetricTone.NEUTRAL),
                new PdfReport.Metric(String.valueOf(mod.fastTrackActivations()), "Fast-track activations", PdfReport.MetricTone.NEUTRAL),
                new PdfReport.Metric(String.valueOf(mod.incidentsReported()), "Safety incidents reported",
                        mod.incidentsReported() > 0 ? PdfReport.MetricTone.ATTENTION : PdfReport.MetricTone.NEUTRAL),
                new PdfReport.Metric(String.valueOf(mod.severeIncidents()), "Severe-harm / death incidents",
                        mod.severeIncidents() == 0 ? PdfReport.MetricTone.GOOD : PdfReport.MetricTone.ATTENTION)));

        r.sectionHeader("Department census", "at generation time");
        censusTiles(r, queries.censusByZone(hospitalId));

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

        PdfReport r = begin("Shift Handover Summary", "Shift Handover", "Shift handover", hospital, requestedBy);
        r.metaStrip(List.of(
                PdfReport.kv("Shift", p + " · " + date),
                PdfReport.kv("Window", DT.format(from) + " → " + DT.format(to)),
                PdfReport.kv("Requested by", requestedBy),
                PdfReport.kv("Source", "Live clinical records")));

        OpenWork w = queries.openWork(hospitalId);
        if (w.criticalAlertsUnacked() > 0) {
            r.alertBanner(w.criticalAlertsUnacked() + " CRITICAL alert(s) UNACKNOWLEDGED — review before accepting handover");
        }

        r.sectionHeader("Open work being handed over");
        r.workRows(List.of(
                workRow("Unacknowledged CRITICAL alerts", w.criticalAlertsUnacked(),
                        PdfReport.WorkTone.CRIT, "Act now", "Clear", false),
                workRow("Sepsis bundles in progress", w.sepsisBundlesOpen(),
                        PdfReport.WorkTone.INFO, "In progress", "None", false),
                workRow("Active isolations", w.isolationsActive(),
                        PdfReport.WorkTone.INFO, "Ongoing", "None", false),
                workRow("Isolations awaiting a room", w.isolationsUnroomed(),
                        PdfReport.WorkTone.WARN, "Attention", "None", false),
                workRow("Unresolved hypoglycemia events", w.hypoUnresolved(),
                        PdfReport.WorkTone.INFO, "Monitor", "None", false),
                workRow("… of which recheck OVERDUE", w.hypoRecheckOverdue(),
                        PdfReport.WorkTone.OVER, "Overdue", "None", true),
                workRow("Open safety incidents", w.incidentsOpen(),
                        PdfReport.WorkTone.WARN, "Attention", "None", false)));

        r.sectionHeader("Department state", "at generation time");
        censusTiles(r, queries.censusByZone(hospitalId));
        r.chipRow(acuityChips(queries.acuityNow(hospitalId)));

        r.sectionHeader("Activity during the shift window");
        ActivityStats stats = queries.activityStats(hospitalId, from, to);
        r.kpiTiles(List.of(
                PdfReport.kpi(grp(stats.arrivals()), null, "Arrivals"),
                PdfReport.kpi(grp(stats.triaged()), null, "Triaged"),
                PdfReport.kpi(dec(stats.avgWaitMinutes()), "min", "Avg wait")), 3);
        Map<String, Long> disp = queries.dispositions(hospitalId, from, to);
        if (!disp.isEmpty()) {
            List<PdfReport.Chip> flow = new ArrayList<>();
            for (Map.Entry<String, Long> e : disp.entrySet()) {
                flow.add(new PdfReport.Chip(null, pretty(e.getKey()), String.valueOf(e.getValue())));
            }
            r.chipRow(flow);
        }

        r.sectionHeader("Rostered staff", p + " shift");
        List<Object[]> roster = queries.staffing(hospitalId, date, p);
        List<String[]> rows = new ArrayList<>();
        for (Object[] s : roster) {
            rows.add(new String[]{
                    str(s[0]), pretty(str(s[1])),
                    (str(s[2]) + " " + str(s[3])).trim() + (Boolean.TRUE.equals(s[4]) ? "  (SHIFT LEAD)" : "")});
        }
        r.dataTable(new String[]{"Zone", "Function", "Staff member"}, new float[]{20, 30, 50}, rows, 3);

        r.spacer(4f);
        r.narrative("Handover is complete only when both leads have reviewed the open work above and signed.");
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

        PdfReport r = begin("Period Activity", "Period Activity", "Activity report — period", hospital, requestedBy);
        r.metaStrip(List.of(
                PdfReport.kv("Period", D_SHORT.format(from) + " → " + D_SHORT.format(to) + " " + to.getYear()),
                PdfReport.kv("Days", (java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1) + " (inclusive)"),
                PdfReport.kv("Requested by", requestedBy),
                PdfReport.kv("Source", "Live clinical records")));

        ActivityStats stats = queries.activityStats(hospitalId, f, t);
        r.sectionHeader("Period totals");
        r.kpiTiles(List.of(
                PdfReport.kpi(grp(stats.arrivals()), null, "Arrivals"),
                PdfReport.kpi(grp(stats.triaged()), null, "Triaged"),
                PdfReport.kpi(dec(stats.avgWaitMinutes()), "min", "Avg wait"),
                PdfReport.kpi(dec(stats.avgLosMinutes()), "min", "Avg length of stay")));

        Map<String, Long> cats = queries.categoryBreakdown(hospitalId, f, t);
        acuityMix(r, cats, stats.triaged());

        Map<String, Long> disp = queries.dispositions(hospitalId, f, t);
        if (!disp.isEmpty()) {
            r.sectionHeader("Dispositions");
            r.barList(dispositionBars(disp));
        }

        List<Object[]> trend = queries.dailyTrend(hospitalId, f, t);
        if (trend.size() >= 2) {
            double[] arrivals = new double[trend.size()];
            double peak = 0, low = Double.MAX_VALUE;
            for (int i = 0; i < trend.size(); i++) {
                arrivals[i] = asLong(trend.get(i)[1]);
                peak = Math.max(peak, arrivals[i]);
                low = Math.min(low, arrivals[i]);
            }
            LocalDate mid = from.plusDays(java.time.temporal.ChronoUnit.DAYS.between(from, to) / 2);
            r.sectionHeader("Daily arrivals — trend",
                    trend.size() + " days · peak " + Math.round(peak) + " · low " + Math.round(low));
            r.trendChart(arrivals, D_SHORT.format(from), D_SHORT.format(mid), D_SHORT.format(to));
        }

        r.sectionHeader("Per-day breakdown");
        List<String[]> rows = new ArrayList<>();
        for (Object[] d : trend) {
            rows.add(new String[]{
                    String.valueOf(d[0]), String.valueOf(d[1]), String.valueOf(d[2]),
                    String.valueOf(d[3]), String.valueOf(d[4]), String.valueOf(d[5]),
                    String.valueOf(d[6]), String.valueOf(d[7]), dec(asDouble(d[8]))});
        }
        String[] totals = new String[]{
                "Period total", grp(stats.arrivals()),
                grp(satsCount(cats, "RED")), grp(satsCount(cats, "ORANGE")),
                grp(satsCount(cats, "YELLOW")), grp(satsCount(cats, "GREEN")),
                grp(dispCount(disp, "ADMIT")), grp(dispCount(disp, "LWBS", "LEFT")),
                dec(stats.avgWaitMinutes())};
        r.dataTable(
                new String[]{"Date", "Arrivals", "Red", "Orange", "Yellow", "Green", "Admitted", "LWBS", "Avg wait"},
                new Color[]{null, null, PdfReport.SATS_RED, PdfReport.SATS_ORANGE,
                        PdfReport.SATS_YELLOW, PdfReport.SATS_GREEN, null, null, null},
                new float[]{16, 11, 9, 10, 10, 10, 12, 9, 13}, rows, totals, 1);

        footerNote(r);
        return new RenderedPdf(r.finish(), "period-activity-" + from + "-to-" + to + ".pdf");
    }

    /** Period activity as CSV — the per-day breakdown, one row per day (same data as the PDF table). */
    public RenderedCsv periodActivityCsv(UUID hospitalId, LocalDate from, LocalDate to) {
        validateRange(from, to);
        hospital(hospitalId); // 404 if the hospital does not exist
        Instant f = from.atStartOfDay(KIGALI).toInstant();
        Instant t = to.plusDays(1).atStartOfDay(KIGALI).toInstant();

        StringBuilder sb = new StringBuilder();
        sb.append("Date,Arrivals,Red,Orange,Yellow,Green,Admitted,LWBS,Avg Wait (min)\n");
        for (Object[] d : queries.dailyTrend(hospitalId, f, t)) {
            csvRow(sb,
                    String.valueOf(d[0]),
                    Long.toString(asLong(d[1])),
                    Long.toString(asLong(d[2])),
                    Long.toString(asLong(d[3])),
                    Long.toString(asLong(d[4])),
                    Long.toString(asLong(d[5])),
                    Long.toString(asLong(d[6])),
                    Long.toString(asLong(d[7])),
                    csvDec(asDouble(d[8])));
        }
        return new RenderedCsv(sb.toString(), "period-activity-" + from + "-to-" + to + ".csv");
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

        PdfReport r = begin("My Clinical Activity", "My Activity", "Clinician report", hospital, name);
        r.metaStrip(List.of(
                PdfReport.kv("Clinician", name + " (" + caller.getRole() + ")"),
                PdfReport.kv("Period", from + " → " + to + " (inclusive)"),
                PdfReport.kv("Source", "Live clinical records")));

        MyActivity a = queries.myActivity(caller.getId(), hospitalId, f, t);
        r.sectionHeader("Workload");
        r.kpiTiles(List.of(
                PdfReport.kpi(grp(a.primaryVisits()), null, "Patients (primary clinician)"),
                PdfReport.kpi(grp(a.notesAuthored()), null, "Clinical notes authored"),
                PdfReport.kpi(grp(a.prescriptions()), null, "Medications prescribed")), 3);

        r.sectionHeader("Patients seen as primary clinician");
        List<Object[]> visitRows = queries.myVisitRows(caller.getId(), hospitalId, f, t, MY_VISITS_CAP);
        List<String[]> rows = new ArrayList<>();
        for (Object[] v : visitRows) {
            rows.add(new String[]{
                    str(v[0]), str(v[1]),
                    v[2] != null ? DT.format(((java.sql.Timestamp) v[2]).toInstant()) : "—",
                    str(v[3]), pretty(str(v[4]))});
        }
        r.dataTable(new String[]{"Visit", "Patient", "Arrival", "Category", "Disposition"},
                new float[]{22, 28, 20, 12, 18}, rows, 5);
        if (a.primaryVisits() > MY_VISITS_CAP) {
            r.tableNote("Showing the most recent " + MY_VISITS_CAP + " of " + a.primaryVisits() + " visits.");
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

        PdfReport r = begin("Quality Metrics", "Quality Metrics", "Quality & governance", hospital, requestedBy);
        r.metaStrip(List.of(
                PdfReport.kv("Period", from + " → " + to + " (inclusive)"),
                PdfReport.kv("Requested by", requestedBy),
                PdfReport.kv("Source", "Scheduled daily quality snapshots")));

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
            r.sectionHeader("Latest captured day", String.valueOf(latest.getSnapshotDate()));
            r.kpiTiles(List.of(
                    PdfReport.kpi(n(latest.getTotalPatients()), null, "Patients"),
                    PdfReport.kpi(n(latest.getTotalAdmissions()), null, "Admissions"),
                    PdfReport.kpi(n(latest.getTotalLeftWithoutBeingSeen()), null, "LWBS"),
                    PdfReport.kpi(dec(latest.getAverageWaitTimeMinutes()), "min", "Avg wait"),
                    PdfReport.kpi(dec(latest.getAverageDoorToTriageMinutes()), "min", "Door → triage"),
                    PdfReport.kpi(dec(latest.getAverageTewsScore()), null, "Avg TEWS")), 3);

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
                    new Color[]{null, null, PdfReport.SATS_RED, PdfReport.SATS_ORANGE, null, null, null, null, null},
                    new float[]{15, 11, 8, 10, 12, 10, 9, 12, 13}, rows, null, 1);
        }

        footerNote(r);
        return new RenderedPdf(r.finish(), "quality-metrics-" + from + "-to-" + to + ".pdf");
    }

    /** Quality metrics as CSV — one row per captured daily snapshot (richer column set than the PDF). */
    public RenderedCsv qualityMetricsCsv(UUID hospitalId, LocalDate from, LocalDate to) {
        validateRange(from, to);
        hospital(hospitalId); // 404 if the hospital does not exist
        List<QualityMetricSnapshot> snaps = qualitySnapshotRepository
                .findByHospitalAndDateRange(hospitalId, from, to).stream()
                .filter(s -> s.getSnapshotPeriod() == MetricPeriod.DAILY)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("Date,Total Patients,Red,Orange,Yellow,Green,Admissions,Discharges,Deaths,LWBS,")
                .append("Avg Wait (min),Median Wait (min),Door to Triage (min),Avg TEWS,")
                .append("Seen Within Target (%),Re-triages\n");
        for (QualityMetricSnapshot s : snaps) {
            csvRow(sb,
                    String.valueOf(s.getSnapshotDate()),
                    csvNum(s.getTotalPatients()),
                    csvNum(s.getRedPatients()),
                    csvNum(s.getOrangePatients()),
                    csvNum(s.getYellowPatients()),
                    csvNum(s.getGreenPatients()),
                    csvNum(s.getTotalAdmissions()),
                    csvNum(s.getTotalDischarges()),
                    csvNum(s.getTotalDeaths()),
                    csvNum(s.getTotalLeftWithoutBeingSeen()),
                    csvDec(s.getAverageWaitTimeMinutes()),
                    csvDec(s.getMedianWaitTimeMinutes()),
                    csvDec(s.getAverageDoorToTriageMinutes()),
                    csvDec(s.getAverageTewsScore()),
                    csvDec(s.getPercentSeenWithinTarget()),
                    csvNum(s.getRetriageCount()));
        }
        return new RenderedCsv(sb.toString(), "quality-metrics-" + from + "-to-" + to + ".csv");
    }

    // ─────────────────────────────────────────────────────────────────
    // shared rendering helpers
    // ─────────────────────────────────────────────────────────────────

    /** The SATS acuity mix: section header w/ total, 100% stacked bar, legend. */
    private void acuityMix(PdfReport r, Map<String, Long> cats, long triaged) {
        long total = cats.values().stream().mapToLong(Long::longValue).sum();
        r.sectionHeader("Triage acuity mix", grp(triaged) + " triaged · SATS");
        if (total <= 0) {
            r.paragraph("No triage records in this window.", PdfReport.F_META);
            return;
        }
        List<PdfReport.Segment> segs = new ArrayList<>();
        List<PdfReport.LegendItem> leg = new ArrayList<>();
        for (String key : satsOrder(cats)) {
            long v = cats.getOrDefault(key, 0L);
            if (v <= 0) continue;
            Color c = satsColor(key);
            segs.add(new PdfReport.Segment(grp(v), v, c, c == PdfReport.SATS_YELLOW));
            leg.add(new PdfReport.LegendItem(c, pretty(key), satsMeaning(key), grp(v), pct(v, total)));
        }
        r.stackedBar(segs);
        r.legend(leg);
    }

    /** Census tiles per zone plus a highlighted in-department total. */
    private void censusTiles(PdfReport r, Map<String, Long> census) {
        if (census.isEmpty()) {
            r.paragraph("No active visits right now.", PdfReport.F_META);
            return;
        }
        List<PdfReport.KeyVal> zones = new ArrayList<>();
        long total = 0;
        for (Map.Entry<String, Long> e : census.entrySet()) {
            zones.add(PdfReport.kv(pretty(e.getKey()), String.valueOf(e.getValue())));
            total += e.getValue();
        }
        r.censusTiles(zones, PdfReport.kv("In department", grp(total)));
    }

    private static PdfReport.WorkRow workRow(String label, long value, PdfReport.WorkTone toneWhenPresent,
                                             String pillWhenPresent, String pillWhenZero, boolean sub) {
        boolean present = value > 0;
        return new PdfReport.WorkRow(label, String.valueOf(value),
                present ? toneWhenPresent : PdfReport.WorkTone.OK, sub,
                present ? pillWhenPresent : pillWhenZero);
    }

    private static List<PdfReport.Chip> acuityChips(Map<String, Long> acuity) {
        List<PdfReport.Chip> chips = new ArrayList<>();
        Map<String, Long> ordered = new LinkedHashMap<>();
        for (String k : satsOrder(acuity)) if (acuity.containsKey(k)) ordered.put(k, acuity.get(k));
        for (Map.Entry<String, Long> e : ordered.entrySet()) {
            chips.add(new PdfReport.Chip(satsColor(e.getKey()), pretty(e.getKey()), String.valueOf(e.getValue())));
        }
        return chips;
    }

    private static List<PdfReport.BarRow> dispositionBars(Map<String, Long> disp) {
        long max = disp.values().stream().mapToLong(Long::longValue).max().orElse(1);
        long total = disp.values().stream().mapToLong(Long::longValue).sum();
        List<PdfReport.BarRow> rows = new ArrayList<>();
        disp.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> rows.add(new PdfReport.BarRow(
                        pretty(e.getKey()), (double) e.getValue() / max,
                        dispositionColor(e.getKey()), grp(e.getValue()), pct(e.getValue(), total))));
        return rows;
    }

    /** SATS keys in clinical order (then anything unexpected, so nothing is dropped). */
    private static List<String> satsOrder(Map<String, Long> map) {
        List<String> order = new ArrayList<>(List.of("RED", "ORANGE", "YELLOW", "GREEN", "BLUE"));
        for (String k : map.keySet()) {
            if (!order.contains(k.toUpperCase())) order.add(k);
        }
        return order;
    }

    private static Color satsColor(String key) {
        String k = key.toUpperCase();
        if (k.contains("RED")) return PdfReport.SATS_RED;
        if (k.contains("ORANGE")) return PdfReport.SATS_ORANGE;
        if (k.contains("YELLOW")) return PdfReport.SATS_YELLOW;
        if (k.contains("GREEN")) return PdfReport.SATS_GREEN;
        if (k.contains("BLUE")) return PdfReport.SATS_BLUE;
        return PdfReport.SLATE_400;
    }

    private static String satsMeaning(String key) {
        return switch (key.toUpperCase()) {
            case "RED" -> "Immediate";
            case "ORANGE" -> "Very urgent";
            case "YELLOW" -> "Urgent";
            case "GREEN" -> "Routine";
            case "BLUE" -> "Dead on arrival";
            default -> "";
        };
    }

    private static Color dispositionColor(String key) {
        String k = key.toUpperCase();
        if (k.contains("DISCHARG")) return PdfReport.SATS_GREEN;
        if (k.contains("ADMIT")) return PdfReport.BRAND;
        if (k.contains("LWBS") || k.contains("LEFT")) return PdfReport.ACCENT;
        if (k.contains("DECEAS") || k.contains("DEAD") || k.contains("DEATH")) return PdfReport.DANGER;
        return PdfReport.SLATE_400;
    }

    private static long satsCount(Map<String, Long> cats, String key) {
        return cats.entrySet().stream()
                .filter(e -> e.getKey().toUpperCase().contains(key))
                .mapToLong(Map.Entry::getValue).sum();
    }

    private static long dispCount(Map<String, Long> disp, String... keys) {
        return disp.entrySet().stream()
                .filter(e -> { for (String k : keys) if (e.getKey().toUpperCase().contains(k)) return true; return false; })
                .mapToLong(Map.Entry::getValue).sum();
    }

    // ─────────────────────────────────────────────────────────────────
    // plumbing helpers
    // ─────────────────────────────────────────────────────────────────

    private Hospital hospital(UUID hospitalId) {
        return hospitalRepository.findByIdAndIsActiveTrue(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital", "id", hospitalId));
    }

    private PdfReport begin(String title, String docType, String eyebrow, Hospital hospital, String requestedBy) {
        List<String> meta = new ArrayList<>();
        if (hospital.getHospitalCode() != null) meta.add("Facility code · " + hospital.getHospitalCode());
        return PdfReport.begin(new PdfReport.Spec(
                title, docType, hospital.getName(), meta, requestedBy,
                "Operational report — generated from live clinical data", eyebrow));
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

    /** "LEFT_WITHOUT_BEING_SEEN" → "Left without being seen"; "RED" → "Red"; acronyms kept. */
    private static String pretty(String key) {
        if (key == null || key.isBlank()) return "—";
        String s = key.replace('_', ' ').trim();
        if (s.length() <= 4 && s.equals(s.toUpperCase()) && !s.contains(" ")
                && !List.of("RED", "BLUE").contains(s)) {
            return s; // short acronyms like LWBS / DOA stay uppercase
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    private static String str(Object v) { return v != null ? v.toString() : "—"; }
    private static String n(Integer v) { return v != null ? String.valueOf(v) : "0"; }
    private static String dec(Double v) { return v != null ? String.format("%.1f", v) : "—"; }
    private static String grp(long v) { return String.format("%,d", v); }

    // ── CSV cell formatting: blank (not em-dash / thousands-separators) so cells stay spreadsheet-clean ──
    private static String csvNum(Integer v) { return v != null ? String.valueOf(v) : ""; }
    private static String csvDec(Double v) { return v != null ? String.format("%.1f", v) : ""; }

    private static void csvRow(StringBuilder sb, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(csvCell(cells[i]));
        }
        sb.append('\n');
    }

    /** CSV-escape a cell: quote when it contains a comma, quote, or newline; "" escapes a quote. */
    private static String csvCell(String v) {
        if (v == null) return "";
        boolean q = v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r");
        String out = v.replace("\"", "\"\"");
        return q ? "\"" + out + "\"" : out;
    }
    private static String pct(long v, long total) {
        return total > 0 ? String.format("%.1f%%", 100.0 * v / total) : "";
    }
    private static long asLong(Object v) { return v instanceof Number num ? num.longValue() : 0L; }
    private static Double asDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number num) return num.doubleValue();
        return null;
    }
}
