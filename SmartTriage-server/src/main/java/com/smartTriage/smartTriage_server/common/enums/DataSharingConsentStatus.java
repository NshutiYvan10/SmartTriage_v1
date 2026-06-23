package com.smartTriage.smartTriage_server.common.enums;

/**
 * Lifecycle of a cross-hospital data-sharing consent (Phase 2).
 *
 * <ul>
 *   <li>{@code GRANTED} — the patient has opted in to sharing their deep record across hospitals.</li>
 *   <li>{@code DENIED} — the patient explicitly refused. Preserved as a legal record; not effective.</li>
 *   <li>{@code WITHDRAWN} — a previously GRANTED consent was revoked. Preserved; not effective.</li>
 * </ul>
 * Only a live {@code GRANTED} row is "effective" (gates a consent-based deep read).
 */
public enum DataSharingConsentStatus {
    GRANTED, DENIED, WITHDRAWN
}
