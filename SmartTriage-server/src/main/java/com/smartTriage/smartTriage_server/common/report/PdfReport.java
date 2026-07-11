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
import com.lowagie.text.pdf.PdfTemplate;
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
        try {
            PdfPTable t = new PdfPTable(new float[]{5.2f, 1.6f, 34f, 59.2f});
            t.setWidthPercentage(100);
            t.setSpacingBefore(12f);
            t.setSpacingAfter(2f);
            PdfPCell badge = boxed(new BoxStyle().fill(BRAND_DEEP).radius(5.5f));
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
        private final Font footFont = new Font(Font.HELVETICA, 7, Font.NORMAL, MUTED);
        private final Font footStrong = new Font(Font.HELVETICA, 7, Font.BOLD, BRAND_DEEP);
        private final Font runHdr = new Font(Font.HELVETICA, 7.5f, Font.BOLD, MUTED);
        private final Instant generatedAt;
        private PdfTemplate totalPages;

        Chrome(Spec spec, Instant generatedAt) { this.spec = spec; this.generatedAt = generatedAt; }

        @Override
        public void onOpenDocument(PdfWriter writer, Document doc) {
            totalPages = writer.getDirectContent().createTemplate(30, 12);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document doc) {
            PdfContentByte cb = writer.getDirectContent();
            int page = writer.getPageNumber();

            // Running header on continuation pages so a multi-page report stays branded.
            if (page > 1) {
                Phrase h = new Phrase("SmartTriage  ·  " + spec.reportKind(), runHdr);
                ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT, h, doc.right(), doc.top() + 18, 0);
                cb.setColorStroke(HAIRLINE);
                cb.setLineWidth(0.5f);
                cb.moveTo(doc.left(), doc.top() + 14);
                cb.lineTo(doc.right(), doc.top() + 14);
                cb.stroke();
            }

            // Footer hairline.
            float y = doc.bottom() - 16;
            cb.setColorStroke(HAIRLINE);
            cb.setLineWidth(0.5f);
            cb.moveTo(doc.left(), y + 8);
            cb.lineTo(doc.right(), y + 8);
            cb.stroke();

            // Left: confidentiality. Center: attribution. Right: Page X of Y.
            String conf = spec.confidentiality() != null ? spec.confidentiality() : "Confidential";
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT,
                    new Phrase("CONFIDENTIAL — " + conf, footFont), doc.left(), y, 0);

            String who = (spec.exportedByName() != null && !spec.exportedByName().isBlank())
                    ? spec.exportedByName() : "SmartTriage user";
            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER,
                    new Phrase("Generated by " + who + " · " + TS.format(generatedAt), footFont),
                    (doc.left() + doc.right()) / 2, y, 0);

            Phrase pageLabel = new Phrase("Page " + page + " of ", footStrong);
            float len = footStrong.getCalculatedBaseFont(true)
                    .getWidthPoint("Page " + page + " of ", 7f);
            float rightX = doc.right();
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT, pageLabel, rightX - len - 14, y, 0);
            cb.addTemplate(totalPages, rightX - 14, y);
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document doc) {
            totalPages.beginText();
            try {
                totalPages.setFontAndSize(footStrong.getCalculatedBaseFont(true), 7f);
                totalPages.setColorFill(BRAND_DEEP);
                // close() has already advanced past the last real page, so the writer's
                // page number is one too high here (a 2-page report would say "of 3").
                totalPages.showText(String.valueOf(writer.getPageNumber() - 1));
            } catch (Exception ignored) {
                // total-count is cosmetic; never fail the render over it
            } finally {
                totalPages.endText();
            }
        }
    }
}
