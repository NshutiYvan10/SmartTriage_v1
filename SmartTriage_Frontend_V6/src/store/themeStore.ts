import { create } from 'zustand';

interface ThemeState {
  isDark: boolean;
  toggle: () => void;
  setDark: (dark: boolean) => void;
}

export const useThemeStore = create<ThemeState>((set) => ({
  isDark: localStorage.getItem('st-dark-mode') === 'true',
  toggle: () =>
    set((state) => {
      const next = !state.isDark;
      localStorage.setItem('st-dark-mode', String(next));
      return { isDark: next };
    }),
  setDark: (dark: boolean) => {
    localStorage.setItem('st-dark-mode', String(dark));
    set({ isDark: dark });
  },
}));
