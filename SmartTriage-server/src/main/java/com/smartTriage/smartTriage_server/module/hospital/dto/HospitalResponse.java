package com.smartTriage.smartTriage_server.module.hospital.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HospitalResponse {

    private UUID id;
    private String name;
    private String hospitalCode;
    private String address;
    private String city;
    private String province;
    private String country;
    private String phoneNumber;
    private String email;
    private String tier;
    private Integer bedCapacity;
    private Integer edCapacity;
    private Integer icuCapacity;

    /** True when this hospital has paeds resus inside its PEDIATRIC zone. */
    private boolean hasPediatricResus;

    /** True when this hospital has a dedicated neonatal unit. */
    private boolean hasNeonatalUnit;

    /** Phase 2 — two-step lab verification toggle. */
    private boolean twoStepVerificationEnabled;

    /**
     * Active flag — false means the hospital has been deactivated. No
     * clinical operations should accept new visits or staff while in
     * this state. Reactivate via the dedicated reactivate endpoint.
     */
    private boolean active;

    // ── Structured location IDs (V46+) ──
    // Surfaced so the admin Edit form can pre-fill the cascading
    // RwandaLocationPicker without an extra round trip.
    private UUID provinceId;
    private UUID districtId;
    private UUID sectorId;
    private UUID cellId;
    private UUID villageId;

    private Instant createdAt;
    private Instant updatedAt;
}
