package com.smartTriage.smartTriage_server.module.bed.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.BedStatus;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import jakarta.persistence.*;
import lombok.*;

/**
 * Bed — a physical or logical treatment space inside an ED zone.
 *
 * A bed is the finest-grained location unit in the system. Zones (RESUS,
 * ACUTE, PEDIATRIC, ISOLATION, OBSERVATION) that deliver monitored care own
 * a set of beds (R1/R2/R3, A1..A8, P1..P4, …). The ambulatory GENERAL zone
 * typically does NOT have beds — patients there stay on a flat list and
 * use portable devices with manual pairing.
 *
 * Patient ↔ bed relationship:
 *   * Placement: BedService.placePatient(bedId, visitId) sets
 *       bed.currentVisit = visit AND visit.currentBed = bed
 *     atomically and transitions bed.status → OCCUPIED.
 *   * Discharge / transfer: bed.currentVisit is cleared and bed.status
 *     becomes CLEANING (mandatory hygiene step).
 *
 * Monitor ↔ bed relationship:
 *   * An IoTDevice may carry assignedBed = this bed. When a patient is
 *     placed here, BedService auto-creates a DeviceSession for that
 *     device, so vitals flow without a nurse clicking "pair".
 *
 * Concurrency:
 *   * A partial unique index (uk_bed_one_active_visit) prevents a second
 *     placement while currentVisitId is non-null — the DB rejects race
 *     conditions where two clinicians try to place different patients.
 */
@Entity
@Table(name = "beds", indexes = {
        @Index(name = "idx_bed_hospital_zone", columnList = "hospital_id, zone"),
        @Index(name = "idx_bed_hospital_status", columnList = "hospital_id, status"),
        @Index(name = "idx_bed_current_visit", columnList = "current_visit_id")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_bed_hospital_code", columnNames = { "hospital_id", "code" })
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Bed extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    /** Which ED zone this bed belongs to. */
    @Enumerated(EnumType.STRING)
    @Column(name = "zone", nullable = false, length = 20)
    private EdZone zone;

    /**
     * Short human-readable bed code, unique per hospital.
     * Convention: zone-prefix + number (R1, A3, P2, I1, O4). Keeps the UI
     * tile compact while still telling staff which zone the bed belongs
     * to at a glance.
     */
    @Column(name = "code", nullable = false, length = 20)
    private String code;

    /** Longer description (e.g. "Resus Bay 1", "Negative-pressure Room 1"). */
    @Column(name = "label", length = 100)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private BedStatus status = BedStatus.AVAILABLE;

    /**
     * Declares whether this bed is expected to have a permanently-mounted
     * monitor. Used by the UI to show "monitor: yes/no" on the bed tile and
     * by admins when configuring the bed. The actual device link is on the
     * IoTDevice side (iot_devices.assigned_bed_id).
     */
    @Column(name = "has_monitor", nullable = false)
    @Builder.Default
    private boolean hasMonitor = false;

    /**
     * The visit currently placed in this bed (null if AVAILABLE / CLEANING /
     * OUT_OF_SERVICE). Mirrors Visit.currentBed and is kept in sync by
     * BedService; a DB partial-unique index prevents double-booking.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_visit_id")
    private Visit currentVisit;

    /**
     * Display order within the zone on the bed-grid UI. Usually matches the
     * numeric suffix of the code (R1=1, R2=2, …). Admins can override.
     */
    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private int displayOrder = 0;

    /** Free-text notes for the bed (e.g. "ventilator installed, 2025-03 check"). */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // ── Helpers ────────────────────────────────────────────────────────────

    /** True when the bed can accept a new placement. */
    public boolean isOccupiable() {
        return status == BedStatus.AVAILABLE && currentVisit == null;
    }

    /** True when a patient is currently placed here. */
    public boolean isOccupied() {
        return status == BedStatus.OCCUPIED && currentVisit != null;
    }
}
