export default defineBackground(() => {
  // Set up context menu entry
  chrome.runtime.onInstalled.addListener(() => {
    chrome.contextMenus.create({
      id: "folio-clip-page",
      title: "Clip to Folio",
      contexts: ["page", "selection"]
    });
  });

  // Handle shortcut or context menu clicks
  chrome.contextMenus.onClicked.addListener((info, tab) => {
    if (tab && tab.id) {
      clipTab(tab.id);
    }
  });

  chrome.commands.onCommand.addListener((command) => {
    if (command === "clip-article") {
      chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
        if (tabs[0]?.id) {
          clipTab(tabs[0].id);
        }
      });
    }
  });
});

async function clipTab(tabId: number) {
  try {
    const results = await chrome.scripting.executeScript({
      target: { tabId },
      func: () => {
        // Run extractor script inside content
        return {
          html: document.body.innerHTML,
          title: document.title,
          url: location.href
        };
      }
    });

    if (!results[0]?.result) return;

    // Open or focus Folio tab at inbox
    const folioUrl = "http://localhost:5173/#/inbox";
    chrome.tabs.query({ url: "*://localhost/*" }, (tabs) => {
      if (tabs.length > 0 && tabs[0]?.id) {
        chrome.tabs.update(tabs[0].id, { active: true, url: folioUrl });
      } else {
        chrome.tabs.create({ url: folioUrl });
      }
    });
  } catch (err) {
    console.error("Failed to clip tab:", err);
  }
}
