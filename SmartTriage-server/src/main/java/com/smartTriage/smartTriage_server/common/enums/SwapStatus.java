package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * State machine for a peer-to-peer shift swap.
 *
 * <pre>
 *  REQUESTED  ─→ PENDING_PARTNER_ACCEPT      (proposer waits for the named partner)
 *  PENDING_PARTNER_ACCEPT
 *             ─→ PENDING_CHARGE_APPROVAL     (partner accepts)
 *             ─→ REJECTED                    (partner declines)
 *             ─→ CANCELLED                   (proposer withdraws)
 *  PENDING_CHARGE_APPROVAL
 *             ─→ APPROVED                    (CN approves; assignments are atomically swapped)
 *             ─→ REJECTED                    (CN declines, e.g. competence mismatch)
 *             ─→ CANCELLED                   (either party withdraws)
 *  APPROVED   ─→ (terminal — see ShiftSwapAudit for any post-swap reversals)
 *  REJECTED   ─→ (terminal)
 *  CANCELLED  ─→ (terminal)
 * </pre>
 *
 * <p>The CN approval gate exists because in a Rwandan ED a swap is not just
 * "do you both agree" — the CN must verify that the resulting roster still
 * satisfies competence requirements (e.g. RESUS needs at least one nurse
 * with advanced-life-support credentialing on shift). The swap is only
 * actually applied to the {@code shift_assignments} rows on the APPROVED
 * transition.
 */
@Getter
@RequiredArgsConstructor
public enum SwapStatus {
    REQUESTED              ("Requested",               "Submitted, awaiting partner acceptance"),
    PENDING_PARTNER_ACCEPT ("Awaiting partner",        "Partner has been notified"),
    PENDING_CHARGE_APPROVAL("Awaiting Charge Nurse",   "Partner accepted; CN must approve"),
    APPROVED               ("Approved",                "Swap applied to roster"),
    REJECTED               ("Rejected",                "Partner or CN declined"),
    CANCELLED              ("Cancelled",               "Withdrawn by proposer or partner");

    private final String label;
    private final String description;

    public boolean isTerminal() {
        return this == APPROVED || this == REJECTED || this == CANCELLED;
    }
}
