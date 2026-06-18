package com.smartTriage.smartTriage_server.module.documentation.service;

import com.smartTriage.smartTriage_server.common.enums.ClinicalDocumentType;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.module.clinical.repository.ClinicalNoteRepository;
import com.smartTriage.smartTriage_server.module.documentation.dto.AmendDocumentRequest;
import com.smartTriage.smartTriage_server.module.documentation.dto.ClinicalDocumentResponse;
import com.smartTriage.smartTriage_server.module.documentation.dto.CreateDocumentRequest;
import com.smartTriage.smartTriage_server.module.documentation.entity.ClinicalDocument;
import com.smartTriage.smartTriage_server.module.documentation.repository.ClinicalDocumentRepository;
import com.smartTriage.smartTriage_server.module.lab.repository.LabOrderRepository;
import com.smartTriage.smartTriage_server.module.medication.repository.MedicationAdministrationRepository;
import com.smartTriage.smartTriage_server.module.triage.repository.TriageRecordRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.service.VisitService;
import com.smartTriage.smartTriage_server.module.vital.repository.VitalSignsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
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
 * Item #1 — Authenticated signatures. Proves that the author/signer/co-signer
 * identity on a clinical document is ALWAYS derived from the authenticated
 * principal and can never be supplied (or forged) by the client.
 *
 * Required scenarios from the goal:
 *   - "signing as another user must fail": the signer is whoever is authenticated;
 *     there is no signer-name input to forge, and the recorded signer always equals
 *     the SecurityContext user. An unauthenticated sign is a hard AccessDeniedException.
 *   - the author on create/amend is the authenticated user, not any request value.
 */
class ClinicalDocumentServiceTest {

    private final ClinicalDocumentRepository documentRepository = mock(ClinicalDocumentRepository.class);
    private final VisitService visitService = mock(VisitService.class);
    private final VitalSignsRepository vitalSignsRepository = mock(VitalSignsRepository.class);
    private final TriageRecordRepository triageRecordRepository = mock(TriageRecordRepository.class);
    private final ClinicalNoteRepository clinicalNoteRepository = mock(ClinicalNoteRepository.class);
    private final MedicationAdministrationRepository medicationRepository = mock(MedicationAdministrationRepository.class);
    private final LabOrderRepository labOrderRepository = mock(LabOrderRepository.class);

    private final ClinicalDocumentService service = new ClinicalDocumentService(
            documentRepository, visitService, vitalSignsRepository, triageRecordRepository,
            clinicalNoteRepository, medicationRepository, labOrderRepository);

