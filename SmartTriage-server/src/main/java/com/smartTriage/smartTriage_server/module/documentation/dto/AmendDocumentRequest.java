package com.smartTriage.smartTriage_server.module.documentation.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to amend an existing clinical document.
 * Creates a NEW document linked to the original — the original is never modified.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AmendDocumentRequest {

    @NotBlank(message = "Amendment reason is required")
    private String amendmentReason;

    @NotBlank(message = "Amended content is required")
    private String content;

    // The amendment's author is derived server-side from the authenticated user —
    // never accepted from the client.

    private String notes;
}
