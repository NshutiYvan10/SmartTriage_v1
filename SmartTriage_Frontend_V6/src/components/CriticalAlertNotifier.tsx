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
 *  3. Renders a top-right card with explicit "Open chart" and "Dismiss"
 *     buttons (separate targets — dismissing can't accidentally
 *     navigate). Auto-dismisses after 12 seconds.
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
import { AlertTriangle, ExternalLink, X } from 'lucide-react';
import { useAlertStore } from '@/store/alertStore';
import { useTheme } from '@/hooks/useTheme';
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

/** Personal work notice (e.g. acting-CN delegation) — visit-less, not ack-able. */
interface NoticeEntry {
  id: string;
  title: string;
  message: string;
  spawnedAt: number;
}
const NOTICE_DURATION_MS = 20_000;

export function CriticalAlertNotifier() {
  const alerts = useAlertStore((s) => s.alerts);
  const navigate = useNavigate();
  const location = useLocation();
  const { glassCard, isDark, text } = useTheme();

  const [flashing, setFlashing] = useState(false);
  const [toasts, setToasts] = useState<ToastEntry[]>([]);
  const [notices, setNotices] = useState<NoticeEntry[]>([]);

  // Personal work notices pushed on /topic/alerts/user/{id} without a visit
  // (routed here by useWebSocket) — e.g. "you are now acting Charge Nurse".
  useEffect(() => {
    const onNotice = (e: Event) => {
      const d = (e as CustomEvent).detail ?? {};
      playAlertTone();
      setNotices((prev) => [...prev, {
        id: `N${Date.now()}${Math.random().toString(36).slice(2, 7)}`,
        title: String(d.title ?? 'Notification'),
        message: String(d.message ?? ''),
        spawnedAt: Date.now(),
      }]);
    };
    window.addEventListener('smarttriage:user-notice', onNotice);
    return () => window.removeEventListener('smarttriage:user-notice', onNotice);
  }, []);

  // Auto-expire notices (longer than critical toasts — they are rarer and
  // carry standing information; still manually dismissible).
  useEffect(() => {
    if (notices.length === 0) return;
    const iv = window.setInterval(() => {
      setNotices((prev) => prev.filter((n) => Date.now() - n.spawnedAt < NOTICE_DURATION_MS));
    }, 1_000);
    return () => window.clearInterval(iv);
  }, [notices.length]);

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

  // Prime the audio unlock so the CRITICAL beep actually sounds — browser
  // autoplay policy keeps the AudioContext suspended until the first real user
  // gesture, so this arms a one-time resume on the next click/key/touch.
  useEffect(() => { installAudioUnlock(); }, []);

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

      {/* Top-right stack. Personal notices keep their own small cyan cards;
          the critical alerts are ENVELOPED in a single red card with a
          header + scrollable list, so a pile of criticals no longer sprawls
          down the whole screen. Each row keeps two SEPARATE targets — an
          "open chart" button and a dismiss X — so clearing one can't
          accidentally navigate away mid-task. */}
      <div className="fixed top-4 right-4 z-[9998] flex flex-col gap-2 w-[min(92vw,400px)]">
        {notices.map((n) => (
          <div key={n.id} style={glassCard} className="rounded-xl shadow-2xl overflow-hidden animate-fade-down border-l-4 border-cyan-500">
            <div className="flex items-start gap-3 p-3.5">
              <div className="flex-1 min-w-0">
                <div className={`text-[10px] font-bold uppercase tracking-widest text-cyan-600`}>
                  {n.title}
                </div>
                <div className={`text-xs mt-0.5 ${text.body}`}>{n.message}</div>
              </div>
              <button
                onClick={() => setNotices((prev) => prev.filter((x) => x.id !== n.id))}
                className={`p-1.5 -m-1 rounded-lg ${text.muted} hover:opacity-70 ${isDark ? 'hover:bg-white/10' : 'hover:bg-slate-100'}`}
                aria-label="Dismiss"
              >
                <X className="w-4 h-4" />
              </button>
            </div>
          </div>
        ))}

        {toasts.length > 0 && (
          <div style={glassCard} className="rounded-2xl shadow-2xl overflow-hidden animate-fade-down border-l-4 border-rose-500">
            {/* Header — count + dismiss-all */}
            <div
              className="flex items-center justify-between px-4 py-3"
              style={{ borderBottom: isDark ? '1px solid rgba(244,63,94,0.2)' : '1px solid rgba(244,63,94,0.15)' }}
            >
              <div className="flex items-center gap-2.5 min-w-0">
                <span className="w-8 h-8 rounded-lg bg-rose-500/15 flex items-center justify-center flex-shrink-0">
                  <AlertTriangle className="w-4 h-4 text-rose-500 animate-pulse" />
                </span>
                <div className="min-w-0">
                  <div className="text-[10px] font-bold uppercase tracking-widest text-rose-500">
                    Critical alert{toasts.length > 1 ? 's' : ''}
                  </div>
                  <div className={`text-xs font-semibold ${text.heading}`}>{toasts.length} active</div>
                </div>
              </div>
              {toasts.length > 1 && (
                <button
                  onClick={() => setToasts([])}
                  className={`text-[11px] font-bold px-2.5 py-1 rounded-lg flex-shrink-0 ${text.body} ${isDark ? 'hover:bg-white/10' : 'hover:bg-slate-100'}`}
                >
                  Dismiss all
                </button>
              )}
            </div>

            {/* Scrollable list — capped height so it never takes over the
                screen. Each entry is its own RED-filled card (as before),
                just stacked inside the one envelope. */}
            <div className="max-h-[60vh] overflow-y-auto p-2 space-y-2">
              {toasts.map((t) => (
                <div
                  key={t.alertId}
                  className={`flex items-center gap-3 p-3 rounded-xl border ${
                    isDark ? 'bg-rose-500/10 border-rose-500/25' : 'bg-rose-50 border-rose-200'
                  }`}
                >
                  <span className="w-8 h-8 rounded-lg bg-rose-500/20 flex items-center justify-center flex-shrink-0">
                    <AlertTriangle className="w-4 h-4 text-rose-500 animate-pulse" />
                  </span>
                  <div className="flex-1 min-w-0">
                    <div className={`text-sm font-bold truncate ${text.heading}`}>
                      {t.patientName ?? 'Patient'}
                    </div>
                    <div className={`text-xs line-clamp-2 ${text.body}`}>
                      {t.message}
                    </div>
                  </div>
                  {t.visitId && (
                    <button
                      onClick={() => { navigate(`/visit/${t.visitId}`); dismiss(t.alertId); }}
                      title="Open chart"
                      aria-label="Open chart"
                      className="p-1.5 rounded-lg flex-shrink-0 text-white bg-rose-600 hover:bg-rose-500 transition-colors"
                    >
                      <ExternalLink className="w-3.5 h-3.5" />
                    </button>
                  )}
                  <button
                    onClick={() => dismiss(t.alertId)}
                    className={`p-1.5 rounded-lg flex-shrink-0 text-rose-500 hover:opacity-70 ${isDark ? 'hover:bg-white/10' : 'hover:bg-rose-100'}`}
                    aria-label="Dismiss"
                  >
                    <X className="w-4 h-4" />
                  </button>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </>
  );
}

function messageOf(a: AIAlert): string {
  return a.message ?? a.type ?? 'Critical clinical alert';
}

/**
 * Shared AudioContext for the critical-alert beep.
 *
 * <p>Browser autoplay policy keeps a freshly-created AudioContext SUSPENDED
 * until the user has made a real gesture (click / key / touch) on the page — a
 * WebSocket frame does NOT count as one. So we (a) keep ONE context and reuse
 * it across beeps (a per-beep {@code new AudioContext()} is created suspended
 * and stays silent), and (b) resume it on the first user gesture via
 * {@link installAudioUnlock}. Until that first interaction the tone may be
 * silent — the full-screen flash + toast are the backup signals.
 */
let sharedCtx: AudioContext | null = null;

function getAudioContext(): AudioContext | null {
  if (typeof window === 'undefined') return null;
  const Ctor = window.AudioContext || (window as any).webkitAudioContext;
  if (!Ctor) return null;
  if (!sharedCtx) {
    try { sharedCtx = new Ctor(); } catch { return null; }
  }
  return sharedCtx;
}

let unlockInstalled = false;

/**
 * Resume (and, on iOS/Safari, cement) the shared AudioContext on the first real
 * user gesture, then detach the listeners. Idempotent — safe to call on every
 * mount. Without this the very first CRITICAL beep is silent under modern
 * autoplay rules.
 */
export function installAudioUnlock(): void {
  if (unlockInstalled || typeof window === 'undefined') return;
  unlockInstalled = true;
  const detach = () => {
    window.removeEventListener('pointerdown', unlock);
    window.removeEventListener('keydown', unlock);
    window.removeEventListener('touchstart', unlock);
  };
  const unlock = () => {
    const ctx = getAudioContext();
    if (!ctx) { detach(); return; }
    const finish = () => {
      // iOS/Safari: a near-silent blip inside the gesture cements the unlock.
      try {
        const osc = ctx.createOscillator();
        const g = ctx.createGain();
        g.gain.value = 0.0001;
        osc.connect(g).connect(ctx.destination);
        osc.start();
        osc.stop(ctx.currentTime + 0.01);
      } catch { /* ignore */ }
      detach();
    };
    if (ctx.state === 'suspended') ctx.resume().then(finish).catch(() => {});
    else finish();
  };
  window.addEventListener('pointerdown', unlock);
  window.addEventListener('keydown', unlock);
  window.addEventListener('touchstart', unlock);
}

/**
 * Two-tone alert beep via Web Audio API on the shared, gesture-unlocked
 * context. Muted via the settings sessionStorage flag. If the context is still
 * suspended (no user gesture yet) it best-effort resumes; a stubbornly
 * suspended context just means no sound — the visual flash is the backup.
 *
 * <p>Wrapped in try/catch because some browsers / OS-level audio mute
 * configurations will throw.
 */
function playAlertTone() {
  try {
    if (sessionStorage.getItem('smarttriage:critical-mute') === '1') return;
    const ctx = getAudioContext();
    if (!ctx) return;
    if (ctx.state === 'suspended') ctx.resume().catch(() => {});
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
    // NB: never close the shared context — it is reused across beeps and its
    // resumed/running state is what keeps subsequent alerts audible.
  } catch {
    /* visual flash is the backup */
  }
}
