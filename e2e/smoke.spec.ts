import { test, expect } from "@playwright/test";

test.describe("Folio PWA Smoke Test", () => {
  test("loads the Library screen with proper navigation and theme attributes", async ({ page }) => {
    await page.goto("/");

    // Verify title
    await expect(page).toHaveTitle(/Folio/);

    // Verify theme data attribute
    const html = page.locator("html");
    await expect(html).toHaveAttribute("data-theme", "light");

    // Verify presence of header
    await expect(page.getByRole("heading", { name: "Library" })).toBeVisible();

    // Verify empty state message
    await expect(page.getByText("Your library is empty")).toBeVisible();
  });

  test("can navigate to Settings and switch themes", async ({ page }) => {
    await page.goto("/settings");

    await expect(page.getByRole("heading", { name: "Settings" })).toBeVisible();

    // Click Warm theme
    await page.getByRole("button", { name: "Warm" }).click();
    const html = page.locator("html");
    await expect(html).toHaveAttribute("data-theme", "warm");

    // Click Dark theme
    await page.getByRole("button", { name: "Dark" }).click();
    await expect(html).toHaveAttribute("data-theme", "dark");
  });
});
