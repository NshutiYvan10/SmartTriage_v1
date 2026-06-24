/* ── RFID tap-to-identify banner (V95) ──
 *
 * Lives on the registrar's dashboard. Subscribes to /topic/rfid/{hospitalId}; when a card is
 * tapped at the desk reader the backend pushes the result here in real time:
 *   • CARD_FOUND     → surface the patient + cross-hospital history; registrar confirms → open visit.
 *   • CARD_NOT_FOUND → "unknown card", shortcut to manual registration.
 * (CARD_BIND events are for the registration form's tap-to-capture and are ignored here.)
 * Only shown to the registration-desk audience.
 */
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ScanLine, UserCheck, UserX, Loader2, X } from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { subscribeToRfidEvents } from '@/api/websocket';
import { rfidApi, type RfidEvent } from '@/api/rfid';
import { ApiError } from '@/api/client';
import { CrossHospitalSafetyBanner } from '@/modules/entry/CrossHospitalSafetyBanner';
import type { UserRole } from '@/types/roles';

const DESK_ROLES: UserRole[] = ['SUPER_ADMIN', 'HOSPITAL_ADMIN', 'REGISTRAR'];

export function RfidPatientFoundBanner() {
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  const role = user?.role;
  const navigate = useNavigate();

  const [event, setEvent] = useState<RfidEvent | null>(null);
  const [opening, setOpening] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const isDeskUser = role != null && DESK_ROLES.includes(role);

  useEffect(() => {
    if (!hospitalId || !isDeskUser) return;
    const unsub = subscribeToRfidEvents(hospitalId, (e: RfidEvent) => {
      // The registration form handles CARD_BIND; the dashboard surfaces identify results only.
      if (e?.type === 'CARD_FOUND' || e?.type === 'CARD_NOT_FOUND') {
        setError(null);
        setEvent(e);
      }
    });
    return unsub;
  }, [hospitalId, isDeskUser]);

  if (!isDeskUser || !event) return null;

  const dismiss = () => { setEvent(null); setError(null); };

  const openVisit = async () => {
    if (!event?.cardId || !hospitalId) return;
    setOpening(true);
    setError(null);
    try {
      const res = await rfidApi.openVisit({ cardId: event.cardId, hospitalId, arrivalMode: 'WALK_IN' });
      setEvent(null);
      if (res?.visit?.id) navigate(`/visit/${res.visit.id}`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not open the visit');
    } finally {
      setOpening(false);
    }
  };

  /* ── CARD_NOT_FOUND ── */
  if (event.type === 'CARD_NOT_FOUND') {
    return (
      <div className="rounded-2xl px-5 py-4 mb-4 animate-fade-in flex items-start gap-3"
        style={{ background: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.3)' }}>
        <UserX className="w-5 h-5 text-slate-500 flex-shrink-0 mt-0.5" />
        <div className="flex-1 min-w-0">
          <p className="text-sm font-bold text-slate-700">Unknown card — no patient found</p>
          <p className="text-xs text-slate-500 mt-0.5">
            Card <span className="font-mono">{event.cardId}</span> isn't linked to anyone. Register the
            patient manually and assign this card.
          </p>
          <button
            onClick={() => { navigate('/entry'); dismiss(); }}
            className="inline-flex items-center gap-1.5 mt-2.5 text-xs font-semibold text-cyan-700 hover:text-cyan-900 px-2.5 py-1 rounded-md hover:bg-cyan-50 transition-colors"
          >
            Register manually
          </button>
        </div>
        <button onClick={dismiss} aria-label="Dismiss" className="text-slate-400 hover:text-slate-600">
          <X className="w-4 h-4" />
        </button>
      </div>
    );
  }

  /* ── CARD_FOUND ── */
  return (
    <div className="rounded-2xl px-5 py-4 mb-4 animate-fade-in"
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

          {/* Cross-hospital safety floor for this person (by card — works without a national ID). */}
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
              className="inline-flex items-center gap-1.5 text-xs font-bold text-white bg-emerald-600 hover:bg-emerald-700 px-3.5 py-1.5 rounded-lg transition-colors disabled:opacity-50"
            >
              {opening ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <UserCheck className="w-3.5 h-3.5" />}
              Open visit
            </button>
            <button
              onClick={dismiss}
              className="text-xs font-semibold text-slate-500 hover:text-slate-700 px-2.5 py-1.5 rounded-lg hover:bg-slate-100 transition-colors"
            >
              Dismiss
            </button>
          </div>
        </div>
        <button onClick={dismiss} aria-label="Dismiss" className="text-emerald-500 hover:text-emerald-700">
          <X className="w-4 h-4" />
        </button>
      </div>
    </div>
  );
}
