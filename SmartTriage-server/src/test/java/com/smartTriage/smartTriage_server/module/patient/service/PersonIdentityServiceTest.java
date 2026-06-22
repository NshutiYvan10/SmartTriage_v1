package com.smartTriage.smartTriage_server.module.patient.service;

import com.smartTriage.smartTriage_server.module.patient.entity.PersonIdentity;
import com.smartTriage.smartTriage_server.module.patient.repository.PersonIdentityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link PersonIdentityService#findOrCreate} — the deterministic national-ID anchor:
 * returns null for blank, reuses an existing identity, creates on first sight, and survives the
 * first-seen-at-two-hospitals-at-once race by adopting the concurrently-created row.
 */
class PersonIdentityServiceTest {

    private final PersonIdentityRepository repo = mock(PersonIdentityRepository.class);
    private final PersonIdentityService service = new PersonIdentityService(repo);

    private PersonIdentity identity(String nid) {
        PersonIdentity p = new PersonIdentity();
        p.setId(UUID.randomUUID());
        p.setNationalId(nid);
        return p;
    }

    @Test
    void returnsNull_forBlankOrNullNationalId() {
        assertThat(service.findOrCreate(null)).isNull();
        assertThat(service.findOrCreate("   ")).isNull();
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void reusesExistingIdentity_withoutCreating() {
        PersonIdentity existing = identity("1199870012345678");
        when(repo.findByNationalIdAndIsActiveTrue("1199870012345678")).thenReturn(Optional.of(existing));

        assertThat(service.findOrCreate("  1199870012345678 ")).isSameAs(existing); // trims input
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void createsIdentity_onFirstSight() {
        when(repo.findByNationalIdAndIsActiveTrue("NID-NEW")).thenReturn(Optional.empty());
        when(repo.saveAndFlush(any(PersonIdentity.class))).thenAnswer(i -> i.getArgument(0));

        PersonIdentity created = service.findOrCreate("NID-NEW");
        assertThat(created).isNotNull();
        assertThat(created.getNationalId()).isEqualTo("NID-NEW");
    }

    @Test
    void survivesConcurrentCreate_byAdoptingTheOtherRow() {
        PersonIdentity other = identity("NID-RACE");
        when(repo.findByNationalIdAndIsActiveTrue("NID-RACE"))
                .thenReturn(Optional.empty())   // first check: not there yet
                .thenReturn(Optional.of(other)); // after the unique-violation: adopt theirs
        when(repo.saveAndFlush(any(PersonIdentity.class)))
                .thenThrow(new DataIntegrityViolationException("uq_person_identity_national_id"));

        assertThat(service.findOrCreate("NID-RACE")).isSameAs(other);
    }
}
