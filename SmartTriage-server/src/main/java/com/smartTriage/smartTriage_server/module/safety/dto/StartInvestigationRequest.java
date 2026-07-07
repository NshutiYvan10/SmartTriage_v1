package com.smartTriage.smartTriage_server.module.safety.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for starting an incident investigation. Replaces the old query-parameter
 * contract ({@code ?investigatorName=}) that the frontend never actually spoke —
 * it always sent this JSON body and got a 500 back.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StartInvestigationRequest {

    /** Who is assigned to investigate (name; may differ from the acting user). */
    @NotBlank(message = "Investigator name is required")
    @Size(max = 200)
    private String investigatorName;
}
