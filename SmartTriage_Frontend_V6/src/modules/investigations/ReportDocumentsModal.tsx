/* ═══════════════════════════════════════════════════════════════
   ReportDocumentsModal — the report-document panel (attach / view /
   download) for one imaging/ECG investigation, in a modal.

   Used from list surfaces that can't inline the panel per row without
   N upfront fetches: the imaging worklist's "Resulted" section and the
   doctor's investigations roll-up. The panel itself (LabDocuments)
   already does in-app preview + upload; this is just the frame.
   ═══════════════════════════════════════════════════════════════ */

import { X, Paperclip } from 'lucide-react';
import { ModalPortal } from '@/components/ModalPortal';
import { LabDocuments } from '@/modules/lab/LabDocuments';
import { useTheme } from '@/hooks/useTheme';

export function ReportDocumentsModal({
  investigationId, testName, patientName, canManage = false, onClose,
}: {
  investigationId: string;
  testName: string;
  patientName?: string | null;
  canManage?: boolean;
  onClose: () => void;
}) {
  const { isDark, text, glassCard } = useTheme();
  return (
    <ModalPortal>
      <div className="fixed inset-0 z-[90] flex items-center justify-center p-4 bg-black/60" onClick={onClose}>
        <div
          className={`w-full max-w-lg rounded-2xl p-4 space-y-3 ${isDark ? 'bg-slate-900' : 'bg-white'}`}
          style={glassCard}
          onClick={(e) => e.stopPropagation()}
        >
          <div className="flex items-start gap-2">
            <Paperclip className="w-4 h-4 text-cyan-500 mt-0.5 flex-shrink-0" />
            <div className="flex-1 min-w-0">
              <h3 className={`text-sm font-bold ${text.heading}`}>Report documents — {testName}</h3>
              {patientName && <p className={`text-[11px] ${text.muted}`}>{patientName}</p>}
            </div>
            <button type="button" onClick={onClose} className={`p-1.5 rounded-lg ${text.muted} hover:bg-white/5`} aria-label="Close">
              <X className="w-4 h-4" />
            </button>
          </div>
          <LabDocuments investigationId={investigationId} canManage={canManage} />
        </div>
      </div>
    </ModalPortal>
  );
}

export default ReportDocumentsModal;
