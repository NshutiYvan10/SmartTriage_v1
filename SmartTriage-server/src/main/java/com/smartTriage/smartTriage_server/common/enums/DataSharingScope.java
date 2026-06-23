package com.smartTriage.smartTriage_server.common.enums;

/**
 * Scope a data-sharing consent covers (Phase 2). Currently a single value — consent to share the
 * full clinical record cross-hospital (what is actually SERVED is a bounded history summary, by
 * design). Per-section / time-boxed scopes are a later phase.
 */
public enum DataSharingScope {
    FULL_RECORD
}
