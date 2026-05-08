package com.smartTriage.smartTriage_server.common.enums;

/**
 * Pre-hospital run state machine.
 *
 * <pre>
 *  DISPATCHED  ─→ EN_ROUTE ─→ ARRIVED ─→ HANDED_OFF
 *      │             │           │
 *      └─────────────┴───────────┴─→ CANCELLED
 * </pre>
 *
 * The paramedic's responsibility ends at HANDED_OFF (transfer of
 * care to the receiving ED nurse). After that the existing visit
 * workflow takes over.
 */
public enum EmsRunStatus {
    DISPATCHED,
    EN_ROUTE,
    ARRIVED,
    HANDED_OFF,
    CANCELLED
}
