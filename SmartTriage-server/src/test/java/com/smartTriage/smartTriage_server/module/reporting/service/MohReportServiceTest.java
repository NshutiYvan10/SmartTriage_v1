package com.smartTriage.smartTriage_server.module.reporting.service;

import com.smartTriage.smartTriage_server.common.enums.MohReportType;
import com.smartTriage.smartTriage_server.common.enums.ReportLevel;
import com.smartTriage.smartTriage_server.common.enums.ReportStatus;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.module.reporting.engine.MohReportGenerator;
import com.smartTriage.smartTriage_server.module.reporting.entity.MohReport;
import com.smartTriage.smartTriage_server.module.reporting.repository.MohReportRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link MohReportService} national-rollup behaviour: delegates to the generator,
 * snapshots the authenticated generator's name, persists, and lists national reports by level.
 */
class MohReportServiceTest {

    private final MohReportRepository mohReportRepository = mock(MohReportRepository.class);
    private final MohReportGenerator mohReportGenerator = mock(MohReportGenerator.class);
    private final MohReportService service = new MohReportService(mohReportRepository, mohReportGenerator);

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private void authenticateAs(String first, String last) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setFirstName(first);
        u.setLastName(last);
        u.setEmail("admin@moh.rw");
        u.setRole(Role.SUPER_ADMIN);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u, null, List.of()));
    }

    @Test
    void generateNationalReport_delegatesToGenerator_snapshotsActor_andPersists() {
        authenticateAs("Jane", "Admin");
        LocalDate start = LocalDate.of(2026, 5, 1);
        LocalDate end = LocalDate.of(2026, 5, 31);

        MohReport generated = MohReport.builder()
                .reportLevel(ReportLevel.NATIONAL)
                .reportType(MohReportType.MONTHLY_STATISTICS)
                .includedHospitalCount(3)
                .status(ReportStatus.GENERATED)
                .totalEdVisits(900)
                .build();
        when(mohReportGenerator.generateNationalSummary(MohReportType.MONTHLY_STATISTICS, start, end))
                .thenReturn(generated);
        when(mohReportRepository.save(any(MohReport.class))).thenAnswer(inv -> inv.getArgument(0));

        MohReport result = service.generateNationalReport(MohReportType.MONTHLY_STATISTICS, start, end);

        assertThat(result.getReportLevel()).isEqualTo(ReportLevel.NATIONAL);
        assertThat(result.getIncludedHospitalCount()).isEqualTo(3);
        assertThat(result.getGeneratedByName()).isEqualTo("Jane Admin");
        assertThat(result.getHospital()).isNull();
        verify(mohReportGenerator).generateNationalSummary(MohReportType.MONTHLY_STATISTICS, start, end);
        verify(mohReportRepository).save(generated);
    }

    @Test
    void getNationalReports_queriesByNationalLevel() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<MohReport> page = new PageImpl<>(List.of(
                MohReport.builder().reportLevel(ReportLevel.NATIONAL).build()));
        when(mohReportRepository.findByReportLevelAndIsActiveTrueOrderByReportPeriodStartDesc(
                eq(ReportLevel.NATIONAL), eq(pageable))).thenReturn(page);

        Page<MohReport> result = service.getNationalReports(pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getReportLevel()).isEqualTo(ReportLevel.NATIONAL);
        verify(mohReportRepository).findByReportLevelAndIsActiveTrueOrderByReportPeriodStartDesc(
                ReportLevel.NATIONAL, pageable);
    }
}
