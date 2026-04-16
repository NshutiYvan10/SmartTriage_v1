package com.smartTriage.smartTriage_server.module.visit.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartTriage.smartTriage_server.common.enums.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisitResponse {

    private UUID id;
    private String visitNumber;
    private UUID patientId;
    private String patientName;
    private UUID hospitalId;
    private ArrivalMode arrivalMode;
    private Instant arrivalTime;
    private String chiefComplaint;
    private VisitStatus status;
    private TriageCategory currentTriageCategory;
    private Integer currentTewsScore;
    private Instant triageTime;
    private Instant assessmentStartTime;
    private DispositionType dispositionType;
    private Instant dispositionTime;
    private String dispositionNotes;
    private String referringFacility;
    @JsonProperty("isPediatric")
    private boolean isPediatric;
    private int retriageCount;
    private Instant createdAt;
    private Instant updatedAt;
}
