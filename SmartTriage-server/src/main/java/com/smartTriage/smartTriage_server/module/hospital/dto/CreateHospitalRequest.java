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
}
