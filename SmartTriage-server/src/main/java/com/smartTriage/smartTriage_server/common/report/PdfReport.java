package com.smartTriage.smartTriage_server.common.report;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.Chunk;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfGState;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPCellEvent;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * PdfReport — the SmartTriage branded report kit. ONE house style for every
 * exportable PDF (PCR, handover/SBAR, MOH, lab pack, safety incident, the
 * operational catalog) so they all read as a polished, consistent,
 * professional deliverable rather than a bare printout.
 *
 * <p>What it standardises:
 * <ul>
 *   <li><b>Brand masthead</b> — the SmartTriage mascot in a tinted rounded
 *       box + the two-tone wordmark (Smart in ink, Triage in brand cyan) and
 *       tagline, with the report eyebrow/title/organisation on the right.</li>
 *   <li><b>Palette + typography</b> — a single cyan/slate scheme, the SATS
 *       clinical colors (red/orange/yellow/green) reserved for acuity, and a
 *       Helvetica type scale (title / section / label / body).</li>
 *   <li><b>Content helpers</b> — section headers, key/value tables, KPI and
 *       stat tiles, metric grids, a labelled meta strip, stacked acuity bars
 *       with legends, proportional bar lists, workload rows with status
 *       pills, chips, a vector trend chart, SBAR group headers, patient
 *       banners, alert banners/callouts, narrative panels (with **bold**
 *       inline emphasis), data tables (with header dots + totals rows), and
 *       signature blocks.</li>
 *   <li><b>Every-page chrome</b> — a slim running header on pages ≥ 2 and a
 *       footer carrying the confidentiality label, the attribution (who
 *       exported + when — so the artifact is traceable), and "Page X of Y".</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   PdfReport r = PdfReport.begin(new Spec("DAILY ED ACTIVITY", "Daily Activity",
 *           hospitalName, orgMetaLines, exportedByName, "Operational report",
 *           "Operational report"));
 *   r.sectionHeader("Activity at a glance");
 *   r.kpiTiles(List.of(PdfReport.kpi("148", null, "Arrivals")));
 *   byte[] pdf = r.finish();
 * </pre>
 * OpenPDF (com.lowagie). Must run inside the caller's load transaction if it
 * reads lazy associations.
 */
public final class PdfReport {

    // ── Brand palette (mirrors the FE cyan/slate design tokens) ──
    public static final Color BRAND = new Color(8, 145, 178);        // cyan-600 — primary
    public static final Color BRAND_DEEP = new Color(3, 105, 161);   // sky-700 — headings
    public static final Color INK = new Color(15, 23, 42);           // slate-900 — body
    public static final Color INK_SOFT = new Color(51, 65, 85);      // slate-700 — secondary body
    public static final Color MUTED = new Color(100, 116, 139);      // slate-500 — labels/meta
    public static final Color SLATE_400 = new Color(148, 163, 184);  // slate-400 — axis/neutral bars
    public static final Color HAIRLINE = new Color(203, 213, 225);   // slate-300 — rules/borders
    public static final Color HAIRLINE_SOFT = new Color(226, 232, 240); // slate-200 — soft borders
    public static final Color PANEL = new Color(248, 250, 252);      // slate-50 — panels/tiles
    public static final Color BAND = new Color(236, 254, 255);       // cyan-50 — masthead wash
    public static final Color BAND_BORDER = new Color(165, 243, 252);// cyan-200 — band borders
    public static final Color ACCENT = new Color(217, 119, 6);       // amber-600 — highlights
    public static final Color DANGER = new Color(190, 30, 45);       // red — alerts

    // ── SATS clinical acuity colors (reserved for triage semantics) ──
    public static final Color SATS_RED = new Color(220, 38, 38);     // red-600 — Immediate
    public static final Color SATS_ORANGE = new Color(234, 88, 12);  // orange-600 — Very urgent
    public static final Color SATS_YELLOW = new Color(202, 138, 4);  // yellow-600 — Urgent
    public static final Color SATS_GREEN = new Color(22, 163, 74);   // green-600 — Routine
    public static final Color SATS_BLUE = new Color(37, 99, 235);    // blue-600 — Dead on arrival

    // ── Semantic status tints (state, not brand) ──
    public static final Color AMBER_TEXT = new Color(180, 83, 9);    // amber-700
    public static final Color AMBER_BG = new Color(255, 251, 235);   // amber-50
    public static final Color AMBER_BORDER = new Color(252, 211, 77);// amber-300
    public static final Color GREEN_TEXT = new Color(21, 128, 61);   // green-700
    public static final Color GREEN_BG = new Color(220, 252, 231);   // green-100
    public static final Color GREEN_BORDER = new Color(134, 239, 172);// green-300
    public static final Color RED_BG = new Color(254, 242, 242);     // red-50
    public static final Color RED_BORDER = new Color(252, 165, 165); // red-300
    // Logo box tint: cyan wash pre-composited over white (flat — no PDF alpha games).
    public static final Color LOGO_TINT = new Color(236, 249, 252);
    public static final Color LOGO_BORDER = new Color(178, 240, 249);

    // ── Redesign #1a additive tokens (chips / callouts / allergy / alert severities) ──
    public static final Color HAIRLINE_FAINT = new Color(241, 245, 249);   // slate-100 — kv underlines
    // Status chips (bg / fg) — match the FE status semantics exactly.
    public static final Color CHIP_GREEN_BG = new Color(220, 252, 231);    // #dcfce7 administered/resulted/confirmed
    public static final Color CHIP_GREEN_FG = new Color(22, 101, 52);      // #166534
    public static final Color CHIP_SLATE_BG = new Color(226, 232, 240);    // #e2e8f0 ordered/pending
    public static final Color CHIP_SLATE_FG = new Color(51, 65, 85);       // #334155
    public static final Color CHIP_RED_BG = new Color(254, 226, 226);      // #fee2e2 critical
    public static final Color CHIP_RED_FG = new Color(153, 27, 27);        // #991b1b
    public static final Color CHIP_AMBER_BG = new Color(254, 243, 199);    // #fef3c7 acknowledgement pending
    public static final Color CHIP_AMBER_FG = new Color(146, 64, 14);      // #92400e
    public static final Color CHIP_ORANGE_BG = new Color(255, 237, 213);   // #ffedd5 worsening
    public static final Color CHIP_ORANGE_FG = new Color(154, 52, 18);     // #9a3412
    // Callout boxes.
    public static final Color CALLOUT_YELLOW_BG = new Color(254, 252, 232);     // #fefce8 chief complaint
    public static final Color CALLOUT_YELLOW_BORDER = new Color(253, 230, 138); // #fde68a
    public static final Color CALLOUT_AMBER_BG = new Color(255, 251, 235);      // #fffbeb red-flag
    public static final Color CALLOUT_AMBER_BORDER = new Color(254, 215, 170);  // #fed7aa
    public static final Color CALLOUT_AMBER_HEAD = new Color(146, 64, 14);      // #92400e
    // Allergy strip.
    public static final Color ALLERGY_BG = new Color(254, 242, 242);       // #fef2f2
    public static final Color ALLERGY_BORDER = new Color(254, 202, 202);   // #fecaca
    public static final Color ALLERGY_TEXT = new Color(153, 27, 27);       // #991b1b
    // Alert severity cards (bg / left-bar / heading).
    public static final Color ALERT_CRIT_BG = new Color(254, 242, 242);    // #fef2f2
    public static final Color ALERT_CRIT_BAR = new Color(220, 38, 38);     // #dc2626
    public static final Color ALERT_CRIT_HEAD = new Color(185, 28, 28);    // #b91c1c
    public static final Color ALERT_HIGH_BG = new Color(255, 247, 237);    // #fff7ed
    public static final Color ALERT_HIGH_BAR = new Color(234, 88, 12);     // #ea580c
    public static final Color ALERT_HIGH_HEAD = new Color(194, 65, 12);    // #c2410c
    public static final Color ALERT_MED_BG = new Color(255, 251, 235);     // #fffbeb
    public static final Color ALERT_MED_BAR = new Color(217, 119, 6);      // #d97706
    public static final Color ALERT_MED_HEAD = new Color(146, 64, 14);     // #92400e

    // Courier timestamp for line-item rows (design ts colour #94a3b8).
    static final Font F_MONO_TS = new Font(Font.COURIER, 7.2f, Font.NORMAL, SLATE_400);

    // ── Type scale (Helvetica) ──
    public static final Font F_WORDMARK = new Font(Font.HELVETICA, 17, Font.BOLD, BRAND_DEEP);
    public static final Font F_TAGLINE  = new Font(Font.HELVETICA, 7, Font.NORMAL, MUTED);
    public static final Font F_TITLE    = new Font(Font.HELVETICA, 14, Font.BOLD, INK);
    public static final Font F_ORG      = new Font(Font.HELVETICA, 10, Font.BOLD, BRAND_DEEP);
    public static final Font F_META     = new Font(Font.HELVETICA, 8, Font.NORMAL, MUTED);
    public static final Font F_SECTION  = new Font(Font.HELVETICA, 11, Font.BOLD, BRAND_DEEP);
    public static final Font F_LABEL    = new Font(Font.HELVETICA, 8, Font.BOLD, MUTED);
    public static final Font F_VALUE    = new Font(Font.HELVETICA, 9, Font.NORMAL, INK);
    public static final Font F_VALUE_BD = new Font(Font.HELVETICA, 9, Font.BOLD, INK);
    public static final Font F_BODY     = new Font(Font.HELVETICA, 9, Font.NORMAL, INK);
    public static final Font F_TILE_NUM = new Font(Font.HELVETICA, 12, Font.BOLD, INK);
    public static final Font F_TILE_LBL = new Font(Font.HELVETICA, 6.5f, Font.BOLD, MUTED);
    public static final Font F_ALERT    = new Font(Font.HELVETICA, 10, Font.BOLD, DANGER);

    // Redesign additions.
    static final Font F_WM_INK   = new Font(Font.HELVETICA, 14.5f, Font.BOLD, INK);
    static final Font F_WM_BRAND = new Font(Font.HELVETICA, 14.5f, Font.BOLD, BRAND);
    static final Font F_EYEBROW  = new Font(Font.HELVETICA, 6.8f, Font.BOLD, BRAND);
    static final Font F_KPI      = new Font(Font.HELVETICA, 19, Font.BOLD, INK);
    static final Font F_KPI_UNIT = new Font(Font.HELVETICA, 8.5f, Font.BOLD, MUTED);
    static final Font F_TH       = new Font(Font.HELVETICA, 6.8f, Font.BOLD, BRAND_DEEP);
    static final Font F_MS_LABEL = new Font(Font.HELVETICA, 6, Font.BOLD, MUTED);
    static final Font F_MS_VALUE = new Font(Font.HELVETICA, 8, Font.BOLD, INK);
    static final Font F_NOTE_IT  = new Font(Font.HELVETICA, 8, Font.ITALIC, MUTED);
    static final Font F_BODY_SOFT = new Font(Font.HELVETICA, 8.5f, Font.NORMAL, INK_SOFT);
    static final Font F_BODY_SOFT_BD = new Font(Font.HELVETICA, 8.5f, Font.BOLD, INK);

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm").withZone(ZoneId.of("Africa/Kigali"));

    // Cache the logo BYTES (not an Image): an OpenPDF Image carries per-document
    // state, so a shared instance would corrupt concurrent renders. We build a
    // fresh Image per report from these cached bytes. EMPTY = classpath miss.
    private static volatile byte[] cachedLogoBytes;
    private static final byte[] EMPTY = new byte[0];

    /** Report metadata driving the masthead + footer chrome. */
    public record Spec(
            String reportTitle,          // e.g. "PRE-HOSPITAL PATIENT CARE REPORT (PCR)"
            String reportKind,           // short footer label, e.g. "Patient Care Report"
            String orgName,              // owning hospital / organisation
            List<String> orgMeta,        // address / code / phone lines (may be empty)
            String exportedByName,       // who generated the export (attribution)
            String confidentiality,      // footer confidentiality clause
            String eyebrow               // small uppercase kicker above the title (null → reportKind)
    ) {
        /** Pre-redesign constructor — kept so existing call sites compile unchanged. */
        public Spec(String reportTitle, String reportKind, String orgName, List<String> orgMeta,
                    String exportedByName, String confidentiality) {
            this(reportTitle, reportKind, orgName, orgMeta, exportedByName, confidentiality, null);
        }
    }

    /** A label/value pair for key-value tables, tiles and strips. */
    public record KeyVal(String label, String value) {}

    public static KeyVal kv(String label, String value) { return new KeyVal(label, value); }

    /** A KPI tile: big value (+ optional small unit) over an uppercase label. */
    public record Kpi(String value, String unit, String label) {}

    public static Kpi kpi(String value, String unit, String label) { return new Kpi(value, unit, label); }

    /** Tone of a metric-grid cell: neutral, needs-attention (amber), or explicitly good (green). */
    public enum MetricTone { NEUTRAL, ATTENTION, GOOD }

    /** A metric-grid cell: value + label with a status tone. */
    public record Metric(String value, String label, MetricTone tone) {}

    /** One segment of a 100% stacked bar (weight is the share, label is drawn inside if it fits). */
    public record Segment(String label, double weight, Color color, boolean darkLabel) {}

    /** One legend entry under a stacked bar. */
    public record LegendItem(Color color, String name, String meaning, String value, String pct) {}

    /** One row of a proportional bar list (frac is 0..1 of the widest row). */
    public record BarRow(String label, double frac, Color color, String value, String pct) {}

    /** A small rounded chip: optional colored dot + label + bold value. */
    public record Chip(Color dot, String label, String value) {}

    /** Status tone for workload rows / pills. */
    public enum WorkTone { CRIT, OVER, WARN, INFO, OK }

    /** One handover workload row: label | count | status pill. */
    public record WorkRow(String label, String value, WorkTone tone, boolean sub, String pillText) {}

    // ── Redesign #1a records ──────────────────────────────────────────

    /** A SITUATION 2-up grid row (label/value); {@code full} spans both columns. */
    public record GridPair(String label, String value, boolean full) {}
    public static GridPair gp(String label, String value) { return new GridPair(label, value, false); }
    public static GridPair gpFull(String label, String value) { return new GridPair(label, value, true); }

    /**
     * A line-item row (investigations / medications / diagnosis): monospace
     * timestamp · bold label · muted type · status chip · right-aligned detail.
     * {@code type} and {@code detail} are optional; chip colours are explicit.
     */
    public record LineItem(String timestamp, String label, String type,
                           String status, Color statusBg, Color statusFg, String detail) {}

    /** Build a line item, auto-selecting the status-chip colour from the status text. */
    public static LineItem lineItem(String ts, String label, String type, String status, String detail) {
        Color[] c = chipColors(status);
        return new LineItem(ts, label, type, status, c[0], c[1], detail);
    }

    /** Map a status string to a [bg, fg] chip colour pair (green / slate / red). */
    public static Color[] chipColors(String status) {
        if (status == null) return new Color[]{CHIP_SLATE_BG, CHIP_SLATE_FG};
        String s = status.toUpperCase();
        if (s.contains("CRITICAL") || s.contains("POSITIVE") || s.contains("ABNORMAL"))
            return new Color[]{CHIP_RED_BG, CHIP_RED_FG};
        if (s.contains("ADMINISTERED") || s.contains("RESULTED") || s.contains("CONFIRMED")
                || s.contains("COMPLETED") || s.contains("GIVEN") || s.contains("DONE"))
            return new Color[]{CHIP_GREEN_BG, CHIP_GREEN_FG};
        return new Color[]{CHIP_SLATE_BG, CHIP_SLATE_FG};
    }

    /** An ASSESSMENT card (fast-track / sepsis screening): heading + inline chip + body lines. */
    public record Card(String heading, Color headingColor, String chip, Color chipBg, Color chipFg,
                       Color bg, Color border, List<String> body) {}

    /** A red-flag row inside the amber red-flag callout: text + severity chip + muted trailing. */
    public record RedFlag(String text, String chip, Color chipBg, Color chipFg, String trailing) {}

    /** A dot-marker timeline row: coloured dot + bold label + value + muted interval note. */
    public record TimelineRow(String label, String value, String note, Color dot) {}

    /** One alert inside a severity group: bold lead + body + optional escalation tag. */
    public record AlertItem(String lead, String body, String escTag) {}
    /** A severity group of alerts: coloured heading + count + left-bordered cards. */
    public record AlertGroup(String title, Color headColor, Color cardBg, Color bar, List<AlertItem> items) {}

    /** Callout-box variant: pale-yellow chief complaint, amber red-flag, or neutral panel. */
    public enum CalloutVariant { CHIEF, REDFLAG, NEUTRAL }

    /** A plain #1a stat/vitals tile: big value (optionally semantic-coloured) over a muted label. */
    public record StatTile(String value, String label, Color valueColor) {}
    public static StatTile stat(String value, String label) { return new StatTile(value, label, INK); }
    public static StatTile stat(String value, String label, Color valueColor) {
        return new StatTile(value, label, valueColor != null ? valueColor : INK);
    }

    private final Document doc;
    private final ByteArrayOutputStream out;

    private PdfReport(Document doc, ByteArrayOutputStream out) {
        this.doc = doc;
        this.out = out;
    }

