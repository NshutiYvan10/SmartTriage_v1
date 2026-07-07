package com.smartTriage.smartTriage_server.module.reporting.engine;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate indicator queries for MoH reports — the module data the report entity
 * always had fields for but never read (sepsis screens, isolation activations,
 * malaria positives, top diagnoses) plus the IDSR notifiable-disease section (V111).
 *
 * <p>Every query is aggregate-only (COUNT / GROUP BY) — no patient-identifiable data
 * ever leaves this component. Each takes a nullable {@code hospitalId}: null means
 * NATIONAL (all hospitals pooled), matching {@code MohReportGenerator}'s two levels.
 * Queries are built conditionally rather than using {@code (:h IS NULL OR ...)}
 * null-parameter tricks, which are fragile on PostgreSQL.
 */
@Slf4j
@Component
public class MohIndicatorQueries {

    @PersistenceContext
    private EntityManager em;

    /** Sepsis screenings performed in the period. */
    public int countSepsisScreened(UUID hospitalId, Instant start, Instant end) {
        String q = "SELECT COUNT(s) FROM SepsisScreening s JOIN s.visit v "
                + "WHERE s.isActive = true AND s.screenedAt >= :start AND s.screenedAt < :end";
        return countQuery(q, hospitalId, start, end);
    }

    /** Infection screenings that REQUIRED isolation (any precaution) in the period. */
    public int countIsolationActivated(UUID hospitalId, Instant start, Instant end) {
        String q = "SELECT COUNT(s) FROM InfectionScreening s JOIN s.visit v "
                + "WHERE s.isActive = true AND s.isolationType IS NOT NULL "
                + "AND s.screenedAt >= :start AND s.screenedAt < :end";
        return countQuery(q, hospitalId, start, end);
    }

    /** IDSR: notifiable-disease cases detected in the period. */
    public int countNotifiableDiseases(UUID hospitalId, Instant start, Instant end) {
        String q = "SELECT COUNT(s) FROM InfectionScreening s JOIN s.visit v "
                + "WHERE s.isActive = true AND s.notifiableDisease IS NOT NULL "
                + "AND s.screenedAt >= :start AND s.screenedAt < :end";
        return countQuery(q, hospitalId, start, end);
    }

    /** IDSR: of the notifiable cases, how many were actually reported to RBC. */
    public int countPublicHealthNotified(UUID hospitalId, Instant start, Instant end) {
        String q = "SELECT COUNT(s) FROM InfectionScreening s JOIN s.visit v "
                + "WHERE s.isActive = true AND s.notifiableDisease IS NOT NULL "
                + "AND s.publicHealthNotifiedAt IS NOT NULL "
                + "AND s.screenedAt >= :start AND s.screenedAt < :end";
        return countQuery(q, hospitalId, start, end);
    }

    /** IDSR: per-disease case counts, descending. Each row: [NotifiableDisease, Long]. */
    public List<Object[]> notifiableDiseaseBreakdown(UUID hospitalId, Instant start, Instant end) {
        String q = "SELECT s.notifiableDisease, COUNT(s) FROM InfectionScreening s JOIN s.visit v "
                + "WHERE s.isActive = true AND s.notifiableDisease IS NOT NULL "
                + "AND s.screenedAt >= :start AND s.screenedAt < :end "
                + (hospitalId != null ? "AND v.hospital.id = :hospitalId " : "")
                + "GROUP BY s.notifiableDisease ORDER BY COUNT(s) DESC";
        var query = em.createQuery(q, Object[].class)
                .setParameter("start", start).setParameter("end", end);
        if (hospitalId != null) query.setParameter("hospitalId", hospitalId);
        return query.getResultList();
    }

    /**
     * Malaria-positive lab results in the period. Mirrors CriticalValueEngine's
     * qualitative malaria recognition (test name contains malaria/rdt/smear/parasit,
     * result text reads positive/detected/+) so the report counts malaria the same
     * way the critical-value alerting does.
     */
    public int countMalariaPositive(UUID hospitalId, Instant start, Instant end) {
        String q = "SELECT COUNT(o) FROM LabOrder o JOIN o.visit v "
                + "WHERE o.isActive = true AND o.resultedAt IS NOT NULL "
                + "AND o.resultedAt >= :start AND o.resultedAt < :end "
                + "AND (LOWER(o.testName) LIKE '%malaria%' OR LOWER(o.testName) LIKE '%rdt%' "
                + "     OR LOWER(o.testName) LIKE '%blood smear%' OR LOWER(o.testName) LIKE '%parasit%') "
                + "AND (LOWER(o.resultValue) LIKE '%pos%' OR LOWER(o.resultValue) LIKE '%detected%' "
                + "     OR o.resultValue LIKE '%+%')";
        return countQuery(q, hospitalId, start, end);
    }

    /** Top diagnoses in the period, descending. Each row: [icdCode, description, Long]. */
    public List<Object[]> topDiagnoses(UUID hospitalId, Instant start, Instant end, int limit) {
        String q = "SELECT d.icdCode, d.description, COUNT(d) FROM Diagnosis d JOIN d.visit v "
                + "WHERE d.isActive = true AND d.diagnosedAt >= :start AND d.diagnosedAt < :end "
                + (hospitalId != null ? "AND v.hospital.id = :hospitalId " : "")
                + "GROUP BY d.icdCode, d.description ORDER BY COUNT(d) DESC";
        var query = em.createQuery(q, Object[].class)
                .setParameter("start", start).setParameter("end", end)
                .setMaxResults(limit);
        if (hospitalId != null) query.setParameter("hospitalId", hospitalId);
        return query.getResultList();
    }

    private int countQuery(String baseJpql, UUID hospitalId, Instant start, Instant end) {
        String q = hospitalId != null ? baseJpql + " AND v.hospital.id = :hospitalId" : baseJpql;
        var query = em.createQuery(q, Long.class)
                .setParameter("start", start).setParameter("end", end);
        if (hospitalId != null) query.setParameter("hospitalId", hospitalId);
        Long result = query.getSingleResult();
        return result != null ? result.intValue() : 0;
    }
}
