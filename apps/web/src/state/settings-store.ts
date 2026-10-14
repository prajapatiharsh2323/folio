import { create } from "zustand";

export type Theme = "light" | "warm" | "dark" | "system";

interface SettingsState {
  theme: Theme;
  setTheme: (theme: Theme) => void;
}

const THEME_STORAGE_KEY = "folio-theme";

const getSavedTheme = (): Theme => {
  if (typeof localStorage === "undefined") return "light";
  const saved = localStorage.getItem(THEME_STORAGE_KEY) as Theme | null;
  if (saved === "light" || saved === "warm" || saved === "dark" || saved === "system") {
    return saved;
  }
  return "light";
};

export const resolveEffectiveTheme = (theme: Theme): "light" | "warm" | "dark" => {
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
    const colors: Record<"light" | "warm" | "dark", string> = {
      light: "#FAFAF7",
      warm: "#F4ECD8",
      dark: "#121214"
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
  if (mql.addEventListener) {
    mql.addEventListener("change", handler);
  } else if ((mql as any).addListener) {
    (mql as any).addListener(handler);
  }
}
