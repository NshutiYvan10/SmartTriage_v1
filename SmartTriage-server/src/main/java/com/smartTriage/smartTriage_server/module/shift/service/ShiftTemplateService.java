package com.smartTriage.smartTriage_server.module.shift.service;

import com.smartTriage.smartTriage_server.common.enums.ShiftPeriod;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.shift.dto.ShiftTemplateAssignmentDto;
import com.smartTriage.smartTriage_server.module.shift.dto.ShiftTemplateResponse;
import com.smartTriage.smartTriage_server.module.shift.dto.UpsertShiftTemplateRequest;
import com.smartTriage.smartTriage_server.module.shift.entity.ShiftTemplate;
import com.smartTriage.smartTriage_server.module.shift.entity.ShiftTemplateAssignment;
import com.smartTriage.smartTriage_server.module.shift.mapper.ShiftTemplateMapper;
import com.smartTriage.smartTriage_server.module.shift.repository.ShiftTemplateAssignmentRepository;
import com.smartTriage.smartTriage_server.module.shift.repository.ShiftTemplateRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Shift Template Service — CRUD for reusable per-shift rosters.
 *
 * A template is a named default layout: "this nurse works RESUS as ZONE_NURSE
 * whenever the DAY template is applied". The {@link ShiftMaterializerService}
 * consumes templates at shift boundary and writes concrete
 * {@link com.smartTriage.smartTriage_server.module.shift.entity.ShiftAssignment}
 * rows for the new shift.
 *
 * Invariants this service enforces application-side (in addition to the
 * database partial unique indexes):
 * <ul>
 *   <li>At most one active template per (hospital, shiftPeriod). When a new
 *       template is created for a period that already has one, the previous
 *       one is soft-deleted.</li>
 *   <li>Each user appears at most once per template.</li>
 *   <li>At most one row per template may carry the shift-lead badge.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftTemplateService {

    private final ShiftTemplateRepository shiftTemplateRepository;
    private final ShiftTemplateAssignmentRepository shiftTemplateAssignmentRepository;
    private final HospitalRepository hospitalRepository;
    private final UserRepository userRepository;

    /* ═════════════════════════════ READ ═════════════════════════════ */

    public List<ShiftTemplateResponse> listForHospital(UUID hospitalId) {
        return shiftTemplateRepository
                .findByHospitalIdAndIsActiveTrueOrderByShiftPeriodAsc(hospitalId)
                .stream()
                .map(ShiftTemplateMapper::toResponse)
                .collect(Collectors.toList());
    }

    public ShiftTemplateResponse getById(UUID templateId) {
        return shiftTemplateRepository.findByIdAndIsActiveTrue(templateId)
                .map(ShiftTemplateMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("ShiftTemplate", "id", templateId));
    }

    /* ═════════════════════════════ WRITE ═════════════════════════════ */

    /**
     * Create a new template for a (hospital, shiftPeriod). If one already
     * exists for that pair, it is soft-deleted first so the new one becomes
     * the single active layout.
     */
    @Transactional
    public ShiftTemplateResponse create(UUID hospitalId, UpsertShiftTemplateRequest request) {
        Hospital hospital = hospitalRepository.findById(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital", "id", hospitalId));

        validateAssignments(request.getAssignments());

        // Soft-delete existing active template for same (hospital, period).
        shiftTemplateRepository
                .findByHospitalIdAndShiftPeriodAndIsActiveTrue(hospitalId, request.getShiftPeriod())
                .ifPresent(existing -> {
                    existing.setActive(false);
                    shiftTemplateRepository.save(existing);
                    log.info("Soft-deleted previous active template {} for hospital {} {}",
                            existing.getId(), hospitalId, request.getShiftPeriod());
                });

        ShiftTemplate template = ShiftTemplate.builder()
                .hospital(hospital)
                .name(request.getName())
                .description(request.getDescription())
                .shiftPeriod(request.getShiftPeriod())
                .build();

        // Attach assignments.
        List<ShiftTemplateAssignment> rows = buildAssignmentRows(template, request.getAssignments());
        template.setAssignments(rows);

        template = shiftTemplateRepository.save(template);
        log.info("Shift template created: {} ({} — {} rows)",
                template.getId(), request.getShiftPeriod(), rows.size());
        return ShiftTemplateMapper.toResponse(template);
    }

    /**
     * Replace a template's contents. Name/description/assignments are
     * overwritten atomically; old assignment rows are deleted via orphan
     * removal on the relationship.
     */
    @Transactional
    public ShiftTemplateResponse update(UUID templateId, UpsertShiftTemplateRequest request) {
        ShiftTemplate template = shiftTemplateRepository.findByIdAndIsActiveTrue(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("ShiftTemplate", "id", templateId));

        validateAssignments(request.getAssignments());

        // If period is changing, make sure we don't collide with another
        // active template for the new (hospital, period).
        if (request.getShiftPeriod() != template.getShiftPeriod()) {
            final UUID currentTemplateId = template.getId();
            shiftTemplateRepository
                    .findByHospitalIdAndShiftPeriodAndIsActiveTrue(
                            template.getHospital().getId(), request.getShiftPeriod())
                    .ifPresent(other -> {
                        if (!other.getId().equals(currentTemplateId)) {
                            throw new ClinicalBusinessException(
                                    "An active template already exists for this hospital and shift period");
                        }
                    });
        }

        template.setName(request.getName());
        template.setDescription(request.getDescription());
        template.setShiftPeriod(request.getShiftPeriod());

        // Replace assignment rows. The intuitive `clear() + addAll()`
        // pattern has a Hibernate flush-ordering trap: Hibernate's
        // default action order is inserts → updates → deletes, so the
        // orphan DELETE of an old row queues *after* the INSERT of its
        // replacement. That trips the unique constraint
        // uk_shift_template_user (template_id, user_id) whenever a row
        // is edited (same user, different zone / function), which the
        // global exception handler then surfaces as the unhelpful
        // "request conflicts with existing data" message — the exact
        // symptom a CN hits when changing a staff member's zone in
        // the template editor.
        //
        // The fix is to flush between the clear and the addAll so
        // Postgres has actually deleted the old rows before the new
        // ones are inserted.
        template.getAssignments().clear();
        shiftTemplateRepository.saveAndFlush(template);

        List<ShiftTemplateAssignment> rows = buildAssignmentRows(template, request.getAssignments());
        template.getAssignments().addAll(rows);

        template = shiftTemplateRepository.save(template);
        log.info("Shift template updated: {} ({} — {} rows)",
                template.getId(), request.getShiftPeriod(), rows.size());
        return ShiftTemplateMapper.toResponse(template);
    }

    /**
     * Soft-delete a template (so history stays queryable).
     */
    @Transactional
    public void delete(UUID templateId) {
        ShiftTemplate template = shiftTemplateRepository.findByIdAndIsActiveTrue(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("ShiftTemplate", "id", templateId));
        template.setActive(false);
        shiftTemplateRepository.save(template);
        log.info("Shift template soft-deleted: {}", templateId);
    }

    /* ═════════════════════════════ HELPERS ═════════════════════════════ */

    private void validateAssignments(List<ShiftTemplateAssignmentDto> rows) {
        if (rows == null || rows.isEmpty()) {
            return; // empty template is allowed — hospital admin can add rows later
        }
        Set<UUID> seen = new HashSet<>();
        int leadCount = 0;
        for (ShiftTemplateAssignmentDto row : rows) {
            if (row.getUserId() == null) {
                throw new ClinicalBusinessException("Template row is missing userId");
            }
            if (!seen.add(row.getUserId())) {
                throw new ClinicalBusinessException(
                        "Duplicate user in template: " + row.getUserId());
            }
            if (row.isShiftLead()) {
                leadCount++;
            }
        }
        if (leadCount > 1) {
            throw new ClinicalBusinessException(
                    "A template may have at most one shift-lead row");
        }
    }

    private List<ShiftTemplateAssignment> buildAssignmentRows(
            ShiftTemplate template, List<ShiftTemplateAssignmentDto> dtos) {
        List<ShiftTemplateAssignment> rows = new ArrayList<>();
        if (dtos == null) {
            return rows;
        }
        for (ShiftTemplateAssignmentDto dto : dtos) {
            User user = userRepository.findByIdAndIsActiveTrue(dto.getUserId())
                    .orElseThrow(() -> new ResourceNotFoundException("User", "id", dto.getUserId()));
            rows.add(ShiftTemplateAssignment.builder()
                    .template(template)
                    .user(user)
                    .zone(dto.getZone())
                    .shiftFunction(dto.getShiftFunction())
                    .isShiftLead(dto.isShiftLead())
                    .build());
        }
        return rows;
    }
}
