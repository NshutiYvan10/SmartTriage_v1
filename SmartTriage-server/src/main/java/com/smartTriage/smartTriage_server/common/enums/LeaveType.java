package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Categories of staff absence the shift planner needs to be aware of.
 *
 * <p>The set is deliberately small and aligned with Rwandan public-sector
 * leave norms (Labour Law of 2018, plus public-hospital HR practice as
 * implemented at CHUK / KFH / RMH). It does <b>not</b> attempt to be a
 * full HR-system leave taxonomy — for that the entry has an optional
 * {@code externalReference} field that lets a future HRIS sync attach the
 * official record id.
 *
 * <p>What matters for SmartTriage is solely "is this person off the floor
 * for the requested shift, and why" — so the planner can render the gap
 * and suggest a swap or backfill.
 */
@Getter
@RequiredArgsConstructor
public enum LeaveType {
    ANNUAL("Annual leave",
           "Pre-approved holiday / vacation"),
    SICK("Sick leave",
         "Acute illness or injury — may be retro-approved"),
    MATERNITY("Maternity leave",
              "Statutory maternity entitlement"),
    BEREAVEMENT("Bereavement leave",
                "Death of an immediate family member"),
    COMPASSIONATE("Compassionate leave",
                  "Family emergency / caregiving need"),
    STUDY("Study leave",
          "Continuing professional development, examinations"),
    OTHER("Other",
          "Use sparingly; see the reason field");

    private final String label;
    private final String description;
}
