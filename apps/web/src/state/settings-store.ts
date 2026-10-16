import { create } from "zustand";

export type Theme = "light" | "warm" | "dark" | "true-black" | "system";

export type EffectiveTheme = Exclude<Theme, "system">;

interface SettingsState {
  theme: Theme;
  setTheme: (theme: Theme) => void;
}

const THEME_STORAGE_KEY = "folio-theme";

const getSavedTheme = (): Theme => {
  if (typeof localStorage === "undefined") return "light";
  const saved = localStorage.getItem(THEME_STORAGE_KEY) as Theme | null;
  if (
    saved === "light" ||
    saved === "warm" ||
    saved === "dark" ||
    saved === "true-black" ||
    saved === "system"
  ) {
    return saved;
  }
  return "light";
};

export const resolveEffectiveTheme = (theme: Theme): EffectiveTheme => {
  if (theme === "system") {
    if (typeof window !== "undefined" && window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches) {
      return "dark";
    }
    return "light";
  }
  return theme;
};

export const applyThemeToDom = (theme: Theme) => {
  if (typeof document === "undefined") return;
  const effective = resolveEffectiveTheme(theme);
  document.documentElement.setAttribute("data-theme", effective);
  document.documentElement.setAttribute("data-theme-setting", theme);
  const metaTheme = document.querySelector('meta[name="theme-color"]');
  if (metaTheme) {
    const colors: Record<EffectiveTheme, string> = {
      light: "#FAFAF7",
      warm: "#F4ECD8",
      dark: "#121214",
      "true-black": "#000000"
    };
    metaTheme.setAttribute("content", colors[effective]);
  }
};

const initialTheme = getSavedTheme();
applyThemeToDom(initialTheme);

export const useSettingsStore = create<SettingsState>((set) => ({
  theme: initialTheme,
  setTheme: (theme: Theme) => {
    applyThemeToDom(theme);
    if (typeof localStorage !== "undefined") {
      localStorage.setItem(THEME_STORAGE_KEY, theme);
    }
    set({ theme });
  }
}));

// Listen for OS color scheme changes when theme is set to "system"
if (typeof window !== "undefined" && window.matchMedia) {
  const mql = window.matchMedia("(prefers-color-scheme: dark)");
  const handler = () => {
    const current = useSettingsStore.getState().theme;
    if (current === "system") {
      applyThemeToDom("system");
    }
  };
  type LegacyMql = MediaQueryList & {
    addEventListener?: MediaQueryList["addEventListener"];
    addListener?: (listener: (ev: MediaQueryListEvent) => void) => void;
  };
  const lmql = mql as LegacyMql;
  if (lmql.addEventListener) {
    lmql.addEventListener("change", handler);
  } else if (lmql.addListener) {
    lmql.addListener(handler);
  }
}
