package com.smartTriage.smartTriage_server.module.documentation.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to electronically sign a clinical document.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignDocumentRequest {

    @NotBlank(message = "Signer name is required")
    private String signerName;

    @NotBlank(message = "License number is required for electronic signature")
    private String licenseNumber;
}
