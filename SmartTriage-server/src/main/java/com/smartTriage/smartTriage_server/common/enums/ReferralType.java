package com.smartTriage.smartTriage_server.common.enums;

/**
 * Types of inter-hospital referrals in the Rwanda national referral system.
 * Health Centers -> District Hospitals -> Provincial Referral Hospitals -> National Referral Hospitals (CHUK, CHUB, RMH).
 */
public enum ReferralType {
    UPWARD_REFERRAL,      // Lower to higher facility (district -> referral)
    LATERAL_REFERRAL,     // Same-level facility (specialist not available)
    DOWNWARD_REFERRAL,    // Higher to lower for continued care
    COUNTER_REFERRAL,     // Back-referral to referring facility
    EMERGENCY_TRANSFER    // Critical emergency transfer
}
