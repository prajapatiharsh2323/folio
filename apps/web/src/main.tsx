import React from "react";
import ReactDOM from "react-dom/client";
import { App } from "./app/App.js";
import "./styles/tokens.css";

// Register PWA service worker with update notifications
if ("serviceWorker" in navigator) {
  import("virtual:pwa-register").then(({ registerSW }) => {
    registerSW({
      immediate: true,
      onNeedRefresh() {
        console.log("Folio update available.");
      },
      onOfflineReady() {
        console.log("Folio is ready for offline reading.");
      },
    });
  }).catch(() => {
    // virtual:pwa-register might not be available in non-PWA dev/test runs
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
