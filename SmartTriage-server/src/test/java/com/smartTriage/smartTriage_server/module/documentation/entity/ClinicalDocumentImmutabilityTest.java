package com.smartTriage.smartTriage_server.module.documentation.entity;

import com.smartTriage.smartTriage_server.common.enums.ClinicalDocumentType;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Item #2 — Lock signed documents. Exercises the persistence-layer immutability
 * guard ({@code @PostLoad}/{@code @PreUpdate}/{@code @PreRemove}) directly:
 * a signed document's content/identity is frozen and it cannot be deleted, while
 * the unsigned->signed transition and a single co-signature remain permitted.
 *
 * Lives in the entity package so the package-private JPA callback methods are
 * invokable without a full persistence context.
 */
class ClinicalDocumentImmutabilityTest {

    private ClinicalDocument signedDoc() {
        ClinicalDocument d = ClinicalDocument.builder()
                .documentType(ClinicalDocumentType.PROGRESS_NOTE)
                .title("Progress Note")
                .content("Original signed content.")
                .authorUserId(UUID.randomUUID())
                .authorName("Alice Mwangi")
                .authorRole("DOCTOR")
                .authorLicenseNumber("RW-DOC-001")
                .signedAt(Instant.now())
                .isSigned(true)
                .build();
        d.setId(UUID.randomUUID());
        return d;
    }

    private ClinicalDocument unsignedDoc() {
        ClinicalDocument d = ClinicalDocument.builder()
                .documentType(ClinicalDocumentType.PROGRESS_NOTE)
                .title("Draft")
                .content("Draft content.")
                .authorUserId(UUID.randomUUID())
                .authorName("Alice Mwangi")
                .isSigned(false)
                .build();
        d.setId(UUID.randomUUID());
        return d;
    }

    @Test
    void editingSignedContent_isRejected() {
        ClinicalDocument d = signedDoc();
        d.captureImmutabilitySnapshot();      // simulates @PostLoad of a signed row
        d.setContent("Silently tampered content.");
        assertThatThrownBy(d::guardSignedImmutability)
                .isInstanceOf(ClinicalBusinessException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    void editingSignedTitleOrAuthor_isRejected() {
        ClinicalDocument d = signedDoc();
        d.captureImmutabilitySnapshot();
        d.setAuthorName("Someone Else");       // forging the recorded author
        assertThatThrownBy(d::guardSignedImmutability)
                .isInstanceOf(ClinicalBusinessException.class);
    }

    @Test
    void editingSignedProcedureStructuredField_isRejected() {
        ClinicalDocument d = signedDoc();
        d.setProcedureFindings("Original operative findings.");
        d.captureImmutabilitySnapshot();
        d.setProcedureFindings("Silently altered findings.");   // tamper a structured field
        assertThatThrownBy(d::guardSignedImmutability)
                .isInstanceOf(ClinicalBusinessException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    void softDeletingSignedDocument_isRejected() {
        ClinicalDocument d = signedDoc();
        d.captureImmutabilitySnapshot();
        d.softDelete();                         // is_active -> false
        assertThatThrownBy(d::guardSignedImmutability)
                .isInstanceOf(ClinicalBusinessException.class);
    }

    @Test
    void hardDeletingSignedDocument_isRejected() {
        ClinicalDocument d = signedDoc();
        assertThatThrownBy(d::guardSignedDeletion)
                .isInstanceOf(ClinicalBusinessException.class)
                .hasMessageContaining("cannot be deleted");
    }

    @Test
    void addingCoSignatureToSignedDocument_isAllowed() {
        ClinicalDocument d = signedDoc();
        d.captureImmutabilitySnapshot();
        // The one permitted post-sign change: a co-signature.
        d.setCoSignedByUserId(UUID.randomUUID());
        d.setCoSignedByName("Carol Senior");
        d.setCoSignedByRole("DOCTOR");
        d.setCoSignedByLicenseNumber("RW-DOC-555");
        d.setCoSignedAt(Instant.now());
        assertThatCode(d::guardSignedImmutability).doesNotThrowAnyException();
    }

    @Test
    void signingAnUnsignedDocument_isAllowed() {
        ClinicalDocument d = unsignedDoc();
        d.captureImmutabilitySnapshot();        // wasSignedOnLoad = false
        d.setSigned(true);
        d.setSignedAt(Instant.now());
        d.setAuthorName("Bob Otieno");          // signer becomes the recorded author
        assertThatCode(d::guardSignedImmutability).doesNotThrowAnyException();
    }

    @Test
    void deletingUnsignedDraft_isAllowed() {
        ClinicalDocument d = unsignedDoc();
        assertThatCode(d::guardSignedDeletion).doesNotThrowAnyException();
    }
}
