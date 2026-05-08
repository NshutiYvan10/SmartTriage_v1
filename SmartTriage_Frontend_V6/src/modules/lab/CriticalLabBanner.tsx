/* ═══════════════════════════════════════════════════════════════
   Critical Lab Banner — surfaces unacknowledged critical lab values
   on a doctor's dashboard. Clicking "Acknowledge" opens the read-back
   modal which posts the JCI-aligned attestation.

   Hidden when there are no unacknowledged criticals so it costs zero
   real estate when the lab is quiet. Updates live via the
   /topic/lab/{hospitalId} subscription that is already firing for
   the lab tech dashboard.
   ═══════════════════════════════════════════════════════════════ */

import { useCallback, useEffect, useState } from 'react';
import { AlertOctagon, Phone, ChevronDown, ChevronUp } from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { labApi } from '@/api/lab';
import type { LabOrder } from '@/api/lab';
import { subscribeToLabOrders } from '@/api/websocket';
import { useTheme } from '@/hooks/useTheme';
import { AcknowledgeCriticalModal } from './AcknowledgeCriticalModal';

export function CriticalLabBanner() {
  const { isDark } = useTheme();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  const docName = user?.fullName ?? '';

  const [items, setItems] = useState<LabOrder[]>([]);
  const [expanded, setExpanded] = useState(false);
  const [ackTarget, setAckTarget] = useState<LabOrder | null>(null);

  const load = useCallback(async () => {
    if (!hospitalId) return;
    try {
      // The /critical endpoint returns a CriticalValueResponse list — to
      // keep the banner simple we treat the payload as LabOrder-shaped
      // (the fields we touch — id, testName, orderNumber, resultValue,
      // resultUnit, criticalValueType — are present on both DTOs). For
      // strict typing we'd add a dedicated endpoint; this is the Phase
      // 1 trade-off documented in the design.
      const data = (await labApi.getCritical(hospitalId)) as unknown as LabOrder[];
      setItems(data || []);
    } catch (err) {
      console.error('[CriticalLabBanner] load failed:', err);
    }
  }, [hospitalId]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    if (!hospitalId) return;
    const unsub = subscribeToLabOrders(hospitalId, () => {
      // Cheap: any lab event re-fetches the unacked list.
      load();
    });
    return () => unsub();
  }, [hospitalId, load]);

  if (items.length === 0) return null;

  return (
    <>
      <div className="rounded-2xl bg-gradient-to-r from-rose-600 to-rose-500 text-white shadow-lg ring-2 ring-rose-300/40 animate-pulse-slow overflow-hidden">
        <div className="px-5 py-4">
          <div className="flex items-center justify-between gap-3">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-white/20 flex items-center justify-center">
                <AlertOctagon className="w-5 h-5" />
              </div>
              <div>
                <div className="text-sm font-bold">
                  {items.length} critical lab {items.length === 1 ? 'result' : 'results'} awaiting acknowledgement
                </div>
                <div className="text-[11px] text-white/80">
                  Tap to acknowledge with read-back (JCI NPSG.02.03.01).
                </div>
              </div>
            </div>
            <button
              onClick={() => setExpanded((e) => !e)}
              className="px-3 py-1.5 rounded-lg bg-white/15 hover:bg-white/25 text-xs font-bold inline-flex items-center gap-1"
            >
              {expanded ? <ChevronUp className="w-3.5 h-3.5" /> : <ChevronDown className="w-3.5 h-3.5" />}
              {expanded ? 'Collapse' : 'Show'}
            </button>
          </div>

          {expanded && (
            <div className="mt-3 space-y-2">
              {items.map((o) => (
                <div
                  key={o.id}
                  className="flex items-center justify-between gap-3 rounded-xl bg-white/10 px-3 py-2"
                >
                  <div className="min-w-0">
                    <div className="text-sm font-bold truncate">{o.testName}</div>
                    <div className="text-[11px] text-white/85 truncate">
                      {o.orderNumber} • {o.resultValue ?? ''} {o.resultUnit ?? ''}
                      {o.criticalValueType ? ` • ${o.criticalValueType.replace(/_/g, ' ')}` : ''}
                    </div>
                  </div>
                  <button
                    onClick={() => setAckTarget(o)}
                    className="shrink-0 inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-white text-rose-600 hover:bg-white/90 text-[11px] font-bold"
                  >
                    <Phone className="w-3 h-3" /> Acknowledge
                  </button>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {ackTarget && (
        <AcknowledgeCriticalModal
          order={ackTarget}
          acknowledgedByName={docName}
          onClose={() => setAckTarget(null)}
          onSaved={() => { setAckTarget(null); load(); }}
        />
      )}
    </>
  );
}
