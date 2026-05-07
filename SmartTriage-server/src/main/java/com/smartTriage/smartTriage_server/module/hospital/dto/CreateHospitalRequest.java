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

    @NotBlank(message = "Hospital code is required")
    @Size(max = 20, message = "Hospital code must not exceed 20 characters")
    private String hospitalCode;

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
