package com.smartTriage.smartTriage_server.module.ems.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Minimal hospital descriptor for the paramedic's destination picker.
 * Deliberately tiny (id + name + code + city) — the full Hospital read
 * endpoint is SUPER_ADMIN-only, and a crew choosing a destination only
 * needs to recognise the facility.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DestinationHospitalResponse {
    private UUID id;
    private String name;
    private String hospitalCode;
    private String city;
}
