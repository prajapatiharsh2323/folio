document.getElementById("clip-btn")?.addEventListener("click", () => {
  chrome.runtime.sendMessage({ type: "clip-current-tab" });
  window.close();
});
