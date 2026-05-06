package com.smartTriage.smartTriage_server.module.patient.dto;

import com.smartTriage.smartTriage_server.common.enums.PregnancyStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdatePregnancyStatusRequest {

    @NotNull(message = "pregnancyStatus is required")
    private PregnancyStatus pregnancyStatus;
}
