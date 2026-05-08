package com.smartTriage.smartTriage_server.common.enums;

/**
 * Explicit lab-order workflow state. Replaces timestamp-derived state
 * so the service layer can reject illegal transitions cleanly.
 *
 * <pre>
 * ORDERED
 *    ├─→ CANCELLED                 (doctor only)
 *    └─→ SPECIMEN_COLLECTED
 *           ├─→ CANCELLED
 *           └─→ RECEIVED_BY_LAB    (tech accessions)
 *                  ├─→ REJECTED    (tech, with reason)
 *                  └─→ PROCESSING  (tech starts assay)
 *                         └─→ RESULTED
 * </pre>
 */
public enum LabOrderStatus {
    ORDERED,
    SPECIMEN_COLLECTED,
    RECEIVED_BY_LAB,
    PROCESSING,
    /**
     * Phase 2 — result has been entered by a junior tech but is
     * gated behind senior verification because the value is critical
     * or the specimen quality was flagged. Doctors do NOT see orders
     * in this state.
     */
    AWAITING_VERIFICATION,
    RESULTED,
    REJECTED,
    CANCELLED
}
