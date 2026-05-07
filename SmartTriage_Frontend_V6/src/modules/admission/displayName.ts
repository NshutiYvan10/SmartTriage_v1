/* ── Patient display-name helpers (V28 — Direct Resus) ──────────────────
 *
 * Centralises how an unidentified Direct Resus patient is rendered
 * across every view. Backend now ships `isUnidentified`, `placeholderLabel`,
 * and `placeholderAssignedAt` on PatientResponse; this module composes
 * them into the canonical phonetic display name.
 *
 * Why a shared module? Because the same patient appears in the bed
 * grid, the visit list, the alerts dashboard, the search results, and
 * the chart header. Without a shared formatter every surface re-rolls
 * its own and they drift apart.
 *
 * Naming rule (locked with the user during design):
 *   - Identified patient   → "Marie Uwimana"
 *   - Unidentified adult   → "Unknown Alpha"
 *   - Unidentified child   → "Unknown Alpha (child)"
 *   - After Z, daily reset → "Unknown Alpha-2", "Bravo-2", ...
 */
import type { PatientResponse, VisitResponse } from '@/api/types';

/**
 * Compose the display name for a Patient. Pass the visit's
 * isPediatric flag when available so we can append "(child)" — the
 * pediatric marker is a property of the visit, not the patient.
 */
export function formatPatientDisplayName(
  patient: Pick<PatientResponse, 'firstName' | 'lastName' | 'isUnidentified' | 'placeholderLabel'> | null | undefined,
  isPediatric?: boolean,
): string {
  if (!patient) return 'Unknown patient';

  if (patient.isUnidentified) {
    const label = patient.placeholderLabel ?? patient.lastName ?? '';
    const base = label ? `Unknown ${label}` : 'Unknown';
    return isPediatric ? `${base} (child)` : base;
  }

  return `${patient.firstName ?? ''} ${patient.lastName ?? ''}`.trim() || 'Unknown patient';
}

/**
 * Convenience — given a Visit, compose its patient's display name. Uses
 * the visit's `patientName` field if present (the backend already
 * formats it correctly via VisitMapper.formatPatientName), but works
 * even when only patient + visit objects are available.
 */
export function formatVisitPatientName(
  visit: Pick<VisitResponse, 'patientName' | 'isPediatric'> | null | undefined,
  patient?: Pick<PatientResponse, 'firstName' | 'lastName' | 'isUnidentified' | 'placeholderLabel'> | null,
): string {
  if (visit?.patientName) return visit.patientName;
  return formatPatientDisplayName(patient ?? undefined, visit?.isPediatric);
}

/**
 * Minutes since this patient's placeholder was assigned, for the
 * identity-overdue countdown banner.
 *
 * Returns null when the patient is identified or has no placeholder
 * timestamp (so the caller can simply skip rendering).
 */
export function minutesSincePlaceholderAssigned(
  patient: Pick<PatientResponse, 'isUnidentified' | 'placeholderAssignedAt'> | null | undefined,
): number | null {
  if (!patient?.isUnidentified || !patient.placeholderAssignedAt) return null;
  const assignedMs = new Date(patient.placeholderAssignedAt).getTime();
  if (Number.isNaN(assignedMs)) return null;
  return Math.floor((Date.now() - assignedMs) / 60_000);
}

/**
 * Severity tier of the identity-unresolved cue. Drives the banner
 * tone — soft amber at 30 min, hard rose at 2 h.
 */
export type IdentityOverdueTier = 'none' | 'soft' | 'hard';

export function identityOverdueTier(minutes: number | null): IdentityOverdueTier {
  if (minutes == null) return 'none';
  if (minutes >= 120) return 'hard';
  if (minutes >= 30) return 'soft';
  return 'none';
}
