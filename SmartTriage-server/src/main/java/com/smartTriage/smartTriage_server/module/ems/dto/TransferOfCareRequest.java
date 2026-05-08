package com.smartTriage.smartTriage_server.module.ems.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Receiving ED nurse acknowledges the paramedic handover. Run status
 * flips to HANDED_OFF. The nurse's name + an optional read-back
 * (which items they're taking responsibility for) are recorded.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferOfCareRequest {

    private String receivedByName;
    private String acknowledgementText;
}
