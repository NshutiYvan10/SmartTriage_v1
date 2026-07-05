/**
 * useAckAlert — shared "acknowledge an alert, prompting for a reason if it is CRITICAL" hook.
 *
 * Patient-safety fix (1b): the server rejects a reason-less acknowledgment of a CRITICAL alert
 * (it would silence every escalation reminder). This hook centralises the behaviour so every
 * quick-ack surface (dashboard tiles, notifications, workspace panels) is consistent:
 *   - non-critical alert  → acknowledge immediately
 *   - critical alert w/o a supplied reason → open the AckReasonModal to capture one
 *
 * Usage:
 *   const { requestAck, ackModal } = useAckAlert();
 *   <button onClick={() => requestAck(alert)}>Acknowledge</button>
 *   {ackModal}
 */
import { useCallback, useState } from 'react';
import { useAlertStore } from '@/store/alertStore';
import { AckReasonModal } from '@/components/AckReasonModal';

export interface AckableAlert {
  id: string;
  severity?: string;
  acknowledged?: boolean;
  patientName?: string;
  message?: string;
}

export function useAckAlert() {
  const acknowledgeAlertApi = useAlertStore((s) => s.acknowledgeAlertApi);
  const [pending, setPending] = useState<AckableAlert | null>(null);
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const requestAck = useCallback(
    (alert: AckableAlert, note?: string) => {
      const isCritical = (alert.severity ?? '').toUpperCase() === 'CRITICAL';
      if (isCritical && !(note && note.trim())) {
        setReason('');
        setPending(alert); // open the reason modal; ack happens on confirm
        return;
      }
      void acknowledgeAlertApi(alert.id, note);
    },
    [acknowledgeAlertApi],
  );

  const confirm = useCallback(async () => {
    if (!pending || !reason.trim()) return;
    setSubmitting(true);
    try {
      await acknowledgeAlertApi(pending.id, reason.trim());
    } finally {
      setSubmitting(false);
      setPending(null);
      setReason('');
    }
  }, [pending, reason, acknowledgeAlertApi]);

  const ackModal = (
    <AckReasonModal
      open={!!pending}
      patientName={pending?.patientName}
      message={pending?.message}
      reason={reason}
      onReasonChange={setReason}
      onConfirm={confirm}
      onCancel={() => {
        setPending(null);
        setReason('');
      }}
      submitting={submitting}
    />
  );

  return { requestAck, ackModal };
}
