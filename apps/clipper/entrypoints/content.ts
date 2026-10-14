import { extractArticleFromDom } from "../src/extractor.js";

export default defineContentScript({
  matches: ["<all_urls>"],
  main() {
    chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
      if (message?.type === "EXTRACT_ARTICLE") {
        try {
          const article = extractArticleFromDom(document, Boolean(message.fallbackFullPage));
          sendResponse({ article });
        } catch (err) {
          sendResponse({ error: (err as Error).message });
        }
        return true;
      }
    });
  }
});
