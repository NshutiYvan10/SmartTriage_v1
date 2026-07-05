/* ── RfidDeskListener ──
 * Mounted ONCE at the app root (after auth). For registration-desk roles it is the SINGLE
 * subscriber to /topic/rfid/{hospitalId}: every card tap is written to useRfidStore so it is
 * caught no matter which page the registrar is on. When the registrar is NOT already on a page
 * that surfaces the tap (the Registration Desk page or the dashboard banner), it shows a compact
 * toast that jumps to the desk — so a tap made while on, say, the patient list is never lost.
 */
import { useEffect } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { ScanLine, UserX } from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { useRfidStore } from '@/store/rfidStore';
import { subscribeToRfidEvents } from '@/api/websocket';
import type { RfidEvent } from '@/api/rfid';
import type { UserRole } from '@/types/roles';

const DESK_ROLES: UserRole[] = ['SUPER_ADMIN', 'HOSPITAL_ADMIN', 'REGISTRAR'];
// Routes that already render the tap in full — no toast needed there.
const OWNS_TAP = ['/registration-desk', '/dashboard'];

export function RfidDeskListener() {
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  const isDeskUser = user?.role != null && DESK_ROLES.includes(user.role);

  const event = useRfidStore((s) => s.event);
  const setEvent = useRfidStore((s) => s.setEvent);
  const clear = useRfidStore((s) => s.clear);
  const navigate = useNavigate();
  const location = useLocation();

  useEffect(() => {
    if (!hospitalId || !isDeskUser) return;
    const unsub = subscribeToRfidEvents(hospitalId, (e: RfidEvent) => {
      // CARD_BIND is for the registration form's tap-to-capture; the desk surfaces identify results.
      if (e?.type === 'CARD_FOUND' || e?.type === 'CARD_NOT_FOUND') setEvent(e);
    });
    return unsub;
  }, [hospitalId, isDeskUser, setEvent]);

  if (!isDeskUser || !event) return null;
  // On the desk page / dashboard the full result card already shows — don't double up.
  if (OWNS_TAP.some((p) => location.pathname.startsWith(p))) return null;

  const found = event.type === 'CARD_FOUND';
  return (
    <div className="fixed top-4 right-4 z-[9997] max-w-sm animate-fade-down">
      <button
        onClick={() => navigate('/registration-desk')}
        className="w-full text-left rounded-2xl shadow-2xl border-2 p-3.5 flex items-start gap-3 transition-colors"
        style={found
          ? { background: '#059669', borderColor: '#6ee7b7', color: '#fff' }
          : { background: '#475569', borderColor: '#cbd5e1', color: '#fff' }}
      >
        {found ? <ScanLine className="w-5 h-5 flex-shrink-0 mt-0.5" /> : <UserX className="w-5 h-5 flex-shrink-0 mt-0.5" />}
        <div className="flex-1 min-w-0">
          <div className="text-[10px] font-bold uppercase tracking-widest opacity-80">Card tapped</div>
          <div className="text-sm font-bold truncate">
            {found ? (event.patientName || 'Patient identified') : 'Unknown card'}
          </div>
          <div className="text-xs opacity-90">Open the Registration Desk →</div>
        </div>
        <span
          role="button"
          aria-label="Dismiss"
          onClick={(e) => { e.stopPropagation(); clear(); }}
          className="opacity-70 hover:opacity-100 text-xs px-1"
        >✕</span>
      </button>
    </div>
  );
}
