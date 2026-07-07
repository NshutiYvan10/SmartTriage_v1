package com.smartTriage.smartTriage_server.module.reporting.engine;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aggregate queries for the operational report catalog (daily activity, shift
 * handover summary, period trend, my-activity, quality). Every method is
 * aggregate-only or hard-capped — reports must stay cheap on large datasets, so
 * counting happens in SQL (COUNT / AVG / GROUP BY / FILTER), never by loading
 * entities into memory. All queries are hospital-scoped by parameter; authz is
 * the controller's job.
 *
 * <p>Native SQL (not JPQL) because the time math (EXTRACT EPOCH on interval)
 * and FILTER clauses have no portable JPQL equivalent; the schema is
 * PostgreSQL-only (Flyway-managed), so this is safe.
 */
@Slf4j
@Component
public class OperationalReportQueries {

    /** Visit statuses that mean the patient has left the department. */
    static final String TERMINAL_STATUSES =
            "('DISCHARGED','ADMITTED','ICU_ADMITTED','TRANSFERRED','LEFT_WITHOUT_BEING_SEEN','DECEASED')";

    @PersistenceContext
    private EntityManager em;

    // ─────────────────────────────────────────────────────────────────
    // Arrivals / waits / dispositions (window-scoped)
    // ─────────────────────────────────────────────────────────────────

    /** [arrivals, triaged, avgWaitMin (arrival→assessment), avgLosMin (arrival→disposition)] */
    public ActivityStats activityStats(UUID hospitalId, Instant from, Instant to) {
        Object[] r = (Object[]) em.createNativeQuery(
                "SELECT count(*), count(triage_time), "
                + " avg(EXTRACT(EPOCH FROM (assessment_start_time - arrival_time)))/60.0, "
                + " avg(EXTRACT(EPOCH FROM (disposition_time - arrival_time)))/60.0 "
                + "FROM visits WHERE hospital_id = :h AND is_active = true "
                + "AND arrival_time >= :f AND arrival_time < :t")
                .setParameter("h", hospitalId).setParameter("f", from).setParameter("t", to)
                .getSingleResult();
        return new ActivityStats(num(r[0]), num(r[1]), dec(r[2]), dec(r[3]));
    }

    /** Triage-category mix of arrivals in the window: category → count (nulls → UNTRIAGED). */
    public Map<String, Long> categoryBreakdown(UUID hospitalId, Instant from, Instant to) {
        return grouped(em.createNativeQuery(
                "SELECT COALESCE(current_triage_category, 'UNTRIAGED'), count(*) FROM visits "
                + "WHERE hospital_id = :h AND is_active = true AND arrival_time >= :f AND arrival_time < :t "
                + "GROUP BY 1 ORDER BY 2 DESC")
                .setParameter("h", hospitalId).setParameter("f", from).setParameter("t", to)
                .getResultList());
    }

    /** Dispositions recorded in the window: type → count. */
    public Map<String, Long> dispositions(UUID hospitalId, Instant from, Instant to) {
        return grouped(em.createNativeQuery(
                "SELECT disposition_type, count(*) FROM visits "
                + "WHERE hospital_id = :h AND is_active = true AND disposition_type IS NOT NULL "
                + "AND disposition_time >= :f AND disposition_time < :t "
                + "GROUP BY 1 ORDER BY 2 DESC")
                .setParameter("h", hospitalId).setParameter("f", from).setParameter("t", to)
                .getResultList());
    }

    // ─────────────────────────────────────────────────────────────────
    // Live department state (now-scoped)
    // ─────────────────────────────────────────────────────────────────

    /** Patients currently in the department, by zone (null zone → UNASSIGNED). */
    public Map<String, Long> censusByZone(UUID hospitalId) {
        return grouped(em.createNativeQuery(
                "SELECT COALESCE(current_ed_zone, 'UNASSIGNED'), count(*) FROM visits "
                + "WHERE hospital_id = :h AND is_active = true AND status NOT IN " + TERMINAL_STATUSES + " "
                + "GROUP BY 1 ORDER BY 2 DESC")
                .setParameter("h", hospitalId).getResultList());
    }

    /** Patients currently in the department, by triage category. */
    public Map<String, Long> acuityNow(UUID hospitalId) {
        return grouped(em.createNativeQuery(
                "SELECT COALESCE(current_triage_category, 'UNTRIAGED'), count(*) FROM visits "
                + "WHERE hospital_id = :h AND is_active = true AND status NOT IN " + TERMINAL_STATUSES + " "
                + "GROUP BY 1 ORDER BY 2 DESC")
                .setParameter("h", hospitalId).getResultList());
    }

