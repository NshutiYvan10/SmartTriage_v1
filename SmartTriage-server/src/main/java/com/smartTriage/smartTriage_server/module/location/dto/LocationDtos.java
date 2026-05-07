package com.smartTriage.smartTriage_server.module.location.dto;

import lombok.*;

import java.util.UUID;

/** Single-file holder for the small location-API DTOs. */
public class LocationDtos {
    private LocationDtos() {}

    /**
     * Generic shape used by every level of the cascading dropdown.
     * The frontend only needs id + name + code; specific level-name
     * fields would be redundant.
     */
    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class LocationOption {
        private UUID id;
        private String code;
        private String name;
    }
}
