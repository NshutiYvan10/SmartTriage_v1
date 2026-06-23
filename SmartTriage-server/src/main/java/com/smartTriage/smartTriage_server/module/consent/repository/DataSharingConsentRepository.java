package com.smartTriage.smartTriage_server.module.consent.repository;

import com.smartTriage.smartTriage_server.common.enums.DataSharingConsentStatus;
import com.smartTriage.smartTriage_server.module.consent.entity.DataSharingConsent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DataSharingConsentRepository extends JpaRepository<DataSharingConsent, UUID> {

    Optional<DataSharingConsent> findByIdAndIsActiveTrue(UUID id);

    /** The current effective consent: the single live row of the given status (use GRANTED). */
    Optional<DataSharingConsent> findFirstByPersonIdentityIdAndStatusAndIsActiveTrueOrderByObtainedAtDesc(
            UUID personIdentityId, DataSharingConsentStatus status);

    /** Full consent history for a person (newest first). */
    List<DataSharingConsent> findByPersonIdentityIdAndIsActiveTrueOrderByObtainedAtDesc(UUID personIdentityId);
}
