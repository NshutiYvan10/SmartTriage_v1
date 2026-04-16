import { useEffect } from 'react';
import { useThemeStore } from '@/store/themeStore';
import type { CSSProperties } from 'react';

/* ═══════════════════════════════════════════════════════════════
   useTheme — Global dark-mode hook
   
   Mirrors the landing page's dark color palette:
     bg:      #020b14  (void)
     card-bg: rgba(12,74,110,0.12) + blur(24px)
     border:  rgba(2,132,199,0.22)
     accent:  #0284c7 → #67e8f9  (cyan range)
   ═══════════════════════════════════════════════════════════════ */

export interface ThemeStyles {
  isDark: boolean;
  toggle: () => void;

  /** Main glass card — replaces the inline glassCard constant everywhere */
  glassCard: CSSProperties;

  /** Inner / nested card or input wrapper */
  glassInner: CSSProperties;

  /** Lighter patient-card glass (monitoring) */
  glassPatientCard: CSSProperties;

  /** Vital tile glass (monitoring) */
  glassVitalTile: CSSProperties;

  /** Expanded section glass bg (monitoring) */
  glassExpandedBg: CSSProperties;

  /** Page-header dark banner gradient */
  headerGradient: string;

  /** Body / page background */
  pageBg: string;

  /** Text color classes */
  text: {
    heading: string;   // text-slate-800 / text-white
    body: string;      // text-slate-500 / text-slate-300
    muted: string;     // text-slate-400 / text-slate-400
    label: string;     // text-slate-700 / text-slate-200
    accent: string;    // text-cyan-600  / text-cyan-400
  };

  /** Card className additions (rounded, overflow, etc.) */
  cardClass: string;
}

export function useTheme(): ThemeStyles {
  const isDark = useThemeStore((s) => s.isDark);
  const toggle = useThemeStore((s) => s.toggle);

  // Sync the <html> class and color-scheme for scrollbars / native elements
  useEffect(() => {
    const html = document.documentElement;
    if (isDark) {
      html.classList.add('dark');
      html.style.colorScheme = 'dark';
    } else {
      html.classList.remove('dark');
      html.style.colorScheme = 'light';
    }
  }, [isDark]);

  if (isDark) {
    return {
      isDark,
      toggle,

      glassCard: {
        background: 'rgba(12, 74, 110, 0.12)',
        backdropFilter: 'blur(24px)',
        WebkitBackdropFilter: 'blur(24px)',
        border: '1px solid rgba(2, 132, 199, 0.22)',
        boxShadow:
          '0 8px 32px rgba(0, 0, 0, 0.25), inset 0 1px 0 rgba(255, 255, 255, 0.05)',
      },

      glassInner: {
        background: 'rgba(12, 74, 110, 0.18)',
        backdropFilter: 'blur(12px)',
        WebkitBackdropFilter: 'blur(12px)',
        border: '1px solid rgba(2, 132, 199, 0.18)',
        boxShadow: '0 2px 8px rgba(0, 0, 0, 0.15)',
      },

      glassPatientCard: {
        background: 'rgba(12, 74, 110, 0.10)',
        backdropFilter: 'blur(20px)',
        WebkitBackdropFilter: 'blur(20px)',
        boxShadow: '0 4px 24px rgba(0, 0, 0, 0.20), inset 0 1px 0 rgba(255, 255, 255, 0.03)',
      },

      glassVitalTile: {
        background: 'rgba(12, 74, 110, 0.14)',
        backdropFilter: 'blur(12px)',
        WebkitBackdropFilter: 'blur(12px)',
        border: '1px solid rgba(2, 132, 199, 0.18)',
        boxShadow: '0 2px 12px rgba(0, 0, 0, 0.15), inset 0 1px 0 rgba(255, 255, 255, 0.03)',
      },

      glassExpandedBg: {
        background: 'rgba(8, 47, 73, 0.25)',
        backdropFilter: 'blur(16px)',
        WebkitBackdropFilter: 'blur(16px)',
      },

      headerGradient: 'bg-gradient-to-r from-[#0c1929] to-[#0a1525]',
      pageBg:
        'linear-gradient(135deg, #020b14 0%, #041525 25%, #030e1c 50%, #041525 75%, #020b14 100%)',

      text: {
        heading: 'text-white',
        body: 'text-slate-300',
        muted: 'text-slate-400',
        label: 'text-slate-200',
        accent: 'text-cyan-400',
      },

      cardClass: 'rounded-2xl',
    };
  }

  // ── LIGHT (default) ──
  return {
    isDark,
    toggle,

    glassCard: {
      background: 'rgba(255, 255, 255, 0.55)',
      backdropFilter: 'blur(24px)',
      WebkitBackdropFilter: 'blur(24px)',
      border: '1px solid rgba(255, 255, 255, 0.6)',
      boxShadow:
        '0 8px 32px rgba(0, 0, 0, 0.04), inset 0 1px 0 rgba(255, 255, 255, 0.8)',
    },

    glassInner: {
      background: 'rgba(255, 255, 255, 0.6)',
      border: '1px solid rgba(203, 213, 225, 0.4)',
      boxShadow: '0 2px 8px rgba(0, 0, 0, 0.04), 0 1px 2px rgba(0, 0, 0, 0.03)',
    },

    glassPatientCard: {
      background: 'rgba(255, 255, 255, 0.45)',
      backdropFilter: 'blur(20px)',
      WebkitBackdropFilter: 'blur(20px)',
      boxShadow: '0 4px 24px rgba(0, 0, 0, 0.04), inset 0 1px 0 rgba(255, 255, 255, 0.7)',
    },

    glassVitalTile: {
      background: 'rgba(255, 255, 255, 0.55)',
      backdropFilter: 'blur(12px)',
      WebkitBackdropFilter: 'blur(12px)',
      border: '1px solid rgba(255, 255, 255, 0.6)',
      boxShadow: '0 2px 12px rgba(0, 0, 0, 0.03), inset 0 1px 0 rgba(255, 255, 255, 0.8)',
    },

    glassExpandedBg: {
      background: 'rgba(248, 250, 252, 0.35)',
      backdropFilter: 'blur(16px)',
      WebkitBackdropFilter: 'blur(16px)',
    },

    headerGradient: 'bg-gradient-to-r from-slate-800 to-slate-700',
    pageBg:
      'linear-gradient(135deg, #fafbfd 0%, #f3f6fb 25%, #f0f4f9 50%, #f5f8fc 75%, #fafbfd 100%)',

    text: {
      heading: 'text-slate-800',
      body: 'text-slate-500',
      muted: 'text-slate-400',
      label: 'text-slate-700',
      accent: 'text-cyan-600',
    },

    cardClass: 'rounded-2xl',
  };
}
