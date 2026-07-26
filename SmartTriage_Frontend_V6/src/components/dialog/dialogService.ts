/* ── Global dialog service ──
 *
 * A singleton imperative API that replaces the browser-native
 * window.confirm / window.prompt / window.alert (which render as ugly,
 * app-breaking "localhost:5173 says…" chrome dialogs) with in-app modals and
 * toasts that match the design system.
 *
 * Any module can `import { dialog } from '@/components/dialog'` and call
 * `await dialog.confirm(...)`, `await dialog.prompt(...)`, or
 * `dialog.notify(...)` — no hook threading required. The <DialogProvider>
 * mounted once at the app root registers the real handlers; until then, the
 * safe fallbacks below resolve to a cancel (never a native popup).
 */

export type DialogTone = 'danger' | 'primary';

export interface ConfirmOptions {
  title?: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  tone?: DialogTone;
}

export interface PromptOptions {
  title?: string;
  message: string;
  /** Reject/return null unless a non-empty value is entered. */
  required?: boolean;
  placeholder?: string;
  defaultValue?: string;
  /** Single-line input instead of a textarea. */
  singleLine?: boolean;
  confirmLabel?: string;
  cancelLabel?: string;
  tone?: DialogTone;
}

export type NotifyType = 'error' | 'success' | 'info';

export interface NotifyOptions {
  type?: NotifyType;
  /** Auto-dismiss after this many ms (default 5000; 0 = sticky). */
  duration?: number;
  title?: string;
}

interface DialogHandlers {
  confirm: (opts: ConfirmOptions) => Promise<boolean>;
  prompt: (opts: PromptOptions) => Promise<string | null>;
  notify: (message: string, opts?: NotifyOptions) => void;
}

// Safe fallbacks used only before the provider mounts (no real call path hits
// these in practice). They NEVER fall back to a native popup.
let handlers: DialogHandlers = {
  confirm: async () => false,
  prompt: async () => null,
  notify: (message) => { /* eslint-disable-next-line no-console */ console.warn('[dialog] notify before provider mounted:', message); },
};

/** Called by <DialogProvider> to wire the real UI handlers. */
export function __registerDialogHandlers(h: DialogHandlers): void {
  handlers = h;
}

export const dialog = {
  /** In-app confirmation. Resolves true if confirmed, false if cancelled. */
  confirm: (opts: ConfirmOptions) => handlers.confirm(opts),
  /** In-app text prompt. Resolves the entered string, or null if cancelled. */
  prompt: (opts: PromptOptions) => handlers.prompt(opts),
  /** Non-blocking toast notification (replaces alert() for messages). */
  notify: (message: string, opts?: NotifyOptions) => handlers.notify(message, opts),
};
