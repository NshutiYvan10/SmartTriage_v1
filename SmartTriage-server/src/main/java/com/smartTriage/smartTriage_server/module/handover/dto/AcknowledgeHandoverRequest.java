package com.smartTriage.smartTriage_server.module.handover.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AcknowledgeHandoverRequest {

    @NotBlank(message = "Receiver name is required")
    private String receiverName;
}
