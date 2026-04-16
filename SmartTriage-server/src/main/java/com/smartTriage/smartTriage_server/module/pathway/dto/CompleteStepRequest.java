package com.smartTriage.smartTriage_server.module.pathway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompleteStepRequest {

    private String completedByName;
    private String notes;
}
