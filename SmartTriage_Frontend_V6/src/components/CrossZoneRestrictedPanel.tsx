import { ShieldAlert } from 'lucide-react';
import type { EdZone } from '@/api/types';

/**
 * Panel rendered on hospital-wide clinical dashboards (Sepsis, Fast-Track,
 * Isolation, ICU, Referrals, Handover, Safety Incidents) when the
 * authenticated user does not have cross-zone read authority.
 *
 * <p>The backend's ClinicalAuthz.canSeeAllZonesAtHospital predicate gates
 * every hospital-wide list endpoint behind HOSPITAL_ADMIN / SUPER_ADMIN /
 * shift-lead / Charge Nurse. Without this panel, a regular doctor opening
 * one of these pages would issue a guaranteed-fail request to the gated
 * endpoint and see a generic 403 / network error. This panel makes the
 * permission boundary explicit and gives the user a clear next step.
 */
interface Props {
  /** Title of the dashboard the user just opened (e.g. "Sepsis Screening"). */
  pageTitle: string;
  /**
   * The user's current zone (from useMyShift), or null when off-shift.
   * Surfaced in the message so the user knows the system understands their
   * status.
   */
  zone: EdZone | null;
  /**
   * "OFF_SHIFT" when the user has no active assignment at all;
   * "ZONE_SCOPED" when they are on shift but only on one zone.
   */
  reason: 'OFF_SHIFT' | 'ZONE_SCOPED';
}

export function CrossZoneRestrictedPanel({ pageTitle, zone, reason }: Props) {
  return (
    <div className="p-8">
      <div className="max-w-2xl mx-auto bg-white rounded-2xl border border-amber-200 p-6 shadow-sm">
        <div className="flex items-start gap-4">
          <div className="flex-shrink-0 w-10 h-10 rounded-full bg-amber-50 border border-amber-200 inline-flex items-center justify-center">
            <ShieldAlert className="w-5 h-5 text-amber-700" />
          </div>
          <div className="flex-1 space-y-2">
            <h2 className="text-lg font-bold text-gray-900">
              {pageTitle} — hospital-wide view
            </h2>
            <p className="text-sm text-gray-700">
              This dashboard shows clinical activity across every ED zone
              and is available only to:
            </p>
            <ul className="text-sm text-gray-600 list-disc ml-5 space-y-0.5">
              <li>Charge Nurses and the active shift-lead</li>
              <li>Hospital administrators</li>
              <li>System administrators</li>
            </ul>
            <p className="text-sm text-gray-700 pt-1">
              {reason === 'OFF_SHIFT'
                ? "You're currently off shift, so the patient list is not available. Pick up a shift assignment to see your zone's patients."
                : `You're currently on shift on the ${zone ?? '—'} zone. Use your zone's specific dashboards (My Patients, Doctor Workspace) instead.`}
            </p>
            <p className="text-[12px] text-gray-500 pt-1">
              If this looks wrong — for example you should be holding the
              shift-lead badge — ask the on-duty Charge Nurse to refresh
              the assignment, then reload.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}
