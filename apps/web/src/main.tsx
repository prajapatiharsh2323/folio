import React from "react";
import ReactDOM from "react-dom/client";
import { App } from "./app/App.js";
import "./styles/tokens.css";

import * as pdfjsLib from "pdfjs-dist/legacy/build/pdf.mjs";
import pdfjsWorkerUrl from "pdfjs-dist/legacy/build/pdf.worker.mjs?url";
import { useToastStore } from "./state/toast-store.js";

// Initialize PDF.js worker to prevent fake worker warnings
if (typeof window !== "undefined" && !pdfjsLib.GlobalWorkerOptions.workerSrc) {
  pdfjsLib.GlobalWorkerOptions.workerSrc = pdfjsWorkerUrl;
}

// Modern Chromium deprecates unload listeners and logs Permissions Policy violations.
// Map 'unload' to 'pagehide' for libraries (such as epubjs) that listen for window unload.
if (typeof window !== "undefined") {
  const origAddEventListener = window.addEventListener;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const patchedAddEventListener = function (this: Window, type: string, listener: any, options?: any) {
    if (type === "unload") {
      return origAddEventListener.call(this, "pagehide", listener, options);
    }
    return origAddEventListener.call(this, type, listener, options);
  };
  Object.setPrototypeOf(patchedAddEventListener, origAddEventListener);
  window.addEventListener = patchedAddEventListener as typeof window.addEventListener;
}

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
      const pendingFiles: File[] = [];
      for (const handle of launchParams.files) {
        try {
          const file = await handle.getFile();
          pendingFiles.push(file);
        } catch (err) {
          console.warn("main.tsx: failed to read launchQueue file handle:", err);
        }
      }
      if (pendingFiles.length > 0) {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (window as any).__FOLIO_QUEUED_FILES__ = pendingFiles;
        window.dispatchEvent(new CustomEvent("folio:files-queued", { detail: pendingFiles }));
      }
      window.location.hash = "#/import";
      window.dispatchEvent(new HashChangeEvent("hashchange"));
    }
  });
}

// Global clip delivery nonce and listener so clips delivered at any screen are captured safely
if (typeof window !== "undefined") {
  const globalNonce = crypto.randomUUID();
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  (window as any).__FOLIO_CLIP_NONCE__ = (window as any).__FOLIO_CLIP_NONCE__ || globalNonce;

  window.addEventListener("message", (event) => {
    if (event.source !== window || event.origin !== window.location.origin) return;
    if (!event.data || event.data.type !== "FOLIO_CLIP_DELIVERY") return;
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    if (event.data.nonce && event.data.nonce === (window as any).__FOLIO_CLIP_NONCE__) {
      const payload = event.data.payload;
      if (payload && payload.contentHtml) {
        try {
          const queue = JSON.parse(sessionStorage.getItem("folio:raw-clips") || "[]");
          queue.push(payload);
          sessionStorage.setItem("folio:raw-clips", JSON.stringify(queue));
          window.dispatchEvent(new CustomEvent("folio:clip-received", { detail: payload }));
        } catch (err) {
          console.warn("main.tsx: failed to persist incoming clip in sessionStorage:", err);
        }
      }
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
