import type { HospitalScope } from '@/hooks/useHospitalScope';

/**
 * Hospital picker for super-admin admin views (audit, override register,
 * dashboard, …). Renders nothing unless the current user is a super admin with
 * hospitals loaded. Two visual variants: `dark` for coloured header bars,
 * `light` for a plain surface. Drives {@link useHospitalScope}.
 */
export function HospitalScopePicker({
  scope,
  variant = 'light',
  label = 'Viewing hospital',
}: {
  scope: HospitalScope;
  variant?: 'dark' | 'light';
  label?: string;
}) {
  if (!scope.showPicker) return null;
  const dark = variant === 'dark';
  return (
    <label className="inline-flex items-center gap-2 text-xs font-semibold">
      <span className={dark ? 'text-white/70' : 'text-slate-500'}>{label}:</span>
      <select
        value={scope.selectedHospitalId}
        onChange={(e) => scope.setSelectedHospitalId(e.target.value)}
        title="Which hospital to view (national role)"
        className={
          dark
            ? 'px-3 py-2 rounded-xl text-xs font-bold bg-white/10 text-white border border-white/15 focus:outline-none [&>option]:text-slate-800'
            : 'px-3 py-2 rounded-xl text-xs font-bold bg-white text-slate-700 border border-slate-300 focus:outline-none'
        }
      >
        {scope.hospitals.map((h) => (
          <option key={h.id} value={h.id}>{h.name}</option>
        ))}
      </select>
    </label>
  );
}
