package com.smartTriage.smartTriage_server.common.enums;

/**
 * The kind of referral / consultation request.
 *   INTERNAL_CONSULT      — ask another specialty within this hospital to review.
 *   EXTERNAL_REFERRAL     — refer the patient out to another facility.
 *   ICU_ADMISSION_REQUEST — request admission to critical care.
 */
public enum ReferralType {
    INTERNAL_CONSULT,
    EXTERNAL_REFERRAL,
    ICU_ADMISSION_REQUEST
}
