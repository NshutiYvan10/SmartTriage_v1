/**
 * AcceptTransferDialog — the "Accept + pick bed" step of the zone-transfer
 * state machine, shared by PendingTransfersDashboard and the per-visit
 * PendingTransferBanner on VisitDetailPage.
 *
 * Clinical model (mirrors real ED practice): accepting an escalation is a
 * receiving-side decision that includes WHERE the patient lands. The dialog
 * loads the free beds in the transfer's target zone and lets the accepter
 * choose one — the backend then performs zone change + bed move + monitor-
 * session hop in one transaction. Two honest fallbacks are always visible:
 *   - "Accept without bed move" (zone + ownership change now, physical move
 *     later from Bed Management) when the bay isn't ready;
 *   - a pointer to "Treat in place" / "Decline" when the zone is full —
 *     those stay on the caller's surface, not in this dialog.
 */
import { useEffect, useState } from 'react';
import {
  AlertTriangle, ArrowRight, BedDouble, CheckCircle2, Loader2,
  MonitorSpeaker, X,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { bedsApi } from '@/api/beds';
import type { BedResponse } from '@/api/types';
import { zoneTransferApi, type ZoneTransferResponse } from '@/api/zoneTransfers';

interface Props {
  transfer: ZoneTransferResponse;
  /** Called after a successful accept — reload whatever list/banner spawned us. */
  onAccepted: () => Promise<void> | void;
  onClose: () => void;
}

/** Sentinel for the "no bed move" radio choice. */
const ZONE_ONLY = '__zone_only__';

export default function AcceptTransferDialog({ transfer, onAccepted, onClose }: Props) {
  const { glassCard, isDark, text } = useTheme();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId ?? '';

  const [beds, setBeds] = useState<BedResponse[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [handover, setHandover] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const isResus = transfer.toZone === 'RESUS';

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const list = await bedsApi.getAvailableInZone(hospitalId, transfer.toZone);
        if (cancelled) return;
        const arr = Array.isArray(list) ? list : [];
        setBeds(arr);
        // Preselect the first monitored bed — for an escalation the
        // monitored bay is almost always the right answer; the accepter
        // can still tap another card or "no bed move".
        const monitored = arr.find((b) => b.assignedDeviceId != null);
        setSelected(monitored?.id ?? arr[0]?.id ?? ZONE_ONLY);
      } catch (e: any) {
        if (cancelled) return;
        setLoadError(e?.message ?? 'Failed to load available beds');
        setBeds([]);
        setSelected(ZONE_ONLY);
      }
    })();
    return () => { cancelled = true; };
  }, [hospitalId, transfer.toZone]);

  const handleAccept = async () => {
    if (submitting || selected == null) return;
    setSubmitting(true);
    setError(null);
    try {
      await zoneTransferApi.accept(transfer.id, {
        handoverNote: handover.trim() || undefined,
        destinationBedId: selected === ZONE_ONLY ? undefined : selected,
      });
      await onAccepted();
      onClose();
    } catch (e: any) {
      setError(e?.message ?? 'Failed to accept transfer');
      setSubmitting(false);
    }
  };

  const accent = isResus
    ? { chip: 'bg-red-500/20', icon: 'text-red-400', ring: 'ring-red-500', border: 'border-red-500' }
    : { chip: 'bg-cyan-500/20', icon: 'text-cyan-400', ring: 'ring-cyan-500', border: 'border-cyan-500' };

  return (
    <div
      className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm"
      style={{ background: 'var(--modal-backdrop)' }}
      onClick={onClose}
    >
      <div
        style={glassCard}
        className="w-full max-w-lg rounded-2xl shadow-2xl overflow-hidden mx-4 animate-scale-in flex flex-col max-h-[90vh]"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-5 py-4 flex items-center justify-between flex-shrink-0">
          <div className="flex items-center gap-3">
            <div className={`w-9 h-9 rounded-xl ${accent.chip} flex items-center justify-center`}>
              <BedDouble className={`w-5 h-5 ${accent.icon}`} />
            </div>
            <div>
              <h3 className="text-sm font-bold text-white">Accept transfer to {transfer.toZone}</h3>
              <p className="text-[10px] text-white/50 mt-0.5 flex items-center gap-1">
                {transfer.patientName ?? transfer.visitNumber}
                <span className="text-white/30">·</span>
                {transfer.fromZone ?? '—'}
                {transfer.fromBedCode ? ` (${transfer.fromBedCode})` : ''}
                <ArrowRight className="w-2.5 h-2.5" />
                {transfer.toZone}
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="w-8 h-8 rounded-xl bg-white/15 hover:bg-white/25 flex items-center justify-center"
            aria-label="Close"
          >
            <X className="w-4 h-4 text-white" />
          </button>
        </div>

        {/* Body */}
        <div className="px-5 py-4 space-y-4 overflow-y-auto">
          <p className={`text-xs leading-relaxed ${text.body}`}>
            Choose the bed the patient is being moved to. The current bed is
            released for cleaning and, if the destination bed has a monitor,
            the monitoring session follows the patient automatically — the
            chart stays one continuous timeline.
          </p>

          {/* Bed picker */}
          {beds == null ? (
            <div className="py-6 text-center">
              <Loader2 className={`w-5 h-5 mx-auto animate-spin ${accent.icon}`} />
              <p className={`text-xs mt-2 ${text.muted}`}>Loading free {transfer.toZone} beds…</p>
            </div>
          ) : (
            <div className="space-y-2">
              {loadError && (
                <div className={`rounded-xl p-2.5 border border-red-500/30 bg-red-500/10 text-[11px] ${isDark ? 'text-red-300' : 'text-red-700'}`}>
                  {loadError}
                </div>
              )}
              {beds.length === 0 && !loadError && (
                <div className="rounded-xl bg-amber-500/10 border border-amber-500/30 px-3 py-3 flex items-start gap-2.5">
                  <AlertTriangle className="w-4 h-4 text-amber-500 flex-shrink-0 mt-0.5" />
                  <p className={`text-xs leading-relaxed ${isDark ? 'text-amber-200' : 'text-amber-800'}`}>
                    <span className="font-bold">No free beds in {transfer.toZone}.</span>{' '}
                    You can still accept without a bed move and place the patient
                    when a bay frees up — or close this and use{' '}
                    <span className="font-semibold">Treat in place</span> to bring
                    the team and equipment to the patient instead.
                  </p>
                </div>
              )}

              <div className="grid grid-cols-2 gap-2">
                {beds.map((b) => {
                  const isSel = selected === b.id;
                  return (
                    <button
                      key={b.id}
                      type="button"
                      onClick={() => setSelected(b.id)}
                      className={`text-left rounded-xl px-3 py-2.5 border transition-all ${
                        isSel
                          ? `${accent.border} ring-2 ${accent.ring}/30 ${isDark ? 'bg-white/10' : 'bg-white'}`
                          : `${isDark ? 'border-slate-600 hover:bg-white/5' : 'border-slate-200 hover:bg-slate-50'}`
                      }`}
                    >
                      <div className="flex items-center justify-between gap-2">
                        <span className={`text-sm font-bold ${text.heading}`}>{b.code}</span>
                        {isSel && <CheckCircle2 className={`w-4 h-4 ${isResus ? 'text-red-500' : 'text-cyan-500'}`} />}
                      </div>
                      {b.label && (
                        <p className={`text-[10px] mt-0.5 truncate ${text.muted}`}>{b.label}</p>
                      )}
                      <p className={`text-[10px] mt-1 inline-flex items-center gap-1 font-semibold ${
                        b.assignedDeviceId
                          ? 'text-emerald-500'
                          : text.muted
                      }`}>
                        <MonitorSpeaker className="w-3 h-3" />
                        {b.assignedDeviceId
                          ? (b.assignedDeviceName ?? 'Monitor mounted')
                          : 'No monitor'}
                      </p>
                    </button>
                  );
                })}

                {/* Zone-only option, always available */}
                <button
                  type="button"
                  onClick={() => setSelected(ZONE_ONLY)}
                  className={`text-left rounded-xl px-3 py-2.5 border border-dashed transition-all ${
                    selected === ZONE_ONLY
                      ? `${accent.border} ring-2 ${accent.ring}/30 ${isDark ? 'bg-white/10' : 'bg-white'}`
                      : `${isDark ? 'border-slate-600 hover:bg-white/5' : 'border-slate-300 hover:bg-slate-50'}`
                  }`}
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className={`text-sm font-bold ${text.heading}`}>No bed move yet</span>
                    {selected === ZONE_ONLY && <CheckCircle2 className={`w-4 h-4 ${isResus ? 'text-red-500' : 'text-cyan-500'}`} />}
                  </div>
                  <p className={`text-[10px] mt-0.5 ${text.muted}`}>
                    Take over the patient now; move the bed later from Bed
                    Management.
                  </p>
                </button>
              </div>
            </div>
          )}

          {/* SBAR handover */}
          <div>
            <label className={`text-[10px] font-bold uppercase tracking-wider ${text.label}`}>
              SBAR handover (optional)
            </label>
            <textarea
              value={handover}
              onChange={(e) => setHandover(e.target.value)}
              rows={2}
              placeholder="Situation · Background · Assessment · Recommendation"
              className={`w-full mt-1 px-2.5 py-2 text-sm rounded-xl border focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                isDark
                  ? 'border-slate-600 bg-slate-800/60 text-slate-100 placeholder:text-slate-500'
                  : 'border-slate-300 bg-white text-slate-800'
              }`}
            />
          </div>

          {error && (
            <p className="text-[11px] text-red-500 font-semibold">{error}</p>
          )}
        </div>

        {/* Footer */}
        <div className={`px-5 py-3.5 flex items-center justify-end gap-2 border-t flex-shrink-0 ${isDark ? 'border-slate-700' : 'border-slate-200'}`}>
          <button
            type="button"
            onClick={onClose}
            disabled={submitting}
            className={`px-3.5 py-2 text-xs font-bold rounded-xl border ${isDark ? 'border-slate-600 text-slate-200 hover:bg-white/5' : 'border-slate-300 text-slate-700 hover:bg-slate-100'}`}
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={handleAccept}
            disabled={submitting || beds == null}
            className={`inline-flex items-center gap-1.5 px-4 py-2 text-xs font-bold rounded-xl text-white disabled:opacity-50 ${
              isResus ? 'bg-red-600 hover:bg-red-700' : 'bg-cyan-600 hover:bg-cyan-700'
            }`}
          >
            {submitting
              ? <Loader2 className="w-3.5 h-3.5 animate-spin" />
              : <CheckCircle2 className="w-3.5 h-3.5" />}
            {selected === ZONE_ONLY
              ? 'Accept (zone only)'
              : 'Accept & move patient'}
          </button>
        </div>
      </div>
    </div>
  );
}
