/* ═══════════════════════════════════════════════════════════════
   Verification Modals — Phase 2 senior-tech sign-off, junior bounce
   back, and emergency override.

   Three small modals share a similar header / submit pattern. Kept in
   one file so the lab dashboard imports stay tight.
   ═══════════════════════════════════════════════════════════════ */

import { useState } from 'react';
import { CheckCircle2, XCircle, AlertOctagon, Loader2, X } from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { labApi } from '@/api/lab';
import type { LabOrder } from '@/api/lab';

// ─────────────────────────────────────────────────────────────────
// VerifyResultModal — senior tech approves and releases the result
// ─────────────────────────────────────────────────────────────────

interface VerifyProps {
  order: LabOrder;
  verifiedByName: string;
  onClose: () => void;
  onSaved: () => void;
}

export function VerifyResultModal({ order, verifiedByName, onClose, onSaved }: VerifyProps) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const [notes, setNotes] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    setSubmitting(true);
    setError(null);
    try {
      await labApi.verifyResult(order.id, {
        verifiedByName: verifiedByName || undefined,
        notes: notes || undefined,
      });
      onSaved();
    } catch (err: any) {
      setError(err?.message || 'Failed to verify');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <ModalShell onClose={onClose} icon={<CheckCircle2 className="w-5 h-5 text-emerald-500" />} iconBg="bg-emerald-500/15" title="Verify & release" subtitle={`${order.testName} • ${order.orderNumber}`} text={text} glassCard={glassCard} isDark={isDark}>
      {/* Result snapshot */}
      <div className="rounded-xl p-3 mb-4" style={glassInner}>
        <div className={`text-[10px] uppercase font-bold mb-1 ${text.label}`}>Junior tech entered</div>
        <div className={`text-base font-bold ${order.isCritical ? 'text-rose-500' : text.heading}`}>
          {order.resultValue} {order.resultUnit}
        </div>
        {order.referenceRangeMin != null && order.referenceRangeMax != null && (
          <div className={`text-[10px] ${text.muted}`}>
            Reference: {order.referenceRangeMin} – {order.referenceRangeMax} {order.resultUnit}
          </div>
        )}
        <div className={`text-[10px] mt-1 ${text.muted}`}>
          Entered by {order.enteredByName ?? 'lab tech'}
        </div>
      </div>

      <div className="rounded-xl p-3 mb-3 bg-emerald-500/10 ring-1 ring-emerald-500/20">
        <p className={`text-[11px] ${text.body}`}>
          <strong>Sanity check:</strong> does this number match the patient's clinical picture
          and the previous reading? If yes, release. If you suspect a typo, click "Reject (bounce back)" instead.
        </p>
      </div>

      <div className="mb-4">
        <label className={`text-[10px] font-bold uppercase tracking-wider mb-1 block ${text.label}`}>Verifier note (optional)</label>
        <textarea
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          rows={2}
          placeholder="Anything to flag for the doctor"
          className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none resize-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
          style={glassInner}
        />
      </div>

      {error && (
        <div className="rounded-xl px-3 py-2 mb-3 text-xs font-semibold bg-rose-500/10 text-rose-500">{error}</div>
      )}

      <ModalActions onClose={onClose} submitting={submitting} text={text} disabled={false}
        submitLabel="Verify & release"
        submitIcon={<CheckCircle2 className="w-3.5 h-3.5" />}
        submitClass="bg-cyan-600 hover:bg-cyan-700"
        onSubmit={submit}
      />
    </ModalShell>
  );
}

// ─────────────────────────────────────────────────────────────────
// RejectVerificationModal — senior bounces it back to junior
// ─────────────────────────────────────────────────────────────────

interface RejectProps {
  order: LabOrder;
  rejectedByName: string;
  onClose: () => void;
  onSaved: () => void;
}

export function RejectVerificationModal({ order, rejectedByName, onClose, onSaved }: RejectProps) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (!reason.trim()) {
      setError('A reason is required so the junior knows what to fix.');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await labApi.rejectVerification(order.id, {
        reason,
        rejectedByName: rejectedByName || undefined,
      });
      onSaved();
    } catch (err: any) {
      setError(err?.message || 'Failed to reject');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <ModalShell onClose={onClose} icon={<XCircle className="w-5 h-5 text-rose-500" />} iconBg="bg-rose-500/15" title="Reject — bounce back to junior" subtitle={`${order.testName} • ${order.orderNumber}`} text={text} glassCard={glassCard} isDark={isDark}>
      <div className="rounded-xl p-3 mb-4" style={glassInner}>
        <div className={`text-[10px] uppercase font-bold mb-1 ${text.label}`}>Result entered</div>
        <div className={`text-base font-bold ${text.heading}`}>{order.resultValue} {order.resultUnit}</div>
        <div className={`text-[10px] ${text.muted}`}>by {order.enteredByName ?? 'lab tech'}</div>
      </div>

      <div className="mb-4">
        <label className={`text-[10px] font-bold uppercase tracking-wider mb-1 block ${text.label}`}>Reason <span className="text-rose-500">*</span></label>
        <textarea
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          rows={3}
          placeholder='e.g. "Looks like a decimal slip — please re-check the tube and re-run."'
          className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none resize-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
          style={glassInner}
          autoFocus
        />
        <p className={`text-[10px] mt-1 ${text.muted}`}>
          The junior tech will see this when they re-enter. Status returns to PROCESSING.
        </p>
      </div>

      {error && (
        <div className="rounded-xl px-3 py-2 mb-3 text-xs font-semibold bg-rose-500/10 text-rose-500">{error}</div>
      )}

      <ModalActions onClose={onClose} submitting={submitting} text={text} disabled={!reason.trim()}
        submitLabel="Reject"
        submitIcon={<XCircle className="w-3.5 h-3.5" />}
        submitClass="bg-gradient-to-r from-rose-600 to-rose-500"
        onSubmit={submit}
      />
    </ModalShell>
  );
}

