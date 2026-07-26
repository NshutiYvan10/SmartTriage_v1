/* ── ConfirmDialog ──
 *
 * In-app "Are you sure?" confirmation for destructive or consequential
 * actions, replacing browser-native window.confirm/window.prompt so the
 * dialog matches the app's glass design system, supports dark mode, and
 * can require a typed reason (e.g. rejection reason) before confirming.
 */
import { useState } from 'react';
import { createPortal } from 'react-dom';
import { AlertTriangle, Loader2, X } from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';

interface Props {
  open: boolean;
  title: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  /** 'danger' (red, default) for destructive actions; 'primary' (cyan) for consequential-but-positive ones. */
  tone?: 'danger' | 'primary';
  /** Show a reason textarea; the typed value is passed to onConfirm. */
  withReason?: boolean;
  reasonLabel?: string;
  reasonPlaceholder?: string;
  /** Disable Confirm until a non-empty reason is typed. */
  reasonRequired?: boolean;
  busy?: boolean;
  onConfirm: (reason?: string) => void;
  onClose: () => void;
}

export function ConfirmDialog({
  open, title, message,
  confirmLabel = 'Confirm', cancelLabel = 'Cancel',
  tone = 'danger',
  withReason = false, reasonLabel = 'Reason', reasonPlaceholder = '', reasonRequired = false,
  busy = false,
  onConfirm, onClose,
}: Props) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const [reason, setReason] = useState('');
  if (!open) return null;

  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
  const ok = !withReason || !reasonRequired || reason.trim().length > 0;
  const confirmBg = tone === 'danger'
    ? 'bg-red-600 hover:bg-red-500'
    : 'bg-cyan-600 hover:bg-cyan-700';

  const confirm = () => {
    if (!ok || busy) return;
    onConfirm(withReason ? reason.trim() || undefined : undefined);
    setReason('');
  };
  const close = () => { setReason(''); onClose(); };

  // PORTAL to <body>: callers often render this inside glass cards whose
  // backdrop-filter creates a CSS containing block — position:fixed then
  // resolves against the CARD, so the dialog (and its backdrop) rendered
  // clipped inside the row: a half-screen dark band with the action
  // buttons cut off. Portaling out guarantees true viewport positioning.
  return createPortal(
    <div
      className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm"
      style={{ background: 'var(--modal-backdrop)' }}
    >
      <div style={glassCard} className="w-full max-w-md rounded-2xl overflow-hidden shadow-2xl animate-scale-in">
        <div className="px-5 py-3 flex items-center justify-between" style={{ borderBottom: borderStyle }}>
          <div className="flex items-center gap-2">
            <span className={`w-7 h-7 rounded-lg flex items-center justify-center ${tone === 'danger' ? 'bg-red-500/15' : 'bg-cyan-500/15'}`}>
              <AlertTriangle className={`w-4 h-4 ${tone === 'danger' ? 'text-red-500' : 'text-cyan-500'}`} />
            </span>
            <h3 className={`text-sm font-bold ${text.heading}`}>{title}</h3>
          </div>
          <button onClick={close} className={`${text.muted} hover:opacity-70`} aria-label="Close">
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="p-5 space-y-3">
          <p className={`text-xs ${text.body}`}>{message}</p>
          {withReason && (
            <div>
              <label className={`block text-[11px] font-bold uppercase tracking-wide mb-1 ${text.label}`}>
                {reasonLabel}{reasonRequired ? ' (required)' : ' (optional)'}
              </label>
              <textarea
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                rows={3}
                placeholder={reasonPlaceholder}
                style={glassInner}
                className={`w-full px-3 py-2 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
              />
            </div>
          )}
        </div>

        <div className="flex items-center justify-end gap-2 px-5 py-3" style={{ borderTop: borderStyle }}>
          <button onClick={close} disabled={busy} className={`px-4 py-1.5 rounded-xl text-xs font-bold hover:bg-white/5 ${text.body}`}>
            {cancelLabel}
          </button>
          <button
            onClick={confirm}
            disabled={!ok || busy}
            className={`inline-flex items-center gap-1.5 px-4 py-1.5 rounded-xl text-xs font-bold text-white ${
              ok && !busy ? confirmBg : 'bg-slate-400 cursor-not-allowed'}`}
          >
            {busy && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  );
}
