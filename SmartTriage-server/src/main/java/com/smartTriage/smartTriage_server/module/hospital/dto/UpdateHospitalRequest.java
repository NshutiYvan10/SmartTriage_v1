package com.smartTriage.smartTriage_server.module.hospital.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Update payload for an existing hospital.
 *
 * <p>All fields are optional; the service treats {@code null} as
 * "leave unchanged" so a partial update from a frontend that doesn't
 * load every field still works without clobbering data. The
 * {@code hospitalCode} is intentionally excluded — once a hospital is
 * created its code is its stable external identifier; renaming would
 * orphan any external references (insurance integrations, MoH
 * reporting joins). If a code-change is ever needed, that's a
 * separate audited operation, not a normal edit.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateHospitalRequest {

    @Size(max = 255, message = "Hospital name must not exceed 255 characters")
    private String name;

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

    private Boolean hasPediatricResus;
    private Boolean hasNeonatalUnit;

    private UUID provinceId;
    private UUID districtId;
    private UUID sectorId;
    private UUID cellId;
    private UUID villageId;
}