// ─────────────────────────────────────────────────────────────────
// OverrideVerificationModal — junior emergency-releases without senior
// ─────────────────────────────────────────────────────────────────

interface OverrideProps {
  order: LabOrder;
  overrideByName: string;
  onClose: () => void;
  onSaved: () => void;
}

export function OverrideVerificationModal({ order, overrideByName, onClose, onSaved }: OverrideProps) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (!reason.trim()) {
      setError('Please document why the senior gate is being bypassed.');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await labApi.releaseWithoutVerification(order.id, {
        reason,
        overrideByName: overrideByName || undefined,
      });
      onSaved();
    } catch (err: any) {
      setError(err?.message || 'Failed to override');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <ModalShell onClose={onClose} icon={<AlertOctagon className="w-5 h-5 text-amber-500" />} iconBg="bg-amber-500/15" title="Release without verification" subtitle={`${order.testName} • ${order.orderNumber}`} text={text} glassCard={glassCard} isDark={isDark}>
      <div className="rounded-xl p-3 mb-3 bg-amber-500/10 ring-1 ring-amber-500/20">
        <p className={`text-[11px] ${text.body}`}>
          You are releasing a high-risk result <strong>without senior sign-off</strong>. Use this only when no senior is on duty
          and the doctor needs the value urgently. The reason is logged for audit.
        </p>
      </div>

      <div className="rounded-xl p-3 mb-4" style={glassInner}>
        <div className={`text-[10px] uppercase font-bold mb-1 ${text.label}`}>Result</div>
        <div className={`text-base font-bold ${order.isCritical ? 'text-rose-500' : text.heading}`}>
          {order.resultValue} {order.resultUnit}
        </div>
      </div>

      <div className="mb-4">
        <label className={`text-[10px] font-bold uppercase tracking-wider mb-1 block ${text.label}`}>Reason <span className="text-rose-500">*</span></label>
        <textarea
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          rows={3}
          placeholder='e.g. "No senior on duty 03:15. Patient deteriorating, doctor calling for value."'
          className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none resize-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
          style={glassInner}
          autoFocus
        />
      </div>

      {error && (
        <div className="rounded-xl px-3 py-2 mb-3 text-xs font-semibold bg-rose-500/10 text-rose-500">{error}</div>
      )}

      <ModalActions onClose={onClose} submitting={submitting} text={text} disabled={!reason.trim()}
        submitLabel="Release without verification"
        submitIcon={<AlertOctagon className="w-3.5 h-3.5" />}
        submitClass="bg-gradient-to-r from-amber-600 to-amber-500"
        onSubmit={submit}
      />
    </ModalShell>
  );
}

// ─────────────────────────────────────────────────────────────────
// Shared shell + actions row
// ─────────────────────────────────────────────────────────────────

function ModalShell({ onClose, icon, iconBg, title, subtitle, text, glassCard, isDark, children }: {
  onClose: () => void;
  icon: React.ReactNode;
  iconBg: string;
  title: string;
  subtitle: string;
  text: any;
  glassCard: any;
  isDark: boolean;
  children: React.ReactNode;
}) {
  return (
    <div className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm" style={{ background: 'rgba(2,6,23,0.65)' }}>
      <div className="rounded-2xl p-6 max-w-lg w-full max-h-[90vh] overflow-y-auto overflow-hidden animate-scale-in shadow-2xl" style={glassCard}>
        <div className="flex items-start justify-between mb-4">
          <div className="flex items-center gap-3">
            <div className={`w-10 h-10 rounded-xl flex items-center justify-center ${iconBg}`}>{icon}</div>
            <div>
              <h3 className={`text-base font-bold ${text.heading}`}>{title}</h3>
              <p className={`text-xs ${text.muted}`}>{subtitle}</p>
            </div>
          </div>
          <button onClick={onClose} className={`w-8 h-8 rounded-lg flex items-center justify-center ${isDark ? 'hover:bg-white/10' : 'hover:bg-slate-100'}`}>
            <X className="w-4 h-4 text-slate-400" />
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}

function ModalActions({ onClose, submitting, disabled, text, submitLabel, submitIcon, submitClass, onSubmit }: {
  onClose: () => void;
  submitting: boolean;
  disabled: boolean;
  text: any;
  submitLabel: string;
  submitIcon: React.ReactNode;
  submitClass: string;
  onSubmit: () => void;
}) {
  return (
    <div className="flex items-center justify-end gap-2">
      <button onClick={onClose} disabled={submitting} className={`px-4 py-2 rounded-xl text-xs font-bold ${text.muted} hover:bg-white/5 disabled:opacity-50`}>Cancel</button>
      <button
        onClick={onSubmit}
        disabled={submitting || disabled}
        className={`inline-flex items-center gap-2 px-5 py-2 rounded-xl text-xs font-bold text-white disabled:opacity-50 ${submitClass}`}
      >
        {submitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : submitIcon}
        {submitLabel}
      </button>
    </div>
  );
}
