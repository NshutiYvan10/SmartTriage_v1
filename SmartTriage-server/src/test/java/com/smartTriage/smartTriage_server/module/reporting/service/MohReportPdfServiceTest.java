package com.smartTriage.smartTriage_server.module.reporting.service;

import com.smartTriage.smartTriage_server.common.enums.MohReportType;
import com.smartTriage.smartTriage_server.common.enums.ReportStatus;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.reporting.entity.MohReport;
import com.smartTriage.smartTriage_server.module.reporting.repository.MohReportRepository;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit test for {@link MohReportPdfService#render} — proves OpenPDF produces a
 * valid PDF document (correct magic bytes, non-trivial size) both for a fully
 * populated report and for a sparse one (exercises the null-value "—" branches),
 * without touching a database.
 */
class MohReportPdfServiceTest {

    private final MohReportPdfService service =
            new MohReportPdfService(mock(MohReportRepository.class));

    private static void assertIsPdf(byte[] pdf) {
        assertTrue(pdf.length > 800, "PDF should be non-trivial in size");
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII), "PDF magic bytes");
    }

    @Test
    void rendersValidPdfForFullyPopulatedReport() {
        Hospital h = new Hospital();
        h.setName("Kigali Emergency Hospital");
        h.setHospitalCode("KGL-ED");

        MohReport r = MohReport.builder()
                .hospital(h)
                .reportType(MohReportType.MONTHLY_STATISTICS)
                .reportPeriodStart(Instant.parse("2026-05-01T00:00:00Z"))
                .reportPeriodEnd(Instant.parse("2026-06-01T00:00:00Z"))
                .generatedAt(Instant.now())
                .generatedByName("Admin Test")
                .status(ReportStatus.SUBMITTED)
                .submittedAt(Instant.now())
                .submittedByName("Admin Test")
                .totalEdVisits(1240)
                .totalTriaged(1198)
                .pediatricVisitCount(312)
                .averageWaitTimeMinutes(27.4)
                .averageLengthOfStayMinutes(186.2)
                .triageCategoryBreakdown("RED: 40, ORANGE: 120, YELLOW: 480, GREEN: 558")
                .admissionCount(210)
                .icuAdmissionCount(18)
                .transferCount(12)
                .leftWithoutBeingSeenCount(34)
                .mortalityCount(9)
                .malariaPositiveCount(73)
                .sepsisScreenedCount(56)
                .isolationActivatedCount(11)
                .topDiagnoses("Malaria, Pneumonia, Gastroenteritis")
                .topChiefComplaints("Fever, Cough, Abdominal pain")
                .build();

        assertIsPdf(service.render(r, "Admin Test"));
    }

    @Test
    void rendersValidPdfForNationalRollup() {
        // National report: no hospital, reportLevel NATIONAL, included-hospital count set —
        // exercises the national-header branch.
        MohReport r = MohReport.builder()
                .reportLevel(com.smartTriage.smartTriage_server.common.enums.ReportLevel.NATIONAL)
                .includedHospitalCount(42)
                .reportType(MohReportType.MONTHLY_STATISTICS)
                .reportPeriodStart(Instant.parse("2026-05-01T00:00:00Z"))
                .reportPeriodEnd(Instant.parse("2026-06-01T00:00:00Z"))
                .generatedAt(Instant.now())
                .generatedByName("MoH Analyst")
                .status(ReportStatus.GENERATED)
                .totalEdVisits(125000)
                .mortalityCount(430)
                .build();

        assertIsPdf(service.render(r, "Admin Test"));
    }

    @Test
    void rendersValidPdfForSparseReport() {
        // No hospital, all aggregate fields null — exercises the "—" / fallback branches.
        MohReport r = MohReport.builder()
                .reportType(MohReportType.DAILY_SUMMARY)
                .reportPeriodStart(Instant.parse("2026-06-17T00:00:00Z"))
                .reportPeriodEnd(Instant.parse("2026-06-18T00:00:00Z"))
                .status(ReportStatus.DRAFT)
                .build();

        assertIsPdf(service.render(r, "Admin Test"));
    }
}
