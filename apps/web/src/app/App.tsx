import React from "react";
import { BrowserRouter, HashRouter, Routes, Route, Navigate } from "react-router-dom";
import { AppLayout } from "../components/AppLayout.js";
import { LibraryScreen } from "../screens/LibraryScreen.js";
import { SearchScreen } from "../screens/SearchScreen.js";
import { NotesScreen } from "../screens/NotesScreen.js";
import { SettingsScreen } from "../screens/SettingsScreen.js";
import { ImportScreen } from "../screens/ImportScreen.js";
import { EpubSpikeScreen } from "../screens/EpubSpikeScreen.js";
import { ReaderScreen } from "../screens/ReaderScreen.js";
import { ClipInboxScreen } from "../screens/ClipInboxScreen.js";

// Supports both BrowserRouter and HashRouter (for PWA shortcut URLs and file handler compatibility)
export const App: React.FC = () => {
  // Use HashRouter if URL hash navigation is used for shortcuts (e.g. /#/search from PWA manifest)
  const isHash = window.location.hash.startsWith("#/");
  const RouterComponent = isHash ? HashRouter : BrowserRouter;

  return (
    <RouterComponent>
      <Routes>
        <Route path="/read/:slug" element={<ReaderScreen />} />
        <Route path="/" element={<AppLayout />}>
          <Route index element={<LibraryScreen />} />
          <Route path="search" element={<SearchScreen />} />
          <Route path="notes" element={<NotesScreen />} />
          <Route path="settings" element={<SettingsScreen />} />
          <Route path="import" element={<ImportScreen />} />
          <Route path="inbox" element={<ClipInboxScreen />} />
          <Route path="spike" element={<EpubSpikeScreen />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </RouterComponent>
  );
};
