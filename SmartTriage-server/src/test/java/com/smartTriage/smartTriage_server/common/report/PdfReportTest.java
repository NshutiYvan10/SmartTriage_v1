package com.smartTriage.smartTriage_server.common.report;

import com.lowagie.text.Image;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the shared branded report kit: the SmartTriage logo asset is actually
 * on the classpath + loadable (a missing asset would silently strip the brand),
 * and a full report exercising every helper renders to a valid, non-trivial PDF.
 */
class PdfReportTest {

    @Test
    void brandLogoIsOnClasspathAndLoadable() throws Exception {
        byte[] bytes;
        try (var in = PdfReport.class.getResourceAsStream("/branding/logo.png")) {
            assertThat(in).as("SmartTriage logo must be bundled at /branding/logo.png").isNotNull();
            bytes = in.readAllBytes();
        }
        assertThat(bytes.length).isGreaterThan(500);
        // OpenPDF must be able to turn it into an Image (else the masthead logo silently drops).
        Image img = Image.getInstance(bytes);
        assertThat(img.getWidth()).isGreaterThan(0);
        assertThat(img.getHeight()).isGreaterThan(0);
    }

    @Test
    void rendersAFullBrandedReport() {
        PdfReport r = PdfReport.begin(new PdfReport.Spec(
                "SAMPLE REPORT",
                "Sample",
                "Kigali Emergency Hospital",
                List.of("Code: KEH", "Kigali, Rwanda", "Tel: +250 000 000"),
                "Dr Grace Uwase",
                "sample report"));
        r.alertBanner("** SAMPLE ALERT BANNER **");
        r.subjectHeadline("Jane Doe", "Age: 34 · Sex: F");
        r.sectionHeader("Key / Value");
        r.keyValues(List.of(
                PdfReport.kv("Service", "BLS"),
                PdfReport.kv("Unit", "A-12"),
                PdfReport.kv("Empty", null)));        // dropped, not rendered
        r.sectionHeader("Vitals");
        r.statTiles(List.of(
                PdfReport.kv("HR", "128"),
                PdfReport.kv("BP", "82/52"),
                PdfReport.kv("SpO2", "90%")));
        r.sectionHeader("Interventions");
        r.bullets(List.of("O2 via NRB · 6 L/min", "IV access · 18G"));
        r.sectionHeader("Narrative");
        r.narrative("Line one.\nLine two.");
        byte[] pdf = r.finish();

        assertThat(pdf).isNotNull();
        assertThat(pdf.length).isGreaterThan(1500);
        // Valid PDF magic header.
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    }
}
