/**
 * Alert categorisation — frontend-only derivation from {@link AlertType}.
 *
 * The backend's AlertType enum is a flat list of 38 codes spanning
 * life-threatening clinical events, workflow events, and system /
 * device events. Visually treating all of them the same as "RED if
 * CRITICAL" makes a deteriorating patient indistinguishable from an
 * IoT device with low battery. This module groups them into three
 * disjoint buckets so the alert centre can colour, filter, and tab
 * by category in a way that matches clinical urgency.
 *
 *   CLINICAL    — life-threatening or patient-deterioration events.
 *                 The clinician must look at this NOW: TEWS critical,
 *                 sepsis screening positive, deterioration detected,
 *                 critical lab back, ICU escalation, doctor
 *                 notifications, retriage required.
 *
 *   OPERATIONAL — workflow / care-coordination events. Important but
 *                 not life-threatening on their own: medication
 *                 safety warnings, results back (non-critical), bed
 *                 availability, waiting-time-exceeded, lab overdue,
 *                 identity-unresolved, reassessment due.
 *
 *   SYSTEM      — system-state / device events. Non-clinical: IoT
 *                 device disconnected, low battery, signal degraded,
 *                 system online/offline, surge warning.
 *
 * <p>The mapping is exhaustive — every value in the backend enum has
 * a category. New AlertType values added on the backend should be
 * added here at the same time; the {@code default} branch falls back
 * to OPERATIONAL so a forgotten enum value never crashes a rendering
 * surface, just shows up in the wrong bucket until corrected.
 */

import type { AlertType, AlertCategory as ServerAlertCategory } from '@/api/types';

export type AlertCategory = 'CLINICAL' | 'OPERATIONAL' | 'SYSTEM';

// Fallback mapping — mirrors common/enums/AlertType.java's AlertCategory assignment
// 1:1. The SERVER now sends an authoritative `category` on every alert (see
// categoryOf); these sets are only the fallback for an alert that somehow arrives
// without one. Keep them in sync with the backend so the fallback can't mis-bucket.
const CLINICAL_TYPES = new Set<AlertType>([
  'TEWS_CRITICAL', 'TEWS_ESCALATION', 'VITAL_SIGN_ABNORMAL', 'RETRIAGE_REQUIRED',
  'DETERIORATION_DETECTED', 'SEPSIS_SCREENING', 'PEDIATRIC_SAFETY', 'CRITICAL_LAB_RESULT',
  'IOT_AUTO_RETRIAGE', 'DOCTOR_NOTIFICATION', 'DOCTOR_ESCALATION', 'MEDICATION_SAFETY_BLOCK',
  'STAT_MEDICATION_OVERDUE', 'URGENT_MEDICATION_OVERDUE', 'MEDICATION_DOSE_OVERDUE',
  'MEDICATION_DOSE_MISSED', 'MEDICATION_EMERGENCY_OVERRIDE', 'STAT_LAB_OVERDUE',
  'CRITICAL_VALUE_UNACKNOWLEDGED', 'LAB_VERIFICATION_OVERRIDDEN',
  'EMS_PRE_ARRIVAL', 'EMS_HANDOVER_PENDING', 'FIELD_TRIAGED_AWAITING_REVIEW',
  'SAFETY_INCIDENT_CRITICAL', 'ICU_ESCALATION_REQUESTED', 'ICU_BED_UNAVAILABLE',
  'DIRECT_RESUS_ADMISSION', 'RESUS_OVERFLOW',
  'SEPSIS_BUNDLE_NOT_STARTED', 'SEPSIS_BUNDLE_OVERDUE',
  'FAST_TRACK_ACTIVATED', 'FAST_TRACK_SLA_BREACH',
  'HYPOGLYCEMIA_CRITICAL', 'HYPOGLYCEMIA_RECHECK_OVERDUE',
  'ISOLATION_REQUIRED', 'ISOLATION_PLACEMENT_OVERDUE', 'NOTIFIABLE_DISEASE',
  'PATHWAY_ACTIVATED', 'PATHWAY_STEP_OVERDUE',
]);

const SYSTEM_TYPES = new Set<AlertType>([
  'IOT_DEVICE_DISCONNECTED', 'IOT_DEVICE_LOW_BATTERY', 'IOT_SIGNAL_QUALITY_DEGRADED',
  'SURGE_WARNING', 'SYSTEM_OFFLINE', 'SYSTEM_ONLINE',
]);

// OPERATIONAL is the implicit default — WAITING_TIME_EXCEEDED, REASSESSMENT_DUE,
// INVESTIGATION_RESULTED, MEDICATION_SAFETY_WARNING, MEDICATION_APPROVAL_REQUIRED,
// URGENT/ROUTINE_LAB_OVERDUE, LAB_NOT_RECEIVED, LAB_SPECIMEN_REJECTED,
// IDENTITY_UNRESOLVED, BED_AVAILABLE.

export function categoryFor(alertType: AlertType | string | undefined | null): AlertCategory {
  if (!alertType) return 'OPERATIONAL';
  const t = alertType as AlertType;
  if (CLINICAL_TYPES.has(t)) return 'CLINICAL';
  if (SYSTEM_TYPES.has(t)) return 'SYSTEM';
  return 'OPERATIONAL';
}

/**
 * Authoritative category for an alert: prefer the server-supplied `category`
 * (driven by the backend AlertType enum, can never drift), falling back to the
 * local {@link categoryFor} map only when it is absent (older cached payloads).
 */
export function categoryOf(
  alert: { category?: ServerAlertCategory | AlertCategory | null; alertType?: AlertType | string | null } | null | undefined,
): AlertCategory {
  if (!alert) return 'OPERATIONAL';
  if (alert.category) return alert.category as AlertCategory;
  return categoryFor(alert.alertType ?? null);
}

export interface CategoryStyle {
  label: string;
  /** Tailwind classes for the small category chip / accent. */
  chipClass: string;
  /** Bare colour name for inline icons / borders. */
  accent: string;
}

const STYLES: Record<AlertCategory, CategoryStyle> = {
  CLINICAL: {
    label: 'Clinical',
    chipClass: 'bg-rose-50 text-rose-700 border-rose-200',
    accent: 'rose',
  },
  OPERATIONAL: {
    label: 'Operational',
    chipClass: 'bg-sky-50 text-sky-700 border-sky-200',
    accent: 'sky',
  },
  SYSTEM: {
    label: 'System',
    chipClass: 'bg-slate-50 text-slate-600 border-slate-200',
    accent: 'slate',
  },
};

export function styleFor(category: AlertCategory): CategoryStyle {
  return STYLES[category];
}
