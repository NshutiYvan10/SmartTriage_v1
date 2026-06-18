package com.smartTriage.smartTriage_server.common.enums;

/**
 * Lifecycle of a referral / consultation.
 *   REQUESTED — the request has been raised, awaiting the consultant.
 *   ACCEPTED  — the consultant accepted and (typically) replied.
 *   DECLINED  — the consultant declined (with a reason).
 *   COMPLETED — the consult/referral is finished.
 *   CANCELLED — the requester withdrew the request.
 */
public enum ReferralStatus {
    REQUESTED,
    ACCEPTED,
    DECLINED,
    COMPLETED,
    CANCELLED
}
