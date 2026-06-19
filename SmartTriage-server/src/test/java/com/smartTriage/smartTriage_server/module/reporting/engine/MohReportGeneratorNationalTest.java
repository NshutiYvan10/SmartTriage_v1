package com.smartTriage.smartTriage_server.module.reporting.engine;

import com.smartTriage.smartTriage_server.common.enums.MohReportType;
import com.smartTriage.smartTriage_server.common.enums.ReportLevel;
import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.common.enums.VisitStatus;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.reporting.entity.MohReport;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link MohReportGenerator#generateNationalSummary} — proves the national
 * rollup pools visits across ALL active hospitals and produces correct national sums, with
 * no owning hospital and the right included-hospital count.
 */
class MohReportGeneratorNationalTest {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

    private final VisitRepository visitRepository = mock(VisitRepository.class);
    private final HospitalRepository hospitalRepository = mock(HospitalRepository.class);
    private final MohReportGenerator generator =
            new MohReportGenerator(visitRepository, hospitalRepository);

    private Hospital hospital(UUID id) {
        Hospital h = new Hospital();
        h.setId(id);
        h.setName("H-" + id.toString().substring(0, 4));
        return h;
    }

    private Visit visit(Instant arrival, VisitStatus status, TriageCategory cat, boolean pediatric) {
        Visit v = new Visit();
        v.setArrivalTime(arrival);
        v.setStatus(status);
        v.setCurrentTriageCategory(cat);
        if (cat != null) {
            v.setTriageTime(arrival.plusSeconds(600)); // 10 min wait
        }
        v.setPediatric(pediatric);
        return v;
    }

    @Test
    void generateNationalSummary_poolsVisitsAcrossAllActiveHospitals() {
        UUID hA = UUID.randomUUID();
        UUID hB = UUID.randomUUID();
        when(hospitalRepository.findByIsActiveTrue())
                .thenReturn(List.of(hospital(hA), hospital(hB)));

        LocalDate day = LocalDate.of(2026, 5, 1);
        Instant inPeriod = day.atStartOfDay(KIGALI).plusHours(10).toInstant();
        Instant outOfPeriod = day.minusDays(2).atStartOfDay(KIGALI).plusHours(10).toInstant();

        // Hospital A: a triaged RED visit (in), a deceased visit (in), and one before the period (out)
        when(visitRepository.findActiveVisitsByStatuses(eq(hA), anyList())).thenReturn(List.of(
                visit(inPeriod, VisitStatus.TRIAGED, TriageCategory.RED, false),
                visit(inPeriod, VisitStatus.DECEASED, null, false),
                visit(outOfPeriod, VisitStatus.ADMITTED, null, false)));

        // Hospital B: an admitted visit (in) and a pediatric visit (in)
        when(visitRepository.findActiveVisitsByStatuses(eq(hB), anyList())).thenReturn(List.of(
                visit(inPeriod, VisitStatus.ADMITTED, null, false),
                visit(inPeriod, VisitStatus.REGISTERED, null, true)));

        MohReport r = generator.generateNationalSummary(MohReportType.DAILY_SUMMARY, day, day);

        assertThat(r.getHospital()).isNull();
        assertThat(r.getReportLevel()).isEqualTo(ReportLevel.NATIONAL);
        assertThat(r.getIncludedHospitalCount()).isEqualTo(2);
        // Pooled, in-period only: A=2 (out-of-period dropped), B=2 → 4
        assertThat(r.getTotalEdVisits()).isEqualTo(4);
        assertThat(r.getTotalTriaged()).isEqualTo(1);     // only the RED visit
        assertThat(r.getMortalityCount()).isEqualTo(1);   // the deceased visit
        assertThat(r.getAdmissionCount()).isEqualTo(1);   // B's admitted (A's admitted was out of period)
        assertThat(r.getPediatricVisitCount()).isEqualTo(1);
        assertThat(r.getReportType()).isEqualTo(MohReportType.DAILY_SUMMARY);
    }

    @Test
    void generateNationalSummary_withNoHospitals_isEmptyButValid() {
        when(hospitalRepository.findByIsActiveTrue()).thenReturn(List.of());

        LocalDate day = LocalDate.of(2026, 5, 1);
        MohReport r = generator.generateNationalSummary(MohReportType.MONTHLY_STATISTICS, day, day);

        assertThat(r.getReportLevel()).isEqualTo(ReportLevel.NATIONAL);
        assertThat(r.getIncludedHospitalCount()).isEqualTo(0);
        assertThat(r.getTotalEdVisits()).isEqualTo(0);
        assertThat(r.getHospital()).isNull();
    }
}
