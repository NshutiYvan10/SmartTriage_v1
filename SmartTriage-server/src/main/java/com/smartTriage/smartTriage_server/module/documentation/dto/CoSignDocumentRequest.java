package com.smartTriage.smartTriage_server.module.documentation.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to co-sign a clinical document (for supervised clinicians).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoSignDocumentRequest {

    @NotBlank(message = "Co-signer name is required")
    private String coSignerName;
}
