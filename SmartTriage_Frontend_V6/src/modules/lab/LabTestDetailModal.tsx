/* ═══════════════════════════════════════════════════════════════
   Lab Test Detail — full "test run" drill-down for clinicians.

   The doctor's investigation lists (My Investigations, the visit
   chart) are deliberately terse. This modal is the drill-down: click
   a lab test → see the WHOLE run — per-analyte results with reference
   ranges + flags, specimen + ordering metadata, turnaround, and,
   crucially, the ACTIONS the clinician has to take (acknowledge a
   critical value with read-back, review an abnormal, open the chart
   to prescribe).

   It fetches the full LabOrderResponse for the visit and matches the
   clicked investigation by `investigationId` (LabOrderResponse carries
   it), so no new backend endpoint is needed — getForVisit already
   returns per-analyte `components`.
   ═══════════════════════════════════════════════════════════════ */

import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  FlaskConical, X, Loader2, AlertOctagon, AlertTriangle, CheckCircle2,
  Phone, ExternalLink, Beaker, ClipboardCheck,
} from 'lucide-react';
import { format } from 'date-fns';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { labApi } from '@/api/lab';
import type { LabOrder, LabOrderStatus } from '@/api/lab';
import { PatientContextLine } from '@/components/PatientContextLine';
import { chartPath } from '@/lib/chartNav';
import { AcknowledgeCriticalModal } from './AcknowledgeCriticalModal';

interface Props {
  visitId: string;
  /** Investigation row id (matched against LabOrderResponse.investigationId). */
  investigationId: string;
  /** Shown in the header while the order loads / if it can't be found. */
  testName?: string;
  onClose: () => void;
  /** Called after an acknowledge so the parent list can refresh. */
  onChanged?: () => void;
}

function statusChip(status: LabOrderStatus | null | undefined): { label: string; className: string } {
  switch (status) {
    case 'ORDERED':               return { label: 'Ordered',              className: 'bg-slate-500/15 text-slate-500' };
    case 'SPECIMEN_COLLECTED':    return { label: 'Specimen collected',   className: 'bg-blue-500/15 text-blue-500' };
    case 'RECEIVED_BY_LAB':       return { label: 'Received',             className: 'bg-indigo-500/15 text-indigo-500' };
    case 'PROCESSING':            return { label: 'Processing',           className: 'bg-violet-500/15 text-violet-500' };
    case 'AWAITING_VERIFICATION': return { label: 'Awaiting verification', className: 'bg-amber-500/15 text-amber-500' };
    case 'RESULTED':              return { label: 'Resulted',             className: 'bg-emerald-500/15 text-emerald-500' };
    case 'REJECTED':              return { label: 'Rejected',             className: 'bg-rose-500/15 text-rose-500' };
    case 'CANCELLED':             return { label: 'Cancelled',            className: 'bg-slate-500/15 text-slate-400' };
    default:                      return { label: status ? String(status).replace(/_/g, ' ') : '—', className: 'bg-slate-500/15 text-slate-500' };
  }
}

function priorityChip(priority: string | null | undefined): string {
  switch (priority) {
    case 'STAT':   return 'bg-rose-500/15 text-rose-500';
    case 'URGENT': return 'bg-amber-500/15 text-amber-500';
    default:       return 'bg-slate-500/15 text-slate-500';
  }
}

function fmt(iso: string | null | undefined): string {
  if (!iso) return '—';
  try { return format(new Date(iso), 'dd MMM yyyy, HH:mm'); } catch { return String(iso); }
}

