package com.smartTriage.smartTriage_server.module.hospital.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Hospital entity — the multi-tenancy anchor.
 * Every clinical resource (patient, visit, user) is scoped to a hospital.
 * This enables multi-hospital deployment from a single backend instance.
 */
@Entity
@Table(name = "hospitals", indexes = {
        @Index(name = "idx_hospital_code", columnList = "hospital_code", unique = true),
        @Index(name = "idx_hospital_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Hospital extends BaseEntity {

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "hospital_code", nullable = false, unique = true, length = 20)
    private String hospitalCode;

    @Column(name = "address")
    private String address;

    @Column(name = "city")
    private String city;

    @Column(name = "province")
    private String province;

    // ── Structured location (Rwanda admin hierarchy) ──
    // V46+ — replaces the free-text {province} above for new records.
    // Existing rows keep {province} populated; new records persist via
    // these FKs. The {address} column above stays for street-level
    // detail (building, landmark) that doesn't fit in the village level.
    @jakarta.persistence.ManyToOne(fetch = jakarta.persistence.FetchType.LAZY)
    @jakarta.persistence.JoinColumn(name = "province_id")
    private com.smartTriage.smartTriage_server.module.location.entity.RwProvince provinceRef;

    @jakarta.persistence.ManyToOne(fetch = jakarta.persistence.FetchType.LAZY)
    @jakarta.persistence.JoinColumn(name = "district_id")
    private com.smartTriage.smartTriage_server.module.location.entity.RwDistrict districtRef;

    @jakarta.persistence.ManyToOne(fetch = jakarta.persistence.FetchType.LAZY)
    @jakarta.persistence.JoinColumn(name = "sector_id")
    private com.smartTriage.smartTriage_server.module.location.entity.RwSector sectorRef;

    @jakarta.persistence.ManyToOne(fetch = jakarta.persistence.FetchType.LAZY)
    @jakarta.persistence.JoinColumn(name = "cell_id")
    private com.smartTriage.smartTriage_server.module.location.entity.RwCell cellRef;

    @jakarta.persistence.ManyToOne(fetch = jakarta.persistence.FetchType.LAZY)
    @jakarta.persistence.JoinColumn(name = "village_id")
    private com.smartTriage.smartTriage_server.module.location.entity.RwVillage villageRef;

    @Column(name = "country", length = 3)
    private String country;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "email")
    private String email;

    @Column(name = "tier", length = 20)
    private String tier; // e.g., District, Regional, Tertiary

    @Column(name = "bed_capacity")
    private Integer bedCapacity;

    @Column(name = "ed_capacity")
    private Integer edCapacity;

    @Column(name = "icu_capacity")
    private Integer icuCapacity;

    /**
     * True when this hospital has full resuscitation capability inside
     * its dedicated PEDIATRIC zone (defibrillator, paeds drug calcs,
     * full ETT range, etc.). Affects how RED pediatric patients are
     * routed: when true, they go to PEDIATRIC; when false (the default
     * for most facilities), they go to the main RESUS zone — the
     * conservative direction.
     *
     * <p>See {@link com.smartTriage.smartTriage_server.common.enums.EdZone#forPatientPlacement}
     * for the full placement decision matrix.
     */
    @Column(name = "has_pediatric_resus", nullable = false)
    @Builder.Default
    private boolean hasPediatricResus = false;

    /**
     * True when this hospital has a dedicated neonatal unit with
     * neonatal-specific equipment and trained staff. Affects how
     * neonatal patients (≤28 days old) are routed: when true they
     * go to the NEONATAL zone regardless of category; when false
     * (the default for most facilities) they fall through to the
     * pediatric routing.
     *
     * <p>See {@link com.smartTriage.smartTriage_server.common.enums.EdZone#forPatientPlacement(
     *   com.smartTriage.smartTriage_server.common.enums.TriageCategory,
     *   boolean, boolean, boolean, boolean, boolean)} for the full
     * placement decision matrix.
     */
    @Column(name = "has_neonatal_unit", nullable = false)
    @Builder.Default
    private boolean hasNeonatalUnit = false;

    /**
     * Phase 2 — gate critical lab results behind a HEAD_LAB_TECHNICIAN
     * verification step. Off by default. Hospitals without senior-tech
     * coverage on every shift should leave it off; the per-priority
     * timeout auto-release prevents it from ever blocking patient care
     * when enabled, but defaults err toward "no change in behaviour".
     */
    @Column(name = "two_step_verification_enabled", nullable = false)
    @Builder.Default
    private boolean twoStepVerificationEnabled = false;
}
