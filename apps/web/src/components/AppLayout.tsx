import React from "react";
import { Outlet } from "react-router-dom";
import { BottomNav } from "./BottomNav.js";
import { Sidebar } from "./Sidebar.js";

export const AppLayout: React.FC = () => {
  return (
    <div className="flex h-screen w-screen overflow-hidden bg-bg text-text">
      {/* Desktop / tablet left sidebar */}
      <Sidebar />

      {/* Main content pane */}
      <main className="flex-1 flex flex-col h-full overflow-y-auto pb-16 md:pb-0">
        <Outlet />
      </main>

      {/* Mobile bottom navigation bar */}
      <BottomNav />
    </div>
  );
};
