package com.smartTriage.smartTriage_server.module.lab.service;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import com.smartTriage.smartTriage_server.common.enums.LabOrderStatus;
import com.smartTriage.smartTriage_server.common.enums.LabPriority;
import com.smartTriage.smartTriage_server.module.lab.entity.LabOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders a LAB REPORTING PACK PDF — a workload + turnaround-time summary over a date window,
 * computed entirely from existing LabOrder timestamps (no new columns): volume by priority,
 * STAT turnaround compliance, mean turnaround, pending/critical/rejected counts, and the busiest
 * tests. Mirrors {@code HandoverPdfService} (OpenPDF). Pure computation over the supplied list.
 */
@Slf4j
@Service
public class LabReportPdfService {

    private static final Set<LabOrderStatus> TERMINAL =
            Set.of(LabOrderStatus.RESULTED, LabOrderStatus.REJECTED, LabOrderStatus.CANCELLED);

    private static final Color NAVY = new Color(11, 74, 110);
    private static final Color GREY = new Color(110, 110, 110);

    private static final Font H_HOSPITAL = new Font(Font.HELVETICA, 18, Font.BOLD, NAVY);
    private static final Font H_TITLE = new Font(Font.HELVETICA, 13, Font.BOLD, Color.BLACK);
    private static final Font H_META = new Font(Font.HELVETICA, 8, Font.NORMAL, GREY);
    private static final Font H_SECTION = new Font(Font.HELVETICA, 11, Font.BOLD, NAVY);
    private static final Font H_BODY = new Font(Font.COURIER, 8, Font.NORMAL, Color.BLACK);