    /**
     * Open clinical work right now — the handover's "what you are inheriting" block.
     * One scalar per line so each figure is independently cheap and index-friendly.
     */
    public OpenWork openWork(UUID hospitalId) {
        long criticalAlerts = scalar(
                "SELECT count(*) FROM clinical_alerts ca JOIN visits v ON v.id = ca.visit_id "
                + "WHERE v.hospital_id = :h AND ca.is_active = true AND ca.is_acknowledged = false "
                + "AND ca.severity = 'CRITICAL'", hospitalId);
        long sepsisBundlesOpen = scalar(
                "SELECT count(*) FROM sepsis_screenings s JOIN visits v ON v.id = s.visit_id "
                + "WHERE v.hospital_id = :h AND s.is_active = true "
                + "AND s.bundle_started_at IS NOT NULL AND s.bundle_completed_at IS NULL", hospitalId);
        long isolationsUnroomed = scalar(
                "SELECT count(*) FROM infection_screenings i JOIN visits v ON v.id = i.visit_id "
                + "WHERE v.hospital_id = :h AND i.is_active = true AND i.isolation_type IS NOT NULL "
                + "AND i.isolation_ended_at IS NULL AND i.isolation_room_assigned IS NULL", hospitalId);
        long isolationsActive = scalar(
                "SELECT count(*) FROM infection_screenings i JOIN visits v ON v.id = i.visit_id "
                + "WHERE v.hospital_id = :h AND i.is_active = true AND i.isolation_type IS NOT NULL "
                + "AND i.isolation_ended_at IS NULL", hospitalId);
        long hypoUnresolved = scalar(
                "SELECT count(*) FROM hypoglycemia_events e JOIN visits v ON v.id = e.visit_id "
                + "WHERE v.hospital_id = :h AND e.is_active = true AND e.resolved = false", hospitalId);
        long hypoRecheckOverdue = scalar(
                "SELECT count(*) FROM hypoglycemia_events e JOIN visits v ON v.id = e.visit_id "
                + "WHERE v.hospital_id = :h AND e.is_active = true AND e.resolved = false "
                + "AND e.recheck_due_at IS NOT NULL AND e.recheck_due_at < now()", hospitalId);
        long incidentsOpen = scalar(
                "SELECT count(*) FROM safety_incidents i "
                + "WHERE i.hospital_id = :h AND i.is_active = true AND i.status <> 'CLOSED'", hospitalId);
        return new OpenWork(criticalAlerts, sepsisBundlesOpen, isolationsUnroomed, isolationsActive,
                hypoUnresolved, hypoRecheckOverdue, incidentsOpen);
    }

    // ─────────────────────────────────────────────────────────────────
    // Module activity in a window
    // ─────────────────────────────────────────────────────────────────

    public ModuleActivity moduleActivity(UUID hospitalId, Instant from, Instant to) {
        long hypo = windowed("SELECT count(*) FROM hypoglycemia_events e JOIN visits v ON v.id = e.visit_id "
                + "WHERE v.hospital_id = :h AND e.is_active = true AND e.detected_at >= :f AND e.detected_at < :t",
                hospitalId, from, to);
        long fastTrack = windowed("SELECT count(*) FROM fast_track_activations a JOIN visits v ON v.id = a.visit_id "
                + "WHERE v.hospital_id = :h AND a.is_active = true AND a.activated_at >= :f AND a.activated_at < :t",
                hospitalId, from, to);
        long incidents = windowed("SELECT count(*) FROM safety_incidents i WHERE i.hospital_id = :h "
                + "AND i.is_active = true AND i.reported_at >= :f AND i.reported_at < :t",
                hospitalId, from, to);
        long severeIncidents = windowed("SELECT count(*) FROM safety_incidents i WHERE i.hospital_id = :h "
                + "AND i.is_active = true AND i.reported_at >= :f AND i.reported_at < :t "
                + "AND i.severity IN ('SEVERE_HARM','DEATH')",
                hospitalId, from, to);
        return new ModuleActivity(hypo, fastTrack, incidents, severeIncidents);
    }

    // ─────────────────────────────────────────────────────────────────
    // Per-day trend (period report)
    // ─────────────────────────────────────────────────────────────────

    /** One row per day: [date, arrivals, red, orange, yellow, green, admissions, lwbs, avgWaitMin]. */
    @SuppressWarnings("unchecked")
    public List<Object[]> dailyTrend(UUID hospitalId, Instant from, Instant to) {
        return em.createNativeQuery(
                "SELECT date(a.arrival_time) AS d, count(*), "
                + " count(*) FILTER (WHERE a.current_triage_category = 'RED'), "
                + " count(*) FILTER (WHERE a.current_triage_category = 'ORANGE'), "
                + " count(*) FILTER (WHERE a.current_triage_category = 'YELLOW'), "
                + " count(*) FILTER (WHERE a.current_triage_category = 'GREEN'), "
                + " count(*) FILTER (WHERE a.disposition_type IN ('ADMITTED_TO_WARD','ICU_ADMISSION')), "
                + " count(*) FILTER (WHERE a.disposition_type = 'LEFT_WITHOUT_BEING_SEEN'), "
                + " avg(EXTRACT(EPOCH FROM (a.assessment_start_time - a.arrival_time)))/60.0 "
                + "FROM visits a WHERE a.hospital_id = :h AND a.is_active = true "
                + "AND a.arrival_time >= :f AND a.arrival_time < :t "
                + "GROUP BY 1 ORDER BY 1")
                .setParameter("h", hospitalId).setParameter("f", from).setParameter("t", to)
                .getResultList();
    }