    private final UUID VISIT = UUID.randomUUID();

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private User user(String first, String last, Role role, String license) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setFirstName(first);
        u.setLastName(last);
        u.setEmail((first + "." + last + "@hospital.rw").toLowerCase());
        u.setRole(role);
        u.setProfessionalLicense(license);
        return u;
    }

    private void authenticateAs(User u) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u, null, List.of()));
    }

    private Visit visit() {
        Visit v = new Visit();
        v.setId(VISIT);
        v.setVisitNumber("V-DOC-1");
        return v;
    }

    /** An unsigned, freshly-created document owned by {@code author}. */
    private ClinicalDocument unsignedDoc(User author) {
        return ClinicalDocument.builder()
                .visit(visit())
                .documentType(ClinicalDocumentType.PROGRESS_NOTE)
                .title("Progress Note")
                .content("Patient reviewed.")
                .authorUserId(author.getId())
                .authorName(author.getFirstName() + " " + author.getLastName())
                .authorRole(author.getRole().name())
                .authorLicenseNumber(author.getProfessionalLicense())
                .isSigned(false)
                .build();
    }

    @Test
    void createDocument_recordsAuthenticatedAuthor_notAnyClientValue() {
        User alice = user("Alice", "Mwangi", Role.DOCTOR, "RW-DOC-001");
        authenticateAs(alice);
        when(visitService.findVisitOrThrow(VISIT)).thenReturn(visit());
        when(vitalSignsRepository.findFirstByVisitIdAndIsActiveTrueOrderByRecordedAtDesc(VISIT))
                .thenReturn(Optional.empty());
        when(documentRepository.save(any(ClinicalDocument.class))).thenAnswer(i -> i.getArgument(0));

        CreateDocumentRequest req = CreateDocumentRequest.builder()
                .visitId(VISIT)
                .documentType(ClinicalDocumentType.PROCEDURE_NOTE)
                .title("Procedure")
                .content("Sutured laceration.")
                .build();

        ClinicalDocumentResponse resp = service.createDocument(VISIT, req);

        ArgumentCaptor<ClinicalDocument> cap = ArgumentCaptor.forClass(ClinicalDocument.class);
        verify(documentRepository).save(cap.capture());
        ClinicalDocument saved = cap.getValue();
        // Author is the authenticated user — name/role/license all from the user's record.
        assertThat(saved.getAuthorUserId()).isEqualTo(alice.getId());
        assertThat(saved.getAuthorName()).isEqualTo("Alice Mwangi");
        assertThat(saved.getAuthorRole()).isEqualTo("DOCTOR");
        assertThat(saved.getAuthorLicenseNumber()).isEqualTo("RW-DOC-001");
        // And the response surfaces the verifiable user id.
        assertThat(resp.getAuthorUserId()).isEqualTo(alice.getId());
    }

    @Test
    void createDocument_withNoAuthenticatedUser_throwsAccessDenied() {
        SecurityContextHolder.clearContext();
        when(visitService.findVisitOrThrow(VISIT)).thenReturn(visit());

        CreateDocumentRequest req = CreateDocumentRequest.builder()
                .visitId(VISIT).documentType(ClinicalDocumentType.PROGRESS_NOTE)
                .title("t").content("c").build();

        assertThatThrownBy(() -> service.createDocument(VISIT, req))
                .isInstanceOf(AccessDeniedException.class);
        verify(documentRepository, never()).save(any());
    }

    @Test
    void signDocument_signerIsTheAuthenticatedUser_cannotSignAsAnother() {
        // Document was authored/drafted by Alice...
        User alice = user("Alice", "Mwangi", Role.DOCTOR, "RW-DOC-001");
        ClinicalDocument doc = unsignedDoc(alice);
        UUID docId = UUID.randomUUID();
        doc.setId(docId);
        when(documentRepository.findByIdAndIsActiveTrue(docId)).thenReturn(Optional.of(doc));
        when(documentRepository.save(any(ClinicalDocument.class))).thenAnswer(i -> i.getArgument(0));

        // ...but Bob is the one logged in when signing. The signature MUST be Bob's,
        // and there is no way to assert it is anyone else (no signer-name input exists).
        User bob = user("Bob", "Otieno", Role.DOCTOR, "RW-DOC-777");
        authenticateAs(bob);

        ClinicalDocumentResponse resp = service.signDocument(docId);

        assertThat(resp.isSigned()).isTrue();
        assertThat(resp.getSignedAt()).isNotNull();
        assertThat(resp.getAuthorUserId()).isEqualTo(bob.getId());
        assertThat(resp.getAuthorName()).isEqualTo("Bob Otieno");
        assertThat(resp.getAuthorLicenseNumber()).isEqualTo("RW-DOC-777");
    }

    @Test
    void signDocument_withNoAuthenticatedUser_throwsAccessDenied_andDoesNotPersist() {
        User alice = user("Alice", "Mwangi", Role.DOCTOR, "RW-DOC-001");
        ClinicalDocument doc = unsignedDoc(alice);
        UUID docId = UUID.randomUUID();
        doc.setId(docId);
        when(documentRepository.findByIdAndIsActiveTrue(docId)).thenReturn(Optional.of(doc));
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> service.signDocument(docId))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(doc.isSigned()).isFalse();
        verify(documentRepository, never()).save(any());
    }

    @Test
    void coSignDocument_coSignerIsTheAuthenticatedUser() {
        User alice = user("Alice", "Mwangi", Role.DOCTOR, "RW-DOC-001");
        ClinicalDocument doc = unsignedDoc(alice);
        UUID docId = UUID.randomUUID();
        doc.setId(docId);
        doc.setSigned(true); // must be signed before co-sign
        when(documentRepository.findByIdAndIsActiveTrue(docId)).thenReturn(Optional.of(doc));
        when(documentRepository.save(any(ClinicalDocument.class))).thenAnswer(i -> i.getArgument(0));

        User supervisor = user("Carol", "Senior", Role.DOCTOR, "RW-DOC-555");
        authenticateAs(supervisor);

        ClinicalDocumentResponse resp = service.coSignDocument(docId);

        assertThat(resp.getCoSignedByUserId()).isEqualTo(supervisor.getId());
        assertThat(resp.getCoSignedByName()).isEqualTo("Carol Senior");
        assertThat(resp.getCoSignedByRole()).isEqualTo("DOCTOR");
        assertThat(resp.getCoSignedByLicenseNumber()).isEqualTo("RW-DOC-555");
        assertThat(resp.getCoSignedAt()).isNotNull();
    }

    @Test
    void coSignDocument_withNoAuthenticatedUser_throwsAccessDenied() {
        User alice = user("Alice", "Mwangi", Role.DOCTOR, "RW-DOC-001");
        ClinicalDocument doc = unsignedDoc(alice);
        UUID docId = UUID.randomUUID();
        doc.setId(docId);
        doc.setSigned(true);
        when(documentRepository.findByIdAndIsActiveTrue(docId)).thenReturn(Optional.of(doc));
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> service.coSignDocument(docId))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(doc.getCoSignedByUserId()).isNull();
        verify(documentRepository, never()).save(any());
    }

    @Test
    void amendDocument_amendmentAuthorIsTheAuthenticatedUser() {
        User alice = user("Alice", "Mwangi", Role.DOCTOR, "RW-DOC-001");
        ClinicalDocument original = unsignedDoc(alice);
        UUID docId = UUID.randomUUID();
        original.setId(docId);
        original.setSigned(true); // only signed docs can be amended
        when(documentRepository.findByIdAndIsActiveTrue(docId)).thenReturn(Optional.of(original));
        when(vitalSignsRepository.findFirstByVisitIdAndIsActiveTrueOrderByRecordedAtDesc(VISIT))
                .thenReturn(Optional.empty());
        when(documentRepository.save(any(ClinicalDocument.class))).thenAnswer(i -> i.getArgument(0));

        User bob = user("Bob", "Otieno", Role.DOCTOR, "RW-DOC-777");
        authenticateAs(bob);

        AmendDocumentRequest req = AmendDocumentRequest.builder()
                .amendmentReason("Corrected dosage")
                .content("Updated content.")
                .build();

        ClinicalDocumentResponse resp = service.amendDocument(docId, req);

        ArgumentCaptor<ClinicalDocument> cap = ArgumentCaptor.forClass(ClinicalDocument.class);
        verify(documentRepository).save(cap.capture());
        ClinicalDocument amendment = cap.getValue();
        assertThat(amendment.isAmendment()).isTrue();
        assertThat(amendment.getOriginalDocument()).isSameAs(original);
        assertThat(amendment.getAuthorUserId()).isEqualTo(bob.getId());
        assertThat(amendment.getAuthorName()).isEqualTo("Bob Otieno");
        // Original is preserved untouched (its author is still Alice).
        assertThat(original.getAuthorUserId()).isEqualTo(alice.getId());
        assertThat(resp.getAuthorUserId()).isEqualTo(bob.getId());
    }
}
