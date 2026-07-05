/* ── RfidResultCard ──
 * Renders the CURRENT RFID tap (from useRfidStore) for a registration-desk user, with the
 * confirm/act buttons. Shared by the dedicated Registration Desk page AND the dashboard banner
 * so there is ONE implementation of the tap-result UX. Returns null when there is no tap or the
 * caller isn't a desk role. On any action it clears the store.
 *
 *   • CARD_FOUND (normal)        → patient + cross-hospital safety → "Open visit"
 *   • CARD_FOUND (unidentified)  → confirm + resolve the temporary record (goal 4.4)
 *   • CARD_NOT_FOUND             → "unknown card" → register manually with this card
 */
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ScanLine, UserCheck, UserX, Loader2, X } from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { useRfidStore } from '@/store/rfidStore';
import { rfidApi } from '@/api/rfid';
import { ApiError } from '@/api/client';
import { CrossHospitalSafetyBanner } from '@/modules/entry/CrossHospitalSafetyBanner';
import type { UserRole } from '@/types/roles';

const DESK_ROLES: UserRole[] = ['SUPER_ADMIN', 'HOSPITAL_ADMIN', 'REGISTRAR'];

export function RfidResultCard() {
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  const event = useRfidStore((s) => s.event);
  const clear = useRfidStore((s) => s.clear);
  const navigate = useNavigate();

  const [opening, setOpening] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const isDeskUser = user?.role != null && DESK_ROLES.includes(user.role);
  if (!isDeskUser || !event) return null;

  const dismiss = () => { setError(null); clear(); };

  const openVisit = async () => {
    if (!event.cardId || !hospitalId) return;
    setOpening(true);
    setError(null);
    try {
      const res = await rfidApi.openVisit({ cardId: event.cardId, hospitalId, arrivalMode: 'WALK_IN' });
      clear();
      // /visit/:id is triage-gated; the registrar would bounce. Route to the patient record.
      if (res?.patient?.id) navigate(`/patients/${res.patient.id}`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not open the visit');
    } finally {
      setOpening(false);
    }
  };

  /* ── CARD_NOT_FOUND ── */
  if (event.type === 'CARD_NOT_FOUND') {
    return (
      <div className="rounded-2xl px-5 py-4 animate-fade-in flex items-start gap-3"
        style={{ background: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.3)' }}>
        <UserX className="w-5 h-5 text-slate-500 flex-shrink-0 mt-0.5" />
        <div className="flex-1 min-w-0">
          <p className="text-sm font-bold text-slate-700">Unknown card — no patient found</p>
          <p className="text-xs text-slate-500 mt-0.5">
            Card <span className="font-mono">{event.cardId}</span> isn't linked to anyone. Register the
            patient and assign this card.
          </p>
          <button
            onClick={() => { navigate('/entry'); dismiss(); }}
            className="inline-flex items-center gap-1.5 mt-2.5 text-xs font-semibold text-cyan-700 hover:text-cyan-900 px-2.5 py-1 rounded-xl hover:bg-cyan-50 transition-colors"
          >
            Register with this card
          </button>
        </div>
        <button onClick={dismiss} aria-label="Dismiss" className="text-slate-400 hover:text-slate-600">
          <X className="w-4 h-4" />
        </button>
      </div>
    );
  }

  /* ── CARD_FOUND → still-unidentified placeholder here (goal 4.4): resolve, don't open new ── */
  if (event.unidentified && event.unidentifiedPatientId) {
    return (
      <div className="rounded-2xl px-5 py-4 animate-fade-in flex items-start gap-3"
        style={{ background: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.35)' }}>
        <UserCheck className="w-5 h-5 text-amber-600 flex-shrink-0 mt-0.5" />
        <div className="flex-1 min-w-0">
          <p className="text-sm font-bold text-amber-800">Card matches an unidentified patient here — confirm identity</p>
          <p className="text-xs text-amber-700/80 mt-0.5">
            This card is linked to a temporary record ({event.patientName || 'Unknown patient'}) still awaiting
            identification. Confirm the patient, then resolve identity — it updates the whole visit record, not a new visit.
          </p>
          <div className="flex items-center gap-2 mt-3">
            <button
              onClick={() => { navigate(`/patients/${event.unidentifiedPatientId}`); dismiss(); }}
              className="inline-flex items-center gap-1.5 text-xs font-bold text-white bg-amber-600 hover:bg-amber-700 px-3.5 py-1.5 rounded-xl transition-colors"
            >
              <UserCheck className="w-3.5 h-3.5" /> Review &amp; resolve identity
            </button>
            <button onClick={dismiss} className="text-xs font-semibold text-slate-500 hover:text-slate-700 px-2.5 py-1.5 rounded-xl hover:bg-slate-100 transition-colors">Dismiss</button>
          </div>
        </div>
        <button onClick={dismiss} aria-label="Dismiss" className="text-amber-500 hover:text-amber-700"><X className="w-4 h-4" /></button>
      </div>
    );
  }

  /* ── CARD_FOUND ── */
  return (
    <div className="rounded-2xl px-5 py-4 animate-fade-in"
      style={{ background: 'rgba(16,185,129,0.08)', border: '1px solid rgba(16,185,129,0.3)' }}>
      <div className="flex items-start gap-3">
        <div className="w-10 h-10 rounded-xl bg-emerald-500/15 flex items-center justify-center flex-shrink-0">
          <ScanLine className="w-5 h-5 text-emerald-600" />
        </div>
        <div className="flex-1 min-w-0">
          <p className="text-sm font-bold text-emerald-800">
            Patient identified by card tap — {event.patientName || 'patient'}
          </p>
          <p className="text-xs text-emerald-700/80 font-medium mt-0.5">
            {(event.linkedHospitalCount ?? 0) > 1
              ? `Known across ${event.linkedHospitalCount} SmartTriage hospitals.`
              : 'On file at this hospital.'} Confirm to open a new visit and enter the queue.
          </p>
          <div className="mt-2.5">
            <CrossHospitalSafetyBanner
              nationalId={event.nationalId || undefined}
              cardId={event.cardId}
              patientName={event.patientName}
            />
          </div>
          {error && <p className="text-xs font-semibold text-red-600 mt-2">{error}</p>}
          <div className="flex items-center gap-2 mt-3">
            <button
              onClick={openVisit}
              disabled={opening}
              className="inline-flex items-center gap-1.5 text-xs font-bold text-white bg-cyan-600 hover:bg-cyan-700 px-3.5 py-1.5 rounded-xl transition-colors disabled:opacity-50"
            >
              {opening ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <UserCheck className="w-3.5 h-3.5" />}
              Open visit
            </button>
            <button onClick={dismiss} className="text-xs font-semibold text-slate-500 hover:text-slate-700 px-2.5 py-1.5 rounded-xl hover:bg-slate-100 transition-colors">Dismiss</button>
          </div>
        </div>
        <button onClick={dismiss} aria-label="Dismiss" className="text-emerald-500 hover:text-emerald-700"><X className="w-4 h-4" /></button>
      </div>
    </div>
  );
}
