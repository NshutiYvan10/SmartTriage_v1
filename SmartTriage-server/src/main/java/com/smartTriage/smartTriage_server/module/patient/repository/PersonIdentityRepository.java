package com.smartTriage.smartTriage_server.module.patient.repository;

import com.smartTriage.smartTriage_server.module.patient.entity.PersonIdentity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PersonIdentityRepository extends JpaRepository<PersonIdentity, UUID> {

    Optional<PersonIdentity> findByNationalIdAndIsActiveTrue(String nationalId);

    /** Resolve the shared identity by RFID card UID — the system-wide tap-to-identify lookup (V95). */
    Optional<PersonIdentity> findByRfidCardIdAndIsActiveTrue(String rfidCardId);
}
