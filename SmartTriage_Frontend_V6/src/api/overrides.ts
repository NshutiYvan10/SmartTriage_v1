import { get } from './client';

/**
 * One row of the unified Override Register — every safety-gate bypass in the system,
 * normalised: who / on whom / when / why. Backed by the authoritative domain tables
 * (module/override), not alert-title parsing.
 */
export interface OverrideRecord {
  overrideType: string; // MED_SAFETY_CHECK | LAB_VERIFICATION_BYPASS | DOSE_ADMINISTRATION | EMERGENCY_APPROVAL | PRESCRIBE_ALLERGY | PRESCRIBE_INTERACTION | BREAK_THE_GLASS
  category: string;     // Medication | Lab | Privacy
  label: string;
  actorName: string | null;
  actorRole: string | null;
  patientName: string | null;
  visitNumber: string | null;
  visitId: string | null;
  patientId: string | null;
  maskedSubject: string | null; // break-the-glass: "National ID ***2780"
  occurredAt: string | null;
  justification: string | null;
  detail: string | null;
  severity: string | null;
  governanceAcknowledged: boolean;
  acknowledgedByName: string | null;
  acknowledgedAt: string | null;
  sourceId: string | null;
}

export const overridesApi = {
  /** Unified override register for a hospital (optional window / patient / type filters). */
  list: (
    hospitalId: string,
    opts: { from?: string; to?: string; patientId?: string; type?: string } = {},
  ) => {
    const qs = new URLSearchParams();
    if (opts.from) qs.set('from', opts.from);
    if (opts.to) qs.set('to', opts.to);
    if (opts.patientId) qs.set('patientId', opts.patientId);
    if (opts.type) qs.set('type', opts.type);
    const suffix = qs.toString() ? `?${qs.toString()}` : '';
    return get<OverrideRecord[]>(`/overrides/hospital/${hospitalId}${suffix}`);
  },
};