    /** Open a branded A4 report and lay down the masthead. */
    public static PdfReport begin(Spec spec) {
        Document doc = new Document(PageSize.A4, 42, 42, 44, 56);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            // ONE generation instant for the whole document — reused by the masthead AND the
            // every-page footer so a multi-page report never shows drifting timestamps.
            Instant generatedAt = Instant.now();
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new Chrome(spec, generatedAt));
            doc.open();
            PdfReport r = new PdfReport(doc, out);
            r.masthead(spec);
            return r;
        } catch (Exception e) {
            throw new IllegalStateException("Could not start PDF report", e);
        }
    }

    // ── Masthead (page 1) ────────────────────────────────────────────
    private void masthead(Spec spec) throws Exception {
        PdfPTable head = new PdfPTable(new float[]{55, 45});
        head.setWidthPercentage(100);
        head.getDefaultCell().setBorder(Rectangle.NO_BORDER);

        // Left: mascot in a tinted rounded box + two-tone wordmark + tagline.
        PdfPTable left = new PdfPTable(new float[]{34, 8, 158});
        left.setTotalWidth(200);
        left.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        PdfPCell logoCell = new PdfPCell();
        logoCell.setBorder(Rectangle.NO_BORDER);
        logoCell.setFixedHeight(34f);
        logoCell.setCellEvent(new RoundBox(new BoxStyle().fill(LOGO_TINT).border(LOGO_BORDER, 0.9f).radius(8f)));
        logoCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        logoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        logoCell.setPadding(3f);
        Image logo = logo();
        if (logo != null) {
            logo.scaleToFit(26, 26);
            logo.setAlignment(Image.ALIGN_CENTER);
            logoCell.addElement(logo);
        }
        left.addCell(logoCell);
        PdfPCell gap = new PdfPCell(new Phrase(""));
        gap.setBorder(Rectangle.NO_BORDER);
        left.addCell(gap);
        PdfPCell mark = new PdfPCell();
        mark.setBorder(Rectangle.NO_BORDER);
        mark.setVerticalAlignment(Element.ALIGN_MIDDLE);
        Paragraph wm = new Paragraph();
        wm.add(new Chunk("Smart", F_WM_INK));
        wm.add(new Chunk("Triage", F_WM_BRAND));
        wm.setLeading(14f);
        mark.addElement(wm);
        mark.addElement(new Paragraph("Healthcare Platform", F_TAGLINE));
        left.addCell(mark);
        PdfPCell leftWrap = new PdfPCell(left);
        leftWrap.setBorder(Rectangle.NO_BORDER);
        leftWrap.setVerticalAlignment(Element.ALIGN_MIDDLE);
        head.addCell(leftWrap);

        // Right: eyebrow + report title + org + meta lines, right-aligned.
        PdfPCell right = new PdfPCell();
        right.setBorder(Rectangle.NO_BORDER);
        right.setHorizontalAlignment(Element.ALIGN_RIGHT);
        String eyebrow = spec.eyebrow() != null && !spec.eyebrow().isBlank()
                ? spec.eyebrow() : spec.reportKind();
        if (eyebrow != null && !eyebrow.isBlank()) {
            Chunk ec = new Chunk(eyebrow.toUpperCase(), F_EYEBROW);
            ec.setCharacterSpacing(1.0f);
            Paragraph ep = new Paragraph(ec);
            ep.setAlignment(Element.ALIGN_RIGHT);
            ep.setLeading(9f);
            right.addElement(ep);
        }
        Paragraph t = new Paragraph(spec.reportTitle(), F_TITLE);
        t.setAlignment(Element.ALIGN_RIGHT);
        t.setLeading(15f);
        right.addElement(t);
        if (spec.orgName() != null && !spec.orgName().isBlank()) {
            Paragraph o = new Paragraph(spec.orgName(), F_ORG);
            o.setAlignment(Element.ALIGN_RIGHT);
            right.addElement(o);
        }
        if (spec.orgMeta() != null) {
            for (String line : spec.orgMeta()) {
                if (line != null && !line.isBlank()) {
                    Paragraph m = new Paragraph(line, F_META);
                    m.setAlignment(Element.ALIGN_RIGHT);
                    right.addElement(m);
                }
            }
        }
        head.addCell(right);
        doc.add(head);

        // Brand rule. (Attribution lives in the every-page footer + meta strips.)
        doc.add(accentRule(2.0f, BRAND));
        spacer(4f);
    }

    // ── The shared rounded-box engine ────────────────────────────────

    /** Style for a rounded box drawn behind a cell: fill, border, radius, accents. */
    static final class BoxStyle {
        Color fill;
        Color borderColor;
        float borderWidth;
        float radius = 6f;
        Color topAccent;
        float topAccentH;
        Color leftStripe;
        float leftStripeW;
        Color dot;               // small circle at the left padding (chips)
        boolean underlay;        // draw on BASECANVAS — for CONTAINERS holding nested boxed cells
        BoxStyle fill(Color c) { this.fill = c; return this; }
        BoxStyle border(Color c, float w) { this.borderColor = c; this.borderWidth = w; return this; }
        BoxStyle radius(float r) { this.radius = r; return this; }
        BoxStyle topAccent(Color c, float h) { this.topAccent = c; this.topAccentH = h; return this; }
        BoxStyle leftStripe(Color c, float w) { this.leftStripe = c; this.leftStripeW = w; return this; }
        BoxStyle dot(Color c) { this.dot = c; return this; }
        BoxStyle underlay() { this.underlay = true; return this; }
    }

    /**
     * Draws a rounded box (fill + border + optional top-accent bar / left stripe /
     * chip dot) behind a borderless cell. This one event powers every tile, pill,
     * panel, chip and banner in the kit.
     */
    private static final class RoundBox implements PdfPCellEvent {
        private final BoxStyle s;
        RoundBox(BoxStyle s) { this.s = s; }

        @Override
        public void cellLayout(PdfPCell cell, Rectangle pos, PdfContentByte[] canvases) {
            // Inner cells of nested tables paint their decorations BEFORE the outer
            // container's event fires — so a container drawn on BACKGROUNDCANVAS would
            // cover its children. Containers therefore draw one layer lower (BASECANVAS).
            PdfContentByte bg = canvases[s.underlay ? PdfPTable.BASECANVAS : PdfPTable.BACKGROUNDCANVAS];
            float inset = s.borderWidth > 0 ? s.borderWidth / 2f : 0f;
            float x = pos.getLeft() + inset, y = pos.getBottom() + inset;
            float w = pos.getWidth() - 2 * inset, h = pos.getHeight() - 2 * inset;
            float r = Math.min(s.radius, Math.min(w, h) / 2f);
            bg.saveState();
            if (s.fill != null) {
                bg.setColorFill(s.fill);
                bg.roundRectangle(x, y, w, h, r);
                bg.fill();
            }
            if (s.topAccent != null && s.topAccentH > 0) {
                bg.roundRectangle(x, y, w, h, r);
                bg.clip();
                bg.newPath();
                bg.setColorFill(s.topAccent);
                bg.rectangle(x, y + h - s.topAccentH, w, s.topAccentH);
                bg.fill();
            }
            bg.restoreState();
            bg.saveState();
            if (s.leftStripe != null && s.leftStripeW > 0) {
                bg.roundRectangle(x, y, w, h, r);
                bg.clip();
                bg.newPath();
                bg.setColorFill(s.leftStripe);
                bg.rectangle(x, y, s.leftStripeW, h);
                bg.fill();
            }
            bg.restoreState();
            if (s.borderColor != null && s.borderWidth > 0) {
                bg.saveState();
                bg.setColorStroke(s.borderColor);
                bg.setLineWidth(s.borderWidth);
                bg.roundRectangle(x, y, w, h, r);
                bg.stroke();
                bg.restoreState();
            }
            if (s.dot != null) {
                bg.saveState();
                bg.setColorFill(s.dot);
                bg.circle(pos.getLeft() + 10f, (pos.getBottom() + pos.getTop()) / 2f, 2.6f);
                bg.fill();
                bg.restoreState();
            }
        }
    }

    private static PdfPCell boxed(BoxStyle style) {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        c.setCellEvent(new RoundBox(style));
        return c;
    }

    /** A borderless spacer cell. */
    private static PdfPCell gapCell() {
        PdfPCell g = new PdfPCell(new Phrase(""));
        g.setBorder(Rectangle.NO_BORDER);
        return g;
    }

    /** Widths for n content columns separated by gutter columns. */
    private static float[] gutteredWidths(int n, float content, float gutter) {
        float[] w = new float[n * 2 - 1];
        for (int i = 0; i < w.length; i++) w[i] = (i % 2 == 0) ? content : gutter;
        return w;
    }

    // ── Content helpers ──────────────────────────────────────────────

    /** Section heading — brand accent bar + label + hairline underline. */
    public void sectionHeader(String label) {
        sectionHeader(label, null);
    }

    /** Section heading with an optional right-aligned aside (e.g. "142 triaged · SATS"). */
    public void sectionHeader(String label, String aside) {
        try {
            boolean hasAside = aside != null && !aside.isBlank();
            PdfPTable t = new PdfPTable(hasAside ? new float[]{1.5f, 68.5f, 30f} : new float[]{1.5f, 98.5f});
            t.setWidthPercentage(100);
            t.setSpacingBefore(11f);
            t.setSpacingAfter(2f);
            PdfPCell bar = new PdfPCell();
            bar.setBorder(Rectangle.NO_BORDER);
            bar.setBackgroundColor(BRAND);
            bar.setFixedHeight(13f);
            t.addCell(bar);
            PdfPCell lbl = new PdfPCell(new Phrase(" " + label.toUpperCase(), F_SECTION));
            lbl.setBorder(Rectangle.BOTTOM);
            lbl.setBorderColor(HAIRLINE);
            lbl.setBorderWidthBottom(0.8f);
            lbl.setPaddingBottom(3f);
            lbl.setVerticalAlignment(Element.ALIGN_BOTTOM);
            t.addCell(lbl);
            if (hasAside) {
                PdfPCell a = new PdfPCell(new Phrase(aside, F_META));
                a.setBorder(Rectangle.BOTTOM);
                a.setBorderColor(HAIRLINE);
                a.setBorderWidthBottom(0.8f);
                a.setPaddingBottom(3f);
                a.setHorizontalAlignment(Element.ALIGN_RIGHT);
                a.setVerticalAlignment(Element.ALIGN_BOTTOM);
                t.addCell(a);
            }
            doc.add(t);
        } catch (Exception e) {
            throw new IllegalStateException("PDF sectionHeader failed", e);
        }
    }

    /** Small uppercase sub-section header (between a section and its content). */
    public void subHeader(String label) {
        try {
            Chunk c = new Chunk(label.toUpperCase(), F_MS_LABEL);
            c.setCharacterSpacing(0.6f);
            Paragraph p = new Paragraph(c);
            p.setSpacingBefore(7f);
            p.setSpacingAfter(2.5f);
            doc.add(p);
        } catch (Exception e) {
            throw new IllegalStateException("PDF subHeader failed", e);
        }
    }

    /** Two-column key/value table. Blank values are dropped so it never shows "—" noise. */
    public void keyValues(List<KeyVal> pairs) {
        List<KeyVal> shown = pairs.stream()
                .filter(p -> p.value() != null && !p.value().isBlank())
                .toList();
        if (shown.isEmpty()) return;
        try {
            PdfPTable t = new PdfPTable(new float[]{30, 70});
            t.setWidthPercentage(100);
            t.setSpacingBefore(3f);
            for (KeyVal p : shown) {
                t.addCell(kvCell(p.label(), F_LABEL, Element.ALIGN_LEFT));
                t.addCell(kvCell(p.value(), F_VALUE, Element.ALIGN_LEFT));
            }
            doc.add(t);
        } catch (Exception e) {
            throw new IllegalStateException("PDF keyValues failed", e);
        }
    }

    private static PdfPCell kvCell(String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(HAIRLINE_SOFT);
        c.setBorderWidthBottom(0.4f);
        c.setHorizontalAlignment(align);
        c.setPadding(4f);
        c.setPaddingLeft(6f);
        return c;
    }

    /**
     * The labelled report-parameter strip under the masthead: a bordered grid of
     * small uppercase labels over values (report date, scope, requested by, …).
     */
    public void metaStrip(List<KeyVal> items) {
        List<KeyVal> shown = items.stream()
                .filter(p -> p.value() != null && !p.value().isBlank())
                .toList();
        if (shown.isEmpty()) return;
        try {
            int perRow = Math.min(shown.size(), 5);
            PdfPTable inner = new PdfPTable(perRow);
            inner.setWidthPercentage(100);
            int i = 0;
            for (KeyVal p : shown) {
                PdfPCell c = new PdfPCell();
                c.setBorder(Rectangle.NO_BORDER);
                boolean lastInRow = (i % perRow) == perRow - 1 || i == shown.size() - 1;
                if (!lastInRow) {
                    c.setBorder(Rectangle.RIGHT);
                    c.setBorderColor(HAIRLINE_SOFT);
                    c.setBorderWidthRight(0.7f);
                }
                if (i >= perRow) {
                    c.setBorder(c.getBorder() | Rectangle.TOP);
                    c.setBorderColor(HAIRLINE_SOFT);
                    c.setBorderWidthTop(0.7f);
                }
                c.setPadding(6f);
                c.setPaddingLeft(9f);
                Chunk lc = new Chunk(p.label().toUpperCase(), F_MS_LABEL);
                lc.setCharacterSpacing(0.5f);
                Paragraph lp = new Paragraph(lc);
                lp.setLeading(7f);
                c.addElement(lp);
                Paragraph vp = new Paragraph(p.value(), F_MS_VALUE);
                vp.setLeading(10f);
                vp.setSpacingBefore(1.5f);
                c.addElement(vp);
                inner.addCell(c);
                i++;
            }
            // Pad the last row so cells keep uniform width.
            int rem = shown.size() % perRow;
            if (rem != 0) for (int k = rem; k < perRow; k++) inner.addCell(gapCell());

            PdfPCell wrap = boxed(new BoxStyle().fill(Color.WHITE).border(HAIRLINE, 0.9f).radius(6f));
            wrap.setPadding(1.5f);
            wrap.addElement(inner);
            PdfPTable outer = new PdfPTable(1);
            outer.setWidthPercentage(100);
            outer.setSpacingBefore(4f);
            outer.addCell(wrap);
            doc.add(outer);
        } catch (Exception e) {
            throw new IllegalStateException("PDF metaStrip failed", e);
        }
    }

    /** KPI hero tiles (up to 4 per row): big value + optional unit + uppercase label. */
    public void kpiTiles(List<Kpi> tiles) {
        kpiTiles(tiles, 4);
    }

    /** KPI hero tiles with an explicit per-row count. */
    public void kpiTiles(List<Kpi> tiles, int perRow) {
        List<Kpi> shown = tiles.stream()
                .filter(k -> k.value() != null && !k.value().isBlank())
                .toList();
        if (shown.isEmpty()) return;
        renderTileRows(shown, Math.min(perRow, Math.max(1, shown.size())), false);
    }

    /**
     * A row of stat tiles (label + value) — for vitals / headline numbers.
     * Restyled onto the shared rounded-tile engine; same signature as ever.
     */
    public void statTiles(List<KeyVal> tiles) {
        List<Kpi> shown = tiles.stream()
                .filter(p -> p.value() != null && !p.value().isBlank())
                .map(p -> new Kpi(p.value(), null, p.label()))
                .toList();
        if (shown.isEmpty()) return;
        renderTileRows(shown, Math.min(shown.size(), 6), true);
    }

    private void renderTileRows(List<Kpi> tiles, int perRow, boolean compact) {
        try {
            PdfPTable t = new PdfPTable(gutteredWidths(perRow, 10f, 0.7f));
            t.setWidthPercentage(100);
            t.setSpacingBefore(4f);
            int i = 0;
            for (Kpi k : tiles) {
                if (i > 0 && i % perRow == 0) {
                    // vertical gap row between wrapped tile rows
                    PdfPCell g = gapCell();
                    g.setColspan(perRow * 2 - 1);
                    g.setFixedHeight(5f);
                    t.addCell(g);
                }
                PdfPCell cell = boxed(new BoxStyle()
                        .fill(PANEL).border(HAIRLINE, 0.8f).radius(6.5f)
                        .topAccent(BRAND, 2.2f));
                cell.setPadding(compact ? 6f : 8f);
                cell.setPaddingLeft(compact ? 6f : 10f);
                Paragraph num = new Paragraph();
                num.add(new Chunk(k.value(), compact ? F_TILE_NUM : F_KPI));
                if (k.unit() != null && !k.unit().isBlank()) {
                    num.add(new Chunk(" " + k.unit(), F_KPI_UNIT));
                }
                num.setLeading(compact ? 13f : 19f);
                num.setAlignment(compact ? Element.ALIGN_CENTER : Element.ALIGN_LEFT);
                cell.addElement(num);
                Chunk lc = new Chunk(k.label().toUpperCase(), F_TILE_LBL);
                lc.setCharacterSpacing(0.5f);
                Paragraph lbl = new Paragraph(lc);
                lbl.setLeading(8f);
                lbl.setSpacingBefore(2.5f);
                lbl.setAlignment(compact ? Element.ALIGN_CENTER : Element.ALIGN_LEFT);
                cell.addElement(lbl);
                t.addCell(cell);
                if (i % perRow != perRow - 1 && i != tiles.size() - 1) t.addCell(gapCell());
                i++;
            }
            // pad the final row
            int rem = tiles.size() % perRow;
            if (rem != 0) {
                for (int k = rem; k < perRow; k++) {
                    t.addCell(gapCell());
                    if (k < perRow - 1) t.addCell(gapCell());
                }
            }
            doc.add(t);
        } catch (Exception e) {
            throw new IllegalStateException("PDF tiles failed", e);
        }
    }

    /** Census tiles: one small tile per zone + a highlighted total tile. */
    public void censusTiles(List<KeyVal> zones, KeyVal total) {
        List<KeyVal> shown = new ArrayList<>(zones.stream()
                .filter(p -> p.value() != null && !p.value().isBlank())
                .toList());
        if (total != null && total.value() != null && !total.value().isBlank()) shown.add(total);
        if (shown.isEmpty()) return;
        try {
            int n = shown.size();
            PdfPTable t = new PdfPTable(gutteredWidths(n, 10f, 0.7f));
            t.setWidthPercentage(100);
            t.setSpacingBefore(4f);
            for (int i = 0; i < n; i++) {
                KeyVal z = shown.get(i);
                boolean isTotal = total != null && i == n - 1;
                PdfPCell cell = boxed(isTotal
                        ? new BoxStyle().fill(BAND).border(BRAND, 0.9f).radius(6f)
                        : new BoxStyle().fill(PANEL).border(HAIRLINE_SOFT, 0.8f).radius(6f));
                cell.setPadding(6f);
                cell.setPaddingLeft(9f);
                Paragraph num = new Paragraph(z.value(),
                        isTotal ? new Font(Font.HELVETICA, 13, Font.BOLD, BRAND_DEEP)
                                : new Font(Font.HELVETICA, 13, Font.BOLD, INK));
                num.setLeading(13f);
                cell.addElement(num);
                Chunk lc = new Chunk(z.label().toUpperCase(), F_TILE_LBL);
                lc.setCharacterSpacing(0.4f);
                Paragraph lbl = new Paragraph(lc);
                lbl.setLeading(8f);
                lbl.setSpacingBefore(2f);
                cell.addElement(lbl);
                t.addCell(cell);
                if (i < n - 1) t.addCell(gapCell());
            }
            doc.add(t);
        } catch (Exception e) {
            throw new IllegalStateException("PDF censusTiles failed", e);
        }
    }

    /** A 2-column metric grid with per-cell status tones (amber stripe / green). */
    public void metricGrid(List<Metric> metrics) {
        List<Metric> shown = metrics.stream()
                .filter(m -> m.value() != null && !m.value().isBlank())
                .toList();
        if (shown.isEmpty()) return;
        try {
            int perRow = 2;
            PdfPTable t = new PdfPTable(gutteredWidths(perRow, 10f, 0.5f));
            t.setWidthPercentage(100);
            t.setSpacingBefore(4f);
            int i = 0;
            for (Metric m : shown) {
                if (i > 0 && i % perRow == 0) {
                    PdfPCell g = gapCell();
                    g.setColspan(perRow * 2 - 1);
                    g.setFixedHeight(4.5f);
                    t.addCell(g);
                }
                BoxStyle style = new BoxStyle().fill(Color.WHITE).border(HAIRLINE_SOFT, 0.8f).radius(6f);
                Color numColor = INK;
                if (m.tone() == MetricTone.ATTENTION) style.leftStripe(ACCENT, 2.2f);
                if (m.tone() == MetricTone.GOOD) { style.leftStripe(SATS_GREEN, 2.2f); numColor = SATS_GREEN; }
                PdfPCell cell = boxed(style);
                cell.setPadding(6.5f);
                cell.setPaddingLeft(10f);
                Paragraph num = new Paragraph(m.value(), new Font(Font.HELVETICA, 14, Font.BOLD, numColor));
                num.setLeading(14f);
                cell.addElement(num);
                Chunk lc = new Chunk(m.label().toUpperCase(), F_TILE_LBL);
                lc.setCharacterSpacing(0.4f);
                Paragraph lbl = new Paragraph(lc);
                lbl.setLeading(8f);
                lbl.setSpacingBefore(2.5f);
                cell.addElement(lbl);
                t.addCell(cell);
                if (i % perRow != perRow - 1 && i != shown.size() - 1) t.addCell(gapCell());
                i++;
            }
            int rem = shown.size() % perRow;
            if (rem != 0) {
                for (int k = rem; k < perRow; k++) {
                    t.addCell(gapCell());
                    if (k < perRow - 1) t.addCell(gapCell());
                }
            }
            doc.add(t);
        } catch (Exception e) {
            throw new IllegalStateException("PDF metricGrid failed", e);
        }
    }

    /** 100% stacked bar (e.g. the SATS acuity mix) with inline segment counts. */
    public void stackedBar(List<Segment> segments) {
        List<Segment> shown = segments.stream().filter(s -> s.weight() > 0).toList();
        double total = shown.stream().mapToDouble(Segment::weight).sum();
        if (shown.isEmpty() || total <= 0) return;
        try {
            PdfPCell cell = new PdfPCell();
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setFixedHeight(22f);
            cell.setCellEvent((c, pos, canvases) -> {
                PdfContentByte bg = canvases[PdfPTable.BACKGROUNDCANVAS];
                PdfContentByte txt = canvases[PdfPTable.TEXTCANVAS];
                float x = pos.getLeft(), y = pos.getBottom() + 1f;
                float w = pos.getWidth(), h = pos.getHeight() - 2f;
                float r = 5f;
                bg.saveState();
                bg.roundRectangle(x, y, w, h, r);
                bg.clip();
                bg.newPath();
                float cx = x;
                for (Segment s : shown) {
                    float sw = (float) (w * (s.weight() / total));
                    bg.setColorFill(s.color());
                    bg.rectangle(cx, y, sw + 0.5f, h);
                    bg.fill();
                    cx += sw;
                }
                bg.restoreState();
                // border on top of the fills
                bg.saveState();
                bg.setColorStroke(new Color(231, 232, 234));
                bg.setLineWidth(0.8f);
                bg.roundRectangle(x, y, w, h, r);
                bg.stroke();
                bg.restoreState();
                // segment labels, centered, only when they fit
                float lx = x;
                for (Segment s : shown) {
                    float sw = (float) (w * (s.weight() / total));
                    String label = s.label();
                    if (label != null && !label.isBlank() && sw > label.length() * 5f + 8f) {
                        Font f = new Font(Font.HELVETICA, 8, Font.BOLD, s.darkLabel() ? INK : Color.WHITE);
                        ColumnText.showTextAligned(txt, Element.ALIGN_CENTER,
                                new Phrase(label, f), lx + sw / 2f, y + h / 2f - 2.8f, 0);
                    }
                    lx += sw;
                }
            });
            PdfPTable t = new PdfPTable(1);
            t.setWidthPercentage(100);
            t.setSpacingBefore(4f);
            t.addCell(cell);
            doc.add(t);
        } catch (Exception e) {
            throw new IllegalStateException("PDF stackedBar failed", e);
        }
    }

    /** Legend row under a stacked bar: color chip, name, meaning, value + pct. */
    public void legend(List<LegendItem> items) {
        if (items == null || items.isEmpty()) return;
        try {
            int n = items.size();
            PdfPTable t = new PdfPTable(gutteredWidths(n, 10f, 0.6f));
            t.setWidthPercentage(100);
            t.setSpacingBefore(6f);
            for (int i = 0; i < n; i++) {
                LegendItem it = items.get(i);
                PdfPTable inner = new PdfPTable(new float[]{13, 87});
                inner.setWidthPercentage(100);
                inner.getDefaultCell().setBorder(Rectangle.NO_BORDER);
                PdfPCell dot = new PdfPCell();
                dot.setBorder(Rectangle.NO_BORDER);
                dot.setCellEvent((c, pos, canvases) -> {
                    PdfContentByte bg = canvases[PdfPTable.BACKGROUNDCANVAS];
                    bg.saveState();
                    bg.setColorFill(it.color());
                    bg.roundRectangle(pos.getLeft() + 1f, pos.getTop() - 9.5f, 7.5f, 7.5f, 2f);
                    bg.fill();
                    bg.restoreState();
                });
                inner.addCell(dot);
                PdfPCell body = new PdfPCell();
                body.setBorder(Rectangle.NO_BORDER);
                Paragraph name = new Paragraph(it.name(), new Font(Font.HELVETICA, 8.5f, Font.BOLD, INK));
                name.setLeading(9f);
                body.addElement(name);
                if (it.meaning() != null && !it.meaning().isBlank()) {
                    Chunk mc = new Chunk(it.meaning().toUpperCase(),
                            new Font(Font.HELVETICA, 5.6f, Font.BOLD, MUTED));
                    mc.setCharacterSpacing(0.4f);
                    Paragraph mean = new Paragraph(mc);
                    mean.setLeading(7f);
                    body.addElement(mean);
                }
                Paragraph val = new Paragraph();
                val.add(new Chunk(it.value(), new Font(Font.HELVETICA, 9, Font.BOLD, INK)));
                if (it.pct() != null && !it.pct().isBlank()) {
                    val.add(new Chunk("  " + it.pct(), new Font(Font.HELVETICA, 6.8f, Font.BOLD, MUTED)));
                }
                val.setLeading(10f);
                val.setSpacingBefore(2f);
                body.addElement(val);
                inner.addCell(body);
                PdfPCell wrap = new PdfPCell(inner);
                wrap.setBorder(Rectangle.NO_BORDER);
                t.addCell(wrap);
                if (i < n - 1) t.addCell(gapCell());
            }
            doc.add(t);
        } catch (Exception e) {
            throw new IllegalStateException("PDF legend failed", e);
        }
    }

    /** Proportional horizontal bar list (label | track+fill | value + pct). */
    public void barList(List<BarRow> rows) {
        List<BarRow> shown = rows.stream().filter(r -> r.label() != null).toList();
        if (shown.isEmpty()) return;
        try {
            PdfPTable t = new PdfPTable(new float[]{27, 56, 17});
            t.setWidthPercentage(100);
            t.setSpacingBefore(4f);
            for (BarRow r : shown) {
                PdfPCell lbl = new PdfPCell(new Phrase(r.label(), F_BODY_SOFT));
                lbl.setBorder(Rectangle.NO_BORDER);
                lbl.setPaddingTop(4f);
                lbl.setPaddingBottom(4.5f);
                lbl.setVerticalAlignment(Element.ALIGN_MIDDLE);
                t.addCell(lbl);

                PdfPCell track = new PdfPCell();
                track.setBorder(Rectangle.NO_BORDER);
                track.setFixedHeight(17f);
                final float frac = (float) Math.max(0, Math.min(1, r.frac()));
                final Color fillColor = r.color();
                track.setCellEvent((c, pos, canvases) -> {
                    PdfContentByte bg = canvases[PdfPTable.BACKGROUNDCANVAS];
                    float x = pos.getLeft(), w = pos.getWidth();
                    float h = 9.5f;
                    float y = (pos.getBottom() + pos.getTop()) / 2f - h / 2f;
                    bg.saveState();
                    bg.setColorFill(HAIRLINE_SOFT);
                    bg.roundRectangle(x, y, w, h, 3f);
                    bg.fill();
                    float fw = w * frac;
                    if (frac > 0 && fw < 3f) fw = 3f;        // visible stub for tiny fractions
                    if (fw > 0) {
                        bg.setColorFill(fillColor);
                        bg.roundRectangle(x, y, fw, h, Math.min(3f, fw / 2f));
                        bg.fill();
                    }
                    bg.restoreState();
                });
                t.addCell(track);

                PdfPCell val = new PdfPCell();
                val.setBorder(Rectangle.NO_BORDER);
                val.setHorizontalAlignment(Element.ALIGN_RIGHT);
                val.setVerticalAlignment(Element.ALIGN_MIDDLE);
                Paragraph vp = new Paragraph();
                vp.add(new Chunk(r.value(), new Font(Font.HELVETICA, 9, Font.BOLD, INK)));
                if (r.pct() != null && !r.pct().isBlank()) {
                    vp.add(new Chunk("  " + r.pct(), new Font(Font.HELVETICA, 6.4f, Font.BOLD, MUTED)));
                }
                vp.setAlignment(Element.ALIGN_RIGHT);
                vp.setLeading(10f);
                val.addElement(vp);
                t.addCell(val);
            }
            doc.add(t);
        } catch (Exception e) {
            throw new IllegalStateException("PDF barList failed", e);
        }
    }

    /** A row of small rounded chips (optional colored dot + label + bold value). */
    public void chipRow(List<Chip> chips) {
        List<Chip> shown = chips.stream().filter(c -> c.label() != null && !c.label().isBlank()).toList();
        if (shown.isEmpty()) return;
        try {
            int n = shown.size();
            PdfPTable t = new PdfPTable(gutteredWidths(n, 10f, 0.5f));
            t.setWidthPercentage(100);
            t.setSpacingBefore(4f);
            for (int i = 0; i < n; i++) {
                Chip ch = shown.get(i);
                BoxStyle style = new BoxStyle().fill(Color.WHITE).border(HAIRLINE_SOFT, 0.8f).radius(8f);
                if (ch.dot() != null) style.dot(ch.dot());
                PdfPCell cell = boxed(style);
                cell.setFixedHeight(16.5f);
                cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
                cell.setPaddingLeft(ch.dot() != null ? 16f : 8f);
                cell.setPaddingTop(3.2f);
                Paragraph p = new Paragraph();
                p.add(new Chunk(ch.label() + " ", new Font(Font.HELVETICA, 7.5f, Font.NORMAL, INK_SOFT)));
                if (ch.value() != null && !ch.value().isBlank()) {
                    p.add(new Chunk(ch.value(), new Font(Font.HELVETICA, 7.5f, Font.BOLD, INK)));
                }
                p.setLeading(8.5f);
                cell.addElement(p);
                t.addCell(cell);
                if (i < n - 1) t.addCell(gapCell());
            }
            doc.add(t);
        } catch (Exception e) {
            throw new IllegalStateException("PDF chipRow failed", e);
        }
    }

    /** Handover workload rows: state-tinted rounded rows with a count and a status pill. */
    public void workRows(List<WorkRow> rows) {
        List<WorkRow> shown = rows.stream().filter(r -> r.label() != null && !r.label().isBlank()).toList();
        if (shown.isEmpty()) return;
        try {
            boolean first = true;
            for (WorkRow r : shown) {
                Color rowFill = switch (r.tone()) {
                    case CRIT, OVER -> RED_BG;
                    case WARN -> AMBER_BG;
                    default -> Color.WHITE;
                };
                Color numColor = (r.tone() == WorkTone.CRIT || r.tone() == WorkTone.OVER) ? DANGER : INK;
                float spacing = first ? 4f : 2.5f;
                first = false;

                // Each row is ONE rounded boxed cell holding an inner 3-col table —
                // per-cell rounded-corner stitching across a row is not worth it.
                PdfPTable inner = new PdfPTable(new float[]{62, 13, 25});
                inner.setWidthPercentage(100);
                inner.getDefaultCell().setBorder(Rectangle.NO_BORDER);

                PdfPCell lbl = new PdfPCell(new Phrase(r.label(),
                        new Font(Font.HELVETICA, 8, r.sub() ? Font.NORMAL : Font.BOLD, r.sub() ? MUTED : INK_SOFT)));
                lbl.setBorder(Rectangle.NO_BORDER);
                lbl.setVerticalAlignment(Element.ALIGN_MIDDLE);
                lbl.setPaddingLeft(r.sub() ? 14f : 2f);
                inner.addCell(lbl);

                PdfPCell num = new PdfPCell(new Phrase(r.value(),
                        new Font(Font.HELVETICA, 12.5f, Font.BOLD, numColor)));
                num.setBorder(Rectangle.NO_BORDER);
                num.setHorizontalAlignment(Element.ALIGN_RIGHT);
                num.setVerticalAlignment(Element.ALIGN_MIDDLE);
                inner.addCell(num);

                PdfPCell pillHost = new PdfPCell();
                pillHost.setBorder(Rectangle.NO_BORDER);
                pillHost.setHorizontalAlignment(Element.ALIGN_RIGHT);
                pillHost.setVerticalAlignment(Element.ALIGN_MIDDLE);
                pillHost.setPaddingLeft(10f);
                if (r.pillText() != null && !r.pillText().isBlank()) {
                    pillHost.addElement(pill(r.pillText(), r.tone()));
                }
                inner.addCell(pillHost);

                PdfPCell rowCell = boxed(new BoxStyle().fill(rowFill).border(HAIRLINE_SOFT, 0.8f).radius(5.5f).underlay());
                rowCell.setPadding(4.5f);
                rowCell.setPaddingLeft(9f);
                rowCell.setPaddingRight(7f);
                rowCell.addElement(inner);
                PdfPTable outer = new PdfPTable(1);
                outer.setWidthPercentage(100);
                outer.setSpacingBefore(spacing);
                outer.addCell(rowCell);
                doc.add(outer);
            }
        } catch (Exception e) {
            throw new IllegalStateException("PDF workRows failed", e);
        }
    }

    /** A small status pill table (tinted rounded bg + tiny bold uppercase text). */
    private static PdfPTable pill(String text, WorkTone tone) {
        Color fg;
        Color bg;
        Color border;
        switch (tone) {
            case CRIT, OVER -> { fg = DANGER; bg = RED_BG; border = RED_BORDER; }
            case WARN -> { fg = AMBER_TEXT; bg = AMBER_BG; border = AMBER_BORDER; }
            case OK -> { fg = GREEN_TEXT; bg = GREEN_BG; border = GREEN_BORDER; }
            default -> { fg = BRAND_DEEP; bg = BAND; border = BAND_BORDER; }
        }
        PdfPTable p = new PdfPTable(1);
        p.setWidthPercentage(100);
        PdfPCell c = boxed(new BoxStyle().fill(bg).border(border, 0.8f).radius(6.5f));
        c.setFixedHeight(13f);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setPaddingTop(2.6f);
        Chunk tc = new Chunk(text.toUpperCase(), new Font(Font.HELVETICA, 6, Font.BOLD, fg));
        tc.setCharacterSpacing(0.4f);
        Paragraph pp = new Paragraph(tc);
        pp.setAlignment(Element.ALIGN_CENTER);
        pp.setLeading(6.5f);
        c.addElement(pp);
        p.addCell(c);
        return p;
    }

    /**
     * A vector trend chart in a rounded panel: area fill + line + faint gridlines +
     * emphasised endpoint. X labels are start / middle / end of the series.
     */
    public void trendChart(double[] values, String xStart, String xMid, String xEnd) {
        if (values == null || values.length < 2) return;
        try {
            double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
            for (double v : values) { mn = Math.min(mn, v); mx = Math.max(mx, v); }
            if (mx <= mn) { mx = mn + 1; }
            final double min = Math.max(0, mn - (mx - mn) * 0.12);
            final double max = mx + (mx - mn) * 0.10;
            final double[] vals = values;

            PdfPCell cell = new PdfPCell();
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setFixedHeight(125f);
            // ONE event draws panel + plot (setCellEvent replaces, so never stack two).
            cell.setCellEvent((c, pos, canvases) -> {
                PdfContentByte bg = canvases[PdfPTable.BACKGROUNDCANVAS];
                PdfContentByte txt = canvases[PdfPTable.TEXTCANVAS];
                // panel
                new RoundBox(new BoxStyle().fill(PANEL).border(HAIRLINE_SOFT, 0.9f).radius(6.5f))
                        .cellLayout(c, pos, canvases);
                float padL = 30f, padR = 12f, padT = 12f, padB = 22f;
                float x0 = pos.getLeft() + padL, x1 = pos.getRight() - padR;
                float y0 = pos.getBottom() + padB, y1 = pos.getTop() - padT;
                float w = x1 - x0, h = y1 - y0;
                // gridlines at min / mid / max
                Font axis = new Font(Font.HELVETICA, 6, Font.NORMAL, SLATE_400);
                double[] ticks = {min, (min + max) / 2d, max};
                bg.saveState();
                bg.setLineWidth(0.6f);
                for (int gi = 0; gi < ticks.length; gi++) {
                    float gy = y0 + (float) ((ticks[gi] - min) / (max - min)) * h;
                    bg.setColorStroke(gi == 0 ? HAIRLINE : new Color(238, 242, 246));
                    bg.moveTo(x0, gy);
                    bg.lineTo(x1, gy);
                    bg.stroke();
                    ColumnText.showTextAligned(txt, Element.ALIGN_RIGHT,
                            new Phrase(String.valueOf(Math.round(ticks[gi])), axis), x0 - 4f, gy - 2f, 0);
                }
                bg.restoreState();
                // points
                int n = vals.length;
                float[] px = new float[n];
                float[] py = new float[n];
                for (int i = 0; i < n; i++) {
                    px[i] = x0 + w * i / (n - 1);
                    py[i] = y0 + (float) ((vals[i] - min) / (max - min)) * h;
                }
                // area fill (flat brand at low opacity via ExtGState — no shading needed)
                PdfGState gs = new PdfGState();
                gs.setFillOpacity(0.15f);
                bg.saveState();
                bg.setGState(gs);
                bg.setColorFill(BRAND);
                bg.moveTo(px[0], y0);
                for (int i = 0; i < n; i++) bg.lineTo(px[i], py[i]);
                bg.lineTo(px[n - 1], y0);
                bg.fill();
                bg.restoreState();
                // line
                bg.saveState();
                bg.setColorStroke(BRAND);
                bg.setLineWidth(1.5f);
                bg.moveTo(px[0], py[0]);
                for (int i = 1; i < n; i++) bg.lineTo(px[i], py[i]);
                bg.stroke();
                bg.restoreState();
                // endpoint dot + value
                bg.saveState();
                bg.setColorFill(BRAND_DEEP);
                bg.circle(px[n - 1], py[n - 1], 2.7f);
                bg.fill();
                bg.setColorStroke(Color.WHITE);
                bg.setLineWidth(1.1f);
                bg.circle(px[n - 1], py[n - 1], 2.7f);
                bg.stroke();
                bg.restoreState();
                ColumnText.showTextAligned(txt, Element.ALIGN_RIGHT,
                        new Phrase(String.valueOf(Math.round(vals[n - 1])),
                                new Font(Font.HELVETICA, 7, Font.BOLD, BRAND_DEEP)),
                        px[n - 1] - 4f, py[n - 1] + 5f, 0);
                // x labels
                if (xStart != null) ColumnText.showTextAligned(txt, Element.ALIGN_LEFT,
                        new Phrase(xStart, axis), x0, y0 - 12f, 0);
                if (xMid != null) ColumnText.showTextAligned(txt, Element.ALIGN_CENTER,
                        new Phrase(xMid, axis), (x0 + x1) / 2f, y0 - 12f, 0);
                if (xEnd != null) ColumnText.showTextAligned(txt, Element.ALIGN_RIGHT,
                        new Phrase(xEnd, axis), x1, y0 - 12f, 0);
            });
            PdfPTable t = new PdfPTable(1);
            t.setWidthPercentage(100);
            t.setSpacingBefore(4f);
            t.addCell(cell);
            doc.add(t);
        } catch (Exception e) {
            throw new IllegalStateException("PDF trendChart failed", e);
        }
    }

    /** Patient banner: cyan band, large name, labelled ids, optional acuity pill. */
    public void patientBanner(String name, List<KeyVal> ids,
                              String acuityLevel, String acuityMeaning, Color acuityColor) {
        try {
            boolean hasPill = acuityLevel != null && !acuityLevel.isBlank() && acuityColor != null;
            PdfPTable inner = new PdfPTable(hasPill ? new float[]{80, 20} : new float[]{100});
            inner.setWidthPercentage(100);
            inner.getDefaultCell().setBorder(Rectangle.NO_BORDER);

            PdfPCell lc = new PdfPCell();
            lc.setBorder(Rectangle.NO_BORDER);
            lc.setVerticalAlignment(Element.ALIGN_MIDDLE);
            Paragraph nm = new Paragraph(name != null && !name.isBlank() ? name : "Unknown",
                    new Font(Font.HELVETICA, 14, Font.BOLD, INK));
            nm.setLeading(15f);
            lc.addElement(nm);
            List<KeyVal> shownIds = ids == null ? List.of() : ids.stream()
                    .filter(p -> p.value() != null && !p.value().isBlank()).toList();
            if (!shownIds.isEmpty()) {
                PdfPTable idT = new PdfPTable(shownIds.size());
                idT.setWidthPercentage(100);
                idT.setSpacingBefore(5f);
                for (KeyVal id : shownIds) {
                    PdfPCell ic = new PdfPCell();
                    ic.setBorder(Rectangle.NO_BORDER);
                    Chunk ilc = new Chunk(id.label().toUpperCase(),
                            new Font(Font.HELVETICA, 5.6f, Font.BOLD, MUTED));
                    ilc.setCharacterSpacing(0.4f);
                    Paragraph il = new Paragraph(ilc);
                    il.setLeading(6.5f);
                    ic.addElement(il);
                    Paragraph iv = new Paragraph(id.value(),
                            new Font(Font.HELVETICA, 8, Font.BOLD, INK_SOFT));
                    iv.setLeading(9.5f);
                    iv.setSpacingBefore(1f);
                    ic.addElement(iv);
                    idT.addCell(ic);
                }
                lc.addElement(idT);
            }
            inner.addCell(lc);

            if (hasPill) {
                PdfPCell pc = new PdfPCell();
                pc.setBorder(Rectangle.NO_BORDER);
                pc.setVerticalAlignment(Element.ALIGN_MIDDLE);
                pc.setHorizontalAlignment(Element.ALIGN_RIGHT);
                PdfPTable pillT = new PdfPTable(1);
                pillT.setWidthPercentage(100);
                PdfPCell pill = boxed(new BoxStyle().fill(acuityColor).radius(6.5f));
                pill.setFixedHeight(30f);
                pill.setVerticalAlignment(Element.ALIGN_MIDDLE);
                pill.setPaddingTop(5f);
                Chunk lvl = new Chunk(acuityLevel.toUpperCase(),
                        new Font(Font.HELVETICA, 9, Font.BOLD, Color.WHITE));
                lvl.setCharacterSpacing(0.5f);
                Paragraph p1 = new Paragraph(lvl);
                p1.setAlignment(Element.ALIGN_CENTER);
                p1.setLeading(9.5f);
                pill.addElement(p1);
                if (acuityMeaning != null && !acuityMeaning.isBlank()) {
                    Chunk mc = new Chunk(acuityMeaning.toUpperCase(),
                            new Font(Font.HELVETICA, 5, Font.BOLD, Color.WHITE));
                    mc.setCharacterSpacing(0.4f);
                    Paragraph p2 = new Paragraph(mc);
                    p2.setAlignment(Element.ALIGN_CENTER);
                    p2.setLeading(6f);
                    pill.addElement(p2);
                }
                pillT.addCell(pill);
                pc.addElement(pillT);
                inner.addCell(pc);
            }

            PdfPCell wrap = boxed(new BoxStyle().fill(BAND).border(BAND_BORDER, 0.9f).radius(7f).underlay());
            wrap.setPadding(9f);
            wrap.setPaddingLeft(12f);
            wrap.addElement(inner);
            PdfPTable outer = new PdfPTable(1);
            outer.setWidthPercentage(100);
            outer.setSpacingBefore(4f);
            outer.addCell(wrap);
            doc.add(outer);
        } catch (Exception e) {
            throw new IllegalStateException("PDF patientBanner failed", e);
        }
    }

    /** SBAR group header: letter badge + uppercase label + band rule. */
    public void sbarGroup(String letter, String label) {
        sbarGroup(letter, label, BRAND_DEEP);
    }

    /**
     * SBAR / section header with an explicit badge colour — brand for S/B/A/R,
     * {@link #SATS_RED} for the alerts section, {@link #MUTED} for the neutral
     * "•" sections (disposition / timeline / notes). Label + rule stay branded
     * so every badged header reads as one family.
     */
    public void sbarGroup(String letter, String label, Color badgeColor) {
        try {
            PdfPTable t = new PdfPTable(new float[]{5.2f, 1.6f, 43f, 50.2f});
            t.setWidthPercentage(100);
            t.setSpacingBefore(12f);
            t.setSpacingAfter(2f);
            PdfPCell badge = boxed(new BoxStyle().fill(badgeColor).radius(5.5f));
            badge.setFixedHeight(19f);
            badge.setVerticalAlignment(Element.ALIGN_MIDDLE);
            badge.setPaddingTop(3.4f);
            Paragraph bp = new Paragraph(letter, new Font(Font.HELVETICA, 10.5f, Font.BOLD, Color.WHITE));
            bp.setAlignment(Element.ALIGN_CENTER);
            bp.setLeading(11f);
            badge.addElement(bp);
            t.addCell(badge);
            t.addCell(gapCell());
            PdfPCell lbl = new PdfPCell();
            lbl.setBorder(Rectangle.NO_BORDER);
            lbl.setVerticalAlignment(Element.ALIGN_MIDDLE);
            Chunk lc = new Chunk(label.toUpperCase(), new Font(Font.HELVETICA, 9.5f, Font.BOLD, BRAND_DEEP));
            lc.setCharacterSpacing(0.8f);
            Paragraph lp = new Paragraph(lc);
            lp.setLeading(10.5f);
            lbl.addElement(lp);
            t.addCell(lbl);
            PdfPCell rule = new PdfPCell();
            rule.setBorder(Rectangle.NO_BORDER);
            rule.setCellEvent((c, pos, canvases) -> {
                PdfContentByte bg = canvases[PdfPTable.BACKGROUNDCANVAS];
                float y = (pos.getBottom() + pos.getTop()) / 2f;
                bg.saveState();
                bg.setColorStroke(BAND);
                bg.setLineWidth(1.6f);
                bg.moveTo(pos.getLeft(), y);
                bg.lineTo(pos.getRight(), y);
                bg.stroke();
                bg.restoreState();
            });
            t.addCell(rule);
            doc.add(t);
        } catch (Exception e) {
            throw new IllegalStateException("PDF sbarGroup failed", e);
        }
    }

    /** Bulleted list (e.g. interventions, medications). */
    public void bullets(List<String> items) {
        if (items == null) return;
        try {
            for (String item : items) {
                if (item == null || item.isBlank()) continue;
                Paragraph p = new Paragraph();
                p.add(new Phrase("•  ", new Font(Font.HELVETICA, 9, Font.BOLD, BRAND)));
                p.add(new Phrase(item, F_BODY));
                p.setLeading(12f);
                p.setSpacingAfter(1.5f);
                p.setIndentationLeft(4f);
                doc.add(p);
            }
        } catch (Exception e) {
            throw new IllegalStateException("PDF bullets failed", e);
        }
    }

    /**
     * Free-text narrative in a rounded panel with a brand left-accent — readable
     * prose, not a code block. Supports lightweight inline emphasis: text wrapped
     * in double asterisks (**like this**) renders bold. Line breaks preserved.
     */
    public void narrative(String text) {
        if (text == null || text.isBlank()) return;
        try {
            PdfPCell cell = boxed(new BoxStyle()
                    .fill(PANEL).border(HAIRLINE_SOFT, 0.9f).radius(5f)
                    .leftStripe(BRAND, 2.2f));
            cell.setPadding(7f);
            cell.setPaddingLeft(11f);
            for (String para : text.split("\n", -1)) {
                Paragraph p = para.isBlank() ? new Paragraph(" ", F_BODY) : richLine(para);
                p.setLeading(12.5f);
                cell.addElement(p);
            }
            PdfPTable wrap = new PdfPTable(1);
            wrap.setWidthPercentage(100);
            wrap.setSpacingBefore(3f);
            wrap.addCell(cell);
            doc.add(wrap);
        } catch (Exception e) {
            throw new IllegalStateException("PDF narrative failed", e);
        }
    }

    /** One line of narrative with **bold** spans as alternating chunks. */
    private static Paragraph richLine(String line) {
        Paragraph p = new Paragraph();
        String[] parts = line.split("\\*\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            p.add(new Chunk(parts[i], i % 2 == 1 ? F_BODY_SOFT_BD : F_BODY_SOFT));
        }
        if (p.isEmpty()) p.add(new Chunk(" ", F_BODY_SOFT));
        return p;
    }

    /** A prominent alert banner (e.g. unidentified patient, critical flag). */
    public void alertBanner(String text) {
        bannerRow(text, DANGER, RED_BG, DANGER, 1.1f);
    }

    /** An amber status banner (e.g. "awaiting acknowledgement"). */
    public void statusBanner(String text) {
        bannerRow(text, AMBER_TEXT, AMBER_BG, AMBER_BORDER, 1.1f);
    }

    private void bannerRow(String text, Color fg, Color bg, Color border, float borderW) {
        if (text == null || text.isBlank()) return;
        try {
            PdfPTable inner = new PdfPTable(new float[]{5.5f, 94.5f});
            inner.setWidthPercentage(100);
            inner.getDefaultCell().setBorder(Rectangle.NO_BORDER);
            PdfPCell disc = new PdfPCell();
            disc.setBorder(Rectangle.NO_BORDER);
            disc.setCellEvent((c, pos, canvases) -> {
                PdfContentByte bgc = canvases[PdfPTable.BACKGROUNDCANVAS];
                PdfContentByte txt = canvases[PdfPTable.TEXTCANVAS];
                float cx = pos.getLeft() + 8f;
                float cy = (pos.getBottom() + pos.getTop()) / 2f;
                bgc.saveState();
                bgc.setColorFill(fg);
                bgc.circle(cx, cy, 6.2f);
                bgc.fill();
                bgc.restoreState();
                ColumnText.showTextAligned(txt, Element.ALIGN_CENTER,
                        new Phrase("!", new Font(Font.HELVETICA, 8.5f, Font.BOLD, Color.WHITE)),
                        cx, cy - 3f, 0);
            });
            inner.addCell(disc);
            PdfPCell body = new PdfPCell(new Phrase(text, new Font(Font.HELVETICA, 9.5f, Font.BOLD, fg)));
            body.setBorder(Rectangle.NO_BORDER);
            body.setVerticalAlignment(Element.ALIGN_MIDDLE);
            body.setPaddingLeft(3f);
            inner.addCell(body);

            PdfPCell wrap = boxed(new BoxStyle().fill(bg).border(border, borderW).radius(6.5f).underlay());
            wrap.setPadding(6.5f);
            wrap.setPaddingLeft(9f);
            wrap.addElement(inner);
            PdfPTable outer = new PdfPTable(1);
            outer.setWidthPercentage(100);
            outer.setSpacingBefore(4f);
            outer.setSpacingAfter(2f);
            outer.addCell(wrap);
            doc.add(outer);
        } catch (Exception e) {
            throw new IllegalStateException("PDF banner failed", e);
        }
    }

    /** A red alert callout with a bold lead ("CRITICAL — …") and body text. */
    public void alertCallout(String boldLead, String body) {
        if ((boldLead == null || boldLead.isBlank()) && (body == null || body.isBlank())) return;
        try {
            PdfPTable inner = new PdfPTable(new float[]{5.5f, 94.5f});
            inner.setWidthPercentage(100);
            inner.getDefaultCell().setBorder(Rectangle.NO_BORDER);
            PdfPCell disc = new PdfPCell();
            disc.setBorder(Rectangle.NO_BORDER);
            disc.setCellEvent((c, pos, canvases) -> {
                PdfContentByte bgc = canvases[PdfPTable.BACKGROUNDCANVAS];
                PdfContentByte txt = canvases[PdfPTable.TEXTCANVAS];
                float cx = pos.getLeft() + 8f;
                float cy = pos.getTop() - 9f;
                bgc.saveState();
                bgc.setColorFill(DANGER);
                bgc.circle(cx, cy, 6.2f);
                bgc.fill();
                bgc.restoreState();
                ColumnText.showTextAligned(txt, Element.ALIGN_CENTER,
                        new Phrase("!", new Font(Font.HELVETICA, 8.5f, Font.BOLD, Color.WHITE)),
                        cx, cy - 3f, 0);
            });
            inner.addCell(disc);
            PdfPCell bodyCell = new PdfPCell();
            bodyCell.setBorder(Rectangle.NO_BORDER);
            bodyCell.setPaddingLeft(3f);
            Font leadFont = new Font(Font.HELVETICA, 8.5f, Font.BOLD, DANGER);
            Font bodyFont = new Font(Font.HELVETICA, 8.5f, Font.NORMAL, new Color(127, 29, 29));
            String[] lines = body != null ? body.split("\n", -1) : new String[]{""};
            for (int i = 0; i < lines.length; i++) {
                Paragraph p = new Paragraph();
                if (i == 0 && boldLead != null && !boldLead.isBlank()) {
                    p.add(new Chunk(boldLead + " ", leadFont));
                }
                if (!lines[i].isBlank()) p.add(new Chunk(lines[i], bodyFont));
                if (p.isEmpty()) continue;
                p.setLeading(11.5f);
                bodyCell.addElement(p);
            }
            inner.addCell(bodyCell);

            PdfPCell wrap = boxed(new BoxStyle().fill(RED_BG).border(DANGER, 1.1f).radius(6.5f).underlay());
            wrap.setPadding(7f);
            wrap.setPaddingLeft(9f);
            wrap.addElement(inner);
            PdfPTable outer = new PdfPTable(1);
            outer.setWidthPercentage(100);
            outer.setSpacingBefore(3f);
            outer.addCell(wrap);
            doc.add(outer);
        } catch (Exception e) {
            throw new IllegalStateException("PDF alertCallout failed", e);
        }
    }

    /** A bold patient/subject name line under the masthead. */
    public void subjectHeadline(String name, String metaLine) {
        try {
            Paragraph n = new Paragraph(name, new Font(Font.HELVETICA, 13, Font.BOLD, INK));
            n.setSpacingBefore(2f);
            doc.add(n);
            if (metaLine != null && !metaLine.isBlank()) {
                doc.add(new Paragraph(metaLine, F_META));
            }
        } catch (Exception e) {
            throw new IllegalStateException("PDF subjectHeadline failed", e);
        }
    }

    public void spacer(float height) {
        try {
            Paragraph p = new Paragraph(" ");
            p.setLeading(height);
            doc.add(p);
        } catch (Exception e) { /* non-fatal */ }
    }

    /** An italic table footnote (e.g. "Showing the most recent 14 of 30 days…"). */
    public void tableNote(String text) {
        if (text == null || text.isBlank()) return;
        try {
            Paragraph p = new Paragraph(text, F_NOTE_IT);
            p.setSpacingBefore(3f);
            doc.add(p);
        } catch (Exception e) {
            throw new IllegalStateException("PDF tableNote failed", e);
        }
    }

    /**
     * Multi-column data table for register / trend reports (one row per record or
     * per day). The header row REPEATS on every page when the table breaks across
     * pages; body rows zebra-stripe for scannability. {@code rightAlignFrom} marks
     * the first column index (0-based) from which cells are right-aligned — the
     * convention for numeric columns; pass {@code headers.length} to left-align all.
     * A null cell renders as an em-dash. Rows beyond a caller-side cap should be
     * truncated BEFORE calling, with a visible "showing first N" note — this method
     * renders exactly what it is given.
     */
    public void dataTable(String[] headers, float[] widths, List<String[]> rows, int rightAlignFrom) {
        dataTable(headers, null, widths, rows, null, rightAlignFrom);
    }

    /**
     * Data table with optional colored header dots (per column; null entries = no
     * dot) and an optional bold totals row rendered ONCE at the very end (never
     * repeated on page breaks — it is a body row with a brand top border).
     */
    public void dataTable(String[] headers, Color[] headerDots, float[] widths,
                          List<String[]> rows, String[] totalsRow, int rightAlignFrom) {
        if (headers == null || headers.length == 0) return;
        try {
            PdfPTable t = new PdfPTable(widths != null && widths.length == headers.length
                    ? widths : uniformWidths(headers.length));
            t.setWidthPercentage(100);
            t.setSpacingBefore(4f);
            t.setHeaderRows(1); // repeat the header on every page the table spans
            for (int i = 0; i < headers.length; i++) {
                String h = headers[i];
                Color dot = headerDots != null && i < headerDots.length ? headerDots[i] : null;
                Chunk hc = new Chunk(h != null ? h.toUpperCase() : "", F_TH);
                hc.setCharacterSpacing(0.3f);
                PdfPCell c = new PdfPCell(new Phrase(hc));
                c.setBackgroundColor(BAND);
                c.setBorder(Rectangle.BOTTOM);
                c.setBorderColor(BRAND);
                c.setBorderWidth(1.1f);
                c.setPadding(4.5f);
                if (i >= rightAlignFrom) c.setHorizontalAlignment(Element.ALIGN_RIGHT);
                if (dot != null) {
                    c.setPaddingLeft(13f);
                    c.setHorizontalAlignment(Element.ALIGN_LEFT);
                    c.setCellEvent((cell, pos, canvases) -> {
                        PdfContentByte bg = canvases[PdfPTable.BACKGROUNDCANVAS];
                        bg.saveState();
                        bg.setColorFill(dot);
                        bg.circle(pos.getLeft() + 7f, (pos.getBottom() + pos.getTop()) / 2f, 2.4f);
                        bg.fill();
                        bg.restoreState();
                    });
                }
                t.addCell(c);
            }
            if (rows == null || rows.isEmpty()) {
                PdfPCell empty = new PdfPCell(new Phrase("No records in this period.", F_META));
                empty.setColspan(headers.length);
                empty.setBorder(Rectangle.NO_BORDER);
                empty.setPadding(6f);
                t.addCell(empty);
            } else {
                boolean stripe = false;
                for (String[] row : rows) {
                    for (int i = 0; i < headers.length; i++) {
                        String v = row != null && i < row.length && row[i] != null ? row[i] : "—";
                        PdfPCell c = new PdfPCell(new Phrase(v, F_VALUE));
                        c.setBackgroundColor(stripe ? PANEL : Color.WHITE);
                        c.setBorder(Rectangle.BOTTOM);
                        c.setBorderColor(HAIRLINE);
                        c.setBorderWidth(0.4f);
                        c.setPadding(4f);
                        if (i >= rightAlignFrom) c.setHorizontalAlignment(Element.ALIGN_RIGHT);
                        t.addCell(c);
                    }
                    stripe = !stripe;
                }
            }
            if (totalsRow != null && totalsRow.length > 0 && rows != null && !rows.isEmpty()) {
                for (int i = 0; i < headers.length; i++) {
                    String v = i < totalsRow.length && totalsRow[i] != null ? totalsRow[i] : "";
                    PdfPCell c = new PdfPCell(new Phrase(v, F_VALUE_BD));
                    c.setBackgroundColor(Color.WHITE);
                    c.setBorder(Rectangle.TOP);
                    c.setBorderColor(BRAND);
                    c.setBorderWidth(1.1f);
                    c.setPadding(4.5f);
                    if (i >= rightAlignFrom) c.setHorizontalAlignment(Element.ALIGN_RIGHT);
                    t.addCell(c);
                }
            }
            doc.add(t);
        } catch (Exception e) {
            throw new IllegalStateException("PDF dataTable failed", e);
        }
    }

    private static float[] uniformWidths(int n) {
        float[] w = new float[n];
        java.util.Arrays.fill(w, 1f);
        return w;
    }

    /**
     * Signature block for clinical handoff artifacts — one signing line per party
     * (e.g. "Outgoing shift lead", "Incoming shift lead"). Each party gets
     * name / signature / date-time rules to complete BY HAND on the printed copy:
     * a generated report becomes a clinical record only when its humans sign it.
     */
    public void signatureBlock(String... parties) {
        if (parties == null || parties.length == 0) return;
        try {
            PdfPTable t = new PdfPTable(parties.length);
            t.setWidthPercentage(100);
            t.setSpacingBefore(14f);
            for (String party : parties) {
                PdfPCell cell = new PdfPCell();
                cell.setBorder(Rectangle.NO_BORDER);
                cell.setPaddingRight(14f);
                Chunk who = new Chunk(party.toUpperCase(), F_LABEL);
                who.setCharacterSpacing(0.5f);
                Paragraph wp = new Paragraph(who);
                wp.setSpacingAfter(16f);
                cell.addElement(wp);
                for (String line : new String[]{"Name", "Signature", "Date / time"}) {
                    Paragraph p = new Paragraph(line + ":  ______________________________", F_VALUE);
                    p.setLeading(15f);
                    cell.addElement(p);
                }
                t.addCell(cell);
            }
            doc.add(t);
        } catch (Exception e) {
            throw new IllegalStateException("PDF signatureBlock failed", e);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Redesign #1a primitives — structured clinical cards / rows / groups.
    // Additive: every method above keeps its exact signature.
    // ══════════════════════════════════════════════════════════════════

    /** A single body line with **bold** spans (no panel). */
    public void bodyLine(String rich) {
        if (rich == null || rich.isBlank()) return;
        try {
            Paragraph p = richBody(rich, F_BODY, F_VALUE_BD);
            p.setLeading(12.5f);
            p.setSpacingBefore(5f);
            doc.add(p);
        } catch (Exception e) { throw new IllegalStateException("PDF bodyLine failed", e); }
    }

    /** A small muted note line with **bold** spans (trend notes, attributions). */
    public void mutedNote(String rich) {
        if (rich == null || rich.isBlank()) return;
        try {
            Paragraph p = richBody(rich, new Font(Font.HELVETICA, 8, Font.NORMAL, MUTED),
                    new Font(Font.HELVETICA, 8, Font.BOLD, MUTED));
            p.setLeading(10.5f);
            p.setSpacingBefore(4f);
            doc.add(p);
        } catch (Exception e) { throw new IllegalStateException("PDF mutedNote failed", e); }
    }

    private static Paragraph richBody(String line, Font normal, Font bold) {
        Paragraph p = new Paragraph();
        String[] parts = line.split("\\*\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            p.add(new Chunk(parts[i], i % 2 == 1 ? bold : normal));
        }
        if (p.isEmpty()) p.add(new Chunk(" ", normal));
        return p;
    }

    /** A crisp rounded status chip sized to hug its text (left-aligned in its host cell). */
    private static PdfPTable miniChip(String text, Color bg, Color fg) {
        Font f = new Font(Font.HELVETICA, 7f, Font.BOLD, fg);
        float w;
        try { w = f.getCalculatedBaseFont(true).getWidthPoint(text, 7f) + 13f; }
        catch (Exception e) { w = text.length() * 4.2f + 13f; }
        PdfPTable t = new PdfPTable(1);
        t.setTotalWidth(w);
        t.setLockedWidth(true);
        t.setHorizontalAlignment(Element.ALIGN_LEFT);
        PdfPCell c = boxed(new BoxStyle().fill(bg).radius(4f));
        c.setFixedHeight(12.5f);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setPaddingTop(2.4f);
        Chunk tc = new Chunk(text, f);
        tc.setCharacterSpacing(0.2f);
        Paragraph p = new Paragraph(tc);
        p.setAlignment(Element.ALIGN_CENTER);
        p.setLeading(7.5f);
        c.addElement(p);
        t.addCell(c);
        return t;
    }

    /** An inline chip (Chunk with a padded background) that flows within prose. */
    private static Chunk inlineChip(String text, Color bg, Color fg) {
        Chunk c = new Chunk(" " + text + " ", new Font(Font.HELVETICA, 7.5f, Font.BOLD, fg));
        c.setBackground(bg, 1.5f, 1.6f, 1.5f, 2.6f);
        return c;
    }

    /**
     * Allergy / chronic-condition strip: two side-by-side boxes. Left is
     * red-tinted with a "!" glyph + red allergy chip(s) when allergies exist
     * (neutral "None recorded" otherwise); right is a neutral chronic panel.
     */
    public void allergyStrip(String allergies, String chronic) {
        try {
            boolean hasAllergy = allergies != null && !allergies.isBlank();
            Paragraph lp = new Paragraph();
            if (hasAllergy) {
                lp.add(new Chunk("!  ", new Font(Font.HELVETICA, 11, Font.BOLD, SATS_RED)));
                Chunk lbl = new Chunk("KNOWN ALLERGIES   ", new Font(Font.HELVETICA, 7.5f, Font.BOLD, ALLERGY_TEXT));
                lbl.setCharacterSpacing(0.4f);
                lp.add(lbl);
                for (String a : allergies.split("[,;]")) {
                    String v = a.trim();
                    if (v.isEmpty()) continue;
                    lp.add(inlineChip(v, CHIP_RED_BG, CHIP_RED_FG));
                    lp.add(new Chunk("  ", F_BODY));
                }
            } else {
                Chunk lbl = new Chunk("KNOWN ALLERGIES   ", new Font(Font.HELVETICA, 7.5f, Font.BOLD, MUTED));
                lbl.setCharacterSpacing(0.4f);
                lp.add(lbl);
                lp.add(new Chunk("None recorded", new Font(Font.HELVETICA, 9, Font.NORMAL, INK_SOFT)));
            }
            lp.setLeading(13f);
            PdfPCell leftCell = boxed(new BoxStyle()
                    .fill(hasAllergy ? ALLERGY_BG : PANEL)
                    .border(hasAllergy ? ALLERGY_BORDER : HAIRLINE_SOFT, 0.9f).radius(8f));
            leftCell.setPadding(8f); leftCell.setPaddingLeft(11f);
            leftCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            leftCell.addElement(lp);

            Paragraph rp = new Paragraph();
            Chunk clbl = new Chunk("CHRONIC CONDITIONS   ", new Font(Font.HELVETICA, 7.5f, Font.BOLD, MUTED));
            clbl.setCharacterSpacing(0.4f);
            rp.add(clbl);
            rp.add(new Chunk(chronic != null && !chronic.isBlank() ? chronic : "None",
                    new Font(Font.HELVETICA, 9, Font.NORMAL, INK_SOFT)));
            rp.setLeading(13f);
            PdfPCell rightCell = boxed(new BoxStyle().fill(PANEL).border(HAIRLINE_SOFT, 0.9f).radius(8f));
            rightCell.setPadding(8f); rightCell.setPaddingLeft(11f);
            rightCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            rightCell.addElement(rp);

            PdfPTable t = new PdfPTable(new float[]{49.3f, 1.4f, 49.3f});
            t.setWidthPercentage(100);
            t.setSpacingBefore(7f);
            t.addCell(leftCell);
            t.addCell(gapCell());
            t.addCell(rightCell);
            doc.add(t);
        } catch (Exception e) { throw new IllegalStateException("PDF allergyStrip failed", e); }
    }

    /**
     * A callout box: {@link CalloutVariant#CHIEF} = pale-yellow with a bold ink
     * heading (chief complaint), {@link CalloutVariant#REDFLAG} = amber with a
     * small colored uppercase heading, {@link CalloutVariant#NEUTRAL} = slate
     * panel. Body supports **bold** and multiple lines; heading is optional.
     */
    public void calloutBox(CalloutVariant v, String heading, String body) {
        if ((heading == null || heading.isBlank()) && (body == null || body.isBlank())) return;
        try {
            Color bg, border;
            switch (v) {
                case CHIEF -> { bg = CALLOUT_YELLOW_BG; border = CALLOUT_YELLOW_BORDER; }
                case REDFLAG -> { bg = CALLOUT_AMBER_BG; border = CALLOUT_AMBER_BORDER; }
                default -> { bg = PANEL; border = HAIRLINE_SOFT; }
            }
            PdfPCell cell = boxed(new BoxStyle().fill(bg).border(border, 0.9f).radius(8f));
            cell.setPadding(9f); cell.setPaddingLeft(13f);
            boolean hasHeading = heading != null && !heading.isBlank();
            if (hasHeading) {
                if (v == CalloutVariant.CHIEF) {
                    Paragraph hp = new Paragraph(heading, new Font(Font.HELVETICA, 9.5f, Font.BOLD, INK));
                    hp.setLeading(11f);
                    cell.addElement(hp);
                } else {
                    Color hc = v == CalloutVariant.REDFLAG ? CALLOUT_AMBER_HEAD : MUTED;
                    Chunk hck = new Chunk(heading.toUpperCase(), new Font(Font.HELVETICA, 8f, Font.BOLD, hc));
                    hck.setCharacterSpacing(0.4f);
                    Paragraph hp = new Paragraph(hck);
                    hp.setLeading(10f);
                    cell.addElement(hp);
                }
            }
            if (body != null && !body.isBlank()) {
                for (String ln : body.split("\n", -1)) {
                    if (ln.isBlank()) continue;
                    Paragraph bp = richBody(ln, new Font(Font.HELVETICA, 9, Font.NORMAL, INK),
                            new Font(Font.HELVETICA, 9, Font.BOLD, INK));
                    bp.setLeading(12.5f);
                    bp.setSpacingBefore(hasHeading ? 2.5f : 0f);
                    cell.addElement(bp);
                }
            }
            PdfPTable wrap = new PdfPTable(1);
            wrap.setWidthPercentage(100);
            wrap.setSpacingBefore(7f);
            wrap.addCell(cell);
            doc.add(wrap);
        } catch (Exception e) { throw new IllegalStateException("PDF calloutBox failed", e); }
    }

    /** The amber red-flag-signs callout: colored heading + a chip-tagged line per sign. */
    public void redFlagCallout(String heading, List<RedFlag> flags) {
        if (flags == null || flags.isEmpty()) return;
        try {
            PdfPCell cell = boxed(new BoxStyle().fill(CALLOUT_AMBER_BG).border(CALLOUT_AMBER_BORDER, 0.9f).radius(8f));
            cell.setPadding(9f); cell.setPaddingLeft(13f);
            if (heading != null && !heading.isBlank()) {
                Chunk hk = new Chunk(heading.toUpperCase(), new Font(Font.HELVETICA, 8f, Font.BOLD, CALLOUT_AMBER_HEAD));
                hk.setCharacterSpacing(0.4f);
                Paragraph hp = new Paragraph(hk);
                hp.setLeading(10f);
                cell.addElement(hp);
            }
            for (RedFlag rf : flags) {
                Paragraph p = new Paragraph();
                p.add(new Chunk(rf.text() + "  ", new Font(Font.HELVETICA, 9, Font.NORMAL, INK)));
                if (rf.chip() != null && !rf.chip().isBlank())
                    p.add(inlineChip(rf.chip(), rf.chipBg(), rf.chipFg()));
                if (rf.trailing() != null && !rf.trailing().isBlank())
                    p.add(new Chunk("  " + rf.trailing(), new Font(Font.HELVETICA, 9, Font.NORMAL, MUTED)));
                p.setLeading(13.5f);
                p.setSpacingBefore(4f);
                cell.addElement(p);
            }
            PdfPTable wrap = new PdfPTable(1);
            wrap.setWidthPercentage(100);
            wrap.setSpacingBefore(7f);
            wrap.addCell(cell);
            doc.add(wrap);
        } catch (Exception e) { throw new IllegalStateException("PDF redFlagCallout failed", e); }
    }

    /**
     * A 2-up SITUATION grid: label/value pairs (thin faint underlines, small
     * muted label + value); a {@code full} pair spans the whole width.
     */
    public void kvGrid(List<GridPair> rows) {
        List<GridPair> shown = rows.stream()
                .filter(r -> r.value() != null && !r.value().isBlank()).toList();
        if (shown.isEmpty()) return;
        try {
            PdfPTable t = new PdfPTable(2);
            t.setWidthPercentage(100);
            t.setSpacingBefore(7f);
            boolean leftFilled = false;
            for (GridPair r : shown) {
                if (r.full()) {
                    if (leftFilled) { t.addCell(pairCell(null, null, false)); leftFilled = false; }
                    PdfPCell c = pairCell(r.label(), r.value(), true);
                    c.setColspan(2);
                    t.addCell(c);
                } else {
                    t.addCell(pairCell(r.label(), r.value(), false));
                    leftFilled = !leftFilled;
                }
            }
            if (leftFilled) t.addCell(pairCell(null, null, false));
            doc.add(t);
        } catch (Exception e) { throw new IllegalStateException("PDF kvGrid failed", e); }
    }

    private static PdfPCell pairCell(String label, String value, boolean full) {
        PdfPCell cell = new PdfPCell();
        if (label == null && value == null) { cell.setBorder(Rectangle.NO_BORDER); return cell; }
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(HAIRLINE_FAINT);
        cell.setBorderWidthBottom(0.9f);
        cell.setPaddingTop(4.5f); cell.setPaddingBottom(4.5f);
        cell.setPaddingRight(full ? 0f : 24f);   // gutter between the two columns
        PdfPTable inner = new PdfPTable(new float[]{44, 56});
        inner.setWidthPercentage(100);
        inner.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        PdfPCell lc = new PdfPCell(new Phrase(label, new Font(Font.HELVETICA, 8.5f, Font.NORMAL, MUTED)));
        lc.setBorder(Rectangle.NO_BORDER); lc.setVerticalAlignment(Element.ALIGN_BOTTOM);
        inner.addCell(lc);
        PdfPCell vc = new PdfPCell(new Phrase(value, new Font(Font.HELVETICA, 9, Font.BOLD, INK)));
        vc.setBorder(Rectangle.NO_BORDER);
        vc.setHorizontalAlignment(Element.ALIGN_RIGHT);
        vc.setVerticalAlignment(Element.ALIGN_BOTTOM);
        inner.addCell(vc);
        cell.addElement(inner);
        return cell;
    }

    /**
     * Line-item rows (investigations / medications / diagnosis): each a bordered
     * rounded row with monospace timestamp · bold label · muted type · status
     * chip · right-aligned detail. The type column is dropped when no row has one.
     */
    public void lineItems(List<LineItem> rows) {
        if (rows == null || rows.isEmpty()) return;
        try {
            boolean showType = rows.stream().anyMatch(r -> r.type() != null && !r.type().isBlank());
            float[] w = showType ? new float[]{17f, 23f, 12f, 19f, 29f}
                                 : new float[]{17f, 33f, 19f, 31f};
            boolean first = true;
            for (LineItem r : rows) {
                PdfPTable inner = new PdfPTable(w);
                inner.setWidthPercentage(100);
                inner.getDefaultCell().setBorder(Rectangle.NO_BORDER);

                PdfPCell ts = new PdfPCell(new Phrase(r.timestamp() != null ? r.timestamp() : "", F_MONO_TS));
                ts.setBorder(Rectangle.NO_BORDER); ts.setVerticalAlignment(Element.ALIGN_MIDDLE);
                inner.addCell(ts);

                PdfPCell lbl = new PdfPCell(new Phrase(r.label() != null ? r.label() : "",
                        new Font(Font.HELVETICA, 8.5f, Font.BOLD, INK)));
                lbl.setBorder(Rectangle.NO_BORDER); lbl.setVerticalAlignment(Element.ALIGN_MIDDLE);
                inner.addCell(lbl);

                if (showType) {
                    PdfPCell ty = new PdfPCell(new Phrase(r.type() != null ? r.type() : "",
                            new Font(Font.HELVETICA, 7f, Font.NORMAL, MUTED)));
                    ty.setBorder(Rectangle.NO_BORDER); ty.setVerticalAlignment(Element.ALIGN_MIDDLE);
                    inner.addCell(ty);
                }

                PdfPCell chip = new PdfPCell();
                chip.setBorder(Rectangle.NO_BORDER); chip.setVerticalAlignment(Element.ALIGN_MIDDLE);
                chip.setPaddingTop(1f); chip.setPaddingBottom(1f);
                if (r.status() != null && !r.status().isBlank())
                    chip.addElement(miniChip(r.status(), r.statusBg(), r.statusFg()));
                inner.addCell(chip);

                PdfPCell det = new PdfPCell(new Phrase(r.detail() != null ? r.detail() : "",
                        new Font(Font.HELVETICA, 8f, Font.NORMAL, MUTED)));
                det.setBorder(Rectangle.NO_BORDER);
                det.setHorizontalAlignment(Element.ALIGN_RIGHT);
                det.setVerticalAlignment(Element.ALIGN_MIDDLE);
                inner.addCell(det);

                PdfPCell rowCell = boxed(new BoxStyle().fill(Color.WHITE).border(HAIRLINE_SOFT, 0.8f).radius(7f).underlay());
                rowCell.setPadding(5.5f); rowCell.setPaddingLeft(9f); rowCell.setPaddingRight(9f);
                rowCell.addElement(inner);
                PdfPTable outer = new PdfPTable(1);
                outer.setWidthPercentage(100);
                outer.setSpacingBefore(first ? 7f : 4f);
                outer.addCell(rowCell);
                doc.add(outer);
                first = false;
            }
        } catch (Exception e) { throw new IllegalStateException("PDF lineItems failed", e); }
    }

    /** Two side-by-side assessment cards (a single card renders full-width). */
    public void twoUpCards(Card left, Card right) {
        try {
            if (left != null && right != null) {
                PdfPTable t = new PdfPTable(new float[]{49f, 2f, 49f});
                t.setWidthPercentage(100); t.setSpacingBefore(7f);
                t.addCell(cardCell(left)); t.addCell(gapCell()); t.addCell(cardCell(right));
                doc.add(t);
            } else {
                Card only = left != null ? left : right;
                if (only == null) return;
                PdfPTable t = new PdfPTable(1);
                t.setWidthPercentage(100); t.setSpacingBefore(7f);
                t.addCell(cardCell(only));
                doc.add(t);
            }
        } catch (Exception e) { throw new IllegalStateException("PDF twoUpCards failed", e); }
    }

    private static PdfPCell cardCell(Card c) {
        PdfPCell cell = boxed(new BoxStyle().fill(c.bg()).border(c.border(), 0.9f).radius(8f));
        cell.setPadding(9f); cell.setPaddingLeft(12f);
        Paragraph hp = new Paragraph();
        Chunk hk = new Chunk(c.heading().toUpperCase(), new Font(Font.HELVETICA, 8f, Font.BOLD, c.headingColor()));
        hk.setCharacterSpacing(0.3f);
        hp.add(hk);
        if (c.chip() != null && !c.chip().isBlank()) {
            hp.add(new Chunk("  ", hk.getFont()));
            hp.add(inlineChip(c.chip(), c.chipBg(), c.chipFg()));
        }
        hp.setLeading(12f);
        cell.addElement(hp);
        List<String> body = c.body();
        for (int i = 0; body != null && i < body.size(); i++) {
            String ln = body.get(i);
            if (ln == null || ln.isBlank()) continue;
            Paragraph bp = new Paragraph(ln, new Font(Font.HELVETICA, 8.5f, Font.NORMAL, i == 0 ? INK : MUTED));
            bp.setLeading(11.5f); bp.setSpacingBefore(i == 0 ? 4.5f : 1.5f);
            cell.addElement(bp);
        }
        return cell;
    }

    /** Dot-marker timeline rows; give the current/last row an acuity-coloured dot. */
    public void timeline(List<TimelineRow> rows) {
        if (rows == null || rows.isEmpty()) return;
        try {
            boolean first = true;
            for (TimelineRow r : rows) {
                PdfPTable t = new PdfPTable(new float[]{3.5f, 96.5f});
                t.setWidthPercentage(100);
                t.setSpacingBefore(first ? 8f : 6f); first = false;
                final Color dotColor = r.dot() != null ? r.dot() : BRAND;
                PdfPCell dc = new PdfPCell(); dc.setBorder(Rectangle.NO_BORDER);
                dc.setCellEvent((cell, pos, canvases) -> {
                    PdfContentByte bg = canvases[PdfPTable.BACKGROUNDCANVAS];
                    bg.saveState();
                    bg.setColorFill(dotColor);
                    bg.circle(pos.getLeft() + 4f, (pos.getBottom() + pos.getTop()) / 2f, 3.2f);
                    bg.fill();
                    bg.restoreState();
                });
                t.addCell(dc);
                Paragraph p = new Paragraph();
                p.add(new Chunk(r.label(), new Font(Font.HELVETICA, 9, Font.BOLD, INK)));
                if (r.value() != null && !r.value().isBlank())
                    p.add(new Chunk("  —  " + r.value(), new Font(Font.HELVETICA, 9, Font.NORMAL, INK)));
                if (r.note() != null && !r.note().isBlank())
                    p.add(new Chunk("   " + r.note(), new Font(Font.HELVETICA, 8.5f, Font.NORMAL, MUTED)));
                p.setLeading(11f);
                PdfPCell tc = new PdfPCell(p);
                tc.setBorder(Rectangle.NO_BORDER); tc.setVerticalAlignment(Element.ALIGN_MIDDLE);
                t.addCell(tc);
                doc.add(t);
            }
        } catch (Exception e) { throw new IllegalStateException("PDF timeline failed", e); }
    }

    /**
     * Severity-grouped alerts: per group a small bold coloured uppercase heading
     * ("CRITICAL · 6") then left-bordered coloured cards (bold lead + body, with
     * an optional trailing "unacknowledged N min" escalation tag).
     */
    public void alertGroups(List<AlertGroup> groups) {
        if (groups == null || groups.isEmpty()) return;
        try {
            for (AlertGroup g : groups) {
                if (g.items() == null || g.items().isEmpty()) continue;
                Chunk gk = new Chunk(g.title().toUpperCase(), new Font(Font.HELVETICA, 8f, Font.BOLD, g.headColor()));
                gk.setCharacterSpacing(0.4f);
                Paragraph gp = new Paragraph(gk);
                gp.setSpacingBefore(10f); gp.setSpacingAfter(1f); gp.setLeading(10f);
                doc.add(gp);
                for (AlertItem it : g.items()) {
                    Paragraph p = new Paragraph();
                    if (it.lead() != null && !it.lead().isBlank())
                        p.add(new Chunk(it.lead() + "  ", new Font(Font.HELVETICA, 8.5f, Font.BOLD, INK)));
                    if (it.body() != null && !it.body().isBlank())
                        p.add(new Chunk(it.body(), new Font(Font.HELVETICA, 8.5f, Font.NORMAL, INK_SOFT)));
                    if (it.escTag() != null && !it.escTag().isBlank())
                        p.add(new Chunk("   " + it.escTag(), new Font(Font.HELVETICA, 7f, Font.BOLD, ALERT_CRIT_HEAD)));
                    p.setLeading(11.5f);
                    PdfPCell rowCell = boxed(new BoxStyle().fill(g.cardBg()).leftStripe(g.bar(), 2.6f).radius(6f));
                    rowCell.setPadding(7f); rowCell.setPaddingLeft(12f); rowCell.setPaddingRight(10f);
                    rowCell.addElement(p);
                    PdfPTable outer = new PdfPTable(1);
                    outer.setWidthPercentage(100);
                    outer.setSpacingBefore(4f);
                    outer.addCell(rowCell);
                    doc.add(outer);
                }
            }
        } catch (Exception e) { throw new IllegalStateException("PDF alertGroups failed", e); }
    }

    /**
     * Two side-by-side bordered signature boxes (handing-over / receiving), each
     * with blank Name / Signature / Date-time underlines to complete by hand.
     */
    public void signatureBoxes(String leftLabel, String rightLabel) {
        try {
            PdfPTable t = new PdfPTable(new float[]{49f, 2f, 49f});
            t.setWidthPercentage(100);
            t.setSpacingBefore(10f);
            t.addCell(sigBox(leftLabel));
            t.addCell(gapCell());
            t.addCell(sigBox(rightLabel));
            doc.add(t);
        } catch (Exception e) { throw new IllegalStateException("PDF signatureBoxes failed", e); }
    }

    private static PdfPCell sigBox(String label) {
        PdfPCell cell = boxed(new BoxStyle().fill(Color.WHITE).border(HAIRLINE_SOFT, 0.9f).radius(8f).underlay());
        cell.setPadding(12f);
        Chunk lk = new Chunk(label != null ? label.toUpperCase() : "", F_LABEL);
        lk.setCharacterSpacing(0.5f);
        Paragraph lp = new Paragraph(lk);
        lp.setSpacingAfter(12f); lp.setLeading(10f);
        cell.addElement(lp);
        for (String line : new String[]{"Name", "Signature", "Date / time"}) {
            Paragraph p = new Paragraph(line, new Font(Font.HELVETICA, 8, Font.NORMAL, SLATE_400));
            p.setLeading(9f);
            cell.addElement(p);
            PdfPTable ul = new PdfPTable(1);
            ul.setWidthPercentage(100);
            PdfPCell ulc = new PdfPCell(new Phrase(" ", F_META));
            ulc.setBorder(Rectangle.BOTTOM);
            ulc.setBorderColor(HAIRLINE);
            ulc.setBorderWidthBottom(0.8f);
            ulc.setFixedHeight(15f);
            ul.addCell(ulc);
            cell.addElement(ul);
        }
        return cell;
    }

    /**
     * The #1a patient banner: a neutral rounded panel (#f8fafc / #e2e8f0) whose
     * first row carries the patient name + a labelled ID row (Visit/MRN in mono)
     * on the left and a solid acuity pill with a "TEWS n" caption on the right,
     * and whose second row — separated by a hairline — carries the report-meta
     * items with the acknowledgement rendered as a status chip.
     *
     * @param ids            label/value pairs; values labelled Visit/MRN render mono
     * @param tews           small caption under the pill (e.g. "TEWS 5"); may be null
     * @param meta           the plain meta items (report type / generated / handing over)
     * @param acknowledgement the acknowledgement item rendered as a chip (may be null)
     * @param ackAmber       true → amber "pending" chip, false → green "done" chip
     */
    public void patientBanner(String name, List<KeyVal> ids,
                              String acuityLevel, String acuityMeaning, Color acuityColor, String tews,
                              List<KeyVal> meta, KeyVal acknowledgement, boolean ackAmber) {
        try {
            boolean hasPill = acuityLevel != null && !acuityLevel.isBlank() && acuityColor != null;

            // ── Row 1: name + id row (left) | acuity pill + TEWS (right) ──
            PdfPTable row1 = new PdfPTable(hasPill ? new float[]{76f, 24f} : new float[]{100f});
            row1.setWidthPercentage(100);
            row1.getDefaultCell().setBorder(Rectangle.NO_BORDER);

            PdfPCell left = new PdfPCell();
            left.setBorder(Rectangle.NO_BORDER);
            left.setVerticalAlignment(Element.ALIGN_TOP);
            Paragraph nm = new Paragraph(name != null && !name.isBlank() ? name : "Unknown",
                    new Font(Font.HELVETICA, 13.5f, Font.BOLD, INK));
            nm.setLeading(15f);
            left.addElement(nm);
            List<KeyVal> shownIds = ids == null ? List.of() : ids.stream()
                    .filter(p -> p.value() != null && !p.value().isBlank()).toList();
            if (!shownIds.isEmpty()) {
                PdfPTable idT = new PdfPTable(idRowWidths(shownIds));
                idT.setWidthPercentage(100);
                idT.setSpacingBefore(6f);
                idT.getDefaultCell().setBorder(Rectangle.NO_BORDER);
                for (KeyVal id : shownIds) {
                    PdfPCell ic = new PdfPCell();
                    ic.setBorder(Rectangle.NO_BORDER);
                    ic.setPaddingRight(8f);
                    Chunk lc = new Chunk(id.label().toUpperCase(), new Font(Font.HELVETICA, 6f, Font.BOLD, MUTED));
                    lc.setCharacterSpacing(0.4f);
                    Paragraph lp = new Paragraph(lc);
                    lp.setLeading(7f);
                    ic.addElement(lp);
                    boolean mono = id.label().toLowerCase().contains("visit") || id.label().equalsIgnoreCase("MRN");
                    Font vf = mono ? new Font(Font.COURIER, 7.3f, Font.NORMAL, INK)
                                   : new Font(Font.HELVETICA, 7.7f, Font.NORMAL, INK);
                    Paragraph vp = new Paragraph(id.value(), vf);
                    vp.setLeading(9.5f);
                    vp.setSpacingBefore(1.5f);
                    ic.addElement(vp);
                    idT.addCell(ic);
                }
                left.addElement(idT);
            }
            row1.addCell(left);

            if (hasPill) {
                PdfPCell right = new PdfPCell();
                right.setBorder(Rectangle.NO_BORDER);
                right.setVerticalAlignment(Element.ALIGN_MIDDLE);
                right.setHorizontalAlignment(Element.ALIGN_CENTER);
                String pillText = acuityMeaning != null && !acuityMeaning.isBlank()
                        ? acuityLevel.toUpperCase() + " · " + acuityMeaning.toUpperCase()
                        : acuityLevel.toUpperCase();
                PdfPTable pill = solidPill(pillText, acuityColor);
                pill.setHorizontalAlignment(Element.ALIGN_CENTER);
                right.addElement(pill);
                if (tews != null && !tews.isBlank()) {
                    Paragraph tp = new Paragraph(tews, new Font(Font.HELVETICA, 6.3f, Font.NORMAL, SLATE_400));
                    tp.setAlignment(Element.ALIGN_CENTER);
                    tp.setLeading(7f);
                    tp.setSpacingBefore(3.5f);
                    right.addElement(tp);
                }
                row1.addCell(right);
            }

            // ── Row 2: report-meta items + acknowledgement chip, over a hairline ──
            List<KeyVal> metaItems = meta == null ? new ArrayList<>() : new ArrayList<>(meta.stream()
                    .filter(p -> p.value() != null && !p.value().isBlank()).toList());
            boolean hasAck = acknowledgement != null && acknowledgement.value() != null
                    && !acknowledgement.value().isBlank();
            int cols = metaItems.size() + (hasAck ? 1 : 0);
            PdfPTable row2 = null;
            if (cols > 0) {
                row2 = new PdfPTable(metaRowWidths(metaItems.size(), hasAck));
                row2.setWidthPercentage(100);
                row2.setSpacingBefore(11f);
                for (KeyVal m : metaItems) row2.addCell(metaCell(m.label(), m.value()));
                if (hasAck) {
                    PdfPCell ac = new PdfPCell();
                    ac.setBorder(Rectangle.TOP);
                    ac.setBorderColor(HAIRLINE_SOFT);
                    ac.setBorderWidthTop(0.9f);
                    ac.setPaddingTop(9f);
                    PdfPTable inner = new PdfPTable(new float[]{60f, 40f});
                    inner.setWidthPercentage(100);
                    inner.getDefaultCell().setBorder(Rectangle.NO_BORDER);
                    PdfPCell la = new PdfPCell();
                    la.setBorder(Rectangle.NO_BORDER);
                    la.setVerticalAlignment(Element.ALIGN_MIDDLE);
                    Chunk lac = new Chunk(acknowledgement.label().toUpperCase(),
                            new Font(Font.HELVETICA, 6f, Font.BOLD, MUTED));
                    lac.setCharacterSpacing(0.3f);
                    Paragraph lap = new Paragraph(lac);
                    lap.setLeading(8f);
                    la.addElement(lap);
                    inner.addCell(la);
                    PdfPCell ca = new PdfPCell();
                    ca.setBorder(Rectangle.NO_BORDER);
                    ca.setVerticalAlignment(Element.ALIGN_MIDDLE);
                    ca.addElement(miniChip(acknowledgement.value(),
                            ackAmber ? CHIP_AMBER_BG : CHIP_GREEN_BG,
                            ackAmber ? CHIP_AMBER_FG : CHIP_GREEN_FG));
                    inner.addCell(ca);
                    ac.addElement(inner);
                    row2.addCell(ac);
                }
            }

            PdfPCell panel = boxed(new BoxStyle().fill(PANEL).border(HAIRLINE_SOFT, 1f).radius(10f).underlay());
            panel.setPaddingTop(12f);
            panel.setPaddingBottom(12f);
            panel.setPaddingLeft(14f);
            panel.setPaddingRight(14f);
            panel.addElement(row1);
            if (row2 != null) panel.addElement(row2);
            PdfPTable outer = new PdfPTable(1);
            outer.setWidthPercentage(100);
            outer.setSpacingBefore(6f);
            outer.addCell(panel);
            doc.add(outer);
        } catch (Exception e) {
            throw new IllegalStateException("PDF patientBanner (banner v2) failed", e);
        }
    }

    /** Column widths for the banner id row — give the (long, mono) Visit value more room. */
    private static float[] idRowWidths(List<KeyVal> ids) {
        int n = ids.size();
        if (n == 4) return new float[]{33f, 20f, 15f, 22f};
        float[] w = new float[n];
        java.util.Arrays.fill(w, 1f);
        return w;
    }

    private static float[] metaRowWidths(int metaCount, boolean hasAck) {
        int n = metaCount + (hasAck ? 1 : 0);
        // ACKNOWLEDGEMENT (col 4) is the longest label + a chip, so it gets the most
        // room; without it "ACKNOWLEDGEMENT" broke mid-word ("ACKNOWLEDGE / MENT").
        if (n == 4) return new float[]{18f, 19f, 30f, 33f};
        float[] w = new float[n];
        java.util.Arrays.fill(w, 1f);
        return w;
    }

    /** One banner meta item: small uppercase label + value on one line, over a hairline. */
    private static PdfPCell metaCell(String label, String value) {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.TOP);
        c.setBorderColor(HAIRLINE_SOFT);
        c.setBorderWidthTop(0.9f);
        c.setPaddingTop(9f);
        c.setPaddingRight(8f);
        Paragraph p = new Paragraph();
        Chunk lc = new Chunk(label.toUpperCase() + "  ", new Font(Font.HELVETICA, 6.3f, Font.BOLD, MUTED));
        lc.setCharacterSpacing(0.3f);
        p.add(lc);
        p.add(new Chunk(value, new Font(Font.HELVETICA, 7.4f, Font.NORMAL, INK)));
        p.setLeading(10f);
        c.addElement(p);
        return c;
    }

    /** A solid, fully-rounded acuity pill (coloured fill, white text), sized to hug its text. */
    private static PdfPTable solidPill(String text, Color bg) {
        Font f = new Font(Font.HELVETICA, 7f, Font.BOLD, Color.WHITE);
        float w;
        try { w = f.getCalculatedBaseFont(true).getWidthPoint(text, 7f) + 18f; }
        catch (Exception e) { w = text.length() * 4.4f + 18f; }
        PdfPTable t = new PdfPTable(1);
        t.setTotalWidth(w);
        t.setLockedWidth(true);
        t.setHorizontalAlignment(Element.ALIGN_CENTER);
        PdfPCell c = boxed(new BoxStyle().fill(bg).radius(11f));
        c.setFixedHeight(16f);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setPaddingTop(3.6f);
        Chunk tc = new Chunk(text, f);
        tc.setCharacterSpacing(0.3f);
        Paragraph p = new Paragraph(tc);
        p.setAlignment(Element.ALIGN_CENTER);
        p.setLeading(8f);
        c.addElement(p);
        t.addCell(c);
        return t;
    }

    /**
     * #1a stat / vitals tiles — a flat bordered card (1px #e2e8f0, radius 8, no
     * accent), centered value (optionally semantic-coloured) over a small muted
     * uppercase label. {@code perRow} tiles per row; {@code valueSize} the value
     * font size (≈11 for the 4-up stats, ≈9.5 for the 6-up vitals).
     */
    public void statCards(List<StatTile> tiles, int perRow, float valueSize) {
        List<StatTile> shown = tiles.stream()
                .filter(s -> s.value() != null && !s.value().isBlank()).toList();
        if (shown.isEmpty()) return;
        try {
            int n = Math.min(perRow, Math.max(1, shown.size()));
            PdfPTable t = new PdfPTable(gutteredWidths(n, 10f, 0.7f));
            t.setWidthPercentage(100);
            t.setSpacingBefore(6f);
            int i = 0;
            for (StatTile s : shown) {
                if (i > 0 && i % n == 0) {
                    PdfPCell g = gapCell();
                    g.setColspan(n * 2 - 1);
                    g.setFixedHeight(5f);
                    t.addCell(g);
                }
                PdfPCell cell = boxed(new BoxStyle().fill(Color.WHITE).border(HAIRLINE_SOFT, 1f).radius(8f));
                cell.setPadding(7f);
                Paragraph num = new Paragraph(s.value(),
                        new Font(Font.HELVETICA, valueSize, Font.BOLD, s.valueColor() != null ? s.valueColor() : INK));
                num.setAlignment(Element.ALIGN_CENTER);
                num.setLeading(valueSize + 1.5f);
                cell.addElement(num);
                Chunk lc = new Chunk(s.label().toUpperCase(), F_TILE_LBL);
                lc.setCharacterSpacing(0.4f);
                Paragraph lbl = new Paragraph(lc);
                lbl.setAlignment(Element.ALIGN_CENTER);
                lbl.setLeading(8f);
                lbl.setSpacingBefore(2.5f);
                cell.addElement(lbl);
                t.addCell(cell);
                if (i % n != n - 1 && i != shown.size() - 1) t.addCell(gapCell());
                i++;
            }
            int rem = shown.size() % n;
            if (rem != 0) for (int k = rem; k < n; k++) { t.addCell(gapCell()); if (k < n - 1) t.addCell(gapCell()); }
            doc.add(t);
        } catch (Exception e) { throw new IllegalStateException("PDF statCards failed", e); }
    }

    public void paragraph(String text, Font font) {
        if (text == null || text.isBlank()) return;
        try { doc.add(new Paragraph(text, font)); }
        catch (Exception e) { throw new IllegalStateException("PDF paragraph failed", e); }
    }

    /** Close the document and return the rendered bytes. */
    public byte[] finish() {
        try {
            doc.close();
        } catch (Exception e) {
            throw new IllegalStateException("Could not finish PDF report", e);
        }
        return out.toByteArray();
    }

    private static Paragraph accentRule(float width, Color color) {
        Paragraph p = new Paragraph("");
        p.setSpacingBefore(3f);
        p.add(new com.lowagie.text.Chunk(new com.lowagie.text.pdf.draw.LineSeparator(
                width, 100f, color, Element.ALIGN_CENTER, -2)));
        p.setSpacingAfter(2f);
        return p;
    }

    /** A FRESH logo Image per call (bytes cached once from the classpath); null-safe. */
    private static Image logo() {
        byte[] bytes = cachedLogoBytes;
        if (bytes == null) {
            try (var in = PdfReport.class.getResourceAsStream("/branding/logo.png")) {
                bytes = in != null ? in.readAllBytes() : EMPTY;
            } catch (Exception e) {
                bytes = EMPTY;
            }
            cachedLogoBytes = bytes;
        }
        if (bytes.length == 0) return null; // brand text still renders; logo is a nice-to-have
        try {
            return Image.getInstance(bytes); // fresh instance — safe across concurrent renders
        } catch (Exception e) {
            return null;
        }
    }

    // ── Every-page chrome: running header (p≥2) + footer w/ attribution + Page X of Y ──
    private static final class Chrome extends PdfPageEventHelper {
        private final Spec spec;
        private final Font footFont = new Font(Font.HELVETICA, 7, Font.NORMAL, SLATE_400);
        private final Font footStrong = new Font(Font.HELVETICA, 7, Font.BOLD, BRAND_DEEP);
        private final Instant generatedAt;

        Chrome(Spec spec, Instant generatedAt) { this.spec = spec; this.generatedAt = generatedAt; }

        @Override
        public void onEndPage(PdfWriter writer, Document doc) {
            PdfContentByte cb = writer.getDirectContent();

            // #1a footer band — a hairline over three parts, repeating on every page.
            // No running header and no page numbers: the masthead is page 1 only and
            // content simply continues on later pages.
            float y = doc.bottom() - 16;
            cb.setColorStroke(HAIRLINE_SOFT);
            cb.setLineWidth(0.7f);
            cb.moveTo(doc.left(), y + 8);
            cb.lineTo(doc.right(), y + 8);
            cb.stroke();

            // Left: confidentiality (#94a3b8). Center: attribution (#94a3b8).
            String conf = spec.confidentiality() != null ? spec.confidentiality() : "Confidential";
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT,
                    new Phrase("CONFIDENTIAL — " + conf, footFont), doc.left(), y, 0);

            String who = (spec.exportedByName() != null && !spec.exportedByName().isBlank())
                    ? spec.exportedByName() : "SmartTriage user";
            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER,
                    new Phrase("Generated by " + who + " · " + TS.format(generatedAt), footFont),
                    (doc.left() + doc.right()) / 2, y, 0);

            // Right: document wordmark (#0369a1, bold).
            String mark = "SmartTriage · " + (spec.reportKind() != null ? spec.reportKind() : "Report");
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT, new Phrase(mark, footStrong), doc.right(), y, 0);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Redesign #1b primitives — flat "ledger / dossier" style.
    // Additive: nothing above changes. beginLedger() swaps ONLY the
    // masthead + footer for the flat ink-ruled ledger chrome; every helper
    // below draws flat bordered tables — no rounded corners, no shadow —
    // per design #1b. Tokens: ink #0f172a, hairline #cbd5e1, muted #64748b,
    // header/zebra fill #f1f5f9, monospace = Courier.
    // ══════════════════════════════════════════════════════════════════

    /** #1b header / zebra fill (#f1f5f9 — slate-100). */
    public static final Color LEDGER_FILL = new Color(241, 245, 249);

    // #1b type scale (Helvetica; Courier for mono data per the README).
    static final Font F_L_WORDMARK  = new Font(Font.HELVETICA, 10.5f, Font.BOLD, INK);
    static final Font F_L_WORDSOFT  = new Font(Font.HELVETICA, 10.5f, Font.NORMAL, MUTED);
    static final Font F_L_ORGLINE   = new Font(Font.HELVETICA, 6.3f, Font.NORMAL, MUTED);
    static final Font F_L_TITLE     = new Font(Font.HELVETICA, 13f, Font.BOLD, INK);
    static final Font F_L_GEN       = new Font(Font.COURIER, 7.5f, Font.NORMAL, MUTED);
    static final Font F_L_LC        = new Font(Font.HELVETICA, 6.8f, Font.BOLD, MUTED);   // .lc id label
    static final Font F_L_LV        = new Font(Font.HELVETICA, 8.5f, Font.NORMAL, INK);   // .lv id value
    static final Font F_L_LV_MONO   = new Font(Font.COURIER, 8f, Font.NORMAL, INK);
    static final Font F_L_SN        = new Font(Font.HELVETICA, 7.5f, Font.BOLD, Color.WHITE); // "01" tag
    static final Font F_L_SH        = new Font(Font.HELVETICA, 9.5f, Font.BOLD, INK);     // section label
    static final Font F_L_K2        = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, MUTED); // .k2
    static final Font F_L_V2        = new Font(Font.HELVETICA, 8.5f, Font.NORMAL, INK);   // .v2
    static final Font F_L_V2_MONO   = new Font(Font.COURIER, 8f, Font.NORMAL, INK);
    static final Font F_L_TH        = new Font(Font.HELVETICA, 6.8f, Font.BOLD, MUTED);   // table header
    static final Font F_L_TD        = new Font(Font.HELVETICA, 8f, Font.NORMAL, INK);     // table cell
    static final Font F_L_TD_MONO   = new Font(Font.COURIER, 7.6f, Font.NORMAL, INK);
    static final Font F_L_SIG       = new Font(Font.HELVETICA, 8f, Font.NORMAL, SLATE_400);

    /** ISO-ish timestamp for the ledger masthead's "Generated …" line. */
    private static final DateTimeFormatter TS_ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Africa/Kigali"));

    /**
     * A cell in a #1b bordered table (the ID table or a key/value table): a label
     * + a styled value. {@code full} spans the value across the whole row;
     * {@code mono} renders the value in Courier (dates / IDs / numeric data);
     * {@code color} colours (and bolds) the value; {@code chip} renders the value
     * as an OUTLINED chip (1.5px border + text both in {@code color}).
     */
    public record LedgerCell(String label, String value, boolean full, boolean mono, Color color, boolean chip) {}
    public static LedgerCell lcell(String label, String value)      { return new LedgerCell(label, value, false, false, null, false); }
    public static LedgerCell lcellFull(String label, String value)  { return new LedgerCell(label, value, true, false, null, false); }
    public static LedgerCell lcellMono(String label, String value)  { return new LedgerCell(label, value, false, true, null, false); }
    public static LedgerCell lcellFullMono(String label, String value) { return new LedgerCell(label, value, true, true, null, false); }
    public static LedgerCell lcellColor(String label, String value, Color color) { return new LedgerCell(label, value, false, false, color, false); }
    public static LedgerCell lcellChip(String label, String value, Color color)  { return new LedgerCell(label, value, false, false, color, true); }

    /** A row of the compact #1b alert table: severity square + label, alert lead, and a detail. */
    public record LedgerAlert(Color severityColor, String severityLabel, String alert, String detail) {}
    public static LedgerAlert lalert(Color severityColor, String severityLabel, String alert, String detail) {
        return new LedgerAlert(severityColor, severityLabel, alert, detail);
    }

    /** Open a flat #1b "ledger / dossier" A4 report — ink-ruled masthead + footer, flat bordered tables. */
    public static PdfReport beginLedger(Spec spec) {
        Document doc = new Document(PageSize.A4, 42, 42, 44, 58);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            Instant generatedAt = Instant.now();
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new LedgerChrome(spec, generatedAt));
            doc.open();
            PdfReport r = new PdfReport(doc, out);
            r.ledgerMasthead(spec, generatedAt);
            return r;
        } catch (Exception e) {
            throw new IllegalStateException("Could not start ledger PDF report", e);
        }
    }

    // ── #1b masthead (page 1) ─────────────────────────────────────────
    private void ledgerMasthead(Spec spec, Instant generatedAt) throws Exception {
        // Row A: 28px logo + "SMARTTRIAGE · Healthcare Platform" (left) | hospital
        // one-liner (right), over a 2px solid ink rule.
        PdfPTable rowA = new PdfPTable(new float[]{47f, 53f});
        rowA.setWidthPercentage(100);
        rowA.getDefaultCell().setBorder(Rectangle.NO_BORDER);

        PdfPTable left = new PdfPTable(new float[]{26f, 174f});
        left.setTotalWidth(200f);
        left.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        PdfPCell logoCell = new PdfPCell();
        logoCell.setBorder(Rectangle.NO_BORDER);
        logoCell.setHorizontalAlignment(Element.ALIGN_LEFT);
        logoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        Image logo = logo();
        if (logo != null) {
            logo.scaleToFit(22, 22);
            logo.setAlignment(Image.ALIGN_LEFT);
            logoCell.addElement(logo);
        }
        left.addCell(logoCell);
        PdfPCell wm = new PdfPCell();
        wm.setBorder(Rectangle.NO_BORDER);
        wm.setVerticalAlignment(Element.ALIGN_MIDDLE);
        Paragraph wmp = new Paragraph();
        Chunk brand = new Chunk("SMARTTRIAGE", F_L_WORDMARK);
        brand.setCharacterSpacing(0.3f);
        wmp.add(brand);
        wmp.add(new Chunk(" · Healthcare Platform", F_L_WORDSOFT));
        wmp.setLeading(13f);
        wm.addElement(wmp);
        left.addCell(wm);
        PdfPCell leftWrap = new PdfPCell(left);
        leftWrap.setBorder(Rectangle.NO_BORDER);
        leftWrap.setVerticalAlignment(Element.ALIGN_BOTTOM);
        leftWrap.setPaddingBottom(3f);
        rowA.addCell(leftWrap);

        PdfPCell right = new PdfPCell(new Phrase(ledgerOrgLine(spec), F_L_ORGLINE));
        right.setBorder(Rectangle.NO_BORDER);
        right.setHorizontalAlignment(Element.ALIGN_RIGHT);
        right.setVerticalAlignment(Element.ALIGN_BOTTOM);
        right.setPaddingBottom(3f);
        rowA.addCell(right);
        doc.add(rowA);

        // 2px solid ink rule.
        doc.add(accentRule(2.0f, INK));

        // Row B: report title (left) | mono "Generated …" (right).
        PdfPTable rowB = new PdfPTable(new float[]{66f, 34f});
        rowB.setWidthPercentage(100);
        rowB.setSpacingBefore(6f);
        rowB.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        Chunk tc = new Chunk(spec.reportTitle() != null ? spec.reportTitle().toUpperCase() : "", F_L_TITLE);
        tc.setCharacterSpacing(0.3f);
        PdfPCell tCell = new PdfPCell(new Phrase(tc));
        tCell.setBorder(Rectangle.NO_BORDER);
        tCell.setVerticalAlignment(Element.ALIGN_BOTTOM);
        rowB.addCell(tCell);
        PdfPCell gCell = new PdfPCell(new Phrase("Generated " + TS_ISO.format(generatedAt), F_L_GEN));
        gCell.setBorder(Rectangle.NO_BORDER);
        gCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        gCell.setVerticalAlignment(Element.ALIGN_BOTTOM);
        rowB.addCell(gCell);
        doc.add(rowB);
        spacer(2f);
    }

    /** "Org · address · phone · email" one-liner for the ledger masthead (blanks dropped). */
    private static String ledgerOrgLine(Spec spec) {
        StringBuilder sb = new StringBuilder();
        if (spec.orgName() != null && !spec.orgName().isBlank()) sb.append(spec.orgName().trim());
        if (spec.orgMeta() != null) {
            for (String m : spec.orgMeta()) {
                if (m == null || m.isBlank()) continue;
                if (sb.length() > 0) sb.append("  ·  ");
                sb.append(m.trim());
            }
        }
        return sb.toString();
    }

    // ── #1b bordered ID table (labels tinted, all cells 1px hairline) ──
    /**
     * The flat bordered ID table under the masthead — the #1b analogue of the
     * patient banner. Cells pack two label/value pairs per row (11–15% tinted
     * label cells + value cells); a {@code full} cell spans the value across the
     * row. A {@code chip} value renders as an OUTLINED chip in its category colour.
     */
    public void ledgerIdTable(List<LedgerCell> cells) {
        List<LedgerCell> shown = cells.stream()
                .filter(c -> c.value() != null && !c.value().isBlank()).toList();
        if (shown.isEmpty()) return;
        try {
            PdfPTable t = new PdfPTable(new float[]{17f, 33f, 17f, 33f});
            t.setWidthPercentage(100);
            t.setSpacingBefore(10f);
            int col = 0;                                   // 0 = row start, 2 = second pair
            for (LedgerCell c : shown) {
                if (c.full() && col == 2) { t.addCell(padCell()); t.addCell(padCell()); col = 0; }
                t.addCell(idLabelCell(c.label()));
                PdfPCell v = idValueCell(c);
                if (c.full()) { v.setColspan(3); t.addCell(v); col = 0; }
                else { t.addCell(v); col = (col == 0) ? 2 : 0; }
            }
            if (col == 2) { t.addCell(padCell()); t.addCell(padCell()); }
            doc.add(t);
        } catch (Exception e) { throw new IllegalStateException("PDF ledgerIdTable failed", e); }
    }

    private static PdfPCell idLabelCell(String label) {
        Chunk c = new Chunk(label != null ? label.toUpperCase() : "", F_L_LC);
        c.setCharacterSpacing(0.3f);
        PdfPCell cell = new PdfPCell(new Phrase(c));
        cell.setBackgroundColor(LEDGER_FILL);
        cell.setBorderColor(HAIRLINE);
        cell.setBorderWidth(1f);
        cell.setPadding(5f);
        cell.setPaddingLeft(7f);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return cell;
    }

    private static PdfPCell idValueCell(LedgerCell c) {
        PdfPCell cell;
        if (c.chip() && c.color() != null) {
            cell = new PdfPCell();
            cell.addElement(ledgerOutlineChip(c.value(), c.color()));
            cell.setPaddingTop(3.5f); cell.setPaddingBottom(3.5f); cell.setPaddingRight(4f);
        } else {
            Font f = c.mono() ? F_L_LV_MONO
                    : (c.color() != null ? new Font(Font.HELVETICA, 8.5f, Font.BOLD, c.color()) : F_L_LV);
            cell = new PdfPCell(new Phrase(c.value(), f));
            cell.setPadding(5f);
        }
        cell.setBorderColor(HAIRLINE);
        cell.setBorderWidth(1f);
        cell.setPaddingLeft(7f);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return cell;
    }

    /** An outlined (border-only) square-ish chip in {@code color}: the #1b triage/severity treatment. */
    private static PdfPTable ledgerOutlineChip(String text, Color color) {
        Font f = new Font(Font.HELVETICA, 8.5f, Font.BOLD, color);
        float w;
        try { w = f.getCalculatedBaseFont(true).getWidthPoint(text, 8.5f) + 16f; }
        catch (Exception e) { w = text.length() * 5f + 16f; }
        PdfPTable t = new PdfPTable(1);
        t.setTotalWidth(w);
        t.setLockedWidth(true);
        t.setHorizontalAlignment(Element.ALIGN_LEFT);
        PdfPCell c = boxed(new BoxStyle().border(color, 1.5f).radius(3f));
        c.setFixedHeight(15f);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setPaddingTop(3f);
        Chunk tc = new Chunk(text, f);
        tc.setCharacterSpacing(0.2f);
        Paragraph p = new Paragraph(tc);
        p.setAlignment(Element.ALIGN_CENTER);
        p.setLeading(9f);
        c.addElement(p);
        t.addCell(c);
        return t;
    }

    // ── #1b numbered section header ───────────────────────────────────
    /** A numbered #1b section header: a dark "01" tag + uppercase label + a 1.5px ink bottom rule. */
    public void ledgerSection(String number, String label) { ledgerSection(number, label, false); }

    /** As {@link #ledgerSection(String, String)}; {@code alert} renders the tag + rule in red. */
    public void ledgerSection(String number, String label, boolean alert) {
        try {
            Color accent = alert ? SATS_RED : INK;
            Paragraph p = new Paragraph();
            Chunk tag = new Chunk(number != null ? number : "", F_L_SN);
            tag.setBackground(accent, 3.5f, 2f, 3.5f, 2.5f);
            p.add(tag);
            Chunk lbl = new Chunk("   " + (label != null ? label.toUpperCase() : ""), F_L_SH);
            lbl.setCharacterSpacing(0.4f);
            p.add(lbl);
            p.setLeading(13f);
            PdfPCell cell = new PdfPCell(p);
            cell.setBorder(Rectangle.BOTTOM);
            cell.setBorderColor(accent);
            cell.setBorderWidthBottom(1.5f);
            cell.setPaddingTop(3f);
            cell.setPaddingBottom(5f);
            cell.setPaddingLeft(0f);
            PdfPTable t = new PdfPTable(1);
            t.setWidthPercentage(100);
            t.setSpacingBefore(15f);
            t.setSpacingAfter(3f);
            t.addCell(cell);
            doc.add(t);
        } catch (Exception e) { throw new IllegalStateException("PDF ledgerSection failed", e); }
    }

    // ── #1b bordered key/value table (faint bottom-rule rows) ─────────
    /** A flat key/value table (#1b .k2/.v2): muted labels + values, faint row rules, no outer border. */
    public void ledgerKv(List<LedgerCell> cells) {
        List<LedgerCell> shown = cells.stream()
                .filter(c -> c.value() != null && !c.value().isBlank()).toList();
        if (shown.isEmpty()) return;
        try {
            PdfPTable t = new PdfPTable(new float[]{18f, 32f, 18f, 32f});
            t.setWidthPercentage(100);
            t.setSpacingBefore(2f);
            int col = 0;
            for (LedgerCell c : shown) {
                if (c.full() && col == 2) { t.addCell(padCell()); t.addCell(padCell()); col = 0; }
                t.addCell(kvLabelCell(c.label()));
                PdfPCell v = kvValueCell(c);
                if (c.full()) { v.setColspan(3); t.addCell(v); col = 0; }
                else { t.addCell(v); col = (col == 0) ? 2 : 0; }
            }
            if (col == 2) { t.addCell(padCell()); t.addCell(padCell()); }
            doc.add(t);
        } catch (Exception e) { throw new IllegalStateException("PDF ledgerKv failed", e); }
    }

    private static PdfPCell kvLabelCell(String label) {
        PdfPCell cell = new PdfPCell(new Phrase(label, F_L_K2));
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(LEDGER_FILL);
        cell.setBorderWidthBottom(1f);
        cell.setPaddingTop(5f); cell.setPaddingBottom(5f);
        cell.setPaddingLeft(2f); cell.setPaddingRight(8f);
        cell.setVerticalAlignment(Element.ALIGN_TOP);
        return cell;
    }

    private static PdfPCell kvValueCell(LedgerCell c) {
        Font f = c.mono() ? F_L_V2_MONO
                : (c.color() != null ? new Font(Font.HELVETICA, 8.5f, Font.BOLD, c.color()) : F_L_V2);
        PdfPCell cell = new PdfPCell(multiLine(c.value(), f));
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(LEDGER_FILL);
        cell.setBorderWidthBottom(1f);
        cell.setPaddingTop(5f); cell.setPaddingBottom(5f);
        cell.setPaddingLeft(2f); cell.setPaddingRight(8f);
        cell.setVerticalAlignment(Element.ALIGN_TOP);
        return cell;
    }

    /** A borderless spacer cell (pads out an odd trailing pair in a 4-col ledger table). */
    private static PdfPCell padCell() {
        PdfPCell cell = new PdfPCell(new Phrase(""));
        cell.setBorder(Rectangle.NO_BORDER);
        return cell;
    }

    /** A phrase that keeps embedded newlines as hard line breaks. */
    private static Phrase multiLine(String text, Font f) {
        Phrase p = new Phrase();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            p.add(new Chunk(lines[i], f));
            if (i < lines.length - 1) p.add(Chunk.NEWLINE);
        }
        return p;
    }

    // ── #1b bordered data table (repeating header, mono data columns) ─
    /**
     * A flat bordered data table (#1b .ic2/.iv2 / .ac/.av): a tinted header row
     * (repeats on page breaks) over 1px-hairline data cells. {@code monoCols[i]}
     * renders column i in Courier (dates / IDs / numeric data).
     */
    public void ledgerDataTable(String[] headers, float[] widths, List<String[]> rows, boolean[] monoCols) {
        if (headers == null || headers.length == 0) return;
        try {
            PdfPTable t = new PdfPTable(widths != null && widths.length == headers.length
                    ? widths : uniformWidths(headers.length));
            t.setWidthPercentage(100);
            t.setSpacingBefore(4f);
            t.setHeaderRows(1);
            for (String h : headers) t.addCell(ledgerHeadCell(h));
            if (rows == null || rows.isEmpty()) {
                PdfPCell empty = new PdfPCell(new Phrase("No records.", F_L_TD));
                empty.setColspan(headers.length);
                empty.setBorderColor(HAIRLINE);
                empty.setBorderWidth(1f);
                empty.setPadding(5f); empty.setPaddingLeft(6f);
                t.addCell(empty);
            } else {
                for (String[] row : rows) {
                    for (int i = 0; i < headers.length; i++) {
                        String v = row != null && i < row.length && row[i] != null ? row[i] : "—";
                        boolean mono = monoCols != null && i < monoCols.length && monoCols[i];
                        PdfPCell c = new PdfPCell(new Phrase(v, mono ? F_L_TD_MONO : F_L_TD));
                        c.setBorderColor(HAIRLINE);
                        c.setBorderWidth(1f);
                        c.setPadding(5f); c.setPaddingLeft(6f);
                        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
                        t.addCell(c);
                    }
                }
            }
            doc.add(t);
        } catch (Exception e) { throw new IllegalStateException("PDF ledgerDataTable failed", e); }
    }

    private static PdfPCell ledgerHeadCell(String h) {
        Chunk hc = new Chunk(h != null ? h.toUpperCase() : "", F_L_TH);
        hc.setCharacterSpacing(0.3f);
        PdfPCell c = new PdfPCell(new Phrase(hc));
        c.setBackgroundColor(LEDGER_FILL);
        c.setBorderColor(HAIRLINE);
        c.setBorderWidth(1f);
        c.setPadding(4f); c.setPaddingLeft(6f);
        return c;
    }

    // ── #1b compact alert table (SEV / ALERT / DETAIL) ────────────────
    /** The compact #1b alert table: a severity-coloured 8px square + CRIT/HIGH/MED, the alert, a detail. */
    public void ledgerAlertTable(List<LedgerAlert> alerts) {
        if (alerts == null || alerts.isEmpty()) return;
        try {
            PdfPTable t = new PdfPTable(new float[]{14f, 40f, 46f});
            t.setWidthPercentage(100);
            t.setSpacingBefore(4f);
            t.setHeaderRows(1);
            for (String h : new String[]{"SEV", "ALERT", "DETAIL"}) t.addCell(ledgerHeadCell(h));
            for (LedgerAlert a : alerts) {
                final Color sq = a.severityColor() != null ? a.severityColor() : MUTED;
                PdfPCell sev = new PdfPCell(new Phrase(a.severityLabel() != null ? a.severityLabel() : "",
                        new Font(Font.HELVETICA, 8f, Font.BOLD, INK)));
                sev.setBorderColor(HAIRLINE); sev.setBorderWidth(1f);
                sev.setPadding(5f); sev.setPaddingLeft(17f);
                sev.setVerticalAlignment(Element.ALIGN_MIDDLE);
                sev.setCellEvent((cell, pos, canvases) -> {
                    PdfContentByte bg = canvases[PdfPTable.BACKGROUNDCANVAS];
                    float s = 6.5f;
                    bg.saveState();
                    bg.setColorFill(sq);
                    bg.rectangle(pos.getLeft() + 6f, (pos.getBottom() + pos.getTop()) / 2f - s / 2f, s, s);
                    bg.fill();
                    bg.restoreState();
                });
                t.addCell(sev);
                PdfPCell al = new PdfPCell(new Phrase(a.alert() != null ? a.alert() : "",
                        new Font(Font.HELVETICA, 8f, Font.BOLD, INK)));
                al.setBorderColor(HAIRLINE); al.setBorderWidth(1f);
                al.setPadding(5f); al.setPaddingLeft(6f);
                al.setVerticalAlignment(Element.ALIGN_MIDDLE);
                t.addCell(al);
                PdfPCell de = new PdfPCell(new Phrase(a.detail() != null ? a.detail() : "", F_L_TD));
                de.setBorderColor(HAIRLINE); de.setBorderWidth(1f);
                de.setPadding(5f); de.setPaddingLeft(6f);
                de.setVerticalAlignment(Element.ALIGN_MIDDLE);
                t.addCell(de);
            }
            doc.add(t);
        } catch (Exception e) { throw new IllegalStateException("PDF ledgerAlertTable failed", e); }
    }

    // ── #1b ledger signature table (bordered, printable blank underlines) ──
    /** A bordered two-column sign-off table with printable blank underlines for Name / Signature / Date-time. */
    public void ledgerSignatures(String leftLabel, String rightLabel) {
        try {
            PdfPTable t = new PdfPTable(new float[]{50f, 50f});
            t.setWidthPercentage(100);
            t.setSpacingBefore(10f);
            t.setKeepTogether(true);
            t.setHeaderRows(1);
            t.addCell(ledgerHeadCell(leftLabel));
            t.addCell(ledgerHeadCell(rightLabel));
            for (String line : new String[]{"Name: ________________________",
                    "Signature: ____________________",
                    "Date / time: __________________"}) {
                t.addCell(sigLineCell(line));
                t.addCell(sigLineCell(line));
            }
            doc.add(t);
        } catch (Exception e) { throw new IllegalStateException("PDF ledgerSignatures failed", e); }
    }

    private static PdfPCell sigLineCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, F_L_SIG));
        cell.setBorderColor(HAIRLINE);
        cell.setBorderWidth(1f);
        cell.setPadding(8f);
        return cell;
    }

    // ── #1b every-page footer: 2px ink top rule + three parts ─────────
    private static final class LedgerChrome extends PdfPageEventHelper {
        private final Spec spec;
        private final Instant generatedAt;
        private final Font footFont = new Font(Font.HELVETICA, 6.4f, Font.NORMAL, MUTED);
        private final Font footStrong = new Font(Font.HELVETICA, 6.4f, Font.BOLD, INK);

        LedgerChrome(Spec spec, Instant generatedAt) { this.spec = spec; this.generatedAt = generatedAt; }

        @Override
        public void onEndPage(PdfWriter writer, Document doc) {
            PdfContentByte cb = writer.getDirectContent();
            float y = doc.bottom() - 20;
            cb.saveState();
            cb.setColorStroke(INK);
            cb.setLineWidth(2f);
            cb.moveTo(doc.left(), y + 11);
            cb.lineTo(doc.right(), y + 11);
            cb.stroke();
            cb.restoreState();

            String conf = spec.confidentiality() != null ? spec.confidentiality() : "protected health record";
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT,
                    new Phrase("CONFIDENTIAL — " + conf.toUpperCase(), footFont), doc.left(), y, 0);

            String who = (spec.exportedByName() != null && !spec.exportedByName().isBlank())
                    ? spec.exportedByName() : "SmartTriage user";
            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER,
                    new Phrase("Generated by " + who + " · " + TS.format(generatedAt), footFont),
                    (doc.left() + doc.right()) / 2, y, 0);

            String kind = spec.reportKind() != null ? spec.reportKind() : "Report";
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT,
                    new Phrase("SMARTTRIAGE · " + kind.toUpperCase(), footStrong), doc.right(), y, 0);
        }
    }
}
