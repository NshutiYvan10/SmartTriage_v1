package com.smartTriage.smartTriage_server.module.pathway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PathwayStepResponse {

    private UUID id;
    private UUID pathwayId;
    private Integer stepOrder;
    private String stepTitle;
    private String stepDescription;
    private Integer timeframeMinutes;
    // Field is `mandatory` (couples with the isMandatory() getter into ONE Jackson property);
    // @JsonProperty pins the JSON key to "isMandatory" — the key the frontend reads for the
    // Required badge / pending-mandatory count / overdue timer. (A field literally named
    // `isMandatory` serialised as BOTH "isMandatory" and "mandatory" — the FE saw neither set.)
    @JsonProperty("isMandatory")
    private boolean mandatory;
    private String category;
}
