package com.smartTriage.smartTriage_server.module.patient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Query parameters for patient lookup. The service treats every non-blank
 * field as a separate matcher and unions the results into a ranked
 * candidate list — so the caller can supply whatever combination of
 * identifiers they have on hand.
 *
 * Example combinations:
 * <ul>
 *   <li>{@code nationalId=...} — Tier 1, single row.</li>
 *   <li>{@code passport=...} — Tier 1, single row.</li>
 *   <li>{@code birthCertificate=...} — Tier 1, single row.</li>
 *   <li>{@code mrn=...} — Tier 2, single row.</li>
 *   <li>{@code phone=...&dob=...} — Tier 3, narrows phone-share collisions.</li>
 *   <li>{@code guardianNationalId=...&firstName=...&dob=...} — Tier 3
 *       pediatric.</li>
 *   <li>{@code guardianPhone=...&firstName=...&dob=...} — Tier 3
 *       pediatric, lower confidence.</li>
 *   <li>{@code firstName=...&lastName=...&dob=...} — Tier 4 fallback.</li>
 * </ul>
 *
 * Hospital scope is supplied separately at the controller layer (path
 * variable) — it is never trusted from the query body.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatientLookupQuery {
    private String nationalId;
    private String passport;
    private String birthCertificate;
    private String mrn;
    private String phone;
    private String guardianNationalId;
    private String guardianPhone;
    private String firstName;
    private String lastName;
    private LocalDate dob;
}
