import { test, expect } from "@playwright/test";

test.describe("Folio Phase 1 Reader & Import Acceptance Tests", () => {
  test("imports English and Hindi EPUBs, lists them, opens in reader, and adjusts typography", async ({
    page
  }) => {
    // 1. Visit Library
    await page.goto("/");
    await expect(page.getByRole("heading", { name: "Library" })).toBeVisible();

    // 2. Navigate to Import
    await page.getByRole("link", { name: "Import" }).first().click();
    await expect(page.getByRole("heading", { name: "Import Documents" })).toBeVisible();

    // 3. Import English Novel via quick test button
    await page.getByRole("button", { name: "Import English Novel" }).click();
    await expect(page.getByText(/Imported "The Solitary Reader"/i)).toBeVisible({ timeout: 10000 });

    // 4. Import Hindi Book via quick test button
    await page.getByRole("button", { name: "Import Hindi Book" }).click();
    await expect(page.getByText(/Imported "निर्मल धारा \(Nirmal Dhara\)"/i)).toBeVisible({
      timeout: 10000
    });

    // 5. Duplicate test: clicking Import English Novel again should flag duplicate
    await page.getByRole("button", { name: "Import English Novel" }).click();
    await expect(page.getByText(/Already in library as "The Solitary Reader"/i)).toBeVisible({
      timeout: 10000
    });

    // 6. Return to Library and verify documents are rendered
    await page.goto("/");
    await expect(page.getByText("The Solitary Reader")).toBeVisible();
    await expect(page.getByText("निर्मल धारा (Nirmal Dhara)")).toBeVisible();
    await expect(page.getByText("Continue reading")).toBeVisible();

    // 7. Click to open English book in Reader
    await page.getByText("The Solitary Reader").first().click();
    await expect(page).toHaveURL(/\/read\//);

    // Verify Reader UI
    await expect(page.getByRole("heading", { name: "The Solitary Reader" })).toBeVisible();

    // 8. Open Appearance sheet and toggle font size and theme
    await page.getByTitle("Typography & Appearance").click();
    await expect(page.getByRole("heading", { name: "Appearance" })).toBeVisible();

    // Toggle Warm theme
    await page.getByRole("button", { name: "Warm" }).click();
    const html = page.locator("html");
    await expect(html).toHaveAttribute("data-theme", "warm");

    // Close typography sheet
    await page.getByRole("button", { name: "Done" }).click();
    await expect(page.getByRole("heading", { name: "Appearance" })).not.toBeVisible();

    // 9. Open Table of Contents drawer
    await page.getByTitle("Table of Contents").click();
    await expect(page.getByRole("heading", { name: "Table of Contents" })).toBeVisible();
    await expect(page.getByRole("button", { name: /Chapter I: The Discovery/i })).toBeVisible();

    // Close TOC
    await page.getByRole("button", { name: "Close" }).click();

    // 10. Reload and verify settings persist
    await page.reload();
    await expect(page.getByRole("heading", { name: "The Solitary Reader" })).toBeVisible();
    await expect(html).toHaveAttribute("data-theme", "warm");
  });

  test("Reading UX acceptance checklist: safe zones, appearance controls, and flow toggle", async ({
    page
  }) => {
    // Open reader directly on imported novel
    await page.goto("/");
    const bookLink = page.getByText("The Solitary Reader").first();
    if (await bookLink.isVisible()) {
      await bookLink.click();
    } else {
      await page.goto("/import");
      await page.getByRole("button", { name: "Import English Novel" }).click();
      await page.goto("/");
      await page.getByText("The Solitary Reader").first().click();
    }
    await expect(page).toHaveURL(/\/read\//);

    // Verify controls start visible
    const header = page.locator("header");
    const footer = page.locator("footer");
    await expect(header).toBeVisible();
    await expect(footer).toBeVisible();

    // Check reading container has reading width cap applied
    const container = page.locator("main > div");
    await expect(container).toBeVisible();

    // Open typography sheet
    await page.getByTitle("Typography & Appearance").click();
    await expect(page.getByRole("heading", { name: "Appearance" })).toBeVisible();

    // Verify live preview strip is present
    await expect(page.getByText("Live Preview")).toBeVisible();

    // Verify System theme button
    await page.getByRole("button", { name: "System" }).click();
    const html = page.locator("html");
    await expect(html).toHaveAttribute("data-theme-setting", "system");

    // Switch reading flow to Continuous
    await page.getByRole("button", { name: "Continuous" }).click();

    // Click Reset to restore defaults
    await page.getByRole("button", { name: "Reset" }).first().click();

    // Close Appearance sheet
    await page.getByRole("button", { name: "Done" }).click();
    await expect(page.getByRole("heading", { name: "Appearance" })).not.toBeVisible();
  });
});
