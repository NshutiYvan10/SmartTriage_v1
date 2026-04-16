import { useState } from 'react';
import {
  ChevronDown, Check, Shield,
} from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { ROLE_META } from '@/types/roles';
import type { UserRole } from '@/types/roles';

const ALL_ROLES: UserRole[] = [
  'SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'NURSE', 'TRIAGE_NURSE', 'REGISTRAR', 'PARAMEDIC', 'LAB_TECHNICIAN', 'READ_ONLY',
];

/**
 * Floating pill that lets developers (or demo users) switch roles instantly.
 * Positioned at the bottom-right of the viewport.
 */
export function RoleSwitcher() {
  const user = useAuthStore((s) => s.user);
  const switchRole = useAuthStore((s) => s.switchRole);
  const [open, setOpen] = useState(false);

  if (!user) return null;

  const meta = ROLE_META[user.role];

  return (
    <div className="fixed bottom-5 right-5 z-[100]">
      {/* Dropdown list */}
      {open && (
        <div
          className="absolute bottom-14 right-0 w-64 rounded-2xl overflow-hidden shadow-2xl border border-gray-200 bg-white animate-fade-in"
          style={{ boxShadow: '0 20px 60px rgba(0,0,0,0.18)' }}
        >
          <div className="px-4 py-3 border-b border-gray-100 bg-gray-50">
            <p className="text-[11px] font-bold uppercase tracking-wider text-gray-400">Switch Role</p>
          </div>
          <div className="py-1.5">
            {ALL_ROLES.map((role) => {
              const rm = ROLE_META[role];
              const active = user.role === role;
              return (
                <button
                  key={role}
                  onClick={() => { switchRole(role); setOpen(false); }}
                  className={`w-full flex items-center gap-3 px-4 py-2.5 text-left transition-all duration-200 ${
                    active ? 'bg-gray-50' : 'hover:bg-gray-50'
                  }`}
                >
                  <div className={`w-2.5 h-2.5 rounded-full ${rm.color}`} />
                  <div className="flex-1 min-w-0">
                    <span className="text-sm font-semibold text-gray-900 block">{rm.label}</span>
                    <span className="text-[11px] text-gray-400 block truncate">{rm.description}</span>
                  </div>
                  {active && <Check className="w-4 h-4 text-cyan-500 shrink-0" />}
                </button>
              );
            })}
          </div>
        </div>
      )}

      {/* Trigger pill */}
      <button
        onClick={() => setOpen(!open)}
        className={`flex items-center gap-2.5 pl-3 pr-4 py-2.5 rounded-full shadow-xl border transition-all duration-300 hover:shadow-2xl hover:-translate-y-0.5 ${
          open ? 'bg-gray-900 text-white border-gray-700' : 'bg-white text-gray-900 border-gray-200'
        }`}
      >
        <div className={`w-7 h-7 rounded-full ${meta.color} flex items-center justify-center`}>
          <Shield className="w-3.5 h-3.5 text-white" />
        </div>
        <span className="text-sm font-bold">{meta.label}</span>
        <ChevronDown className={`w-4 h-4 transition-transform duration-300 ${open ? 'rotate-180' : ''} ${
          open ? 'text-gray-400' : 'text-gray-500'
        }`} />
      </button>
    </div>
  );
}
