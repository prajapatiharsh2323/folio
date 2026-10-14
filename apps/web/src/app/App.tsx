import React, { useEffect } from "react";
import { BrowserRouter, Routes, Route, Navigate, useNavigate, useLocation } from "react-router-dom";
import { AppLayout } from "../components/AppLayout.js";
import { LibraryScreen } from "../screens/LibraryScreen.js";
import { SearchScreen } from "../screens/SearchScreen.js";
import { NotesScreen } from "../screens/NotesScreen.js";
import { SettingsScreen } from "../screens/SettingsScreen.js";
import { ImportScreen } from "../screens/ImportScreen.js";
import { EpubSpikeScreen } from "../screens/EpubSpikeScreen.js";
import { ReaderScreen } from "../screens/ReaderScreen.js";
import { ClipInboxScreen } from "../screens/ClipInboxScreen.js";

// Synchronizes hash navigation (#/inbox, #/import, #/search, etc.) into React Router
const HashSync: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();

  useEffect(() => {
    const handleHash = () => {
      const hash = window.location.hash;
      if (hash.startsWith("#/")) {
        const targetPath = hash.slice(1); // e.g. "/inbox" or "/import"
        if (location.pathname !== targetPath) {
          navigate(targetPath);
        }
        // Clear hash from location bar so user isn't trapped when navigating away
        window.history.replaceState(null, "", targetPath);
      }
    };

    handleHash();
    window.addEventListener("hashchange", handleHash);
    return () => window.removeEventListener("hashchange", handleHash);
  }, [navigate, location.pathname]);

  return null;
};

export const App: React.FC = () => {
  return (
    <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <HashSync />
      <Routes>
        <Route path="/read/:slug" element={<ReaderScreen />} />
        <Route path="/" element={<AppLayout />}>
          <Route index element={<LibraryScreen />} />
          <Route path="search" element={<SearchScreen />} />
          <Route path="notes" element={<NotesScreen />} />
          <Route path="settings" element={<SettingsScreen />} />
          <Route path="import" element={<ImportScreen />} />
          <Route path="open" element={<Navigate to="/import" replace />} />
          <Route path="share-target" element={<Navigate to="/import" replace />} />
          <Route path="inbox" element={<ClipInboxScreen />} />
          <Route path="spike" element={<EpubSpikeScreen />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
};
