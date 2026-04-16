package com.smartTriage.smartTriage_server.module.documentation.service;

import com.smartTriage.smartTriage_server.common.enums.ClinicalDocumentType;
import com.smartTriage.smartTriage_server.common.enums.NoteType;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.clinical.entity.ClinicalNote;
import com.smartTriage.smartTriage_server.module.clinical.repository.ClinicalNoteRepository;
import com.smartTriage.smartTriage_server.module.documentation.dto.AmendDocumentRequest;
import com.smartTriage.smartTriage_server.module.documentation.dto.ClinicalDocumentResponse;
import com.smartTriage.smartTriage_server.module.documentation.dto.CreateDocumentRequest;
import com.smartTriage.smartTriage_server.module.documentation.entity.ClinicalDocument;
import com.smartTriage.smartTriage_server.module.documentation.mapper.ClinicalDocumentMapper;
import com.smartTriage.smartTriage_server.module.documentation.repository.ClinicalDocumentRepository;
import com.smartTriage.smartTriage_server.module.lab.entity.LabOrder;
import com.smartTriage.smartTriage_server.module.lab.repository.LabOrderRepository;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationAdministration;
import com.smartTriage.smartTriage_server.module.medication.repository.MedicationAdministrationRepository;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.triage.repository.TriageRecordRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.service.VisitService;
import com.smartTriage.smartTriage_server.module.vital.entity.VitalSigns;
import com.smartTriage.smartTriage_server.module.vital.repository.VitalSignsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Clinical Documentation Service — manages legally compliant clinical documents.
 *
 * Key legal requirements enforced:
 *   - Once signed, document content CANNOT be modified
 *   - Amendments create NEW linked documents (original preserved)
 *   - Electronic signatures require name and license number
 *   - Auto-attaches latest vitals at documentation time
 *
 * Also provides auto-generation of discharge summaries and handover documents
 * by compiling data from across the visit record.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClinicalDocumentService {

    private final ClinicalDocumentRepository documentRepository;
    private final VisitService visitService;
    private final VitalSignsRepository vitalSignsRepository;
    private final TriageRecordRepository triageRecordRepository;
    private final ClinicalNoteRepository clinicalNoteRepository;
    private final MedicationAdministrationRepository medicationRepository;
    private final LabOrderRepository labOrderRepository;

    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Africa/Kigali"));

    // ====================================================================
    // CREATE DOCUMENT
    // ====================================================================

    @Transactional
    public ClinicalDocumentResponse createDocument(UUID visitId, CreateDocumentRequest request) {
        Visit visit = visitService.findVisitOrThrow(visitId);

        // Auto-attach latest vitals
        VitalSigns latestVitals = vitalSignsRepository
                .findFirstByVisitIdAndIsActiveTrueOrderByRecordedAtDesc(visitId)
                .orElse(null);

        ClinicalDocument document = ClinicalDocument.builder()
                .visit(visit)
                .documentType(request.getDocumentType())
                .title(request.getTitle())
                .content(request.getContent())
                .authorName(request.getAuthorName())
                .authorRole(request.getAuthorRole())
                .authorLicenseNumber(request.getAuthorLicenseNumber())
                .vitalSigns(latestVitals)
                .templateUsed(request.getTemplateUsed())
                .notes(request.getNotes())
                .build();

        document = documentRepository.save(document);

        log.info("Clinical document created for visit {} — type:{} title:'{}' author:{}",
                visit.getVisitNumber(), document.getDocumentType(),
                document.getTitle(), document.getAuthorName());

        return ClinicalDocumentMapper.toResponse(document);
    }

    // ====================================================================
    // SIGN DOCUMENT (Electronic Signature — Legal Requirement)
    // ====================================================================

    @Transactional
    public ClinicalDocumentResponse signDocument(UUID documentId, String signerName, String licenseNumber) {
        ClinicalDocument document = findDocumentOrThrow(documentId);

        if (document.isSigned()) {
            throw new ClinicalBusinessException(
                    "Document is already signed. Signed documents cannot be re-signed. "
                    + "Use amendment to make corrections.");
        }

        document.setSigned(true);
        document.setSignedAt(Instant.now());
        document.setAuthorName(signerName);
        document.setAuthorLicenseNumber(licenseNumber);

        document = documentRepository.save(document);

        log.info("Document electronically signed — id:{} signer:{} license:{}",
                document.getId(), signerName, licenseNumber);

        return ClinicalDocumentMapper.toResponse(document);
    }

    // ====================================================================
    // CO-SIGN DOCUMENT (Supervised Clinicians)
    // ====================================================================

    @Transactional
    public ClinicalDocumentResponse coSignDocument(UUID documentId, String coSignerName) {
        ClinicalDocument document = findDocumentOrThrow(documentId);

        if (!document.isSigned()) {
            throw new ClinicalBusinessException(
                    "Document must be signed before co-signing. "
                    + "The primary author must sign first.");
        }

        if (document.getCoSignedByName() != null) {
            throw new ClinicalBusinessException(
                    "Document has already been co-signed by " + document.getCoSignedByName());
        }

        document.setCoSignedByName(coSignerName);
        document.setCoSignedAt(Instant.now());

        document = documentRepository.save(document);

        log.info("Document co-signed — id:{} co-signer:{}", document.getId(), coSignerName);

        return ClinicalDocumentMapper.toResponse(document);
    }

    // ====================================================================
    // AMEND DOCUMENT (Creates New Linked Document — Legal Audit Trail)
    // ====================================================================

    @Transactional
    public ClinicalDocumentResponse amendDocument(UUID documentId, AmendDocumentRequest request) {
        ClinicalDocument original = findDocumentOrThrow(documentId);

        if (!original.isSigned()) {
            throw new ClinicalBusinessException(
                    "Only signed documents can be amended. Unsigned documents can be edited directly.");
        }

        // Auto-attach latest vitals
        VitalSigns latestVitals = vitalSignsRepository
                .findFirstByVisitIdAndIsActiveTrueOrderByRecordedAtDesc(original.getVisit().getId())
                .orElse(null);

        ClinicalDocument amendment = ClinicalDocument.builder()
                .visit(original.getVisit())
                .documentType(original.getDocumentType())
                .title("AMENDMENT: " + original.getTitle())
                .content(request.getContent())
                .authorName(request.getAuthorName())
                .authorRole(request.getAuthorRole())
                .authorLicenseNumber(request.getAuthorLicenseNumber())
                .vitalSigns(latestVitals)
                .isAmendment(true)
                .amendmentReason(request.getAmendmentReason())
                .originalDocument(original)
                .amendedAt(Instant.now())
                .notes(request.getNotes())
                .build();

        amendment = documentRepository.save(amendment);

        log.info("Document amended — original:{} amendment:{} reason:'{}'",
                original.getId(), amendment.getId(), request.getAmendmentReason());

        return ClinicalDocumentMapper.toResponse(amendment);
    }

    // ====================================================================
    // QUERIES
    // ====================================================================

    public Page<ClinicalDocumentResponse> getDocumentsForVisit(UUID visitId, Pageable pageable) {
        return documentRepository
                .findByVisitIdAndIsActiveTrueOrderByCreatedAtDesc(visitId, pageable)
                .map(ClinicalDocumentMapper::toResponse);
    }

    public ClinicalDocumentResponse getDocument(UUID documentId) {
        return ClinicalDocumentMapper.toResponse(findDocumentOrThrow(documentId));
    }

    // ====================================================================
    // AUTO-GENERATE DISCHARGE SUMMARY
    // ====================================================================

    @Transactional
    public ClinicalDocumentResponse generateDischargeSummary(UUID visitId) {
        Visit visit = visitService.findVisitOrThrow(visitId);
        Patient patient = visit.getPatient();

        StringBuilder sb = new StringBuilder();
        sb.append("=== DISCHARGE SUMMARY ===\n");
        sb.append("Generated: ").append(DATETIME_FMT.format(Instant.now())).append("\n\n");

        // Patient demographics
        sb.append("--- PATIENT DEMOGRAPHICS ---\n");
        sb.append("Name: ").append(patient.getFirstName()).append(" ").append(patient.getLastName()).append("\n");
        if (patient.getDateOfBirth() != null) {
            sb.append("Date of Birth: ").append(patient.getDateOfBirth()).append(" (Age: ").append(patient.getAgeInYears()).append(")\n");
        }
        if (patient.getGender() != null) {
            sb.append("Gender: ").append(patient.getGender()).append("\n");
        }
        if (patient.getNationalId() != null) {
            sb.append("National ID: ").append(patient.getNationalId()).append("\n");
        }
        if (patient.getMedicalRecordNumber() != null) {
            sb.append("MRN: ").append(patient.getMedicalRecordNumber()).append("\n");
        }
        if (patient.getBloodType() != null) {
            sb.append("Blood Type: ").append(patient.getBloodType()).append("\n");
        }
        sb.append("\n");

        // Chief complaint
        sb.append("--- CHIEF COMPLAINT ---\n");
        sb.append(visit.getChiefComplaint() != null ? visit.getChiefComplaint() : "Not recorded").append("\n\n");

        // Visit details
        sb.append("--- VISIT DETAILS ---\n");
        sb.append("Visit Number: ").append(visit.getVisitNumber()).append("\n");
        sb.append("Arrival Time: ").append(DATETIME_FMT.format(visit.getArrivalTime())).append("\n");
        if (visit.getArrivalMode() != null) {
            sb.append("Arrival Mode: ").append(visit.getArrivalMode()).append("\n");
        }
        sb.append("Status: ").append(visit.getStatus()).append("\n");
        sb.append("\n");

        // Triage history
        sb.append("--- TRIAGE HISTORY ---\n");
        Page<TriageRecord> triageRecords = triageRecordRepository
                .findByVisitIdAndIsActiveTrueOrderByTriageTimeDesc(visitId, PageRequest.of(0, 20));
        if (triageRecords.isEmpty()) {
            sb.append("No triage records found.\n");
        } else {
            for (TriageRecord tr : triageRecords) {
                sb.append("  [").append(DATETIME_FMT.format(tr.getTriageTime())).append("] ");
                sb.append("Category: ").append(tr.getTriageCategory());
                sb.append(" | TEWS: ").append(tr.getTewsScore());
                if (tr.isRetriage()) {
                    sb.append(" (Re-triage");
                    if (tr.getPreviousCategory() != null) {
                        sb.append(" from ").append(tr.getPreviousCategory());
                    }
                    sb.append(")");
                }
                sb.append("\n");
            }
        }
        sb.append("\n");

        // Vitals summary (latest 5)
        sb.append("--- VITALS SUMMARY ---\n");
        Page<VitalSigns> vitals = vitalSignsRepository
                .findByVisitIdAndIsActiveTrueOrderByRecordedAtDesc(visitId, PageRequest.of(0, 5));
        if (vitals.isEmpty()) {
            sb.append("No vitals recorded.\n");
        } else {
            for (VitalSigns v : vitals) {
                sb.append("  [").append(DATETIME_FMT.format(v.getRecordedAt())).append("] ");
                if (v.getHeartRate() != null) sb.append("HR:").append(v.getHeartRate()).append(" ");
                if (v.getSystolicBp() != null) sb.append("BP:").append(v.getSystolicBp()).append("/").append(v.getDiastolicBp()).append(" ");
                if (v.getRespiratoryRate() != null) sb.append("RR:").append(v.getRespiratoryRate()).append(" ");
                if (v.getTemperature() != null) sb.append("T:").append(v.getTemperature()).append("C ");
                if (v.getSpo2() != null) sb.append("SpO2:").append(v.getSpo2()).append("% ");
                if (v.getGcsScore() != null) sb.append("GCS:").append(v.getGcsScore()).append(" ");
                if (v.getBloodGlucose() != null) sb.append("BG:").append(v.getBloodGlucose()).append(" ");
                sb.append("\n");
            }
        }
        sb.append("\n");

        // Clinical notes
        sb.append("--- CLINICAL NOTES ---\n");
        List<ClinicalNote> notes = clinicalNoteRepository
                .findByVisitIdAndIsActiveTrueOrderByRecordedAtAsc(visitId);
        if (notes.isEmpty()) {
            sb.append("No clinical notes recorded.\n");
        } else {
            for (ClinicalNote note : notes) {
                sb.append("  [").append(DATETIME_FMT.format(note.getRecordedAt())).append("] ");
                sb.append(note.getNoteType()).append(": ").append(note.getContent()).append("\n");
            }
        }
        sb.append("\n");

        // Medications given
        sb.append("--- MEDICATIONS GIVEN ---\n");
        List<MedicationAdministration> meds = medicationRepository
                .findByVisitIdAndIsActiveTrueOrderByPrescribedAtAsc(visitId);
        if (meds.isEmpty()) {
            sb.append("No medications recorded.\n");
        } else {
            for (MedicationAdministration med : meds) {
                sb.append("  ").append(med.getDrugName());
                if (med.getDose() != null) sb.append(" ").append(med.getDose());
                if (med.getRoute() != null) sb.append(" ").append(med.getRoute());
                sb.append(" [").append(med.getStatus()).append("]");
                if (med.getPrescribedAt() != null) {
                    sb.append(" prescribed:").append(DATETIME_FMT.format(med.getPrescribedAt()));
                }
                if (med.getAdministeredAt() != null) {
                    sb.append(" given:").append(DATETIME_FMT.format(med.getAdministeredAt()));
                }
                sb.append("\n");
            }
        }
        sb.append("\n");

        // Investigations & results
        sb.append("--- INVESTIGATIONS & RESULTS ---\n");
        Page<LabOrder> labs = labOrderRepository
                .findByVisitIdAndIsActiveTrueOrderByOrderedAtDesc(visitId, PageRequest.of(0, 50));
        if (labs.isEmpty()) {
            sb.append("No investigations ordered.\n");
        } else {
            for (LabOrder lab : labs) {
                sb.append("  ").append(lab.getTestName());
                sb.append(" [").append(lab.getPriority()).append("]");
                if (lab.getOrderedAt() != null) {
                    sb.append(" ordered:").append(DATETIME_FMT.format(lab.getOrderedAt()));
                }
                if (lab.getResultValue() != null) {
                    sb.append(" result:").append(lab.getResultValue());
                    if (lab.getResultUnit() != null) {
                        sb.append(" ").append(lab.getResultUnit());
                    }
                    if (lab.getReferenceRangeMin() != null && lab.getReferenceRangeMax() != null) {
                        sb.append(" ref:").append(lab.getReferenceRangeMin()).append("-").append(lab.getReferenceRangeMax());
                    }
                } else if (lab.getResultedAt() == null && lab.getCancelledAt() == null) {
                    sb.append(" PENDING");
                } else if (lab.getCancelledAt() != null) {
                    sb.append(" CANCELLED");
                }
                if (lab.isCritical()) {
                    sb.append(" **CRITICAL**");
                }
                sb.append("\n");
            }
        }
        sb.append("\n");

        // Disposition
        sb.append("--- DISPOSITION ---\n");
        if (visit.getDispositionType() != null) {
            sb.append("Type: ").append(visit.getDispositionType()).append("\n");
        }
        if (visit.getDispositionTime() != null) {
            sb.append("Time: ").append(DATETIME_FMT.format(visit.getDispositionTime())).append("\n");
        }
        if (visit.getDispositionNotes() != null) {
            sb.append("Notes: ").append(visit.getDispositionNotes()).append("\n");
        }
        sb.append("\n");

        // Known allergies & chronic conditions
        sb.append("--- PATIENT BACKGROUND ---\n");
        sb.append("Known Allergies: ").append(patient.getKnownAllergies() != null ? patient.getKnownAllergies() : "None recorded").append("\n");
        sb.append("Chronic Conditions: ").append(patient.getChronicConditions() != null ? patient.getChronicConditions() : "None recorded").append("\n");

        // Create the document
        VitalSigns latestVitals = vitalSignsRepository
                .findFirstByVisitIdAndIsActiveTrueOrderByRecordedAtDesc(visitId)
                .orElse(null);

        ClinicalDocument document = ClinicalDocument.builder()
                .visit(visit)
                .documentType(ClinicalDocumentType.DISCHARGE_SUMMARY)
                .title("Discharge Summary — " + patient.getFirstName() + " " + patient.getLastName()
                       + " — " + visit.getVisitNumber())
                .content(sb.toString())
                .authorName("SYSTEM (Auto-generated)")
                .authorRole("System")
                .vitalSigns(latestVitals)
                .templateUsed("AUTO_DISCHARGE_SUMMARY")
                .build();

        document = documentRepository.save(document);

        log.info("Discharge summary auto-generated for visit {}", visit.getVisitNumber());

        return ClinicalDocumentMapper.toResponse(document);
    }

    // ====================================================================
    // AUTO-GENERATE HANDOVER DOCUMENT
    // ====================================================================

    @Transactional
    public ClinicalDocumentResponse generateHandoverDocument(UUID visitId) {
        Visit visit = visitService.findVisitOrThrow(visitId);
        Patient patient = visit.getPatient();

        StringBuilder sb = new StringBuilder();
        sb.append("=== SHIFT HANDOVER DOCUMENT ===\n");
        sb.append("Generated: ").append(DATETIME_FMT.format(Instant.now())).append("\n\n");

        // Patient identification
        sb.append("--- PATIENT ---\n");
        sb.append("Name: ").append(patient.getFirstName()).append(" ").append(patient.getLastName()).append("\n");
        sb.append("Visit: ").append(visit.getVisitNumber()).append("\n");
        sb.append("Status: ").append(visit.getStatus()).append("\n");
        if (visit.getCurrentTriageCategory() != null) {
            sb.append("Triage Category: ").append(visit.getCurrentTriageCategory()).append("\n");
        }
        if (visit.getCurrentTewsScore() != null) {
            sb.append("Current TEWS: ").append(visit.getCurrentTewsScore()).append("\n");
        }
        sb.append("Arrival: ").append(DATETIME_FMT.format(visit.getArrivalTime())).append("\n");
        sb.append("\n");

        // Chief complaint
        sb.append("--- PRESENTING COMPLAINT ---\n");
        sb.append(visit.getChiefComplaint() != null ? visit.getChiefComplaint() : "Not recorded").append("\n\n");

        // Current vitals
        sb.append("--- CURRENT VITALS ---\n");
        Optional<VitalSigns> latestVitalsOpt = vitalSignsRepository
                .findFirstByVisitIdAndIsActiveTrueOrderByRecordedAtDesc(visitId);
        if (latestVitalsOpt.isPresent()) {
            VitalSigns v = latestVitalsOpt.get();
            sb.append("Recorded at: ").append(DATETIME_FMT.format(v.getRecordedAt())).append("\n");
            if (v.getHeartRate() != null) sb.append("Heart Rate: ").append(v.getHeartRate()).append(" bpm\n");
            if (v.getSystolicBp() != null) sb.append("Blood Pressure: ").append(v.getSystolicBp()).append("/").append(v.getDiastolicBp()).append(" mmHg\n");
            if (v.getRespiratoryRate() != null) sb.append("Respiratory Rate: ").append(v.getRespiratoryRate()).append(" /min\n");
            if (v.getTemperature() != null) sb.append("Temperature: ").append(v.getTemperature()).append(" C\n");
            if (v.getSpo2() != null) sb.append("SpO2: ").append(v.getSpo2()).append("%\n");
            if (v.getGcsScore() != null) sb.append("GCS: ").append(v.getGcsScore()).append("\n");
            if (v.getAvpu() != null) sb.append("AVPU: ").append(v.getAvpu()).append("\n");
            if (v.getBloodGlucose() != null) sb.append("Blood Glucose: ").append(v.getBloodGlucose()).append(" mmol/L\n");
        } else {
            sb.append("No vitals recorded.\n");
        }
        sb.append("\n");

        // Key clinical notes (latest of each type)
        sb.append("--- KEY CLINICAL NOTES ---\n");
        appendLatestNote(sb, visitId, NoteType.DOCTOR_NOTE, "Doctor Notes");
        appendLatestNote(sb, visitId, NoteType.NURSING_NOTE, "Nursing Notes");
        appendLatestNote(sb, visitId, NoteType.TREATMENT_PLAN, "Treatment Plan");
        appendLatestNote(sb, visitId, NoteType.PROGRESS_NOTE, "Progress Notes");
        sb.append("\n");

        // Active medications
        sb.append("--- ACTIVE MEDICATIONS ---\n");
        List<MedicationAdministration> meds = medicationRepository
                .findByVisitIdAndIsActiveTrueOrderByPrescribedAtAsc(visitId);
        if (meds.isEmpty()) {
            sb.append("No active medications.\n");
        } else {
            for (MedicationAdministration med : meds) {
                sb.append("  ").append(med.getDrugName());
                if (med.getDose() != null) sb.append(" ").append(med.getDose());
                if (med.getRoute() != null) sb.append(" ").append(med.getRoute());
                if (med.getFrequency() != null) sb.append(" ").append(med.getFrequency());
                sb.append(" [").append(med.getStatus()).append("]\n");
            }
        }
        sb.append("\n");

        // Pending investigations
        sb.append("--- PENDING INVESTIGATIONS ---\n");
        Page<LabOrder> labs = labOrderRepository
                .findByVisitIdAndIsActiveTrueOrderByOrderedAtDesc(visitId, PageRequest.of(0, 50));
        boolean hasPending = false;
        for (LabOrder lab : labs) {
            if (lab.getResultedAt() == null && lab.getCancelledAt() == null) {
                sb.append("  PENDING: ").append(lab.getTestName())
                        .append(" [").append(lab.getPriority()).append("]")
                        .append(" ordered:").append(DATETIME_FMT.format(lab.getOrderedAt()))
                        .append("\n");
                hasPending = true;
            }
        }
        if (!hasPending) {
            sb.append("No pending investigations.\n");
        }
        sb.append("\n");

        // Allergies & warnings
        sb.append("--- ALERTS & WARNINGS ---\n");
        sb.append("Known Allergies: ").append(patient.getKnownAllergies() != null ? patient.getKnownAllergies() : "NKDA").append("\n");
        sb.append("Chronic Conditions: ").append(patient.getChronicConditions() != null ? patient.getChronicConditions() : "None").append("\n");
        if (visit.isPediatric()) {
            sb.append("** PEDIATRIC PATIENT **\n");
        }

        // Create the document
        VitalSigns latestVitals = latestVitalsOpt.orElse(null);

        ClinicalDocument document = ClinicalDocument.builder()
                .visit(visit)
                .documentType(ClinicalDocumentType.HANDOVER_DOCUMENT)
                .title("Shift Handover — " + patient.getFirstName() + " " + patient.getLastName()
                       + " — " + visit.getVisitNumber())
                .content(sb.toString())
                .authorName("SYSTEM (Auto-generated)")
                .authorRole("System")
                .vitalSigns(latestVitals)
                .templateUsed("AUTO_HANDOVER")
                .build();

        document = documentRepository.save(document);

        log.info("Handover document auto-generated for visit {}", visit.getVisitNumber());

        return ClinicalDocumentMapper.toResponse(document);
    }

    // ====================================================================
    // INTERNAL HELPERS
    // ====================================================================

    private void appendLatestNote(StringBuilder sb, UUID visitId, NoteType type, String label) {
        clinicalNoteRepository
                .findFirstByVisitIdAndNoteTypeAndIsActiveTrueOrderByRecordedAtDesc(visitId, type)
                .ifPresent(note -> {
                    sb.append(label).append(" [").append(DATETIME_FMT.format(note.getRecordedAt())).append("]: ");
                    sb.append(note.getContent()).append("\n");
                });
    }

    public ClinicalDocument findDocumentOrThrow(UUID id) {
        return documentRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("ClinicalDocument", "id", id));
    }
}
