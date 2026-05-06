package com.smartTriage.smartTriage_server.module.zonetransfer.entity;

/**
 * Lifecycle states for a zone transfer.
 *
 * <ul>
 *   <li>{@link #PENDING_ACCEPT} — auto/manually proposed; both zones
 *       see the patient; original primary clinician retains
 *       responsibility until acceptance.</li>
 *   <li>{@link #ACCEPTED} — receiving doctor took the patient. Visit's
 *       current_ed_zone + primary_clinician update on transition.</li>
 *   <li>{@link #DECLINED} — receiving zone says no (e.g. resus full).
 *       Patient stays in original zone with declined_reason set.</li>
 *   <li>{@link #RESUS_IN_PLACE} — explicit "treating at higher acuity
 *       in the current physical location" — common at district
 *       hospitals where there's only one resus bay.</li>
 *   <li>{@link #CANCELLED} — initiator changed mind / system auto-bump
 *       was immediately undone within the cooldown.</li>
 * </ul>
 */
public enum ZoneTransferStatus {
    PENDING_ACCEPT,
    ACCEPTED,
    DECLINED,
    RESUS_IN_PLACE,
    CANCELLED
}
