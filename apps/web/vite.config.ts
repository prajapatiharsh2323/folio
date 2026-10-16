import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { VitePWA } from "vite-plugin-pwa";
import path from "path";

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: "prompt",
      includeAssets: ["favicon.ico", "icons/*.png", "icons/*.svg"],
      manifest: {
        name: "Folio",
        short_name: "Folio",
        description: "Offline reading library",
        start_url: "/?source=pwa",
        scope: "/",
        display: "standalone",
        background_color: "#FAFAF7",
        theme_color: "#FAFAF7",
        orientation: "any",
        categories: ["books", "productivity"],
        icons: [
          {
            src: "/icons/logo.png",
            sizes: "678x716",
            type: "image/png"
          },
          {
            src: "/icons/icon-192.png",
            sizes: "192x192",
            type: "image/png"
          },
          {
            src: "/icons/icon-512.png",
            sizes: "512x512",
            type: "image/png"
          },
          {
            src: "/icons/maskable-512.png",
            sizes: "512x512",
            type: "image/png",
            purpose: "maskable"
          }
        ],
        shortcuts: [
          { name: "Search", url: "/#/search" },
          { name: "Import", url: "/#/import" }
        ],
        share_target: {
          action: "/share-target",
          method: "POST",
          enctype: "multipart/form-data",
          params: {
            title: "title",
            text: "text",
            url: "url",
            files: [
              {
                name: "files",
                accept: [
                  ".epub",
                  ".pdf",
                  ".html",
                  ".htm",
                  ".txt",
                  "application/epub+zip",
                  "application/pdf",
                  "text/html",
                  "text/plain"
                ]
              }
            ]
          }
        },
        file_handlers: [
          {
            action: "/open",
            accept: {
              "application/epub+zip": [".epub"],
              "application/pdf": [".pdf"]
            }
          }
        ]
      },
      workbox: {
        globPatterns: ["**/*.{js,css,html,ico,png,svg,woff2}"],
        importScripts: ["/sw-share-target.js"]
      }
    })
  ],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src")
    }
  }
});
