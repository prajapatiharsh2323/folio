import React, { useEffect, useState, useRef } from "react";
import { useSettingsStore, Theme } from "../state/settings-store.js";
import {
  Moon,
  Sun,
  Coffee,
  Database,
  ShieldCheck,
  Download,
  Upload,
  AlertTriangle,
  CheckCircle2,
  Clock,
  HardDrive,
  RefreshCw,
  FileCheck,
  Cloud,
  FolderOpen,
  Wifi,
  ChevronDown,
  ChevronUp,
  ExternalLink,
  Lock,
  HelpCircle,
  FolderSync
} from "lucide-react";
import {
  exportBackupBytes,
  restoreBackupZip,
  verifyBackupZip,
  type VerificationResult,
  type RestoreSummary,
  GoogleDriveSyncProvider,
  SyncManager,
  type SyncProgress,
  type SyncResult,
  FsAccessStore
} from "@folio/core";
import {
  fileStore,
  libraryManager,
  setCustomFileStore,
  useLibraryStore
} from "../state/library-store.js";

const LAST_BACKUP_KEY = "folio-last-backup-date";
const CHANGES_SINCE_BACKUP_KEY = "folio-changes-since-backup";

export const SettingsScreen: React.FC = () => {
  const { theme, setTheme } = useSettingsStore();
  const { loadLibrary } = useLibraryStore();

  const [persistentStorage, setPersistentStorage] = useState<boolean | null>(null);
  const [storageUsage, setStorageUsage] = useState<{ usedMb: number; quotaMb: number } | null>(null);
  const [lastBackupDate, setLastBackupDate] = useState<string | null>(null);
  const [changesSinceBackup, setChangesSinceBackup] = useState<number>(0);

  // PWA Install prompt state (Checklist #8)
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const [deferredPrompt, setDeferredPrompt] = useState<any>(null);
  const [isAppInstalled, setIsAppInstalled] = useState(false);

  // Backup & Restore states
  const [isExporting, setIsExporting] = useState(false);
  const [exportProgress, setExportProgress] = useState<string>("");
  const [isRestoring, setIsRestoring] = useState(false);
  const [restoreProgress, setRestoreProgress] = useState<string>("");
  const [restoreSummary, setRestoreSummary] = useState<RestoreSummary | null>(null);
  const [verificationResult, setVerificationResult] = useState<VerificationResult | null>(null);

  // Restore Modal State
  const [pendingRestoreBytes, setPendingRestoreBytes] = useState<Uint8Array | null>(null);
  const [restoreMode, setRestoreMode] = useState<"merge" | "replace">("merge");
  const [replaceConfirmInput, setReplaceConfirmInput] = useState("");
  const [showRestoreModal, setShowRestoreModal] = useState(false);

  // Google Drive & Folder Sync States (Phase 6)
  const [clientId, setClientId] = useState<string>(() => localStorage.getItem("folio-google-client-id") || "");
  const [wifiOnly, setWifiOnly] = useState<boolean>(() => localStorage.getItem("folio-sync-wifi-only") !== "false");
  const [isDriveSignedIn, setIsDriveSignedIn] = useState(false);
  const [isSyncing, setIsSyncing] = useState(false);
  const [syncProgress, setSyncProgress] = useState<SyncProgress | null>(null);
  const [syncResult, setSyncResult] = useState<SyncResult | null>(null);
  const [syncError, setSyncError] = useState<string | null>(null);
  const [showSyncModal, setShowSyncModal] = useState(false);
  const [showOAuthHelp, setShowOAuthHelp] = useState(false);
  const [activeFolderName, setActiveFolderName] = useState<string | null>(() => localStorage.getItem("folio-active-folder-name"));
  const isFolderModeSupported = typeof window !== "undefined" && "showDirectoryPicker" in window;

  const driveProviderRef = useRef<GoogleDriveSyncProvider | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    // Check if running in standalone mode (already installed)
    const isStandalone =
      window.matchMedia("(display-mode: standalone)").matches ||
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      (window.navigator as any).standalone === true;
    if (isStandalone) {
      setIsAppInstalled(true);
    }

    // Capture beforeinstallprompt
    const handleBeforeInstallPrompt = (e: Event) => {
      e.preventDefault();
      setDeferredPrompt(e);
    };

    window.addEventListener("beforeinstallprompt", handleBeforeInstallPrompt);

    window.addEventListener("appinstalled", () => {
      setIsAppInstalled(true);
      setDeferredPrompt(null);
    });

    return () => {
      window.removeEventListener("beforeinstallprompt", handleBeforeInstallPrompt);
    };
  }, []);

  const handleInstallApp = async () => {
    if (!deferredPrompt) return;
    deferredPrompt.prompt();
    const { outcome } = await deferredPrompt.userChoice;
    if (outcome === "accepted") {
      setIsAppInstalled(true);
    }
    setDeferredPrompt(null);
  };

  useEffect(() => {
    // Check storage persistence
    if (typeof navigator !== "undefined" && navigator.storage?.persisted) {
      navigator.storage
        .persisted()
        .then(setPersistentStorage)
        .catch(() => setPersistentStorage(false));
    }

    // Check storage estimate
    if (typeof navigator !== "undefined" && navigator.storage?.estimate) {
      navigator.storage
        .estimate()
        .then((estimate) => {
          const usedMb = estimate.usage ? Math.round(estimate.usage / (1024 * 1024)) : 0;
          const quotaMb = estimate.quota ? Math.round(estimate.quota / (1024 * 1024)) : 0;
          setStorageUsage({ usedMb, quotaMb });
        })
        .catch(console.error);
    }

    // Load last backup info
    const storedDate = localStorage.getItem(LAST_BACKUP_KEY);
    if (storedDate) setLastBackupDate(storedDate);

    const changes = localStorage.getItem(CHANGES_SINCE_BACKUP_KEY);
    if (changes) setChangesSinceBackup(parseInt(changes, 10));
  }, []);

  // Initialize Drive Provider whenever Client ID changes
  useEffect(() => {
    if (clientId.trim()) {
      localStorage.setItem("folio-google-client-id", clientId.trim());
      driveProviderRef.current = new GoogleDriveSyncProvider({ clientId: clientId.trim() });
    } else {
      localStorage.removeItem("folio-google-client-id");
      driveProviderRef.current = null;
    }
  }, [clientId]);

  const handleToggleWifiOnly = (enabled: boolean) => {
    setWifiOnly(enabled);
    localStorage.setItem("folio-sync-wifi-only", enabled ? "true" : "false");
  };

  const handleDriveSignIn = async () => {
    if (!driveProviderRef.current) return;
    setSyncError(null);
    try {
      await driveProviderRef.current.signIn();
      setIsDriveSignedIn(driveProviderRef.current.isSignedIn());
    } catch (err) {
      console.error(err);
      setSyncError((err as Error).message);
    }
  };

  const handleDriveSignOut = async () => {
    if (driveProviderRef.current) {
      await driveProviderRef.current.signOut();
      setIsDriveSignedIn(false);
    }
  };

  const handleSyncNow = async () => {
    if (!driveProviderRef.current) return;
    setIsSyncing(true);
    setSyncError(null);
    setSyncResult(null);
    setShowSyncModal(true);

    try {
      const syncManager = new SyncManager({
        fileStore,
        libraryManager,
        provider: driveProviderRef.current,
        wifiOnly,
        onProgress: (p) => setSyncProgress(p)
      });

      const res = await syncManager.sync();
      setSyncResult(res);
      await loadLibrary();
    } catch (err) {
      console.error("Sync failed:", err);
      setSyncError((err as Error).message);
    } finally {
      setIsSyncing(false);
    }
  };

  const handlePickLocalFolder = async () => {
    try {
      const store = await FsAccessStore.pickDirectory();
      setCustomFileStore(store);
      setActiveFolderName("Connected Local Folder");
      localStorage.setItem("folio-active-folder-name", "Connected Local Folder");
      await loadLibrary();
    } catch (err) {
      if ((err as Error).name !== "AbortError") {
        console.error("Failed to pick folder:", err);
      }
    }
  };

  const handleRequestPersistence = async () => {
    if (typeof navigator !== "undefined" && navigator.storage?.persist) {
      const granted = await navigator.storage.persist();
      setPersistentStorage(granted);
    }
  };

  const handleExportBackup = async () => {
    setIsExporting(true);
    setExportProgress("Preparing streaming archive...");
    try {
      const { filename, bytes } = await exportBackupBytes(fileStore, {
        onProgress: ({ current, total, filename: currentFile }) => {
          setExportProgress(`Packing ${current}/${total}: ${currentFile.split("/").pop()}`);
        }
      });

      // Check size warning (> 500 MB)
      if (bytes.byteLength > 500 * 1024 * 1024) {
        alert(
          "Notice: Backup archive is larger than 500 MB. We recommend exporting partial sets or syncing over Wi-Fi."
        );
      }

      // Download file
      const blob = new Blob([bytes as BlobPart], { type: "application/zip" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);

      const now = new Date().toISOString();
      setLastBackupDate(now);
      localStorage.setItem(LAST_BACKUP_KEY, now);
      setChangesSinceBackup(0);
      localStorage.setItem(CHANGES_SINCE_BACKUP_KEY, "0");

      setExportProgress(`Backup saved: ${filename}`);
      setTimeout(() => setExportProgress(""), 4000);
    } catch (err) {
      console.error("Backup export error:", err);
      setExportProgress("Failed to export backup");
    } finally {
      setIsExporting(false);
    }
  };

  const handleSelectRestoreFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    try {
      const arrayBuffer = await file.arrayBuffer();
      const bytes = new Uint8Array(arrayBuffer);

      // Verify ZIP first
      const verification = await verifyBackupZip(bytes);
      setVerificationResult(verification);
      setPendingRestoreBytes(bytes);
      setRestoreMode("merge");
      setReplaceConfirmInput("");
      setShowRestoreModal(true);
    } catch (err) {
      alert(`Invalid backup file: ${(err as Error).message}`);
    } finally {
      if (fileInputRef.current) fileInputRef.current.value = "";
    }
  };

  const handleExecuteRestore = async () => {
    if (!pendingRestoreBytes) return;
    if (restoreMode === "replace" && replaceConfirmInput.trim().toUpperCase() !== "REPLACE") {
      alert("Please type REPLACE to confirm wiping the entire library.");
      return;
    }

    setIsRestoring(true);
    setRestoreProgress("Restoring library files...");
    try {
      const { summary } = await restoreBackupZip(
        fileStore,
        libraryManager,
        pendingRestoreBytes,
        {
          mode: restoreMode,
          onProgress: ({ message }) => {
            setRestoreProgress(message);
          }
        }
      );

      setRestoreSummary(summary);
      await loadLibrary();
      setShowRestoreModal(false);
      setPendingRestoreBytes(null);
    } catch (err) {
      alert(`Restore failed: ${(err as Error).message}`);
    } finally {
      setIsRestoring(false);
      setRestoreProgress("");
    }
  };

  // Determine if backup reminder banner should show (14 days or 20 changes)
  const isBackupDue =
    changesSinceBackup >= 20 ||
    (lastBackupDate &&
      Date.now() - new Date(lastBackupDate).getTime() > 14 * 24 * 60 * 60 * 1000) ||
    (!lastBackupDate && changesSinceBackup > 0);

  const themes: { id: Theme; name: string; icon: React.FC<{ className?: string }> }[] = [
    { id: "light", name: "Light", icon: Sun },
    { id: "warm", name: "Warm", icon: Coffee },
    { id: "dark", name: "Dark", icon: Moon }
  ];

  return (
    <div className="flex-1 flex flex-col p-4 md:p-8 max-w-3xl mx-auto w-full">
      <header className="mb-6 pb-4 border-b border-border">
        <h2 className="text-2xl font-bold tracking-tight text-text">Settings</h2>
        <p className="text-sm text-muted">
          Preferences, backup & restore, and offline storage status
        </p>
      </header>

      {/* Backup Reminder Banner */}
      {isBackupDue && (
        <div className="mb-6 p-4 rounded-xl border border-amber-500/40 bg-amber-500/10 flex items-start justify-between gap-4">
          <div className="flex items-start gap-3">
            <AlertTriangle className="w-5 h-5 text-amber-500 flex-shrink-0 mt-0.5" />
            <div>
              <h4 className="text-sm font-semibold text-text">Backup Recommended</h4>
              <p className="text-xs text-muted mt-0.5">
                {lastBackupDate
                  ? `Last backup was ${new Date(lastBackupDate).toLocaleDateString()}. Save a fresh copy to prevent data loss.`
                  : "You haven't exported a backup copy of your library yet."}
              </p>
            </div>
          </div>
          <button
            onClick={handleExportBackup}
            disabled={isExporting}
            className="px-3 py-1.5 rounded-lg bg-amber-500 hover:bg-amber-600 text-white text-xs font-semibold whitespace-nowrap shadow-xs"
          >
            Backup Now
          </button>
        </div>
      )}

      {/* Install App Section (Checklist #8) */}
      {!isAppInstalled && deferredPrompt && (
        <section className="mb-6 p-4 rounded-2xl border border-accent/40 bg-accent/10 flex items-center justify-between gap-4">
          <div>
            <h4 className="text-sm font-semibold text-text">Install Folio App</h4>
            <p className="text-xs text-muted mt-0.5">
              Install Folio as a standalone PWA on your device for offline reading and quick launch.
            </p>
          </div>
          <button
            onClick={handleInstallApp}
            className="px-4 py-2 rounded-xl bg-accent text-white text-xs font-semibold shadow-xs hover:opacity-90 transition-opacity whitespace-nowrap"
          >
            Install
          </button>
        </section>
      )}

      {/* Theme Selection */}
      <section className="mb-8">
        <h3 className="text-sm font-semibold uppercase tracking-wider text-muted mb-3">
          Appearance Theme
        </h3>
        <div className="grid grid-cols-3 gap-3">
          {themes.map(({ id, name, icon: Icon }) => (
            <button
              key={id}
              onClick={() => setTheme(id)}
              className={`flex items-center justify-center gap-2.5 p-3 rounded-lg border text-sm font-medium transition-all ${
                theme === id
                  ? "border-accent bg-accent/10 text-accent font-semibold"
                  : "border-border bg-surface text-text hover:border-muted"
              }`}
            >
              <Icon className="w-4 h-4" />
              <span>{name}</span>
            </button>
          ))}
        </div>
      </section>

      {/* Backup & Restore Section */}
      <section className="mb-8 p-5 rounded-2xl border border-border bg-surface space-y-4 shadow-xs">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2.5 text-base font-semibold text-text">
            <HardDrive className="w-5 h-5 text-accent" />
            <span>Backup & Restore Library</span>
          </div>
          <span className="text-xs text-muted flex items-center gap-1 font-mono">
            <Clock className="w-3.5 h-3.5" />
            {lastBackupDate
              ? `Last: ${new Date(lastBackupDate).toLocaleDateString()}`
              : "No backup yet"}
          </span>
        </div>
        <p className="text-xs text-muted leading-relaxed">
          Export your entire library—including original books, articles, annotations, reading
          progress, and collections—into a single portable ZIP archive. Restoring supports
          intelligent merge or clean replacement with full SHA-256 integrity verification.
        </p>

        {exportProgress && (
          <div className="text-xs p-2 rounded-lg bg-accent/10 text-accent font-medium flex items-center gap-2">
            <RefreshCw className="w-3.5 h-3.5 animate-spin" />
            <span>{exportProgress}</span>
          </div>
        )}

        {restoreSummary && (
          <div className="p-3.5 rounded-xl border border-green-500/30 bg-green-500/10 text-xs space-y-1">
            <div className="flex items-center gap-1.5 font-semibold text-green-700 dark:text-green-300">
              <CheckCircle2 className="w-4 h-4" />
              <span>Restore Completed Successfully</span>
            </div>
            <p className="text-muted">
              Mode: <strong className="capitalize text-text">{restoreSummary.mode}</strong> ·{" "}
              {restoreSummary.newDocsAdded} new documents added ·{" "}
              {restoreSummary.duplicatesSkipped} duplicates skipped ·{" "}
              {restoreSummary.annotationsMerged} annotations merged
            </p>
          </div>
        )}

        <div className="flex flex-wrap items-center gap-3 pt-2 border-t border-border/60">
          <button
            onClick={handleExportBackup}
            disabled={isExporting}
            className="inline-flex items-center gap-2 px-4 py-2 rounded-xl bg-accent text-white text-xs font-semibold hover:opacity-90 shadow-xs transition-opacity disabled:opacity-50"
          >
            <Download className="w-4 h-4" />
            <span>{isExporting ? "Exporting ZIP..." : "Export Backup ZIP"}</span>
          </button>

          <button
            onClick={() => fileInputRef.current?.click()}
            disabled={isRestoring}
            className="inline-flex items-center gap-2 px-4 py-2 rounded-xl border border-border bg-bg text-text text-xs font-semibold hover:border-accent transition-colors"
          >
            <Upload className="w-4 h-4" />
            <span>Restore from ZIP</span>
          </button>
          <input
            ref={fileInputRef}
            type="file"
            accept=".zip,application/zip"
            className="hidden"
            onChange={handleSelectRestoreFile}
          />
        </div>
      </section>

      {/* Google Drive Mirror & Sync (Phase 6) */}
      <section className="mb-8 p-5 rounded-2xl border border-border bg-surface space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Cloud className="w-5 h-5 text-accent" />
            <h2 className="text-sm font-bold text-text">Google Drive Mirror & Sync</h2>
          </div>
          <span
            className={`text-xs px-2.5 py-0.5 rounded-full font-medium ${
              isDriveSignedIn
                ? "bg-green-500/10 text-green-700 dark:text-green-300 border border-green-500/20"
                : "bg-muted/10 text-muted border border-border"
            }`}
          >
            {isDriveSignedIn ? "Connected" : "Not Signed In"}
          </span>
        </div>

        <p className="text-xs text-muted leading-relaxed">
          Mirror your offline library into a dedicated Google Drive folder (<strong>Folio Library</strong>).
          Sync converges your documents, notes, and reading progress across multiple devices without
          any intermediate server.
        </p>

        {/* OAuth Client ID Input */}
        <div className="space-y-1.5">
          <label className="block text-xs font-semibold text-text">
            Google OAuth Client ID
          </label>
          <div className="flex gap-2">
            <input
              type="text"
              value={clientId}
              onChange={(e) => setClientId(e.target.value)}
              placeholder="e.g. 123456789-abcdef.apps.googleusercontent.com"
              className="flex-1 px-3 py-2 rounded-xl border border-border bg-bg text-text text-xs font-mono focus:border-accent focus:outline-none"
            />
            {isDriveSignedIn ? (
              <button
                type="button"
                onClick={handleDriveSignOut}
                className="px-3 py-2 rounded-xl border border-border bg-bg text-text text-xs font-semibold hover:border-red-500 hover:text-red-500 transition-colors"
              >
                Sign Out
              </button>
            ) : (
              <button
                type="button"
                onClick={handleDriveSignIn}
                disabled={!clientId.trim()}
                className="px-4 py-2 rounded-xl bg-accent text-white text-xs font-semibold hover:opacity-90 transition-opacity disabled:opacity-40"
              >
                Sign In
              </button>
            )}
          </div>
        </div>

        {/* Wi-Fi Only & Privacy Disclosures */}
        <div className="pt-2 border-t border-border/50 space-y-3">
          <div className="flex items-center justify-between">
            <label className="flex items-center gap-2 cursor-pointer text-xs text-text font-medium select-none">
              <input
                type="checkbox"
                checked={wifiOnly}
                onChange={(e) => handleToggleWifiOnly(e.target.checked)}
                className="rounded text-accent focus:ring-accent"
              />
              <Wifi className="w-4 h-4 text-muted" />
              <span>Wi-Fi only sync (prevents cellular data transfer)</span>
            </label>
          </div>

          <div className="flex items-center gap-2 text-[11px] text-muted">
            <Lock className="w-3.5 h-3.5 text-accent shrink-0" />
            <span>
              Drive files are unencrypted. Folio only requests access to files it creates under the Folio Library folder.
            </span>
          </div>
        </div>

        {/* Sync Now Trigger */}
        <div className="pt-2 border-t border-border/50 flex flex-wrap items-center justify-between gap-3">
          <button
            type="button"
            onClick={handleSyncNow}
            disabled={isSyncing || !isDriveSignedIn}
            className="inline-flex items-center gap-2 px-4 py-2 rounded-xl bg-accent text-white text-xs font-semibold hover:opacity-90 shadow-xs transition-opacity disabled:opacity-40"
          >
            <FolderSync className={`w-4 h-4 ${isSyncing ? "animate-spin" : ""}`} />
            <span>{isSyncing ? "Syncing..." : "Sync Now"}</span>
          </button>

          <button
            type="button"
            onClick={() => setShowOAuthHelp(!showOAuthHelp)}
            className="inline-flex items-center gap-1.5 text-xs text-accent hover:underline font-medium"
          >
            <HelpCircle className="w-3.5 h-3.5" />
            <span>{showOAuthHelp ? "Hide Setup Guide" : "How to get a Client ID"}</span>
            {showOAuthHelp ? <ChevronUp className="w-3.5 h-3.5" /> : <ChevronDown className="w-3.5 h-3.5" />}
          </button>
        </div>

        {/* Expandable Google Cloud Setup Instructions */}
        {showOAuthHelp && (
          <div className="p-4 rounded-xl border border-border bg-bg/50 text-xs space-y-2.5 text-text">
            <h3 className="font-bold text-accent flex items-center gap-1.5">
              <span>Google Cloud Console Setup (3 Minutes)</span>
            </h3>
            <ol className="list-decimal list-inside space-y-1.5 text-muted leading-relaxed">
              <li>
                Open the{" "}
                <a
                  href="https://console.cloud.google.com"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-accent underline font-semibold inline-flex items-center gap-0.5"
                >
                  Google Cloud Console <ExternalLink className="w-3 h-3" />
                </a>{" "}
                and create a project named <strong>Folio</strong>.
              </li>
              <li>
                Navigate to <strong>APIs & Services &gt; Library</strong>, search for <strong>Google Drive API</strong>, and click <strong>Enable</strong>.
              </li>
              <li>
                In <strong>APIs & Services &gt; OAuth consent screen</strong>, select <strong>External</strong>, fill in your App name and email, and add your Google account under <strong>Test users</strong>.
              </li>
              <li>
                Go to <strong>APIs & Services &gt; Credentials</strong>, click <strong>Create Credentials &gt; OAuth client ID</strong>, and choose <strong>Web application</strong>.
              </li>
              <li>
                Under <strong>Authorized JavaScript origins</strong>, add this app&apos;s origin (e.g. <code className="px-1 py-0.5 bg-surface border rounded text-text font-mono">{typeof window !== "undefined" ? window.location.origin : "http://localhost:5173"}</code>).
              </li>
              <li>
                Click <strong>Create</strong>, copy the generated <strong>Client ID</strong>, and paste it into the field above.
              </li>
            </ol>
          </div>
        )}
      </section>

      {/* Windows Folder Mode (File System Access) */}
      {isFolderModeSupported && (
        <section className="mb-8 p-5 rounded-2xl border border-border bg-surface space-y-3.5">
          <div className="flex items-center justify-between text-sm font-semibold text-text">
            <div className="flex items-center gap-2">
              <FolderOpen className="w-4 h-4 text-accent" />
              <span>Windows Folder Mode (Direct Filesystem)</span>
            </div>
            {activeFolderName && (
              <span className="text-xs px-2.5 py-0.5 rounded-full bg-accent/10 text-accent font-medium border border-accent/20">
                {activeFolderName}
              </span>
            )}
          </div>
          <p className="text-xs text-muted leading-relaxed">
            Choose a folder on your computer to store your library files directly. If you pick a
            folder inside your desktop cloud drive (such as Google Drive for desktop), Windows keeps
            it synchronized automatically with zero API requests.
          </p>

          <div className="pt-2 border-t border-border/50 flex items-center justify-between">
            <button
              type="button"
              onClick={handlePickLocalFolder}
              className="inline-flex items-center gap-2 px-4 py-2 rounded-xl border border-border bg-bg text-text text-xs font-semibold hover:border-accent transition-colors"
            >
              <FolderOpen className="w-4 h-4" />
              <span>Select Local Library Folder</span>
            </button>
          </div>
        </section>
      )}

      {/* Storage & Persistence Status */}
      <section className="mb-8 p-5 rounded-2xl border border-border bg-surface space-y-3.5">
        <div className="flex items-center justify-between text-sm font-semibold text-text">
          <div className="flex items-center gap-2">
            <Database className="w-4 h-4 text-accent" />
            <span>Local Storage Engine (OPFS)</span>
          </div>
          {storageUsage && (
            <span className="text-xs font-mono text-muted">
              {storageUsage.usedMb} MB used {storageUsage.quotaMb > 0 && `of ${storageUsage.quotaMb} MB`}
            </span>
          )}
        </div>
        <p className="text-xs text-muted leading-relaxed">
          Folio stores all documents locally in your browser&apos;s Origin Private File System.
          Your files never leave this device.
        </p>

        <div className="flex items-center justify-between pt-2 border-t border-border/50 text-xs">
          <div className="flex items-center gap-2 text-muted">
            <ShieldCheck className="w-4 h-4 text-accent" />
            <span>
              Storage Eviction Protection:{" "}
              <strong className="text-text">
                {persistentStorage === null
                  ? "Checking..."
                  : persistentStorage
                  ? "Granted"
                  : "Standard"}
              </strong>
            </span>
          </div>
          {!persistentStorage && (
            <button
              onClick={handleRequestPersistence}
              className="text-xs font-medium text-accent hover:underline"
            >
              Request Persistence
            </button>
          )}
        </div>
      </section>

      {/* About & Version */}
      <section className="text-xs text-muted space-y-1">
        <p>Folio v0.1.0 · Local-First Library</p>
        <p>Built with TypeScript, OPFS, epub.js, and MiniSearch.</p>
      </section>

      {/* Restore Confirmation Modal */}
      {showRestoreModal && verificationResult && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/50 backdrop-blur-xs">
          <div className="bg-surface border border-border rounded-2xl shadow-2xl max-w-lg w-full p-6 space-y-5">
            <div className="flex items-center gap-2.5 pb-3 border-b border-border">
              <FileCheck className="w-5 h-5 text-accent" />
              <h3 className="text-base font-bold text-text">Confirm Restore</h3>
            </div>

            {/* Verification status */}
            <div
              className={`p-3 rounded-xl border text-xs ${
                verificationResult.valid
                  ? "border-green-500/30 bg-green-500/10 text-green-700 dark:text-green-300"
                  : "border-amber-500/30 bg-amber-500/10 text-amber-700 dark:text-amber-300"
              }`}
            >
              <div className="flex items-center gap-1.5 font-semibold">
                {verificationResult.valid ? (
                  <CheckCircle2 className="w-4 h-4" />
                ) : (
                  <AlertTriangle className="w-4 h-4" />
                )}
                <span>
                  {verificationResult.valid
                    ? "Archive Integrity Verified (All checksums match)"
                    : `Archive contains ${verificationResult.issues.length} warnings`}
                </span>
              </div>
              <p className="mt-1 text-muted">
                {verificationResult.backupMeta?.docCount || 0} documents in backup ·{" "}
                {Math.round(verificationResult.totalSize / 1024)} KB total uncompressed
              </p>
            </div>

            {/* Mode selection: Merge vs Replace */}
            <div className="space-y-3">
              <label className="block text-xs font-semibold uppercase tracking-wider text-muted">
                Restore Mode
              </label>
              <div className="grid grid-cols-2 gap-3">
                <button
                  type="button"
                  onClick={() => setRestoreMode("merge")}
                  className={`p-3 rounded-xl border text-left text-xs transition-all ${
                    restoreMode === "merge"
                      ? "border-accent bg-accent/10 text-accent font-semibold"
                      : "border-border bg-bg text-text"
                  }`}
                >
                  <span className="block font-bold mb-1">Merge (Default)</span>
                  <span className="text-[11px] text-muted block">
                    Safely adds new documents and updates notes without deleting anything.
                  </span>
                </button>
                <button
                  type="button"
                  onClick={() => setRestoreMode("replace")}
                  className={`p-3 rounded-xl border text-left text-xs transition-all ${
                    restoreMode === "replace"
                      ? "border-red-500 bg-red-500/10 text-red-500 font-semibold"
                      : "border-border bg-bg text-text"
                  }`}
                >
                  <span className="block font-bold mb-1">Replace</span>
                  <span className="text-[11px] text-muted block">
                    Wipes existing library first and restores clean state.
                  </span>
                </button>
              </div>
            </div>

            {/* Replace Warning & Confirmation Input */}
            {restoreMode === "replace" && (
              <div className="p-3 rounded-xl border border-red-500/30 bg-red-500/10 space-y-2">
                <p className="text-xs text-red-500 font-medium">
                  Type <strong>REPLACE</strong> below to confirm wiping all current library files.
                </p>
                <input
                  type="text"
                  value={replaceConfirmInput}
                  onChange={(e) => setReplaceConfirmInput(e.target.value)}
                  placeholder="Type REPLACE..."
                  className="w-full px-3 py-1.5 rounded-lg border border-red-500/40 bg-surface text-text text-xs focus:outline-none"
                />
              </div>
            )}

            {/* Progress indicator */}
            {restoreProgress && (
              <div className="text-xs text-accent font-medium flex items-center gap-2">
                <RefreshCw className="w-3.5 h-3.5 animate-spin" />
                <span>{restoreProgress}</span>
              </div>
            )}

            {/* Actions */}
            <div className="flex items-center justify-end gap-3 pt-3 border-t border-border">
              <button
                type="button"
                onClick={() => {
                  setShowRestoreModal(false);
                  setPendingRestoreBytes(null);
                }}
                disabled={isRestoring}
                className="px-4 py-2 rounded-xl border border-border text-xs font-semibold text-text hover:bg-bg"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleExecuteRestore}
                disabled={
                  isRestoring ||
                  (restoreMode === "replace" &&
                    replaceConfirmInput.trim().toUpperCase() !== "REPLACE")
                }
                className={`px-5 py-2 rounded-xl text-white text-xs font-semibold shadow-xs disabled:opacity-50 ${
                  restoreMode === "replace" ? "bg-red-600 hover:bg-red-700" : "bg-accent hover:opacity-90"
                }`}
              >
                {isRestoring ? "Restoring..." : "Proceed with Restore"}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Sync Progress & Results Modal (Phase 6) */}
      {showSyncModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/50 backdrop-blur-xs">
          <div className="bg-surface border border-border rounded-2xl shadow-2xl max-w-md w-full p-6 space-y-4">
            <div className="flex items-center justify-between pb-3 border-b border-border">
              <div className="flex items-center gap-2">
                <Cloud className="w-5 h-5 text-accent" />
                <h3 className="text-sm font-bold text-text">
                  {isSyncing ? "Sync in Progress" : syncError ? "Sync Encountered an Issue" : "Sync Complete"}
                </h3>
              </div>
              {!isSyncing && (
                <button
                  type="button"
                  onClick={() => setShowSyncModal(false)}
                  className="text-muted hover:text-text text-xs font-bold"
                >
                  ✕
                </button>
              )}
            </div>

            {isSyncing && syncProgress && (
              <div className="space-y-3">
                <div className="flex items-center justify-between text-xs text-text font-medium">
                  <span className="capitalize text-accent font-semibold flex items-center gap-1.5">
                    <RefreshCw className="w-3.5 h-3.5 animate-spin" />
                    {syncProgress.phase}
                  </span>
                  <span className="text-muted font-mono">{syncProgress.current}%</span>
                </div>

                <div className="w-full h-2 rounded-full bg-bg border border-border overflow-hidden">
                  <div
                    className="h-full bg-accent transition-all duration-300"
                    style={{ width: `${Math.min(100, syncProgress.current)}%` }}
                  />
                </div>

                <p className="text-xs text-muted truncate">{syncProgress.message}</p>
              </div>
            )}

            {syncError && (
              <div className="p-3 rounded-xl border border-red-500/30 bg-red-500/10 text-xs text-red-600 dark:text-red-400 space-y-1">
                <div className="flex items-center gap-1.5 font-semibold">
                  <AlertTriangle className="w-4 h-4 shrink-0" />
                  <span>Sync failed</span>
                </div>
                <p className="break-words">{syncError}</p>
              </div>
            )}

            {syncResult && !isSyncing && (
              <div className="space-y-3 text-xs">
                <div className="p-3.5 rounded-xl border border-green-500/30 bg-green-500/10 text-green-700 dark:text-green-300 flex items-center gap-2 font-semibold">
                  <CheckCircle2 className="w-4 h-4 shrink-0" />
                  <span>Library converged with Google Drive mirror</span>
                </div>

                <div className="grid grid-cols-2 gap-2 text-text">
                  <div className="p-2.5 rounded-lg border border-border bg-bg/60">
                    <span className="text-muted block text-[11px]">Documents Uploaded</span>
                    <strong className="text-sm font-semibold">{syncResult.uploadedDocs}</strong>
                  </div>
                  <div className="p-2.5 rounded-lg border border-border bg-bg/60">
                    <span className="text-muted block text-[11px]">Documents Downloaded</span>
                    <strong className="text-sm font-semibold">{syncResult.downloadedDocs}</strong>
                  </div>
                  <div className="p-2.5 rounded-lg border border-border bg-bg/60">
                    <span className="text-muted block text-[11px]">Files Uploaded</span>
                    <strong className="text-sm font-semibold">{syncResult.uploadedFiles}</strong>
                  </div>
                  <div className="p-2.5 rounded-lg border border-border bg-bg/60">
                    <span className="text-muted block text-[11px]">User Data Reconciled</span>
                    <strong className="text-sm font-semibold">{syncResult.mergedUserFiles}</strong>
                  </div>
                </div>
              </div>
            )}

            {!isSyncing && (
              <div className="pt-2 flex justify-end">
                <button
                  type="button"
                  onClick={() => setShowSyncModal(false)}
                  className="px-4 py-2 rounded-xl bg-accent text-white text-xs font-semibold hover:opacity-90"
                >
                  Close
                </button>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};
