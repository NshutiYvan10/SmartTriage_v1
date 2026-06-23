package com.smartTriage.smartTriage_server.module.lab.service;

import com.smartTriage.smartTriage_server.common.enums.LabOrderStatus;
import com.smartTriage.smartTriage_server.common.enums.LabPriority;
import com.smartTriage.smartTriage_server.module.lab.entity.LabOrder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for {@link LabReportPdfService#render} — proves the lab reporting-pack PDF renders
 * (valid magic bytes, non-trivial size) over a mixed set of orders, and survives an empty period.
 */
class LabReportPdfServiceTest {

    private final LabReportPdfService service = new LabReportPdfService();

    private static void assertIsPdf(byte[] pdf) {
        assertTrue(pdf.length > 800, "PDF should be non-trivial in size");
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII), "PDF magic bytes");
    }

    private LabOrder order(String test, LabPriority p, LabOrderStatus s, Integer tat, boolean critical) {
        return LabOrder.builder()
                .orderNumber("LAB-1")
                .testName(test).priority(p).status(s)
                .orderedAt(Instant.now()).resultedAt(s == LabOrderStatus.RESULTED ? Instant.now() : null)
                .turnaroundMinutes(tat).isCritical(critical).isAbnormal(critical)
                .build();
    }

    @Test
    void rendersValidPdfOverMixedOrders() {
        List<LabOrder> orders = List.of(
                order("CBC", LabPriority.STAT, LabOrderStatus.RESULTED, 25, false),
                order("Lactate", LabPriority.STAT, LabOrderStatus.RESULTED, 48, true),
                order("U&E", LabPriority.ROUTINE, LabOrderStatus.PROCESSING, null, false),
                order("Malaria RDT", LabPriority.URGENT, LabOrderStatus.RESULTED, 90, false));
        byte[] pdf = service.render("Kigali Emergency Hospital",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 23), orders);
        assertIsPdf(pdf);
    }

    @Test
    void rendersValidPdfForEmptyPeriod() {
        byte[] pdf = service.render("Kigali Emergency Hospital",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 23), List.of());
        assertIsPdf(pdf);
    }
}
