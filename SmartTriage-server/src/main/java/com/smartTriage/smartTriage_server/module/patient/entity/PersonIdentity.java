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
 * Anchoring is by national ID AND/OR RFID card (V95) — both deterministic-exact (a wrong
 * probabilistic merge would be a patient-safety incident). The card makes identity system-wide
 * even for patients with no national ID (unconscious / newborn / foreign / unidentified). At least
 * one anchor is always present (DB CHECK + partial-unique indexes on each, declared in V95 — JPA
 * cannot express partial uniqueness). {@link PersonIdentityService} resolves/merges the two keys
 * and REJECTS a card + national ID that point at different identities rather than auto-merging.
 */
@Entity
@Table(name = "person_identities")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PersonIdentity extends BaseEntity {

    @Column(name = "national_id", length = 30)
    private String nationalId;

    /** RFID card UID — a co-equal cross-hospital anchor (V95). Nullable; partial-unique in V95 SQL. */
    @Column(name = "rfid_card_id", length = 64)
    private String rfidCardId;
}
