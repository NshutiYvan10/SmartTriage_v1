package com.smartTriage.smartTriage_server.module.hypoglycemia.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Optional body for resolving a hypoglycemia event. A {@code reason} is MANDATORY
 * when the recheck protocol is incomplete (no post-treatment repeat glucose on
 * record, or the repeat is still hypoglycemic) — see
 * {@code HypoglycemiaService.resolveEvent}. When the protocol completed normally
 * the body may be omitted entirely.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResolveEventRequest {

    /** Why the event is being closed without a completed recheck protocol. */
    private String reason;
}
