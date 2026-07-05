import { create } from 'zustand';
import type { RfidEvent } from '@/api/rfid';

/**
 * Current RFID desk tap. A SINGLE global listener (RfidDeskListener, mounted at the app root for
 * registration-desk roles) subscribes to /topic/rfid/{hospitalId} and writes the latest tap here;
 * every surface that shows it (the dedicated Registration Desk page, the dashboard banner) READS
 * from this store. That means a tap is caught no matter which page the registrar is on, and it is
 * still available after they navigate to the desk — fixing the "banner only listens on the
 * dashboard" gap. One tap at a time (a new tap replaces the current one).
 */
interface RfidState {
  event: RfidEvent | null;
  /** Wall-clock ms the current event arrived (for the desk page's freshness display). */
  receivedAt: number | null;
  setEvent: (e: RfidEvent) => void;
  clear: () => void;
}

export const useRfidStore = create<RfidState>((set) => ({
  event: null,
  receivedAt: null,
  setEvent: (e) => set({ event: e, receivedAt: Date.now() }),
  clear: () => set({ event: null, receivedAt: null }),
}));
