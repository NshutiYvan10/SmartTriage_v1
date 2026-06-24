package com.smartTriage.smartTriage_server.module.patient.service;

import com.smartTriage.smartTriage_server.common.exception.IdentityConflictException;
import com.smartTriage.smartTriage_server.module.patient.entity.PersonIdentity;
import com.smartTriage.smartTriage_server.module.patient.repository.PersonIdentityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    // ── V95: two-key (national ID + RFID card) resolve-or-merge ──

    private PersonIdentity identity(String nid, String card) {
        PersonIdentity p = identity(nid);
        p.setRfidCardId(card);
        return p;
    }

    @Test
    void createsCardOnlyIdentity_whenNoNationalIdPresent() {
        when(repo.findByRfidCardIdAndIsActiveTrue("CARD-NEW")).thenReturn(Optional.empty());
        when(repo.saveAndFlush(any(PersonIdentity.class))).thenAnswer(i -> i.getArgument(0));

        // The unconscious / newborn / foreign / unidentified case: a card anchors the identity alone.
        PersonIdentity created = service.findOrCreate(null, "CARD-NEW");
        assertThat(created).isNotNull();
        assertThat(created.getNationalId()).isNull();
        assertThat(created.getRfidCardId()).isEqualTo("CARD-NEW");
    }

    @Test
    void attachesNewCard_toExistingNationalIdIdentity() {
        PersonIdentity existing = identity("NID-1", null);
        when(repo.findByNationalIdAndIsActiveTrue("NID-1")).thenReturn(Optional.of(existing));
        when(repo.findByRfidCardIdAndIsActiveTrue("CARD-1")).thenReturn(Optional.empty());
        when(repo.saveAndFlush(any(PersonIdentity.class))).thenAnswer(i -> i.getArgument(0));

        PersonIdentity result = service.findOrCreate("NID-1", "CARD-1");
        assertThat(result).isSameAs(existing);
        assertThat(result.getRfidCardId()).isEqualTo("CARD-1"); // card now attached to the known person
    }

    @Test
    void attachesNationalId_toCardAnchoredPlaceholder_onIdentification() {
        PersonIdentity placeholder = identity(null, "CARD-2");
        when(repo.findByNationalIdAndIsActiveTrue("NID-2")).thenReturn(Optional.empty());
        when(repo.findByRfidCardIdAndIsActiveTrue("CARD-2")).thenReturn(Optional.of(placeholder));
        when(repo.saveAndFlush(any(PersonIdentity.class))).thenAnswer(i -> i.getArgument(0));

        // The "unidentified placeholder is now identified" upgrade.
        PersonIdentity result = service.findOrCreate("NID-2", "CARD-2");
        assertThat(result).isSameAs(placeholder);
        assertThat(result.getNationalId()).isEqualTo("NID-2");
    }

    @Test
    void returnsSameIdentity_whenBothKeysAlreadyResolveToIt() {
        PersonIdentity both = identity("NID-3", "CARD-3");
        when(repo.findByNationalIdAndIsActiveTrue("NID-3")).thenReturn(Optional.of(both));
        when(repo.findByRfidCardIdAndIsActiveTrue("CARD-3")).thenReturn(Optional.of(both));

        assertThat(service.findOrCreate("NID-3", "CARD-3")).isSameAs(both);
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void rejects_whenNationalIdAndCardBelongToDifferentIdentities() {
        PersonIdentity byNid = identity("NID-4", null);
        PersonIdentity byCard = identity(null, "CARD-4");
        when(repo.findByNationalIdAndIsActiveTrue("NID-4")).thenReturn(Optional.of(byNid));
        when(repo.findByRfidCardIdAndIsActiveTrue("CARD-4")).thenReturn(Optional.of(byCard));

        // No auto-merge: a wrong merge would surface another patient's allergies/history.
        assertThatThrownBy(() -> service.findOrCreate("NID-4", "CARD-4"))
                .isInstanceOf(IdentityConflictException.class);
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void rejects_reassigningADifferentCardToAPatientWhoAlreadyHasOne() {
        PersonIdentity existing = identity("NID-5", "OLD-CARD");
        when(repo.findByNationalIdAndIsActiveTrue("NID-5")).thenReturn(Optional.of(existing));
        when(repo.findByRfidCardIdAndIsActiveTrue("NEW-CARD")).thenReturn(Optional.empty());

        // Reassignment must be explicit/audited — never a silent overwrite.
        assertThatThrownBy(() -> service.findOrCreate("NID-5", "NEW-CARD"))
                .isInstanceOf(IdentityConflictException.class);
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void returnsNull_whenNeitherAnchorPresent() {
        assertThat(service.findOrCreate(null, null)).isNull();
        assertThat(service.findOrCreate("  ", "  ")).isNull();
        verify(repo, never()).saveAndFlush(any());
    }

    // ── V95: replaceCard (lost/damaged-card workflow) ──

    @Test
    void replaceCard_swapsCard_andReturnsOldCard() {
        PersonIdentity id = identity("NID-9", "OLD-CARD");
        when(repo.findByRfidCardIdAndIsActiveTrue("NEW-CARD")).thenReturn(Optional.empty());
        when(repo.saveAndFlush(any(PersonIdentity.class))).thenAnswer(i -> i.getArgument(0));

        String old = service.replaceCard(id, "NEW-CARD");
        assertThat(old).isEqualTo("OLD-CARD");
        assertThat(id.getRfidCardId()).isEqualTo("NEW-CARD"); // old card now stops resolving
    }

    @Test
    void replaceCard_rejects_whenNewCardBelongsToAnotherPatient() {
        PersonIdentity id = identity("NID-10", "OLD-CARD");
        PersonIdentity other = identity("NID-11", "TAKEN-CARD");
        when(repo.findByRfidCardIdAndIsActiveTrue("TAKEN-CARD")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.replaceCard(id, "TAKEN-CARD"))
                .isInstanceOf(IdentityConflictException.class);
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void replaceCard_isNoOp_whenSameCardReentered() {
        PersonIdentity id = identity("NID-12", "SAME-CARD");
        when(repo.findByRfidCardIdAndIsActiveTrue("SAME-CARD")).thenReturn(Optional.of(id));

        assertThat(service.replaceCard(id, "SAME-CARD")).isEqualTo("SAME-CARD");
        verify(repo, never()).saveAndFlush(any());
    }
}
