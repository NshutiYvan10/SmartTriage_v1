/**
 * CriticalAlertNotifier — global audible + visual cue for new CRITICAL
 * alerts.
 *
 * Mounts once at the app root (after authentication). Watches the alert
 * store for new CRITICAL-severity alerts and:
 *
 *  1. Plays a short alert tone (Web Audio API — no asset shipping).
 *  2. Briefly flashes a full-viewport red border so the alert is
 *     impossible to miss even when the user is scrolled deep into a
 *     non-alert page.
 *  3. Renders a top-right toast that links to the patient/visit. The
 *     toast auto-dismisses after 12 seconds; the user can also click
 *     it to navigate, or click the X to dismiss earlier.
 *
 * <p>The point of this component is to close the gap a clinician would
 * otherwise have between "alert was generated server-side" and "I
 * actually noticed it on my screen". For a system handling deteriorating
 * patients in real Rwandan EDs, a silent CRITICAL alert is a clinical
 * safety failure.
 *
 * <p>Quiets itself when the user is on the alert center pages (they're
 * already looking at alerts) and when the user has muted in settings
 * (sessionStorage flag).
 */

import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { AlertTriangle, X } from 'lucide-react';
import { useAlertStore } from '@/store/alertStore';
import type { AIAlert } from '@/types';

const QUIET_PATH_PREFIXES = ['/alerts', '/alert-dashboard', '/notifications', '/login', '/activate'];
const TOAST_DURATION_MS = 12_000;
const FLASH_DURATION_MS = 1_500;

interface ToastEntry {
  alertId: string;
  message: string;
  visitId?: string;
  patientName?: string;
  spawnedAt: number;
}

export function CriticalAlertNotifier() {
  const alerts = useAlertStore((s) => s.alerts);
  const navigate = useNavigate();
  const location = useLocation();

  const [flashing, setFlashing] = useState(false);
  const [toasts, setToasts] = useState<ToastEntry[]>([]);

  // Track the (alert, escalation-tier) pairs we've already announced so re-renders /
  // store reorders don't double-fire — BUT a re-escalation (same id, higher tier) is a
  // NEW key, so an unacknowledged time-critical alert that the scheduler re-pages will
  // re-alarm rather than stay silent. Persists across re-renders via ref.
  const announcedKeys = useRef<Set<string>>(new Set());
  const keyOf = (a: AIAlert) => `${a.id}:${a.escalationTier ?? 0}`;
  const isQuietRoute = QUIET_PATH_PREFIXES.some((p) => location.pathname.startsWith(p));

  // Has this alert id already been announced at a LOWER escalation tier? Then this is a
  // RE-ESCALATION (the scheduler re-paged an unacknowledged critical nobody acted on) —
  // distinct from a brand-new alert.
  const isReescalation = (a: AIAlert): boolean => {
    const tier = a.escalationTier ?? 0;
    for (const k of announcedKeys.current) {
      const sep = k.lastIndexOf(':');
      if (sep > 0 && k.slice(0, sep) === a.id && Number(k.slice(sep + 1)) < tier) return true;
    }
    return false;
  };

  useEffect(() => {
    // First render: seed announced set with everything currently in
    // the store so we don't blast on initial hydration.
    if (announcedKeys.current.size === 0 && alerts.length > 0) {
      alerts.forEach((a) => announcedKeys.current.add(keyOf(a)));
      return;
    }

    const fresh = alerts.filter(
      (a) => !a.acknowledged
        && a.severity === 'CRITICAL'
        && !announcedKeys.current.has(keyOf(a)),
    );
    if (fresh.length === 0) return;

    // Re-escalations (a tier bump on an alert we've already seen) must be heard even on
    // the alert pages — that screen is exactly where the responder is sitting, and the
    // backend re-pages a time-critical alert only ONCE, so swallowing it on a quiet route
    // would lose the audible re-alarm permanently. Brand-new alerts stay suppressed on the
    // alert pages (the user is already looking at the list) as before.
    const reescalations = fresh.filter(isReescalation);

    fresh.forEach((a) => announcedKeys.current.add(keyOf(a)));

    const toAnnounce = isQuietRoute ? reescalations : fresh;
    if (toAnnounce.length === 0) return;

    // Audible cue.
    playAlertTone();

    // Visual flash + toast — only off the alert pages (avoid blasting the list view).
    if (!isQuietRoute) {
      setFlashing(true);
      window.setTimeout(() => setFlashing(false), FLASH_DURATION_MS);

      const now = Date.now();
      setToasts((prev) => [
        ...prev,
        ...fresh.map<ToastEntry>((a) => ({
          alertId: a.id,
          message: messageOf(a),
          // The store mapper stores the backend's visitId under
          // AIAlert.patientId — see alertStore.mapToAIAlert.
          visitId: a.patientId || undefined,
          patientName: a.patientName,
          spawnedAt: now,
        })),
      ]);
    }
  }, [alerts, isQuietRoute]);

  // Auto-expire toasts.
  useEffect(() => {
    if (toasts.length === 0) return;
    const iv = window.setInterval(() => {
      setToasts((prev) => prev.filter((t) => Date.now() - t.spawnedAt < TOAST_DURATION_MS));
    }, 1_000);
    return () => window.clearInterval(iv);
  }, [toasts.length]);

  const dismiss = (id: string) =>
    setToasts((prev) => prev.filter((t) => t.alertId !== id));

  return (
    <>
      {/* Full-viewport flash overlay. pointer-events:none so it never
          eats clicks even while visible. */}
      {flashing && (
        <div
          aria-hidden
          className="fixed inset-0 z-[9999] pointer-events-none animate-pulse"
          style={{
            boxShadow: 'inset 0 0 0 6px rgba(244,63,94,0.85)',
            background: 'rgba(244,63,94,0.05)',
          }}
        />
      )}

      {/* Toast stack — top right, below sidebar header. */}
      <div className="fixed top-4 right-4 z-[9998] flex flex-col gap-2 max-w-sm">
        {toasts.map((t) => (
          <div
            key={t.alertId}
            className="bg-rose-600 text-white rounded-xl shadow-2xl border-2 border-rose-300 p-3 animate-fade-down cursor-pointer hover:bg-rose-700 transition-colors"
            onClick={() => {
              if (t.visitId) navigate(`/visit/${t.visitId}`);
              dismiss(t.alertId);
            }}
          >
            <div className="flex items-start gap-2">
              <AlertTriangle className="w-5 h-5 flex-shrink-0 mt-0.5 animate-pulse" />
              <div className="flex-1 min-w-0">
                <div className="text-[10px] font-bold uppercase tracking-widest opacity-80">
                  Critical alert
                </div>
                <div className="text-sm font-bold truncate">
                  {t.patientName ?? 'Patient'}
                </div>
                <div className="text-xs opacity-95 line-clamp-2">
                  {t.message}
                </div>
                {t.visitId && (
                  <div className="text-[10px] opacity-80 mt-1">Click to open chart →</div>
                )}
              </div>
              <button
                onClick={(e) => { e.stopPropagation(); dismiss(t.alertId); }}
                className="opacity-70 hover:opacity-100"
                aria-label="Dismiss"
              >
                <X className="w-4 h-4" />
              </button>
            </div>
          </div>
        ))}
      </div>
    </>
  );
}

