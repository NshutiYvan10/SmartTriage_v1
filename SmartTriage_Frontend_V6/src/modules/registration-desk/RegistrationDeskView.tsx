/* ── Registration Desk (RFID tap station) ──
 * The registrar's dedicated card surface. Shows live reader status, an always-on "ready to tap"
 * state, and — the moment a card is tapped — the identified patient with the confirm/act buttons
 * (via the shared RfidResultCard, driven by useRfidStore, which the global RfidDeskListener feeds).
 * Manual search on the Patient Registry always remains available; the tap is a speed layer.
 */
import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ScanLine, UserPlus, Globe, Wifi, WifiOff, RadioTower, RefreshCw } from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { useRfidStore } from '@/store/rfidStore';
import { rfidApi, type RfidDevice } from '@/api/rfid';
import { useTheme } from '@/hooks/useTheme';
import { RfidResultCard } from './RfidResultCard';

export function RegistrationDeskView() {
  const { glassCard, text } = useTheme();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  const navigate = useNavigate();

  const event = useRfidStore((s) => s.event);

  const [readers, setReaders] = useState<RfidDevice[] | null>(null);
  const [loading, setLoading] = useState(false);

  const loadReaders = useCallback(async () => {
    if (!hospitalId) return;
    setLoading(true);
    try { setReaders(await rfidApi.listDevices(hospitalId)); }
    catch { setReaders([]); }
    finally { setLoading(false); }
  }, [hospitalId]);

  useEffect(() => {
    loadReaders();
    const id = setInterval(loadReaders, 15000); // reflect heartbeat online/offline
    return () => clearInterval(id);
  }, [loadReaders]);

  const list = readers || [];
  const online = list.filter((r) => (r.status || '').toUpperCase() === 'ONLINE');
  const readerState: 'none' | 'online' | 'offline' = list.length === 0 ? 'none' : online.length > 0 ? 'online' : 'offline';

  return (
    <div className="min-h-full p-4 lg:p-6 max-w-4xl mx-auto space-y-4">
      {/* Header */}
      <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
        <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5 flex items-center gap-3">
          <div className="w-11 h-11 rounded-2xl bg-white/10 flex items-center justify-center">
            <ScanLine className="w-6 h-6 text-white" />
          </div>
          <div className="flex-1 min-w-0">
            <h1 className="text-lg font-bold text-white">Registration Desk</h1>
            <p className="text-xs text-slate-300">Tap a patient's card to identify them and start a visit</p>
          </div>
          <ReaderChip state={readerState} loading={loading} onRefresh={loadReaders} />
        </div>
      </div>

      {/* Tap surface: the live result, or the idle "ready" state */}
      {event ? (
        <RfidResultCard />
      ) : (
        <div className="rounded-3xl px-6 py-10 text-center animate-fade-in" style={glassCard}>
          <div className={`w-20 h-20 mx-auto rounded-full flex items-center justify-center mb-4 ${readerState === 'online' ? 'bg-emerald-500/10' : 'bg-slate-400/10'}`}>
            <ScanLine className={`w-10 h-10 ${readerState === 'online' ? 'text-emerald-500 animate-pulse' : 'text-slate-400'}`} />
          </div>
          {readerState === 'online' && (
            <>
              <p className={`text-base font-bold ${text.body}`}>Ready — tap a patient's card</p>
              <p className={`text-sm mt-1 ${text.muted}`}>The identified patient will appear here with a one-tap “Open visit”.</p>
            </>
          )}
          {readerState === 'offline' && (
            <>
              <p className={`text-base font-bold ${text.body}`}>Reader offline</p>
              <p className={`text-sm mt-1 ${text.muted}`}>The desk reader isn't responding. Check its power/WiFi, or use manual search below — registration is never blocked.</p>
            </>
          )}
          {readerState === 'none' && (
            <>
              <p className={`text-base font-bold ${text.body}`}>No card reader registered</p>
              <p className={`text-sm mt-1 ${text.muted}`}>Register an RFID reader for this hospital (Admin → IoT Devices), or use manual search below.</p>
            </>
          )}
        </div>
      )}

      {/* Always-available fallbacks — the tap is a speed layer, never the only way. */}
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <button onClick={() => navigate('/entry')}
          className="rounded-2xl px-5 py-4 text-left flex items-center gap-3 hover:-translate-y-0.5 transition-transform" style={glassCard}>
          <div className="w-10 h-10 rounded-xl bg-cyan-500/15 flex items-center justify-center flex-shrink-0"><UserPlus className="w-5 h-5 text-cyan-600" /></div>
          <div><p className={`text-sm font-bold ${text.body}`}>Register new patient</p><p className={`text-xs ${text.muted}`}>First-time patient — assign a card on the form</p></div>
        </button>
        <button onClick={() => navigate('/registry')}
          className="rounded-2xl px-5 py-4 text-left flex items-center gap-3 hover:-translate-y-0.5 transition-transform" style={glassCard}>
          <div className="w-10 h-10 rounded-xl bg-indigo-500/15 flex items-center justify-center flex-shrink-0"><Globe className="w-5 h-5 text-indigo-600" /></div>
          <div><p className={`text-sm font-bold ${text.body}`}>Manual search</p><p className={`text-xs ${text.muted}`}>Find any patient by name, national ID or card</p></div>
        </button>
      </div>

      {/* Reader inventory (small) */}
      {list.length > 0 && (
        <div className="rounded-2xl px-5 py-3" style={glassCard}>
          <p className={`text-[10px] font-bold uppercase tracking-wider mb-2 ${text.muted}`}>Desk readers</p>
          <div className="flex flex-wrap gap-2">
            {list.map((r) => {
              const on = (r.status || '').toUpperCase() === 'ONLINE';
              return (
                <span key={r.id} className="inline-flex items-center gap-1.5 text-xs font-semibold px-2.5 py-1 rounded-lg"
                  style={{ background: on ? 'rgba(16,185,129,0.1)' : 'rgba(100,116,139,0.1)', color: on ? '#059669' : '#64748b' }}>
                  {on ? <Wifi className="w-3 h-3" /> : <WifiOff className="w-3 h-3" />}{r.deviceName}
                </span>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}

function ReaderChip({ state, loading, onRefresh }: { state: 'none' | 'online' | 'offline'; loading: boolean; onRefresh: () => void }) {
  const cfg = {
    online:  { bg: 'rgba(16,185,129,0.18)', color: '#d1fae5', label: 'Reader online', Icon: RadioTower },
    offline: { bg: 'rgba(239,68,68,0.20)',  color: '#fecaca', label: 'Reader offline', Icon: WifiOff },
    none:    { bg: 'rgba(255,255,255,0.12)', color: '#e2e8f0', label: 'No reader',     Icon: WifiOff },
  }[state];
  const Icon = cfg.Icon;
  return (
    <div className="flex items-center gap-2">
      <span className="inline-flex items-center gap-1.5 text-xs font-bold px-2.5 py-1.5 rounded-full" style={{ background: cfg.bg, color: cfg.color }}>
        <Icon className="w-3.5 h-3.5" />{cfg.label}
      </span>
      <button onClick={onRefresh} aria-label="Refresh reader status" className="text-white/70 hover:text-white">
        <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} />
      </button>
    </div>
  );
}
