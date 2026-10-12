import { create } from "zustand";

export type Theme = "light" | "warm" | "dark";

interface SettingsState {
  theme: Theme;
  setTheme: (theme: Theme) => void;
}

export const useSettingsStore = create<SettingsState>((set) => ({
  theme: "light",
  setTheme: (theme: Theme) => {
    document.documentElement.setAttribute("data-theme", theme);
    const metaTheme = document.querySelector('meta[name="theme-color"]');
    if (metaTheme) {
      const colors: Record<Theme, string> = {
        light: "#FAFAF7",
        warm: "#F4ECD8",
        dark: "#121214"
      };
      metaTheme.setAttribute("content", colors[theme]);
    }
    set({ theme });
  }
}));