    // ─────────────────────────────────────────────────────────────────
    // Staffing (shift report)
    // ─────────────────────────────────────────────────────────────────

    /** Roster rows for a shift: [zone, shift_function, first_name, last_name, is_shift_lead]. */
    @SuppressWarnings("unchecked")
    public List<Object[]> staffing(UUID hospitalId, java.time.LocalDate date, String period) {
        return em.createNativeQuery(
                "SELECT sa.zone, sa.shift_function, u.first_name, u.last_name, sa.is_shift_lead "
                + "FROM shift_assignments sa JOIN users u ON u.id = sa.user_id "
                + "WHERE sa.hospital_id = :h AND sa.shift_date = :d AND sa.shift_period = :p "
                + "AND sa.is_active = true ORDER BY sa.zone, sa.shift_function")
                .setParameter("h", hospitalId).setParameter("d", date).setParameter("p", period)
                .getResultList();
    }

    // ─────────────────────────────────────────────────────────────────
    // My clinical activity (self-scoped)
    // ─────────────────────────────────────────────────────────────────

    public MyActivity myActivity(UUID userId, UUID hospitalId, Instant from, Instant to) {
        long primaryVisits = scalarUser(
                "SELECT count(*) FROM visits v WHERE v.hospital_id = :h AND v.is_active = true "
                + "AND v.primary_clinician_id = :u AND v.arrival_time >= :f AND v.arrival_time < :t",
                userId, hospitalId, from, to);
        long notes = scalarUser(
                "SELECT count(*) FROM clinical_notes n JOIN visits v ON v.id = n.visit_id "
                + "WHERE v.hospital_id = :h AND n.is_active = true AND n.author_user_id = :u "
                + "AND n.created_at >= :f AND n.created_at < :t",
                userId, hospitalId, from, to);
        long prescriptions = scalarUser(
                "SELECT count(*) FROM medication_administrations m JOIN visits v ON v.id = m.visit_id "
                + "WHERE v.hospital_id = :h AND m.is_active = true AND m.prescribed_by_id = :u "
                + "AND m.prescribed_at >= :f AND m.prescribed_at < :t",
                userId, hospitalId, from, to);
        return new MyActivity(primaryVisits, notes, prescriptions);
    }

    /** Capped list of the caller's primary-clinician visits: [visit_number, name, arrival, category, disposition]. */
    @SuppressWarnings("unchecked")
    public List<Object[]> myVisitRows(UUID userId, UUID hospitalId, Instant from, Instant to, int cap) {
        return em.createNativeQuery(
                "SELECT v.visit_number, p.first_name || ' ' || p.last_name, v.arrival_time, "
                + " COALESCE(v.current_triage_category,'—'), COALESCE(v.disposition_type,'IN CARE') "
                + "FROM visits v JOIN patients p ON p.id = v.patient_id "
                + "WHERE v.hospital_id = :h AND v.is_active = true AND v.primary_clinician_id = :u "
                + "AND v.arrival_time >= :f AND v.arrival_time < :t "
                + "ORDER BY v.arrival_time DESC LIMIT " + Math.max(1, cap))
                .setParameter("u", userId).setParameter("h", hospitalId)
                .setParameter("f", from).setParameter("t", to)
                .getResultList();
    }

    // ─────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────

    private long scalar(String sql, UUID hospitalId) {
        return num(em.createNativeQuery(sql).setParameter("h", hospitalId).getSingleResult());
    }

    private long windowed(String sql, UUID hospitalId, Instant from, Instant to) {
        return num(em.createNativeQuery(sql)
                .setParameter("h", hospitalId).setParameter("f", from).setParameter("t", to)
                .getSingleResult());
    }

    private long scalarUser(String sql, UUID userId, UUID hospitalId, Instant from, Instant to) {
        return num(em.createNativeQuery(sql)
                .setParameter("u", userId).setParameter("h", hospitalId)
                .setParameter("f", from).setParameter("t", to)
                .getSingleResult());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> grouped(List<?> rows) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Object row : rows) {
            Object[] r = (Object[]) row;
            out.put(String.valueOf(r[0]), num(r[1]));
        }
        return out;
    }

    private static long num(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString());
    }

    private static Double dec(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b.doubleValue();
        if (v instanceof Number n) return n.doubleValue();
        return null;
    }

    public record ActivityStats(long arrivals, long triaged, Double avgWaitMinutes, Double avgLosMinutes) {}
    public record OpenWork(long criticalAlertsUnacked, long sepsisBundlesOpen, long isolationsUnroomed,
                           long isolationsActive, long hypoUnresolved, long hypoRecheckOverdue,
                           long incidentsOpen) {}
    public record ModuleActivity(long hypoglycemiaEvents, long fastTrackActivations,
                                 long incidentsReported, long severeIncidents) {}
    public record MyActivity(long primaryVisits, long notesAuthored, long prescriptions) {}
}