function messageOf(a: AIAlert): string {
  return a.message ?? a.type ?? 'Critical clinical alert';
}

/**
 * Two-tone alert beep via Web Audio API. No asset to ship, no autoplay
 * issues if it's triggered by a state change after a real WebSocket
 * frame (browsers consider that user-initiated context).
 *
 * <p>Wrapped in try/catch because some browsers / OS-level audio mute
 * configurations will throw — the visual flash is the backup signal.
 */
function playAlertTone() {
  try {
    const muted = sessionStorage.getItem('smarttriage:critical-mute') === '1';
    if (muted) return;
    const ctx = new (window.AudioContext || (window as any).webkitAudioContext)();
    const beep = (freq: number, startAt: number, duration: number, gain: number) => {
      const osc = ctx.createOscillator();
      const g = ctx.createGain();
      osc.type = 'sine';
      osc.frequency.value = freq;
      g.gain.setValueAtTime(0, ctx.currentTime + startAt);
      g.gain.linearRampToValueAtTime(gain, ctx.currentTime + startAt + 0.02);
      g.gain.linearRampToValueAtTime(0, ctx.currentTime + startAt + duration);
      osc.connect(g).connect(ctx.destination);
      osc.start(ctx.currentTime + startAt);
      osc.stop(ctx.currentTime + startAt + duration);
    };
    // Two short beeps a third apart — the standard medical-monitor
    // "attend to me" cadence, distinct from a phone ring or chat ping.
    beep(880, 0,    0.18, 0.28);
    beep(1100, 0.22, 0.22, 0.28);
    window.setTimeout(() => ctx.close().catch(() => {}), 600);
  } catch {
    /* visual flash is the backup */
  }
}