    public String filename(LocalDate from, LocalDate to) {
        return ("lab-report_" + from + "_" + to + ".pdf").replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public byte[] render(String hospitalName, LocalDate from, LocalDate to, List<LabOrder> orders) {
        Document doc = new Document(PageSize.A4, 42, 42, 60, 54);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new Footer());
            doc.open();

            doc.add(new Paragraph(hospitalName != null ? hospitalName : "Hospital", H_HOSPITAL));
            doc.add(rule());
            Paragraph title = new Paragraph("LABORATORY REPORTING PACK", H_TITLE);
            title.setSpacingBefore(8f);
            title.setSpacingAfter(2f);
            doc.add(title);
            doc.add(new Paragraph("Period: " + from + " to " + to + "   ·   " + orders.size() + " orders", H_META));
            doc.add(rule());

            addSection(doc, "Volume", buildVolume(orders));
            addSection(doc, "Turnaround time", buildTurnaround(orders));
            addSection(doc, "Pending & exceptions", buildPending(orders));
            addSection(doc, "Busiest tests", buildTopTests(orders));

            doc.close();
        } catch (Exception e) {
            log.error("Failed to render lab report PDF: {}", e.getMessage(), e);
            throw new IllegalStateException("Could not generate lab report PDF", e);
        }
        return out.toByteArray();
    }

    private String buildVolume(List<LabOrder> orders) {
        long stat = orders.stream().filter(o -> o.getPriority() == LabPriority.STAT).count();
        long urgent = orders.stream().filter(o -> o.getPriority() == LabPriority.URGENT).count();
        long routine = orders.stream().filter(o -> o.getPriority() == LabPriority.ROUTINE).count();
        long resulted = orders.stream().filter(o -> o.getStatus() == LabOrderStatus.RESULTED).count();
        StringBuilder sb = new StringBuilder();
        line(sb, "Total orders", orders.size());
        line(sb, "STAT", stat);
        line(sb, "Urgent", urgent);
        line(sb, "Routine", routine);
        line(sb, "Resulted", resulted);
        return sb.toString();
    }

    private String buildTurnaround(List<LabOrder> orders) {
        // Mean turnaround over resulted orders that recorded a turnaround.
        List<Integer> tats = orders.stream()
                .filter(o -> o.getTurnaroundMinutes() != null)
                .map(LabOrder::getTurnaroundMinutes).toList();
        StringBuilder sb = new StringBuilder();
        if (!tats.isEmpty()) {
            double mean = tats.stream().mapToInt(Integer::intValue).average().orElse(0);
            line(sb, "Mean turnaround (min)", Math.round(mean));
            line(sb, "Resulted with turnaround recorded", tats.size());
        } else {
            sb.append("No resulted orders with a recorded turnaround in this period.\n");
        }
        // STAT compliance: of STAT orders with a turnaround, the share within the 30-min target.
        List<LabOrder> statWithTat = orders.stream()
                .filter(o -> o.getPriority() == LabPriority.STAT && o.getTurnaroundMinutes() != null).toList();
        if (!statWithTat.isEmpty()) {
            long within = statWithTat.stream()
                    .filter(o -> o.getTurnaroundMinutes() <= LabPriority.STAT.getTargetMinutes()).count();
            double pct = 100.0 * within / statWithTat.size();
            line(sb, "STAT within 30-min target", String.format("%d/%d (%.0f%%)", within, statWithTat.size(), pct));
        }
        return sb.toString();
    }

    private String buildPending(List<LabOrder> orders) {
        long pending = orders.stream().filter(o -> !TERMINAL.contains(o.getStatus())).count();
        long critical = orders.stream().filter(LabOrder::isCritical).count();
        long abnormal = orders.stream().filter(LabOrder::isAbnormal).count();
        long rejected = orders.stream().filter(o -> o.getStatus() == LabOrderStatus.REJECTED).count();
        long cancelled = orders.stream().filter(o -> o.getStatus() == LabOrderStatus.CANCELLED).count();
        StringBuilder sb = new StringBuilder();
        line(sb, "Still pending (not resulted/rejected/cancelled)", pending);
        line(sb, "Critical results", critical);
        line(sb, "Abnormal results", abnormal);
        line(sb, "Rejected specimens", rejected);
        line(sb, "Cancelled", cancelled);
        return sb.toString();
    }

    private String buildTopTests(List<LabOrder> orders) {
        Map<String, Integer> byTest = new LinkedHashMap<>();
        for (LabOrder o : orders) {
            String t = o.getTestName() != null ? o.getTestName() : "(unnamed)";
            byTest.merge(t, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        byTest.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(10)
                .forEach(e -> line(sb, e.getKey(), e.getValue()));
        return sb.toString();
    }

    private static void line(StringBuilder sb, String label, Object value) {
        sb.append(label).append(": ").append(value).append('\n');
    }

    private void addSection(Document doc, String label, String content) throws Exception {
        if (content == null || content.isBlank()) return;
        Paragraph heading = new Paragraph(label, H_SECTION);
        heading.setSpacingBefore(11f);
        heading.setSpacingAfter(3f);
        doc.add(heading);
        for (String l : content.split("\n", -1)) {
            doc.add(new Paragraph(l.isEmpty() ? Chunk.NEWLINE.getContent() : l, H_BODY));
        }
    }

    private static Paragraph rule() {
        Paragraph p = new Paragraph("");
        p.setSpacingBefore(2f);
        p.add(new Chunk(new com.lowagie.text.pdf.draw.LineSeparator(0.6f, 100f, NAVY, Element.ALIGN_CENTER, -2)));
        p.setSpacingAfter(4f);
        return p;
    }

    private static final class Footer extends PdfPageEventHelper {
        private final Font f = new Font(Font.HELVETICA, 7, Font.ITALIC, GREY);
        @Override
        public void onEndPage(PdfWriter writer, Document doc) {
            PdfContentByte cb = writer.getDirectContent();
            Phrase p = new Phrase("CONFIDENTIAL — laboratory reporting pack · Page " + writer.getPageNumber(), f);
            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER, p,
                    (doc.left() + doc.right()) / 2, doc.bottom() - 20, 0);
        }
    }
}
