document.getElementById("clip-btn")?.addEventListener("click", () => {
  try {
    chrome.runtime?.sendMessage?.({ type: "clip-current-tab" })?.catch(() => {});
  } catch {
    // Ignore runtime disconnection
  }
  window.close();
});
