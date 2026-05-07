package com.smartTriage.smartTriage_server.module.hospital.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateHospitalRequest {

    @NotBlank(message = "Hospital name is required")
    @Size(max = 255, message = "Hospital name must not exceed 255 characters")
    private String name;

    /**
     * Optional. When omitted (or blank), the server auto-generates a
     * unique code from the hospital name (e.g. "King Faisal Hospital"
     * → "KFH-001"). Operators can still supply a specific code if
     * they want to mirror an external system's identifier; uniqueness
     * is enforced either way.
     */
    @Size(max = 20, message = "Hospital code must not exceed 20 characters")
    private String hospitalCode;

    /**
     * True when this hospital has full resuscitation capability inside
     * its dedicated PEDIATRIC zone (paeds defibrillator, paeds drug
     * calcs, full ETT range). Affects RED-pediatric placement: when
     * true, RED peds go to PEDIATRIC; when false they go to RESUS.
     * Defaults to false — the conservative direction.
     */
    private Boolean hasPediatricResus;

    /**
     * True when this hospital has a dedicated neonatal unit with
     * neonatal-specific equipment and trained staff. Affects neonatal
     * (≤28 days) placement: when true, they go to NEONATAL regardless
     * of category; when false they fall through to pediatric routing.
     */
    private Boolean hasNeonatalUnit;

    private String address;
    private String city;
    private String province;

    @Size(max = 3, message = "Country code must be ISO 3166-1 alpha-3")
    private String country;

    @Size(max = 20, message = "Phone number must not exceed 20 characters")
    private String phoneNumber;

    @Email(message = "Invalid email format")
    private String email;

    private String tier;
    private Integer bedCapacity;
    private Integer edCapacity;
    private Integer icuCapacity;

    // ── Structured location (Rwanda admin hierarchy) ──
    // V46+ — optional. Frontend's RwandaLocationPicker submits IDs
    // for whichever levels the user picked. Existing free-text
    // {province} / {address} stay for legacy display.
    private java.util.UUID provinceId;
    private java.util.UUID districtId;
    private java.util.UUID sectorId;
    private java.util.UUID cellId;
    private java.util.UUID villageId;
}
