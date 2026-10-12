import { create } from "zustand";

interface Toast {
  id: string;
  message: string;
  actionLabel?: string;
  onAction?: () => void;
  durationMs?: number;
}

interface ToastState {
  toasts: Toast[];
  showToast: (opts: Omit<Toast, "id">) => string;
  dismissToast: (id: string) => void;
}

export const useToastStore = create<ToastState>((set) => ({
  toasts: [],
  showToast: (opts) => {
    const id = crypto.randomUUID();
    const newToast: Toast = { id, ...opts };
    set((state) => ({ toasts: [...state.toasts, newToast] }));

    const duration = opts.durationMs || 4000;
    setTimeout(() => {
      set((state) => ({ toasts: state.toasts.filter((t) => t.id !== id) }));
    }, duration);

    return id;
  },
  dismissToast: (id) => {
    set((state) => ({ toasts: state.toasts.filter((t) => t.id !== id) }));
  }
}));
