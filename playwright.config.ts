import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: "html",
  use: {
    baseURL: "http://localhost:5173",
    trace: "on-first-retry"
  },
  projects: [
    {
      name: "Desktop Chromium",
      use: { ...devices["Desktop Chrome"] }
    },
    {
      name: "Mobile Chrome (Pixel 7)",
      use: { ...devices["Pixel 7"] }
    }
  ],
  webServer: {
    command: "pnpm --filter @folio/web run dev --host",
    url: "http://localhost:5173",
    reuseExistingServer: !process.env.CI
  }
});
