/* ═══════════════════════════════════════════════════════════════
   PregnancyBanner — page-level pregnancy signage on the patient chart.

   Rendered ABOVE the tab bar (beside the isolation / hypoglycemia banners) so
   pregnancy is unmissable from EVERY tab — it changes medication safety, imaging,
   dosing, and triage, so any clinician opening the chart must see it before acting.
   Renders only for PREGNANT / POSSIBLY_PREGNANT / BREASTFEEDING; silent otherwise.
   Reads the already-loaded patient — no extra fetch.
   ═══════════════════════════════════════════════════════════════ */

import { Baby, AlertTriangle } from 'lucide-react';
import type { PatientResponse } from '@/api/types';

const CONFIG: Record<string, { label: string; bg: string; text: string; border: string }> = {
  PREGNANT:          { label: 'PREGNANT',          bg: 'bg-pink-500/12',  text: 'text-pink-600',   border: 'border-pink-500/30' },
  POSSIBLY_PREGNANT: { label: 'POSSIBLY PREGNANT', bg: 'bg-fuchsia-500/10', text: 'text-fuchsia-600', border: 'border-fuchsia-500/30' },
  BREASTFEEDING:     { label: 'BREASTFEEDING',     bg: 'bg-purple-500/10', text: 'text-purple-600', border: 'border-purple-500/25' },
};

export function PregnancyBanner({ patient }: { patient: PatientResponse | null | undefined }) {
  const status = patient?.pregnancyStatus;
  if (!status || !CONFIG[status]) return null;
  const c = CONFIG[status];
  const weeks = patient?.gestationalAgeWeeks;
  const trimester = weeks == null ? null : weeks < 14 ? '1st trimester' : weeks < 28 ? '2nd trimester' : '3rd trimester';

  return (
    <div className={`rounded-2xl px-4 py-3 ${c.bg} border ${c.border} flex items-center gap-3 flex-wrap animate-fade-up`}>
      <Baby className={`w-5 h-5 shrink-0 ${c.text}`} />
      <span className={`text-xs font-black uppercase tracking-wide ${c.text}`}>{c.label}</span>
      {weeks != null && (
        <span className={`text-[11px] font-semibold ${c.text}`}>
          {weeks} weeks{trimester ? ` · ${trimester}` : ''}
        </span>
      )}
      <span className={`ml-auto inline-flex items-center gap-1.5 text-[10px] font-bold ${c.text}`}>
        <AlertTriangle className="w-3.5 h-3.5" />
        Check medication safety, imaging &amp; dosing
      </span>
    </div>
  );
}
