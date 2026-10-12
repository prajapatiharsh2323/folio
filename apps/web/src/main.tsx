import React from "react";
import ReactDOM from "react-dom/client";
import { App } from "./app/App.js";
import "./styles/tokens.css";

import { useToastStore } from "./state/toast-store.js";

// Register PWA service worker with update notifications (Checklist #7)
if ("serviceWorker" in navigator) {
  import("virtual:pwa-register").then(({ registerSW }) => {
    const updateSW = registerSW({
      immediate: true,
      onNeedRefresh() {
        useToastStore.getState().showToast({
          message: "New version of Folio is available.",
          actionLabel: "Update",
          onAction: () => {
            updateSW(true);
          },
          durationMs: 15000
        });
      },
      onOfflineReady() {
        useToastStore.getState().showToast({
          message: "Folio is ready for offline reading.",
          durationMs: 3000
        });
      }
    });
  }).catch(() => {
    // virtual:pwa-register might not be available in non-PWA dev/test runs
  });
}

// Windows & Android File Handlers API launchQueue (Checklist #1 & Section 9)
if ("launchQueue" in window) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  (window as any).launchQueue.setConsumer(async (launchParams: any) => {
    if (launchParams.files && launchParams.files.length > 0) {
      window.location.hash = "#/import";
    }
  });
}

// Request persistent storage on startup as per Section 4 Rule 4
if (typeof navigator !== "undefined" && navigator.storage?.persist) {
  navigator.storage.persist().then((persisted) => {
    console.log("Storage persistence status:", persisted);
  }).catch((err) => {
    console.warn("Storage persistence request failed:", err);
  });
}

const rootElement = document.getElementById("root");
if (rootElement) {
  ReactDOM.createRoot(rootElement).render(
    <React.StrictMode>
      <App />
    </React.StrictMode>
  );
}
