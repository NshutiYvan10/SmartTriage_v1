/* ── DialogProvider ──
 *
 * Mounts once at the app root, renders the confirm/prompt modal and the toast
 * stack, and registers the imperative handlers into the {@link dialog} service
 * so any module can call `dialog.confirm/prompt/notify` without hooks.
 *
 * The modal styling mirrors ConfirmDialog (glass card, dark-mode aware) so the
 * whole app shares one look; prompts add an input the native prompt() couldn't
 * style, with optional default value, single-line mode, and required gating.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { AlertTriangle, CheckCircle2, Info, Loader2, X, XCircle } from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import {
  __registerDialogHandlers,
  type ConfirmOptions,
  type NotifyOptions,
  type NotifyType,
  type PromptOptions,
} from './dialogService';

type ModalState =
  | { kind: 'confirm'; opts: ConfirmOptions; resolve: (v: boolean) => void }
  | { kind: 'prompt'; opts: PromptOptions; resolve: (v: string | null) => void }
  | null;

interface Toast { id: number; message: string; type: NotifyType; title?: string; }

let toastSeq = 1;

export function DialogProvider({ children }: { children: React.ReactNode }) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const [modal, setModal] = useState<ModalState>(null);
  const [inputValue, setInputValue] = useState('');
  const [toasts, setToasts] = useState<Toast[]>([]);
  const inputRef = useRef<HTMLTextAreaElement & HTMLInputElement>(null);

  const dismissToast = useCallback((id: number) => {
    setToasts((t) => t.filter((x) => x.id !== id));
  }, []);

  useEffect(() => {
    __registerDialogHandlers({
      confirm: (opts) => new Promise<boolean>((resolve) => {
        setInputValue('');
        setModal({ kind: 'confirm', opts, resolve });
      }),
      prompt: (opts) => new Promise<string | null>((resolve) => {
        setInputValue(opts.defaultValue ?? '');
        setModal({ kind: 'prompt', opts, resolve });
      }),
      notify: (message: string, opts?: NotifyOptions) => {
        const id = toastSeq++;
        const type = opts?.type ?? 'info';
        setToasts((t) => [...t, { id, message, type, title: opts?.title }]);
        const duration = opts?.duration ?? 5000;
        if (duration > 0) window.setTimeout(() => dismissToast(id), duration);
      },
    });
  }, [dismissToast]);

  // Focus the prompt input when a prompt opens.
  useEffect(() => {
    if (modal?.kind === 'prompt') {
      const t = window.setTimeout(() => inputRef.current?.focus(), 60);
      return () => window.clearTimeout(t);
    }
  }, [modal]);

  const closeModal = useCallback((confirmed: boolean) => {
    setModal((cur) => {
      if (cur) {
        if (cur.kind === 'confirm') cur.resolve(confirmed);
        else cur.resolve(confirmed ? (inputValue.trim() ? inputValue.trim() : (cur.opts.required ? '' : inputValue.trim())) : null);
      }
      return null;
    });
  }, [inputValue]);

  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
  const tone = modal?.opts.tone ?? 'danger';
  const confirmBg = tone === 'danger' ? 'bg-red-600 hover:bg-red-500' : 'bg-cyan-600 hover:bg-cyan-700';
  const isPrompt = modal?.kind === 'prompt';
  const promptOk = !isPrompt || !modal.opts.required || inputValue.trim().length > 0;

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape') { e.preventDefault(); closeModal(false); }
    if (e.key === 'Enter' && (modal?.kind === 'confirm' || (isPrompt && modal?.opts.singleLine))) {
      e.preventDefault();
      if (promptOk) closeModal(true);
    }
  };

  return (
    <>
      {children}

      {/* ── confirm / prompt modal ── */}
      {modal && (
        <div
          className="fixed inset-0 z-[10000] flex items-center justify-center p-4 backdrop-blur-sm"
          style={{ background: 'var(--modal-backdrop)' }}
          onKeyDown={onKeyDown}
        >
          <div className="absolute inset-0" onClick={() => closeModal(false)} />
          <div style={glassCard} className="relative w-full max-w-md rounded-2xl overflow-hidden shadow-2xl animate-scale-in">
            <div className="px-5 py-3 flex items-center justify-between" style={{ borderBottom: borderStyle }}>
              <div className="flex items-center gap-2">
                <span className={`w-7 h-7 rounded-lg flex items-center justify-center ${tone === 'danger' ? 'bg-red-500/15' : 'bg-cyan-500/15'}`}>
                  <AlertTriangle className={`w-4 h-4 ${tone === 'danger' ? 'text-red-500' : 'text-cyan-500'}`} />
                </span>
                <h3 className={`text-sm font-bold ${text.heading}`}>
                  {modal.opts.title ?? (modal.kind === 'confirm' ? 'Please confirm' : 'Enter details')}
                </h3>
              </div>
              <button onClick={() => closeModal(false)} className={`${text.muted} hover:opacity-70`} aria-label="Close">
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="p-5 space-y-3">
              <p className={`text-xs whitespace-pre-line ${text.body}`}>{modal.opts.message}</p>
              {isPrompt && (
                modal.opts.singleLine ? (
                  <input
                    ref={inputRef}
                    type="text"
                    value={inputValue}
                    onChange={(e) => setInputValue(e.target.value)}
                    placeholder={modal.opts.placeholder}
                    style={glassInner}
                    className={`w-full px-3 py-2 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
                  />
                ) : (
                  <textarea
                    ref={inputRef}
                    value={inputValue}
                    onChange={(e) => setInputValue(e.target.value)}
                    rows={3}
                    placeholder={modal.opts.placeholder}
                    style={glassInner}
                    className={`w-full px-3 py-2 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
                  />
                )
              )}
              {isPrompt && modal.opts.required && (
                <p className={`text-[11px] ${promptOk ? text.muted : 'text-red-500'}`}>A value is required.</p>
              )}
            </div>

            <div className="flex items-center justify-end gap-2 px-5 py-3" style={{ borderTop: borderStyle }}>
              <button onClick={() => closeModal(false)} className={`px-4 py-1.5 rounded-xl text-xs font-bold hover:bg-white/5 ${text.body}`}>
                {modal.opts.cancelLabel ?? 'Cancel'}
              </button>
              <button
                onClick={() => closeModal(true)}
                disabled={!promptOk}
                className={`inline-flex items-center gap-1.5 px-4 py-1.5 rounded-xl text-xs font-bold text-white ${promptOk ? confirmBg : 'bg-slate-400 cursor-not-allowed'}`}
              >
                {modal.opts.confirmLabel ?? (modal.kind === 'confirm' ? 'Confirm' : 'OK')}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ── toast stack ── */}
      {toasts.length > 0 && (
        <div className="fixed top-4 right-4 z-[10001] flex flex-col gap-2 w-[min(92vw,380px)]">
          {toasts.map((t) => (
            <ToastCard key={t.id} toast={t} onClose={() => dismissToast(t.id)} glassCard={glassCard} text={text} />
          ))}
        </div>
      )}
    </>
  );
}

function ToastCard({
  toast, onClose, glassCard, text,
}: {
  toast: Toast;
  onClose: () => void;
  glassCard: React.CSSProperties;
  text: { heading: string; body: string; muted: string };
}) {
  const Icon = toast.type === 'error' ? XCircle : toast.type === 'success' ? CheckCircle2 : Info;
  const accent = toast.type === 'error' ? 'text-red-500' : toast.type === 'success' ? 'text-emerald-500' : 'text-cyan-500';
  return (
    <div style={glassCard} className="rounded-xl shadow-2xl overflow-hidden animate-fade-down">
      <div className="flex items-start gap-3 p-3.5">
        <Icon className={`w-5 h-5 flex-shrink-0 mt-0.5 ${accent}`} />
        <div className="flex-1 min-w-0">
          {toast.title && <p className={`text-xs font-bold ${text.heading}`}>{toast.title}</p>}
          <p className={`text-xs ${toast.title ? text.muted : text.body} break-words`}>{toast.message}</p>
        </div>
        <button onClick={onClose} className={`${text.muted} hover:opacity-70 flex-shrink-0`} aria-label="Dismiss">
          <X className="w-3.5 h-3.5" />
        </button>
      </div>
    </div>
  );
}
