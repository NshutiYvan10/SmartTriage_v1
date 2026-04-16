package com.smartTriage.smartTriage_server.module.patient.dto;

import com.smartTriage.smartTriage_server.module.visit.dto.VisitResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Combined registration response — returns both the created Patient
 * and the created Visit so the frontend has everything in one call.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterPatientResponse {
    private PatientResponse patient;
    private VisitResponse visit;
}
