/* ── DirectResusFAB ─────────────────────────────────────────────────
 *
 * Persistent floating action button for the Red-patient bypass
 * pathway (V28 — Direct Resus Admission).
 *
 * Design intent
 * -------------
 * In a real ED, when a patient arrives in obvious extremis, the nurse
 * cannot stop to navigate menus. The Direct Resus action must be:
 *
 *   - Visible on every clinical screen the staff might be on
 *   - Reachable in one click — no navigation, no form to dig through
 *   - Visually unmistakable (red, large, siren icon) but out of the way
 *     of routine workflow
 *
 * This component renders a fixed-position red button bottom-right,
 * over every route that's not login / onboarding / the entry page.
 * Click → opens DirectResusModal directly. On success it navigates
 * the caller to the new visit page so the resus team has the chart
 * open instantly.
 *
 * Hidden when:
 *   - User is not a clinical role (LAB_TECHNICIAN, READ_ONLY)
 *   - Route is /entry (the registration page already has the
 *     Stable/Unstable banner; a second action would be visual noise)
 *   - Route is unauthenticated (login, accept-invite, public pages)
 */
import { useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Siren } from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { DirectResusModal } from '@/modules/admission/DirectResusModal';
import { ResusOverflowModal } from '@/modules/admission/ResusOverflowModal';
import type { DirectResusAdmissionResponse } from '@/api/types';
import type { UserRole } from '@/types/roles';

/**
 * Roles that can plausibly trigger a Direct Resus admission. We err
 * on the side of inclusion — in a Rwandan ED the first staff member
 * a critical patient encounters is often the registration clerk or a
 * paramedic, not a nurse or doctor. Blocking them would defeat the
 * whole point of this pathway.
 */
const CLINICAL_ROLES: UserRole[] = [
  'SUPER_ADMIN',
  'HOSPITAL_ADMIN',
  'DOCTOR',
  'NURSE',
  'REGISTRAR',
  'PARAMEDIC',
];
// V29 note: TRIAGE_NURSE was previously listed here. It's no longer a Role
// — triage nurses are NURSE-role users with Designation.TRIAGE_NURSE, so
// they're already covered by the NURSE entry above.

/** Routes where the FAB is hidden to avoid duplicating the registration banner. */
const HIDDEN_ROUTE_PREFIXES = [
  '/entry',
  '/login',
  '/accept-invite',
  '/onboarding',
  '/forgot-password',
  '/reset-password',
];

export function DirectResusFAB() {
  const location = useLocation();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);

  const [showModal, setShowModal] = useState(false);
  const [overflow, setOverflow] = useState<DirectResusAdmissionResponse | null>(null);

  // ── Visibility gates ─────────────────────────────────────────
  if (!user) return null;
  if (!CLINICAL_ROLES.includes(user.role)) return null;
  if (HIDDEN_ROUTE_PREFIXES.some((p) => location.pathname.startsWith(p))) return null;

  const hospitalId = user.hospitalId || 'a0000000-0000-0000-0000-000000000001';

  const handleSuccess = (response: DirectResusAdmissionResponse) => {
    setShowModal(false);
    if (response.overflow) {
      setOverflow(response);
    } else {
      navigate(`/visit/${response.visitId}`);
    }
  };

  return (
    <>
      {/* The button itself ─ fixed, bottom-right, always above page content. */}
      <button
        type="button"
        onClick={() => setShowModal(true)}
        title="Direct Resus Admission — Red patient (Ctrl+Shift+R)"
        aria-label="Direct Resus Admission"
        className={[
          'fixed z-[60] bottom-6 right-6',
          // Shape & size
          'h-14 px-5 rounded-full',
          // Color: emergency red, escalating ring on hover so it doesn't
          // recede into a busy dashboard background
          'bg-gradient-to-br from-rose-600 to-red-700 text-white',
          'shadow-2xl shadow-rose-700/40 ring-2 ring-rose-300/60',
          'hover:from-rose-500 hover:to-red-600 hover:ring-rose-200',
          'hover:-translate-y-0.5 active:scale-95',
          // Layout
          'inline-flex items-center gap-2.5',
          'transition-all duration-150',
          // Accessibility outline
          'focus:outline-none focus-visible:ring-4 focus-visible:ring-rose-300',
        ].join(' ')}
      >
        <Siren className="w-5 h-5 animate-pulse" strokeWidth={2.5} />
        <span className="text-xs font-extrabold uppercase tracking-wider whitespace-nowrap">
          Direct Resus
        </span>
      </button>

      {/* The admission modal — opens on click, closes on cancel or success. */}
      {showModal && (
        <DirectResusModal
          hospitalId={hospitalId}
          onClose={() => setShowModal(false)}
          onSuccess={handleSuccess}
        />
      )}

      {/* Overflow prompt — only when admit returned overflow=true. */}
      {overflow && (
        <ResusOverflowModal
          newAdmissionVisitId={overflow.visitId}
          newAdmissionVisitNumber={overflow.visitNumber}
          newAdmissionPatientName={
            overflow.isUnidentified
              ? `Unknown ${overflow.placeholderLabel ?? ''}`
              : `${overflow.patientFirstName} ${overflow.patientLastName}`
          }
          candidates={overflow.transferCandidates ?? []}
          onClose={() => {
            const id = overflow.visitId;
            setOverflow(null);
            navigate(`/visit/${id}`);
          }}
          onTransferComplete={() => {
            const id = overflow.visitId;
            setOverflow(null);
            navigate(`/visit/${id}`);
          }}
        />
      )}
    </>
  );
}
