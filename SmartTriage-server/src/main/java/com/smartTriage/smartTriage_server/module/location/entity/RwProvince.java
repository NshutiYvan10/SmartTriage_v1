package com.smartTriage.smartTriage_server.module.location.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * One of the 5 administrative provinces of Rwanda. Reference data —
 * seeded by Flyway V47, never user-editable at runtime.
 */
@Entity
@Table(name = "rw_provinces")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class RwProvince {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false, columnDefinition = "uuid")
    private UUID id;

    /** Internal stable code, e.g. "RW.01" — used as the natural join key. */
    @Column(name = "code", nullable = false, unique = true, length = 8)
    private String code;

    @Column(name = "name", nullable = false, unique = true, length = 120)
    private String name;

    /** Display order on form dropdowns; lower = earlier. */
    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
