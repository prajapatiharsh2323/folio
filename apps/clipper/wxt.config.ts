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
    action: {
      default_title: "Clip to Folio"
    }
  }
});
