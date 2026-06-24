/* ── Replace RFID card (V95) — lost/damaged-card workflow ──
 *
 * Sets a new card on the patient's shared cross-hospital identity; the OLD card immediately stops
 * resolving anywhere. The registrar taps the NEW card on the desk reader (tap-to-capture) or types
 * its UID. A card already assigned to another patient is rejected by the backend (shown inline).
 */
import { useCallback, useEffect, useState } from 'react';
import { ScanLine, Loader2, X, AlertTriangle, CreditCard } from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { rfidApi, type RfidDevice, type RfidEvent } from '@/api/rfid';
import { subscribeToRfidEvents } from '@/api/websocket';
import { ApiError } from '@/api/client';

interface Props {
  patientId: string;
  patientName?: string;
  currentCardId?: string | null;
  onClose: () => void;
  onReplaced: (newCardId: string) => void;
}

export function ReplaceCardModal({ patientId, patientName, currentCardId, onClose, onReplaced }: Props) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
  const hospitalId = useAuthStore((s) => s.user?.hospitalId) || '';

  const [newCard, setNewCard] = useState('');
  const [devices, setDevices] = useState<RfidDevice[]>([]);
  const [deviceId, setDeviceId] = useState<string>(() => localStorage.getItem('st-rfid-device') || '');
  const [capturing, setCapturing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!hospitalId) return;
    rfidApi.listDevices(hospitalId)
      .then((d) => { setDevices(d); setDeviceId((cur) => (cur && d.some((x) => x.id === cur)) ? cur : (d.length === 1 ? d[0].id : cur)); })
      .catch(() => setDevices([]));
  }, [hospitalId]);

  const capture = useCallback(async () => {
    if (!deviceId || !hospitalId) return;
    localStorage.setItem('st-rfid-device', deviceId);
    setCapturing(true);
    setError(null);
    let unsub: (() => void) | null = null;
    let timer = 0;
    const stop = () => { if (unsub) unsub(); unsub = null; if (timer) window.clearTimeout(timer); setCapturing(false); };
    timer = window.setTimeout(stop, 32000);
    unsub = subscribeToRfidEvents(hospitalId, (e: RfidEvent) => {
      if (e?.type === 'CARD_BIND' && e.cardId) { setNewCard(e.cardId); stop(); }
    });
    try { await rfidApi.armBindMode(deviceId); } catch { stop(); }
  }, [deviceId, hospitalId]);

  const submit = async () => {
    if (!newCard.trim()) return;
    setSaving(true);
    setError(null);
    try {
      const res = await rfidApi.replaceCard(patientId, newCard.trim());
      onReplaced(res?.rfidCardId || newCard.trim());
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not replace the card');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-[9999] flex items-center justify-center p-4" style={{ background: 'rgba(2,11,20,0.55)' }}>
      <div className="w-full max-w-md rounded-2xl overflow-hidden shadow-2xl animate-scale-in" style={glassCard}>
        <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-5 py-4 flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <CreditCard className="w-5 h-5 text-cyan-300" />
            <h2 className="text-sm font-bold text-white">Replace RFID card</h2>
          </div>
          <button onClick={onClose} aria-label="Close" className="text-white/70 hover:text-white"><X className="w-4 h-4" /></button>
        </div>

        <div className="p-5 space-y-3">
          <p className={`text-xs ${text.body}`}>
            For <span className={`font-semibold ${text.label}`}>{patientName || 'this patient'}</span>.
            {currentCardId
              ? <> Current card <span className="font-mono">{currentCardId}</span> will stop working immediately.</>
              : ' This patient has no card on file yet.'}
          </p>

          <div>
            <label className={`text-xs font-semibold ${text.label}`}>New card ID</label>
            <div className="flex gap-2 mt-1">
              <input
                type="text" value={newCard} onChange={(e) => setNewCard(e.target.value)}
                placeholder="Tap the new card, or type its ID"
                className={`flex-1 px-3 py-2 rounded-xl text-sm ${text.body} focus:outline-none focus:ring-2 focus:ring-cyan-500/20`} style={glassInner}
              />
              <button
                type="button" onClick={capture} disabled={capturing || !deviceId}
                title={devices.length === 0 ? 'No RFID reader at this hospital' : 'Tap the new card on the desk reader'}
                className="flex items-center gap-1.5 px-3 rounded-xl text-xs font-bold text-cyan-400 bg-cyan-500/20 border border-cyan-500/30 hover:bg-cyan-500/30 transition-colors disabled:opacity-50 whitespace-nowrap"
              >
                {capturing ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ScanLine className="w-3.5 h-3.5" />}
                {capturing ? 'Tap card…' : 'Tap to capture'}
              </button>
            </div>
            {devices.length > 1 && (
              <select value={deviceId} onChange={(e) => { setDeviceId(e.target.value); localStorage.setItem('st-rfid-device', e.target.value); }}
                className={`mt-1.5 text-xs px-2 py-1 rounded-lg w-full ${text.body} focus:outline-none focus:ring-2 focus:ring-cyan-500/20`} style={glassInner}>
                <option value="">Select desk reader…</option>
                {devices.map((d) => <option key={d.id} value={d.id}>{d.deviceName}</option>)}
              </select>
            )}
          </div>

          {error && (
            <div className="flex items-start gap-2 p-2.5 rounded-lg bg-red-500/10 border border-red-500/30">
              <AlertTriangle className={`w-4 h-4 flex-shrink-0 mt-0.5 ${isDark ? 'text-red-300' : 'text-red-500'}`} />
              <p className={`text-xs font-semibold ${isDark ? 'text-red-300' : 'text-red-600'}`}>{error}</p>
            </div>
          )}

          <div className="flex items-center justify-end gap-2 pt-3" style={{ borderTop: borderStyle }}>
            <button onClick={onClose} className={`text-xs font-semibold ${text.body} hover:text-cyan-400 px-3 py-2`}>Cancel</button>
            <button
              onClick={submit} disabled={saving || !newCard.trim()}
              className="inline-flex items-center gap-1.5 text-xs font-bold text-white bg-cyan-600 hover:bg-cyan-700 px-4 py-2 rounded-xl transition-colors disabled:opacity-50"
            >
              {saving ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <CreditCard className="w-3.5 h-3.5" />}
              Replace card
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
