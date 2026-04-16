package com.smartTriage.smartTriage_server.module.hospital.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Hospital entity — the multi-tenancy anchor.
 * Every clinical resource (patient, visit, user) is scoped to a hospital.
 * This enables multi-hospital deployment from a single backend instance.
 */
@Entity
@Table(name = "hospitals", indexes = {
        @Index(name = "idx_hospital_code", columnList = "hospital_code", unique = true),
        @Index(name = "idx_hospital_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Hospital extends BaseEntity {

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "hospital_code", nullable = false, unique = true, length = 20)
    private String hospitalCode;

    @Column(name = "address")
    private String address;

    @Column(name = "city")
    private String city;

    @Column(name = "province")
    private String province;

    @Column(name = "country", length = 3)
    private String country;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "email")
    private String email;

    @Column(name = "tier", length = 20)
    private String tier; // e.g., District, Regional, Tertiary

    @Column(name = "bed_capacity")
    private Integer bedCapacity;

    @Column(name = "ed_capacity")
    private Integer edCapacity;

    @Column(name = "icu_capacity")
    private Integer icuCapacity;
}
