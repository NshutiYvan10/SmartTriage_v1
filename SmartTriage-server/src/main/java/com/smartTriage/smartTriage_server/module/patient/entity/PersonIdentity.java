package com.smartTriage.smartTriage_server.module.patient.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Cross-hospital patient identity anchor (Phase 1 of the federated-hybrid model).
 *
 * One {@code PersonIdentity} per real person, keyed on national ID. Each hospital keeps its
 * own local {@link Patient} row (deep clinical records stay hospital-owned); those local rows
 * link to a single shared identity via {@code patients.person_identity_id}. This is what lets a
 * returning patient be recognised at a different SmartTriage hospital instead of re-registered
 * from blank, and what the minimal cross-hospital safety summary is assembled against.
 *
 * Phase 1 matches on national ID only (deterministic — a wrong probabilistic merge would be a
 * patient-safety incident). Patients without a national ID (e.g. unidentified placeholders) are
 * never linked and stay purely local.
 */
@Entity
@Table(name = "person_identities")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PersonIdentity extends BaseEntity {

    @Column(name = "national_id", nullable = false, length = 30, unique = true)
    private String nationalId;
}
