import { User, MapPin, BedDouble } from 'lucide-react';

/* ════════════════════════════════════════════════════════════════════
   PatientContextLine — the canonical "who + where" line for ANY list,
   board, queue, or alert row that refers to a patient.

   Rationale: a recurring class of defect shipped where rows showed the
   clinical payload (a drug, a lab, an alert) but NOT who the patient is
   or where they are — so a nurse/doctor under pressure could not act
   without leaving the list to hunt for the patient. There was no shared
   component enforcing patient context, so every hand-built board re-made
   the same omission. This component is that enforcement: render it on
   every patient-scoped row and the identity/location is always present.

   Always renders SOMETHING for identity (falls back to "Unidentified
   patient") so a missing name reads as a real, visible state rather than
   a silently blank row. Zone / bed / visit are shown when known.
   ════════════════════════════════════════════════════════════════════ */

interface PatientContextLineProps {
  patientName?: string | null;
  /** ED zone label (e.g. RESUS, ACUTE). */
  zone?: string | null;
  /** Bed code/label (e.g. "A-12"). */
  bedLabel?: string | null;
  /** Visit number for disambiguation. */
  visitNumber?: string | null;
  /** Extra classes (color/size) applied to the wrapper. */
  className?: string;
  /** Hide the visit number (some dense rows show it elsewhere). */
  hideVisitNumber?: boolean;
}

export function PatientContextLine({
  patientName,
  zone,
  bedLabel,
  visitNumber,
  className = '',
  hideVisitNumber = false,
}: PatientContextLineProps) {
  const name = (patientName ?? '').trim();
  return (
    <span className={`inline-flex items-center gap-1.5 flex-wrap ${className}`}>
      <User className="w-3 h-3 flex-shrink-0 opacity-70" aria-hidden />
      <span className="font-semibold">{name || 'Unidentified patient'}</span>
      {zone && (
        <span className="inline-flex items-center gap-1">
          <span className="opacity-40">·</span>
          <MapPin className="w-3 h-3 opacity-70" aria-hidden />
          {zone}
        </span>
      )}
      {bedLabel && (
        <span className="inline-flex items-center gap-1">
          <span className="opacity-40">·</span>
          <BedDouble className="w-3 h-3 opacity-70" aria-hidden />
          Bed {bedLabel}
        </span>
      )}
      {!hideVisitNumber && visitNumber && (
        <span className="inline-flex items-center gap-1 opacity-80">
          <span className="opacity-40">·</span>
          #{visitNumber}
        </span>
      )}
    </span>
  );
}
