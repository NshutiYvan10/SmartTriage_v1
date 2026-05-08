package com.smartTriage.smartTriage_server.module.lab.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lab tech "accessions" the specimen on receipt: writes the lab's
 * own barcode/sequence on the tube and records who received it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReceiveSpecimenRequest {

    /** Optional — server generates one if blank. */
    private String accessionNumber;

    private String receivedByName;
}
