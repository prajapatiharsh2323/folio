import React from "react";
import { useToastStore } from "../state/toast-store.js";
import { X } from "lucide-react";

export const ToastContainer: React.FC = () => {
  const { toasts, dismissToast } = useToastStore();

  if (toasts.length === 0) return null;

  return (
    <div className="fixed bottom-20 md:bottom-6 right-4 left-4 md:left-auto md:max-w-sm z-50 flex flex-col gap-2 pointer-events-none">
      {toasts.map((toast) => (
        <div
          key={toast.id}
          className="pointer-events-auto p-3.5 rounded-xl border border-border bg-surface text-text shadow-xl flex items-center justify-between gap-3 animate-in fade-in slide-in-from-bottom-2"
        >
          <span className="text-xs font-medium leading-snug">{toast.message}</span>
          <div className="flex items-center gap-2 flex-shrink-0">
            {toast.actionLabel && toast.onAction && (
              <button
                onClick={() => {
                  toast.onAction?.();
                  dismissToast(toast.id);
                }}
                className="text-xs font-bold text-accent hover:underline px-1.5 py-0.5 rounded"
              >
                {toast.actionLabel}
              </button>
            )}
            <button
              onClick={() => dismissToast(toast.id)}
              className="text-muted hover:text-text p-1 rounded-md"
            >
              <X className="w-3.5 h-3.5" />
            </button>
          </div>
        </div>
      ))}
    </div>
  );
};
