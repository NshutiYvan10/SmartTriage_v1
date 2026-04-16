package com.smartTriage.smartTriage_server.module.visit.dto;

import com.smartTriage.smartTriage_server.common.enums.ArrivalMode;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateVisitRequest {

    @NotNull(message = "Patient ID is required")
    private UUID patientId;

    @NotNull(message = "Hospital ID is required")
    private UUID hospitalId;

    private ArrivalMode arrivalMode;
    private String chiefComplaint;
    private String referringFacility;
}
