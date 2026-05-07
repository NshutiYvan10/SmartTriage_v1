package com.smartTriage.smartTriage_server.module.shift.service;

import com.smartTriage.smartTriage_server.common.enums.LeaveStatus;
import com.smartTriage.smartTriage_server.common.enums.SwapStatus;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.shift.entity.ShiftSwapRequest;
import com.smartTriage.smartTriage_server.module.shift.entity.StaffLeave;
import com.smartTriage.smartTriage_server.module.shift.repository.ShiftSwapRequestRepository;
import com.smartTriage.smartTriage_server.module.shift.repository.StaffLeaveRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * V44+ Auto-escalation for stale pending shift requests.
 *
 * <p>Closes the operational gap identified during the charge-nurse-absence
 * audit: when both day-shift and night-shift Charge Nurses are out, swap
 * requests sit in {@code PENDING_CHARGE_APPROVAL} and leave requests in
 * {@code REQUESTED} indefinitely. The admin has no way to know unless
 * they happen to open the approval queue.
 *
 * <p>This scheduler runs every hour and emits a real-time WebSocket
 * notification per hospital that has at least one pending request older
 * than {@link #STALE_THRESHOLD}. The actual approval still requires a
 * Charge Nurse or Hospital Admin to click — the scheduler is a nudge,
 * not an auto-approver.
 *
 * <p>Why no persistent alert row? {@code ClinicalAlert.visit_id} is
 * non-null on the schema (the alert system is patient-scoped). Shift
 * requests aren't tied to a visit, so we surface them on a separate
 * channel ({@code /topic/admin-alerts/{hospitalId}}) and leave the
 * swap / leave rows themselves as the persistent source of truth —
 * an admin opening the approval queue sees them all.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StalePendingRequestScheduler {

    /** Pending requests older than this trigger an escalation nudge. */
    private static final Duration STALE_THRESHOLD = Duration.ofHours(24);

    private final ShiftSwapRequestRepository shiftSwapRequestRepository;
    private final StaffLeaveRepository staffLeaveRepository;
    private final RealTimeEventPublisher eventPublisher;

    /**
     * Hourly scan. Finds pending swap + leave requests older than
     * {@link #STALE_THRESHOLD} grouped by hospital and emits a single
     * summary notification per hospital so admins aren't spammed
     * one-per-stale-row.
     *
     * <p>Initial delay is 90 s so it doesn't fire immediately on app
     * start (which would otherwise produce a noisy boot message during
     * local dev / restarts).
     */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT90S")
    @Transactional(readOnly = true)
    public void scanStalePending() {
        Instant cutoff = Instant.now().minus(STALE_THRESHOLD);

        // Aggregate per-hospital so we send ONE summary notification per
        // hospital even when several requests are stale. Map key = hospital id;
        // values are the stale request counts. We don't need the request
        // payload itself — admins click through to the approval queue
        // for details.
        Map<UUID, StaleSummary> byHospital = new HashMap<>();

        // 1) Pending swap requests (any status that requires an approver).
        Set<SwapStatus> pendingSwap = Set.of(
                SwapStatus.REQUESTED,
                SwapStatus.PENDING_PARTNER_ACCEPT,
                SwapStatus.PENDING_CHARGE_APPROVAL);
        // The repo doesn't have a "stale across all hospitals" query, so we
        // page through all swaps with these statuses. In a healthy ED this
        // result set is tiny (< 10 per hospital). For very large datasets
        // a future migration would add a dedicated index + query.
        for (ShiftSwapRequest swap : shiftSwapRequestRepository.findAll()) {
            if (!swap.isActive()) continue;
            if (!pendingSwap.contains(swap.getStatus())) continue;
            Instant requested = swap.getCreatedAt();
            if (requested == null || requested.isAfter(cutoff)) continue;
            UUID hid = swap.getHospital().getId();
            byHospital.computeIfAbsent(hid, k -> new StaleSummary()).staleSwaps++;
            byHospital.get(hid).hospitalCode = swap.getHospital().getHospitalCode();
        }

        // 2) Pending leave requests (REQUESTED, not yet decided).
        for (StaffLeave leave : staffLeaveRepository.findAll()) {
            if (!leave.isActive()) continue;
            if (leave.getLeaveStatus() != LeaveStatus.REQUESTED) continue;
            Instant requested = leave.getRequestedAt();
            if (requested == null || requested.isAfter(cutoff)) continue;
            UUID hid = leave.getHospital().getId();
            byHospital.computeIfAbsent(hid, k -> new StaleSummary()).staleLeaves++;
            byHospital.get(hid).hospitalCode = leave.getHospital().getHospitalCode();
        }

        if (byHospital.isEmpty()) {
            log.trace("[stale-pending] No stale requests this scan");
            return;
        }

        Set<UUID> notified = new HashSet<>();
        for (Map.Entry<UUID, StaleSummary> e : byHospital.entrySet()) {
            UUID hospitalId = e.getKey();
            StaleSummary s = e.getValue();
            int total = s.staleSwaps + s.staleLeaves;
            String message = String.format(
                    "%d shift request%s pending > %dh: %d swap%s, %d leave request%s. "
                            + "Open the approval queue to resolve.",
                    total, total == 1 ? "" : "s",
                    STALE_THRESHOLD.toHours(),
                    s.staleSwaps, s.staleSwaps == 1 ? "" : "s",
                    s.staleLeaves, s.staleLeaves == 1 ? "" : "s");
            log.warn("[stale-pending] Hospital {} ({}): {}",
                    s.hospitalCode != null ? s.hospitalCode : hospitalId,
                    hospitalId, message);

            // Publish a transient summary to the hospital's admin-alerts
            // topic. The frontend admin dashboard subscribes here and
            // surfaces a non-blocking toast / banner so the admin can
            // intervene before the queue chokes.
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "SHIFT_REQUEST_PENDING_OVERDUE");
            payload.put("hospitalId", hospitalId.toString());
            payload.put("staleSwaps", s.staleSwaps);
            payload.put("staleLeaves", s.staleLeaves);
            payload.put("thresholdHours", STALE_THRESHOLD.toHours());
            payload.put("message", message);
            payload.put("emittedAt", Instant.now().toString());
            try {
                eventPublisher.publishAlert(hospitalId, payload);
                notified.add(hospitalId);
            } catch (Exception ex) {
                log.error("[stale-pending] Failed to publish alert for hospital {}: {}",
                        hospitalId, ex.getMessage(), ex);
            }
        }

        log.info("[stale-pending] Scan complete: {} hospital(s) had stale requests; "
                        + "notifications dispatched to {}.",
                byHospital.size(), notified.size());
    }

    /** Per-hospital aggregation of stale pending counts. */
    private static class StaleSummary {
        int staleSwaps;
        int staleLeaves;
        String hospitalCode;
    }
}