export function LabTestDetailModal({ visitId, investigationId, testName, onClose, onChanged }: Props) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const user = useAuthStore((s) => s.user);
  const canAcknowledge = user?.role === 'DOCTOR' || user?.role === 'SUPER_ADMIN';
  const actorName = user?.fullName ?? '';

  const [order, setOrder] = useState<LabOrder | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [ackOpen, setAckOpen] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      // A visit rarely has more than a handful of lab orders; 100 is a safe cap.
      const res = await labApi.getForVisit(visitId, 0, 100);
      const match = (res.content || []).find((o) => o.investigationId === investigationId) ?? null;
      if (!match) {
        setError('No lab record found for this investigation. It may be an imaging/ECG order, or the result is still being routed.');
      }
      setOrder(match);
    } catch (e: any) {
      setError(e?.message || 'Failed to load the test detail');
    } finally {
      setLoading(false);
    }
  }, [visitId, investigationId]);

  useEffect(() => { void load(); }, [load]);

  const sc = statusChip(order?.status);
  const isResulted = order?.status === 'RESULTED';
  const needsAck = !!order?.isCritical && !order?.criticalValueAcknowledgedAt;
  const components = order?.components ?? [];
  const hasPanel = components.length > 0;

  return (
    <>
    <div
      className="fixed inset-0 z-[9998] flex items-center justify-center p-4 backdrop-blur-sm"
      style={{ background: 'var(--modal-backdrop)' }}
      onClick={onClose}
    >
      <div
        className="rounded-2xl max-w-2xl w-full max-h-[90vh] overflow-y-auto shadow-2xl animate-scale-in"
        style={glassCard}
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-4 flex items-start justify-between gap-3 sticky top-0 z-10">
          <div className="flex items-center gap-3 min-w-0">
            <div className="w-10 h-10 rounded-xl bg-cyan-500/20 flex items-center justify-center flex-shrink-0">
              <FlaskConical className="w-5 h-5 text-cyan-300" />
            </div>
            <div className="min-w-0">
              <h3 className="text-base font-bold text-white truncate">{order?.testName ?? testName ?? 'Lab test'}</h3>
              <p className="text-white/50 text-[11px] font-mono truncate">
                {order?.orderNumber ?? ''}{order?.accessionNumber ? ` • ${order.accessionNumber}` : ''}
              </p>
            </div>
          </div>
          <button onClick={onClose} className="w-8 h-8 rounded-lg flex items-center justify-center hover:bg-white/10 flex-shrink-0">
            <X className="w-4 h-4 text-white/70" />
          </button>
        </div>

        <div className="p-6 space-y-4">
          {loading ? (
            <div className="flex items-center justify-center py-12">
              <Loader2 className="w-6 h-6 animate-spin text-cyan-500" />
            </div>
          ) : error && !order ? (
            <div className="rounded-xl p-4 bg-amber-500/10 ring-1 ring-amber-500/20 flex items-start gap-2">
              <AlertTriangle className="w-4 h-4 text-amber-500 mt-0.5 flex-shrink-0" />
              <p className={`text-sm ${text.body}`}>{error}</p>
            </div>
          ) : order ? (
            <>
              {/* Status + priority + who/where */}
              <div className="flex items-center gap-2 flex-wrap">
                <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg ${priorityChip(order.priority)}`}>{order.priority}</span>
                <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg ${sc.className}`}>{sc.label}</span>
                {order.isCritical && (
                  <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg bg-rose-500/20 text-rose-500 ring-1 ring-rose-500/30 inline-flex items-center gap-1">
                    <AlertOctagon className="w-3 h-3" /> Critical
                  </span>
                )}
                {!order.isCritical && order.isAbnormal && (
                  <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg bg-amber-500/15 text-amber-500">Abnormal</span>
                )}
              </div>
              <PatientContextLine
                patientName={order.patientName}
                zone={order.currentZone}
                bedLabel={order.currentBedLabel}
                visitNumber={order.visitNumber}
                className={`text-xs ${text.body}`}
              />
              {order.clinicalIndication && (
                <p className={`text-xs italic ${text.body}`}>“{order.clinicalIndication}”</p>
              )}

              {/* ── Results ── */}
              <div>
                <div className={`text-[10px] font-bold uppercase tracking-wider mb-2 ${text.label}`}>Result</div>
                {!isResulted ? (
                  <div className="rounded-xl p-4 text-center" style={glassInner}>
                    <Beaker className="w-6 h-6 mx-auto mb-2 text-slate-400" />
                    <p className={`text-sm font-bold ${text.heading}`}>Result not yet available</p>
                    <p className={`text-xs ${text.muted}`}>Currently {sc.label.toLowerCase()} — the lab will release the result.</p>
                  </div>
                ) : hasPanel ? (
                  /* Per-analyte (panel) breakdown — each analyte independently flagged. */
                  <div className="rounded-xl overflow-hidden" style={glassInner}>
                    <table className="w-full text-xs">
                      <thead>
                        <tr className={`${text.muted}`}>
                          <th className="text-left font-bold uppercase tracking-wider text-[10px] px-3 py-2">Analyte</th>
                          <th className="text-right font-bold uppercase tracking-wider text-[10px] px-3 py-2">Result</th>
                          <th className="text-right font-bold uppercase tracking-wider text-[10px] px-3 py-2">Reference</th>
                          <th className="text-right font-bold uppercase tracking-wider text-[10px] px-3 py-2">Flag</th>
                        </tr>
                      </thead>
                      <tbody>
                        {components.map((c) => (
                          <tr key={c.analyteName} className="border-t border-white/5">
                            <td className={`px-3 py-2 ${text.body}`}>{c.analyteName}</td>
                            <td className={`px-3 py-2 text-right font-bold ${c.isCritical ? 'text-rose-500' : c.isAbnormal ? 'text-amber-500' : text.heading}`}>
                              {c.resultValue}{c.resultUnit ? ` ${c.resultUnit}` : ''}
                            </td>
                            <td className={`px-3 py-2 text-right ${text.muted}`}>
                              {(c.referenceLow != null || c.referenceHigh != null)
                                ? `${c.referenceLow ?? '–'}–${c.referenceHigh ?? '–'}${c.resultUnit ? ` ${c.resultUnit}` : ''}`
                                : '—'}
                            </td>
                            <td className="px-3 py-2 text-right">
                              {c.isCritical
                                ? <span className="text-[9px] font-bold px-1.5 py-0.5 rounded bg-rose-500/15 text-rose-500">CRIT</span>
                                : c.isAbnormal
                                  ? <span className="text-[9px] font-bold px-1.5 py-0.5 rounded bg-amber-500/15 text-amber-500">ABN</span>
                                  : <span className={`text-[9px] ${text.muted}`}>—</span>}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <div className="rounded-xl p-4" style={glassInner}>
                    <div className={`text-2xl font-extrabold ${order.isCritical ? 'text-rose-500' : order.isAbnormal ? 'text-amber-500' : text.heading}`}>
                      {order.resultValue ?? '—'} {order.resultUnit ?? ''}
                    </div>
                    {order.referenceRangeMin != null && order.referenceRangeMax != null && (
                      <div className={`text-xs mt-1 ${text.muted}`}>
                        Reference: {order.referenceRangeMin} – {order.referenceRangeMax} {order.resultUnit ?? ''}
                      </div>
                    )}
                    {order.criticalValueType && (
                      <div className="text-[11px] mt-1 font-bold text-rose-500">{order.criticalValueType.replace(/_/g, ' ')}</div>
                    )}
                  </div>
                )}
                {order.notes && (
                  <p className={`text-[11px] mt-2 ${text.muted}`}>Lab note: <span className={text.body}>{order.notes}</span></p>
                )}
              </div>

              {/* ── Metadata ── */}
              <div>
                <div className={`text-[10px] font-bold uppercase tracking-wider mb-2 ${text.label}`}>Test run</div>
                <div className={`grid grid-cols-2 gap-x-4 gap-y-2 text-[11px] rounded-xl p-3 ${text.muted}`} style={glassInner}>
                  <Meta label="Ordered by" value={order.orderedByName} text={text} />
                  <Meta label="Ordered at" value={fmt(order.orderedAt)} text={text} />
                  <Meta label="Specimen" value={order.specimenType} text={text} />
                  <Meta label="Collected" value={fmt(order.specimenCollectedAt)} text={text} />
                  <Meta label="Resulted at" value={fmt(order.resultedAt)} text={text} />
                  <Meta label="Entered by" value={order.enteredByName} text={text} />
                  {order.verifiedByName && <Meta label="Verified by" value={order.verifiedByName} text={text} />}
                  {order.turnaroundMinutes != null && <Meta label="Turnaround" value={`${order.turnaroundMinutes} min`} text={text} />}
                </div>
              </div>

              {/* ── Actions the clinician has to take ── */}
              <div>
                <div className={`text-[10px] font-bold uppercase tracking-wider mb-2 ${text.label}`}>Actions</div>

                {needsAck ? (
                  <div className="rounded-xl p-3 bg-rose-500/10 ring-1 ring-rose-500/20 mb-2">
                    <div className="flex items-start gap-2">
                      <AlertOctagon className="w-4 h-4 text-rose-500 mt-0.5 flex-shrink-0" />
                      <div className="min-w-0">
                        <p className={`text-xs font-bold ${text.heading}`}>Critical value — acknowledgement required</p>
                        <p className={`text-[11px] ${text.muted}`}>
                          JCI NPSG.02.03.01: a doctor must acknowledge this panic value with a read-back to close the escalation loop.
                        </p>
                        {canAcknowledge ? (
                          <button
                            onClick={() => setAckOpen(true)}
                            className="mt-2 inline-flex items-center gap-1.5 px-4 py-2 rounded-xl text-[11px] font-bold text-white bg-rose-500 hover:bg-rose-600"
                          >
                            <Phone className="w-3.5 h-3.5" /> Acknowledge with read-back
                          </button>
                        ) : (
                          <p className={`text-[11px] mt-1 font-semibold text-rose-500`}>Awaiting doctor acknowledgement.</p>
                        )}
                      </div>
                    </div>
                  </div>
                ) : order.isCritical && order.criticalValueAcknowledgedAt ? (
                  <div className="rounded-xl p-3 bg-emerald-500/10 ring-1 ring-emerald-500/20 mb-2">
                    <div className="flex items-start gap-2">
                      <CheckCircle2 className="w-4 h-4 text-emerald-500 mt-0.5 flex-shrink-0" />
                      <div className="min-w-0">
                        <p className={`text-xs font-bold ${text.heading}`}>Critical value acknowledged</p>
                        <p className={`text-[11px] ${text.muted}`}>
                          {order.criticalValueNotifiedTo ? `By ${order.criticalValueNotifiedTo} • ` : ''}{fmt(order.criticalValueAcknowledgedAt)}
                        </p>
                        {order.criticalReadbackText && (
                          <p className={`text-[11px] mt-1 italic ${text.body}`}>“{order.criticalReadbackText}”</p>
                        )}
                      </div>
                    </div>
                  </div>
                ) : isResulted && order.isAbnormal ? (
                  <div className="rounded-xl p-3 bg-amber-500/10 ring-1 ring-amber-500/20 mb-2 flex items-start gap-2">
                    <AlertTriangle className="w-4 h-4 text-amber-500 mt-0.5 flex-shrink-0" />
                    <p className={`text-[11px] ${text.body}`}>Abnormal result — review and consider clinical action.</p>
                  </div>
                ) : isResulted ? (
                  <div className="rounded-xl p-3 bg-emerald-500/10 ring-1 ring-emerald-500/20 mb-2 flex items-start gap-2">
                    <ClipboardCheck className="w-4 h-4 text-emerald-500 mt-0.5 flex-shrink-0" />
                    <p className={`text-[11px] ${text.body}`}>Result within reference range — no critical action required.</p>
                  </div>
                ) : null}

                <div className="flex items-center gap-2 flex-wrap">
                  <Link
                    to={chartPath(order.visitId ?? visitId)}
                    className="inline-flex items-center gap-1.5 px-4 py-2 rounded-xl text-[11px] font-bold text-white bg-cyan-600 hover:bg-cyan-700"
                  >
                    <ExternalLink className="w-3.5 h-3.5" /> Open full chart
                  </Link>
                  <button onClick={onClose} className={`px-4 py-2 rounded-xl text-[11px] font-bold ${text.muted} hover:bg-white/5`}>Close</button>
                </div>
              </div>
            </>
          ) : null}
        </div>
      </div>
    </div>

    {/* Read-back acknowledge — rendered as a SIBLING (not a child) of the backdrop
        above so a click on ITS backdrop can't bubble to this modal's onClose. */}
      {ackOpen && order && (
        <AcknowledgeCriticalModal
          order={order}
          acknowledgedByName={actorName}
          onClose={() => setAckOpen(false)}
          onSaved={() => { setAckOpen(false); void load(); onChanged?.(); }}
        />
      )}
    </>
  );
}

function Meta({ label, value, text }: { label: string; value: string | null | undefined; text: any }) {
  return (
    <div className="min-w-0">
      <span className="block text-[9px] uppercase tracking-wider opacity-70">{label}</span>
      <span className={`${text.body} truncate block`}>{value || '—'}</span>
    </div>
  );
}

export default LabTestDetailModal;
