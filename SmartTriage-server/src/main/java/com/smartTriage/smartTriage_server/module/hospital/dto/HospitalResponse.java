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
    private Instant createdAt;
    private Instant updatedAt;
}
