package com.smartTriage.smartTriage_server.module.shift.service;

import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.ShiftFunction;
import com.smartTriage.smartTriage_server.common.enums.ShiftPeriod;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.shift.dto.CreateShiftAssignmentRequest;
import com.smartTriage.smartTriage_server.module.shift.entity.ShiftAssignment;
import com.smartTriage.smartTriage_server.module.shift.repository.ShiftAssignmentRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The shift-lead badge edit path. updateAssignment used to IGNORE isShiftLead, so a
 * Charge Nurse could neither grant nor REMOVE the badge from the edit form — the
 * reported "unchecked shift-lead, saved, nothing happened" bug, which also left a
 * zone nurse stuck on all-zones alert scope. These lock the fix + the single-lead
 * invariant.
 */
class ShiftAssignmentServiceTest {

    private final ShiftAssignmentRepository repo = mock(ShiftAssignmentRepository.class);
    private final ShiftAssignmentService service = new ShiftAssignmentService(
            repo,
            mock(com.smartTriage.smartTriage_server.module.user.repository.UserRepository.class),
            mock(com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository.class),
            mock(com.smartTriage.smartTriage_server.module.shift.repository.StaffLeaveRepository.class),
            mock(com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository.class));

    private ShiftAssignment assignment(boolean isLead) {
        Hospital h = mock(Hospital.class);
        when(h.getId()).thenReturn(UUID.randomUUID());
        return ShiftAssignment.builder()
                .hospital(h)
                .shiftDate(LocalDate.now())
                .shiftPeriod(ShiftPeriod.DAY)
                .user(new User())
                .zone(EdZone.GENERAL)
                .shiftFunction(ShiftFunction.ZONE_NURSE)
                .isShiftLead(isLead)
                .build();
    }

    @Test
    void updateAssignment_uncheckShiftLead_removesBadge() {
        ShiftAssignment a = assignment(true);
        when(repo.findByIdAndIsActiveTrue(any())).thenReturn(Optional.of(a));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateShiftAssignmentRequest req = new CreateShiftAssignmentRequest();
        req.setIsShiftLead(false);
        service.updateAssignment(UUID.randomUUID(), req);

        // The reported bug: unchecking + saving now actually persists.
        assertFalse(a.isShiftLead());
    }

    @Test
    void updateAssignment_makeShiftLead_clearsAnyExistingLead() {
        ShiftAssignment target = assignment(false);
        ShiftAssignment stale = assignment(true);
        when(repo.findByIdAndIsActiveTrue(any())).thenReturn(Optional.of(target));
        when(repo.findAllShiftLeads(any(), any(), any()))
                .thenReturn(new java.util.ArrayList<>(List.of(stale)));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateShiftAssignmentRequest req = new CreateShiftAssignmentRequest();
        req.setIsShiftLead(true);
        service.updateAssignment(UUID.randomUUID(), req);

        // Single-lead invariant: granting clears any other lead for the shift.
        assertTrue(target.isShiftLead());
        assertFalse(stale.isShiftLead());
    }

    @Test
    void updateAssignment_noIsShiftLeadInRequest_leavesBadgeUntouched() {
        ShiftAssignment a = assignment(true);
        when(repo.findByIdAndIsActiveTrue(any())).thenReturn(Optional.of(a));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateShiftAssignmentRequest req = new CreateShiftAssignmentRequest();
        req.setZone(EdZone.GENERAL); // an unrelated edit; isShiftLead null
        service.updateAssignment(UUID.randomUUID(), req);

        assertTrue(a.isShiftLead()); // null means "don't touch"
    }
}
