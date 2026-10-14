import { defineConfig } from "wxt";

// See https://wxt.dev/api/config.html
export default defineConfig({
  extensionApi: "chrome",
  manifest: {
    name: "Folio Clipper",
    description: "Clip web articles cleanly to your Folio offline reading library",
    version: "0.1.0",
    permissions: [
      "activeTab",
      "scripting",
      "storage",
      "contextMenus",
      "downloads"
    ],
    host_permissions: [
      "http://localhost/*",
      "http://127.0.0.1/*",
      "<all_urls>"
    ],
    action: {
      default_title: "Clip to Folio"
    },
    commands: {
      "clip-article": {
        suggested_key: {
          default: "Alt+Shift+F",
          mac: "Alt+Shift+F"
        },
        description: "Clip current article to Folio"
      }
    }
  }
});
