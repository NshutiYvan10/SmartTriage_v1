/* ── RFID tap-to-identify banner (dashboard inline surface) ──
 * Thin wrapper over the shared RfidResultCard. The single WebSocket subscription now lives in the
 * app-root <RfidDeskListener>, which writes the current tap into useRfidStore; this just renders
 * that result inline on the registrar's dashboard (returns null when there's no tap). Kept as a
 * named component so RegistrarHome/Dashboard imports don't change.
 */
import { RfidResultCard } from '@/modules/registration-desk/RfidResultCard';

export function RfidPatientFoundBanner() {
  return <RfidResultCard />;
}
