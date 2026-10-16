/** @vitest-environment jsdom */
import React from "react";
import { describe, it, expect, afterEach, vi } from "vitest";
import { cleanup, fireEvent, render } from "@testing-library/react";
import { BookCard } from "../../../apps/web/src/components/BookCard.js";
import type { DocMeta } from "../src/types.js";

const mockDoc: DocMeta = {
  schema: 1,
  id: "doc-menu-1",
  slug: "sample-book",
  title: "Sample Book",
  author: "Author",
  kind: "book",
  sourceFormat: "epub",
  capturedAt: new Date().toISOString(),
  importedAt: new Date().toISOString(),
  contentHash: "hash",
  searchable: true,
  tags: [],
  collectionIds: [],
  files: {},
  chapters: []
};

afterEach(cleanup);

function openMoreMenu(view: ReturnType<typeof render>) {
  const moreBtn = view.getByLabelText(/More actions for Sample Book/i);
  expect(moreBtn.getAttribute("aria-expanded")).toBe("false");
  fireEvent.click(moreBtn);
  expect(moreBtn.getAttribute("aria-expanded")).toBe("true");
  return moreBtn;
}

describe("BookCard three-dot action menu", () => {
  it("opens and the popover is NOT clipped by the overflow-hidden card", () => {
    const view = render(
      <BookCard doc={mockDoc} onOpen={vi.fn()} onOpenDetails={vi.fn()} onDelete={vi.fn()} />
    );

    openMoreMenu(view);

    // A menu row "Open Book" proves the popover mounted at all.
    const openRow = view.getAllByText(/Open Book/i)[0]!;
    // Resolve the popover root: the proper menu container from the fix, or its
    // parent element on the pre-fix implementation.
    const menuEl =
      openRow.closest('[role="menu"]') ?? openRow.parentElement!;
    // `.folio-book-card` is overflow:hidden — a tall/wide popover inside it is
    // clipped to death on real cards (~200px wide). The popover must live
    // outside the card, portaled to document.body.
    expect(menuEl.closest(".folio-book-card")).toBeNull();
    expect(menuEl.parentElement).toBe(document.body);

    // Role/label so it is a proper accessible menu.
    expect(menuEl.getAttribute("role")).toBe("menu");
    expect(openRow.closest('[role="menu"]')).not.toBeNull();

    // Escape closes the menu.
    fireEvent.keyDown(document, { key: "Escape", code: "Escape" });
    expect(view.queryByText(/Open Book/i)).toBeNull();
  });
});
