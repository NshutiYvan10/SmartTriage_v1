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
        r.ledgerIdTable(List.of(
                PdfReport.lcellMono("Report date", date.toString()),
                PdfReport.lcell("Scope", "Whole department · all zones"),
                PdfReport.lcell("Requested by", requestedBy),
                PdfReport.lcell("Source", "Live clinical records")));

        int s = 0;
        ActivityStats stats = queries.activityStats(hospitalId, from, to);
        r.ledgerSection(sec(++s), "Activity at a glance");
        List<PdfReport.LedgerCell> glance = new ArrayList<>();
        kpiCell(glance, grp(stats.arrivals()), null, "Arrivals");
        kpiCell(glance, grp(stats.triaged()), null, "Triaged");
        kpiCell(glance, dec(stats.avgWaitMinutes()), "min", "Avg wait");
        kpiCell(glance, dec(stats.avgLosMinutes()), "min", "Avg length of stay");
        r.ledgerKv(glance);

        acuityMix(r, sec(++s), queries.categoryBreakdown(hospitalId, from, to), stats.triaged());

        r.ledgerSection(sec(++s), "Dispositions");
        Map<String, Long> disp = queries.dispositions(hospitalId, from, to);
        if (disp.isEmpty()) {
            r.paragraph("None recorded on this date.", PdfReport.F_META);
        } else {
            dispositionTable(r, disp);
        }

        r.ledgerSection(sec(++s), "Clinical module activity");
        ModuleActivity mod = queries.moduleActivity(hospitalId, from, to);
        int sepsis = mohIndicatorQueries.countSepsisScreened(hospitalId, from, to);
        int isolation = mohIndicatorQueries.countIsolationActivated(hospitalId, from, to);
        List<PdfReport.LedgerCell> module = new ArrayList<>();
        module.add(PdfReport.lcellMono("Sepsis screens", String.valueOf(sepsis)));
        module.add(PdfReport.lcellMono("Isolations activated", String.valueOf(isolation)));
        module.add(PdfReport.lcellMono("Hypoglycemia events", String.valueOf(mod.hypoglycemiaEvents())));
        module.add(PdfReport.lcellMono("Fast-track activations", String.valueOf(mod.fastTrackActivations())));
        module.add(mod.incidentsReported() > 0
                ? PdfReport.lcellColor("Safety incidents reported", String.valueOf(mod.incidentsReported()), PdfReport.ACCENT)
                : PdfReport.lcellMono("Safety incidents reported", "0"));
        module.add(mod.severeIncidents() == 0
                ? PdfReport.lcellColor("Severe-harm / death incidents", "0", PdfReport.SATS_GREEN)
                : PdfReport.lcellColor("Severe-harm / death incidents", String.valueOf(mod.severeIncidents()), PdfReport.DANGER));
        r.ledgerKv(module);

        r.ledgerSection(sec(++s), "Department census");
        r.paragraph("At generation time.", PdfReport.F_META);
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
        r.ledgerIdTable(List.of(
                PdfReport.lcell("Shift", p + " · " + date),
                PdfReport.lcell("Requested by", requestedBy),
                PdfReport.lcellFullMono("Window", DT.format(from) + " → " + DT.format(to)),
                PdfReport.lcell("Source", "Live clinical records")));

        OpenWork w = queries.openWork(hospitalId);
        boolean critical = w.criticalAlertsUnacked() > 0;
        if (critical) {
            r.paragraph(w.criticalAlertsUnacked() + " CRITICAL alert(s) UNACKNOWLEDGED — review before accepting handover",
                    PdfReport.F_ALERT);
        }

        int s = 0;
        r.ledgerSection(sec(++s), "Open work being handed over", critical);
        r.ledgerDataTable(new String[]{"Open work", "Count", "Status"}, new float[]{58, 15, 27}, List.of(
                openRow("Unacknowledged CRITICAL alerts", w.criticalAlertsUnacked(), "Act now", "Clear"),
                openRow("Sepsis bundles in progress", w.sepsisBundlesOpen(), "In progress", "None"),
                openRow("Active isolations", w.isolationsActive(), "Ongoing", "None"),
                openRow("Isolations awaiting a room", w.isolationsUnroomed(), "Attention", "None"),
                openRow("Unresolved hypoglycemia events", w.hypoUnresolved(), "Monitor", "None"),
                openRow("… of which recheck OVERDUE", w.hypoRecheckOverdue(), "Overdue", "None"),
                openRow("Open safety incidents", w.incidentsOpen(), "Attention", "None")),
                new boolean[]{false, true, false});

        r.ledgerSection(sec(++s), "Department state");
        r.paragraph("At generation time.", PdfReport.F_META);
        censusTiles(r, queries.censusByZone(hospitalId));
        Map<String, Long> acuityNow = queries.acuityNow(hospitalId);
        if (!acuityNow.isEmpty()) {
            r.subHeader("Acuity now");
            List<String[]> acuityRows = new ArrayList<>();
            for (String key : satsOrder(acuityNow)) {
                if (acuityNow.containsKey(key)) acuityRows.add(new String[]{pretty(key), grp(acuityNow.get(key))});
            }
            r.ledgerDataTable(new String[]{"Category", "In department"}, new float[]{60, 40}, acuityRows,
                    new boolean[]{false, true});
        }

        r.ledgerSection(sec(++s), "Activity during the shift window");
        ActivityStats stats = queries.activityStats(hospitalId, from, to);
        List<PdfReport.LedgerCell> activity = new ArrayList<>();
        kpiCell(activity, grp(stats.arrivals()), null, "Arrivals");
        kpiCell(activity, grp(stats.triaged()), null, "Triaged");
        kpiCell(activity, dec(stats.avgWaitMinutes()), "min", "Avg wait");
        r.ledgerKv(activity);
        Map<String, Long> disp = queries.dispositions(hospitalId, from, to);
        if (!disp.isEmpty()) {
            r.subHeader("Dispositions");
            dispositionTable(r, disp);
        }

        r.ledgerSection(sec(++s), "Rostered staff — " + p + " shift");
        List<Object[]> roster = queries.staffing(hospitalId, date, p);
        List<String[]> rows = new ArrayList<>();
        for (Object[] st : roster) {
            rows.add(new String[]{
                    str(st[0]), pretty(str(st[1])),
                    (str(st[2]) + " " + str(st[3])).trim() + (Boolean.TRUE.equals(st[4]) ? "  (SHIFT LEAD)" : "")});
        }
        r.ledgerDataTable(new String[]{"Zone", "Function", "Staff member"}, new float[]{20, 30, 50}, rows,
                new boolean[]{false, false, false});

        r.spacer(4f);
        r.paragraph("Handover is complete only when both leads have reviewed the open work above and signed.",
                PdfReport.F_BODY);
        r.ledgerSignatures("Outgoing shift lead", "Incoming shift lead");

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
        r.ledgerIdTable(List.of(
                PdfReport.lcell("Period", D_SHORT.format(from) + " → " + D_SHORT.format(to) + " " + to.getYear()),
                PdfReport.lcell("Days", (java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1) + " (inclusive)"),
                PdfReport.lcell("Requested by", requestedBy),
                PdfReport.lcell("Source", "Live clinical records")));

        int s = 0;
        ActivityStats stats = queries.activityStats(hospitalId, f, t);
        r.ledgerSection(sec(++s), "Period totals");
        List<PdfReport.LedgerCell> totalsKv = new ArrayList<>();
        kpiCell(totalsKv, grp(stats.arrivals()), null, "Arrivals");
        kpiCell(totalsKv, grp(stats.triaged()), null, "Triaged");
        kpiCell(totalsKv, dec(stats.avgWaitMinutes()), "min", "Avg wait");
        kpiCell(totalsKv, dec(stats.avgLosMinutes()), "min", "Avg length of stay");
        r.ledgerKv(totalsKv);

        Map<String, Long> cats = queries.categoryBreakdown(hospitalId, f, t);
        acuityMix(r, sec(++s), cats, stats.triaged());

        Map<String, Long> disp = queries.dispositions(hospitalId, f, t);
        if (!disp.isEmpty()) {
            r.ledgerSection(sec(++s), "Dispositions");
            dispositionTable(r, disp);
        }

        List<Object[]> trend = queries.dailyTrend(hospitalId, f, t);
        r.ledgerSection(sec(++s), "Daily breakdown");
        if (trend.size() >= 2) {
            double peak = 0, low = Double.MAX_VALUE;
            for (Object[] d : trend) {
                double v = asLong(d[1]);
                peak = Math.max(peak, v);
                low = Math.min(low, v);
            }
            r.paragraph(trend.size() + " days · peak " + Math.round(peak) + " · low " + Math.round(low)
                    + " arrivals/day", PdfReport.F_META);
        }
        List<String[]> rows = new ArrayList<>();
        for (Object[] d : trend) {
            rows.add(new String[]{
                    String.valueOf(d[0]), String.valueOf(d[1]), String.valueOf(d[2]),
                    String.valueOf(d[3]), String.valueOf(d[4]), String.valueOf(d[5]),
                    String.valueOf(d[6]), String.valueOf(d[7]), dec(asDouble(d[8]))});
        }
        // Period totals as the final row (the 1a table's dedicated totals row → a flat labelled row).
        rows.add(new String[]{
                "Period total", grp(stats.arrivals()),
                grp(satsCount(cats, "RED")), grp(satsCount(cats, "ORANGE")),
                grp(satsCount(cats, "YELLOW")), grp(satsCount(cats, "GREEN")),
                grp(dispCount(disp, "ADMIT")), grp(dispCount(disp, "LWBS", "LEFT")),
                dec(stats.avgWaitMinutes())});
        r.ledgerDataTable(
                new String[]{"Date", "Arrivals", "Red", "Orange", "Yellow", "Green", "Admitted", "LWBS", "Avg wait"},
                new float[]{16, 11, 9, 10, 10, 10, 12, 9, 13}, rows,
                new boolean[]{true, true, true, true, true, true, true, true, true});

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
        r.ledgerIdTable(List.of(
                PdfReport.lcell("Clinician", name + " (" + caller.getRole() + ")"),
                PdfReport.lcell("Period", from + " → " + to + " (inclusive)"),
                PdfReport.lcellFull("Source", "Live clinical records")));

        int s = 0;
        MyActivity a = queries.myActivity(caller.getId(), hospitalId, f, t);
        r.ledgerSection(sec(++s), "Workload");
        List<PdfReport.LedgerCell> workload = new ArrayList<>();
        kpiCell(workload, grp(a.primaryVisits()), null, "Patients (primary clinician)");
        kpiCell(workload, grp(a.notesAuthored()), null, "Clinical notes authored");
        kpiCell(workload, grp(a.prescriptions()), null, "Medications prescribed");
        r.ledgerKv(workload);

        r.ledgerSection(sec(++s), "Patients seen as primary clinician");
        List<Object[]> visitRows = queries.myVisitRows(caller.getId(), hospitalId, f, t, MY_VISITS_CAP);
        List<String[]> rows = new ArrayList<>();
        for (Object[] v : visitRows) {
            rows.add(new String[]{
                    str(v[0]), str(v[1]),
                    v[2] != null ? DT.format(((java.sql.Timestamp) v[2]).toInstant()) : "—",
                    str(v[3]), pretty(str(v[4]))});
        }
        r.ledgerDataTable(new String[]{"Visit", "Patient", "Arrival", "Category", "Disposition"},
                new float[]{22, 28, 20, 12, 18}, rows, new boolean[]{true, false, true, false, false});
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
        r.ledgerIdTable(List.of(
                PdfReport.lcell("Period", from + " → " + to + " (inclusive)"),
                PdfReport.lcell("Requested by", requestedBy),
                PdfReport.lcellFull("Source", "Scheduled daily quality snapshots")));

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
            int s = 0;
            QualityMetricSnapshot latest = snaps.get(snaps.size() - 1);
            r.ledgerSection(sec(++s), "Latest captured day");
            r.paragraph(String.valueOf(latest.getSnapshotDate()), PdfReport.F_META);
            List<PdfReport.LedgerCell> kpis = new ArrayList<>();
            kpiCell(kpis, n(latest.getTotalPatients()), null, "Patients");
            kpiCell(kpis, n(latest.getTotalAdmissions()), null, "Admissions");
            kpiCell(kpis, n(latest.getTotalLeftWithoutBeingSeen()), null, "LWBS");
            kpiCell(kpis, dec(latest.getAverageWaitTimeMinutes()), "min", "Avg wait");
            kpiCell(kpis, dec(latest.getAverageDoorToTriageMinutes()), "min", "Door → triage");
            kpiCell(kpis, dec(latest.getAverageTewsScore()), null, "Avg TEWS");
            r.ledgerKv(kpis);

            r.ledgerSection(sec(++s), "Daily snapshots");
            List<String[]> rows = new ArrayList<>();
            for (QualityMetricSnapshot snap : snaps) {
                rows.add(new String[]{
                        String.valueOf(snap.getSnapshotDate()), n(snap.getTotalPatients()),
                        n(snap.getRedPatients()), n(snap.getOrangePatients()),
                        n(snap.getTotalAdmissions()), n(snap.getTotalDeaths()),
                        n(snap.getTotalLeftWithoutBeingSeen()), dec(snap.getAverageWaitTimeMinutes()),
                        n(snap.getRetriageCount())});
            }
            r.ledgerDataTable(
                    new String[]{"Date", "Patients", "Red", "Orange", "Admitted", "Deaths", "LWBS", "Avg wait", "Re-triages"},
                    new float[]{15, 11, 8, 10, 12, 10, 9, 12, 13}, rows,
                    new boolean[]{true, true, true, true, true, true, true, true, true});
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

    /** The SATS acuity mix as a #1b bordered data table (category · meaning · count · share). */
    private void acuityMix(PdfReport r, String section, Map<String, Long> cats, long triaged) {
        long total = cats.values().stream().mapToLong(Long::longValue).sum();
        r.ledgerSection(section, "Triage acuity mix");
        if (total <= 0) {
            r.paragraph("No triage records in this window.", PdfReport.F_META);
            return;
        }
        r.paragraph(grp(triaged) + " triaged · SATS", PdfReport.F_META);
        List<String[]> rows = new ArrayList<>();
        for (String key : satsOrder(cats)) {
            long v = cats.getOrDefault(key, 0L);
            if (v <= 0) continue;
            rows.add(new String[]{pretty(key), satsMeaning(key), grp(v), pct(v, total)});
        }
        r.ledgerDataTable(new String[]{"Category", "Meaning", "Count", "Share"},
                new float[]{22, 34, 22, 22}, rows, new boolean[]{false, false, true, true});
    }

    /** Department census as a #1b bordered data table (per zone) plus a final in-department total row. */
    private void censusTiles(PdfReport r, Map<String, Long> census) {
        if (census.isEmpty()) {
            r.paragraph("No active visits right now.", PdfReport.F_META);
            return;
        }
        List<String[]> rows = new ArrayList<>();
        long total = 0;
        for (Map.Entry<String, Long> e : census.entrySet()) {
            rows.add(new String[]{pretty(e.getKey()), grp(e.getValue())});
            total += e.getValue();
        }
        rows.add(new String[]{"In department (total)", grp(total)});
        r.ledgerDataTable(new String[]{"Zone", "Patients"}, new float[]{60, 40}, rows,
                new boolean[]{false, true});
    }

    /** Dispositions as a #1b bordered data table (disposition · count · share), busiest first. */
    private void dispositionTable(PdfReport r, Map<String, Long> disp) {
        long total = disp.values().stream().mapToLong(Long::longValue).sum();
        List<String[]> rows = new ArrayList<>();
        disp.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> rows.add(new String[]{pretty(e.getKey()), grp(e.getValue()), pct(e.getValue(), total)}));
        r.ledgerDataTable(new String[]{"Disposition", "Count", "Share"},
                new float[]{56, 22, 22}, rows, new boolean[]{false, true, true});
    }

    /** One open-work row: label · count · a status word (the "present" word when > 0, else the "clear" word). */
    private static String[] openRow(String label, long value, String pillWhenPresent, String pillWhenZero) {
        return new String[]{label, grp(value), value > 0 ? pillWhenPresent : pillWhenZero};
    }

    /** SATS keys in clinical order (then anything unexpected, so nothing is dropped). */
    private static List<String> satsOrder(Map<String, Long> map) {
        List<String> order = new ArrayList<>(List.of("RED", "ORANGE", "YELLOW", "GREEN", "BLUE"));
        for (String k : map.keySet()) {
            if (!order.contains(k.toUpperCase())) order.add(k);
        }
        return order;
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
        // Footer confidentiality clause is kept SHORT (the ledger footer renders it
        // left-aligned as "CONFIDENTIAL — {clause}"; a long clause overruns the centred
        // attribution). The "generated from live clinical data" provenance lives in the
        // body footer note instead.
        return PdfReport.beginLedger(new PdfReport.Spec(
                title, docType, hospital.getName(), meta, requestedBy,
                "OPERATIONAL REPORT", eyebrow));
    }

    /** Two-digit #1b section number: 1 → "01". */
    private static String sec(int n) { return n < 10 ? "0" + n : String.valueOf(n); }

    /** Append a KPI as a monospace ledger key/value cell (folds the unit into the value). */
    private static void kpiCell(List<PdfReport.LedgerCell> cells, String value, String unit, String label) {
        cells.add(PdfReport.lcellMono(label, unit != null && !unit.isBlank() ? value + " " + unit : value));
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
