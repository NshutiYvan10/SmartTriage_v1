package com.smartTriage.smartTriage_server.module.lab.service;

import com.smartTriage.smartTriage_server.common.enums.LabOrderStatus;
import com.smartTriage.smartTriage_server.common.enums.LabPriority;
import com.smartTriage.smartTriage_server.common.report.PdfReport;
import com.smartTriage.smartTriage_server.module.lab.entity.LabOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.smartTriage.smartTriage_server.common.report.PdfReport.kv;

/**
 * Renders a LAB REPORTING PACK PDF — a workload + turnaround-time summary over a date window,
 * computed entirely from existing LabOrder timestamps (no new columns): volume by priority,
 * STAT turnaround compliance, mean turnaround, pending/critical/rejected counts, and the busiest
 * tests. Rendered through the shared {@link PdfReport} house style so it matches every other
 * SmartTriage export. Pure computation over the supplied list.
 */
@Slf4j
@Service
public class LabReportPdfService {

    private static final Set<LabOrderStatus> TERMINAL =
            Set.of(LabOrderStatus.RESULTED, LabOrderStatus.REJECTED, LabOrderStatus.CANCELLED);

    public String filename(LocalDate from, LocalDate to) {
        return ("lab-report_" + from + "_" + to + ".pdf").replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public byte[] render(String hospitalName, LocalDate from, LocalDate to,
                         List<LabOrder> orders, String exportedBy) {
        try {
            PdfReport r = PdfReport.begin(new PdfReport.Spec(
                    "LABORATORY REPORTING PACK",
                    "Laboratory Report",
                    hospitalName != null ? hospitalName : "Hospital",
                    List.of(),
                    exportedBy,
                    "laboratory report"));

            r.subjectHeadline("Workload & turnaround summary",
                    "Period: " + from + " to " + to + "  ·  " + orders.size() + " orders");

            r.sectionHeader("Volume");
            r.statTiles(buildVolume(orders));

            r.sectionHeader("Turnaround time");
            List<PdfReport.KeyVal> tat = buildTurnaround(orders);
            if (tat.isEmpty()) {
                r.narrative("No resulted orders with a recorded turnaround in this period.");
            } else {
                r.keyValues(tat);
            }

            r.sectionHeader("Pending & exceptions");
            r.keyValues(buildPending(orders));

            r.sectionHeader("Busiest tests");
            List<String> top = buildTopTests(orders);
            if (top.isEmpty()) {
                r.narrative("No orders in this period.");
            } else {
                r.bullets(top);
            }

            return r.finish();
        } catch (Exception e) {
            log.error("Failed to render lab report PDF: {}", e.getMessage(), e);
            throw new IllegalStateException("Could not generate lab report PDF", e);
        }
    }

    private List<PdfReport.KeyVal> buildVolume(List<LabOrder> orders) {
        long stat = orders.stream().filter(o -> o.getPriority() == LabPriority.STAT).count();
        long urgent = orders.stream().filter(o -> o.getPriority() == LabPriority.URGENT).count();
        long routine = orders.stream().filter(o -> o.getPriority() == LabPriority.ROUTINE).count();
        long resulted = orders.stream().filter(o -> o.getStatus() == LabOrderStatus.RESULTED).count();
        return List.of(
                kv("Total orders", String.valueOf(orders.size())),
                kv("STAT", String.valueOf(stat)),
                kv("Urgent", String.valueOf(urgent)),
                kv("Routine", String.valueOf(routine)),
                kv("Resulted", String.valueOf(resulted)));
    }

    private List<PdfReport.KeyVal> buildTurnaround(List<LabOrder> orders) {
        List<PdfReport.KeyVal> rows = new ArrayList<>();
        // Mean turnaround over resulted orders that recorded a turnaround.
        List<Integer> tats = orders.stream()
                .filter(o -> o.getTurnaroundMinutes() != null)
                .map(LabOrder::getTurnaroundMinutes).toList();
        if (!tats.isEmpty()) {
            double mean = tats.stream().mapToInt(Integer::intValue).average().orElse(0);
            rows.add(kv("Mean turnaround (min)", String.valueOf(Math.round(mean))));
            rows.add(kv("Resulted with turnaround recorded", String.valueOf(tats.size())));
        }
        // STAT compliance: of STAT orders with a turnaround, the share within the 30-min target.
        List<LabOrder> statWithTat = orders.stream()
                .filter(o -> o.getPriority() == LabPriority.STAT && o.getTurnaroundMinutes() != null).toList();
        if (!statWithTat.isEmpty()) {
            long within = statWithTat.stream()
                    .filter(o -> o.getTurnaroundMinutes() <= LabPriority.STAT.getTargetMinutes()).count();
            double pct = 100.0 * within / statWithTat.size();
            rows.add(kv("STAT within 30-min target",
                    String.format("%d/%d (%.0f%%)", within, statWithTat.size(), pct)));
        }
        return rows;
    }

    private List<PdfReport.KeyVal> buildPending(List<LabOrder> orders) {
        long pending = orders.stream().filter(o -> !TERMINAL.contains(o.getStatus())).count();
        long critical = orders.stream().filter(LabOrder::isCritical).count();
        long abnormal = orders.stream().filter(LabOrder::isAbnormal).count();
        long rejected = orders.stream().filter(o -> o.getStatus() == LabOrderStatus.REJECTED).count();
        long cancelled = orders.stream().filter(o -> o.getStatus() == LabOrderStatus.CANCELLED).count();
        return List.of(
                kv("Still pending (not resulted/rejected/cancelled)", String.valueOf(pending)),
                kv("Critical results", String.valueOf(critical)),
                kv("Abnormal results", String.valueOf(abnormal)),
                kv("Rejected specimens", String.valueOf(rejected)),
                kv("Cancelled", String.valueOf(cancelled)));
    }

    private List<String> buildTopTests(List<LabOrder> orders) {
        Map<String, Integer> byTest = new LinkedHashMap<>();
        for (LabOrder o : orders) {
            String t = o.getTestName() != null ? o.getTestName() : "(unnamed)";
            byTest.merge(t, 1, Integer::sum);
        }
        return byTest.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(10)
                .map(e -> e.getKey() + " — " + e.getValue())
                .toList();
    }
}
