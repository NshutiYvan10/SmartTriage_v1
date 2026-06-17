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

import type { AlertType } from '@/api/types';

export type AlertCategory = 'CLINICAL' | 'OPERATIONAL' | 'SYSTEM';

const CLINICAL_TYPES = new Set<AlertType>([
  'TEWS_CRITICAL',
  'TEWS_ESCALATION',
  'VITAL_SIGN_ABNORMAL',
  'RETRIAGE_REQUIRED',
  'DETERIORATION_DETECTED',
  'SEPSIS_SCREENING',
  'PEDIATRIC_SAFETY',
  'CRITICAL_LAB_RESULT',
  'IOT_AUTO_RETRIAGE',
  'DOCTOR_NOTIFICATION',
  'DOCTOR_ESCALATION',
  'MEDICATION_SAFETY_BLOCK' as AlertType,
  'DIRECT_RESUS_ADMISSION' as AlertType,
  'RESUS_OVERFLOW' as AlertType,
  'ICU_ESCALATION_REQUESTED' as AlertType,
  'ICU_BED_UNAVAILABLE' as AlertType,
  'CRITICAL_VALUE_UNACKNOWLEDGED' as AlertType,
  // A junior releasing an unverified (often critical) result without senior
  // sign-off is a clinical safety-gate bypass, not mere workflow.
  'LAB_VERIFICATION_OVERRIDDEN',
  // LAB_SPECIMEN_REJECTED (redraw workflow) intentionally falls through to the
  // OPERATIONAL default — listed here in a comment to keep the mapping honest.
]);

const SYSTEM_TYPES = new Set<AlertType>([
  'IOT_DEVICE_DISCONNECTED',
  'IOT_DEVICE_LOW_BATTERY',
  'IOT_SIGNAL_QUALITY_DEGRADED',
  'SURGE_WARNING',
  'SYSTEM_OFFLINE' as AlertType,
  'SYSTEM_ONLINE' as AlertType,
]);

// OPERATIONAL is the implicit default — anything not above (medication
// safety warnings, investigation results, waiting-time-exceeded,
// reassessment-due, bed-available, identity-unresolved, lab overdue,
// safety-incident).

export function categoryFor(alertType: AlertType | string | undefined | null): AlertCategory {
  if (!alertType) return 'OPERATIONAL';
  const t = alertType as AlertType;
  if (CLINICAL_TYPES.has(t)) return 'CLINICAL';
  if (SYSTEM_TYPES.has(t)) return 'SYSTEM';
  return 'OPERATIONAL';
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
