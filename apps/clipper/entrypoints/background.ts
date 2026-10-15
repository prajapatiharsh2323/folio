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

  // Keyboard shortcut listener (Alt+Shift+F)
  if (typeof chrome !== "undefined" && chrome.commands?.onCommand) {
    chrome.commands.onCommand.addListener((command) => {
      if (command === "clip-article") {
        chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
          if (tabs[0]?.id) {
            clipTab(tabs[0].id);
          }
        });
      }
    });
  }

  // Listen for messages from popup
  chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    if (message?.type === "clip-current-tab") {
      chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
        if (tabs[0]?.id) {
          clipTab(tabs[0].id).catch(() => {});
        }
      });
      sendResponse?.({ status: "ok" });
      return false;
    }
  });
});

async function clipTab(tabId: number, fallbackFullPage = false) {
  try {
    // 1. Request extraction from content script
    let article: {
      url: string;
      title: string;
      byline?: string;
      siteName?: string;
      lang?: string;
      publishedAt?: string;
      contentHtml: string;
      images: string[];
    } | null = null;

    try {
      const response = await chrome.tabs.sendMessage(tabId, {
        type: "EXTRACT_ARTICLE",
        fallbackFullPage
      }).catch(() => null);
      if (response && response.article) {
        article = response.article;
      }
    } catch {
      // Content script may not be injected yet. Attempt to inject it and retry message.
      try {
        await chrome.scripting.executeScript({
          target: { tabId },
          files: ["content-scripts/content.js"]
        });
        const retryResp = await chrome.tabs.sendMessage(tabId, {
          type: "EXTRACT_ARTICLE",
          fallbackFullPage
        }).catch(() => null);
        if (retryResp && retryResp.article) {
          article = retryResp.article;
        }
      } catch {
        // Fallback to in-page extraction
      }

      if (!article) {
        const results = await chrome.scripting.executeScript({
        target: { tabId },
        func: () => {
          const canonical = document.querySelector<HTMLLinkElement>('link[rel="canonical"]')?.href;
          const url = canonical || location.href;
          const siteName =
            document.querySelector<HTMLMetaElement>('meta[property="og:site_name"]')?.content ||
            location.hostname.replace(/^www\./, "");
          const lang = document.documentElement.lang || "en";
          const publishedAt = document.querySelector<HTMLMetaElement>(
            'meta[property="article:published_time"]'
          )?.content;

          // Clone body to isolate manipulation from host page
          const bodyClone = document.body ? (document.body.cloneNode(true) as HTMLElement) : null;
          const images: string[] = [];

          if (bodyClone) {
            // Remove scripts, styles, iframes, and inline event handlers
            bodyClone.querySelectorAll("script, style, iframe, noscript").forEach((el) => el.remove());

            const imgEls = Array.from(bodyClone.querySelectorAll("img"));
            for (const img of imgEls) {
              const src = img.getAttribute("src") || img.getAttribute("data-src") || img.currentSrc;
              if (src && !src.startsWith("data:") && images.length < 40) {
                try {
                  const resolved = new URL(src, url).href;
                  if (!images.includes(resolved)) {
                    images.push(resolved);
                  }
                  img.setAttribute("src", resolved);
                } catch {
                  // ignore
                }
              }
            }
          }

          return {
            url,
            title: document.title || "Untitled Article",
            siteName,
            lang,
            publishedAt,
            contentHtml: bodyClone ? bodyClone.innerHTML : "",
            images
          };
        }
      });
      article = results[0]?.result || null;
    }
  }

    if (!article || !article.contentHtml) {
      console.warn("Folio Clipper: No readable article found on tab", tabId);
      return;
    }

    // 2. Fetch images (up to 40 images, max 10MB each) and convert to base64
    const processedImages: { originalUrl: string; mime: string; base64: string }[] = [];
    if (Array.isArray(article.images)) {
      for (const imgUrl of article.images.slice(0, 40)) {
        try {
          const res = await fetch(imgUrl);
          if (!res.ok) continue;
          const blob = await res.blob();
          if (blob.size > 10 * 1024 * 1024) continue; // Section 6: 10MB limit

          const buffer = await blob.arrayBuffer();
          const bytes = new Uint8Array(buffer);
          let binary = "";
          const len = bytes.byteLength;
          for (let i = 0; i < len; i++) {
            binary += String.fromCharCode(bytes[i]!);
          }

          processedImages.push({
            originalUrl: imgUrl,
            mime: blob.type || "image/jpeg",
            base64: btoa(binary)
          });
        } catch {
          // Keep alt text and skip broken image fetches as specified
        }
      }
    }

    // 3. Assemble Section 6 ClipPayload
    const payload = {
      version: 1,
      url: article.url,
      title: article.title,
      byline: article.byline,
      siteName: article.siteName,
      lang: article.lang,
      publishedAt: article.publishedAt,
      contentHtml: article.contentHtml,
      images: processedImages
    };

    // 4. Open or focus Folio tab at inbox
    const storedConfig = await chrome.storage?.local?.get("folioBaseUrl");
    const configuredUrl = storedConfig?.folioBaseUrl;
    const baseUrl = (typeof configuredUrl === "string" && configuredUrl.trim())
      ? configuredUrl.trim().replace(/\/+$/, "")
      : "http://localhost:5173";
    const folioUrl = `${baseUrl}/#/inbox`;
    const targetOrigin = new URL(baseUrl).origin;

    // Specifically find existing Folio tabs matching configured origin only
    const candidateTabs = await chrome.tabs.query({});
    const folioExistingTab = candidateTabs.find((t) => {
      if (!t.url) return false;
      try {
        const u = new URL(t.url);
        return u.origin === targetOrigin;
      } catch {
        return false;
      }
    });

    let folioTab: chrome.tabs.Tab | undefined;
    if (folioExistingTab?.id) {
      folioTab = await chrome.tabs.update(folioExistingTab.id, { active: true, url: folioUrl });
    } else {
      folioTab = await chrome.tabs.create({ url: folioUrl });
    }

    if (!folioTab?.id) return;
    const targetTabId = folioTab.id;

    // 5. Deliver clip payload to Folio page via MAIN world postMessage with page nonce
    const deliverBridge = (clipPayload: unknown) => {
      let attempts = 0;
      const tryDeliver = () => {
        attempts++;
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const nonce = (window as any).__FOLIO_CLIP_NONCE__;
        if (nonce) {
          window.postMessage(
            {
              type: "FOLIO_CLIP_DELIVERY",
              nonce,
              payload: clipPayload
            },
            window.location.origin
          );
          return true;
        }
        return false;
      };

      if (!tryDeliver()) {
        const interval = setInterval(() => {
          if (tryDeliver() || attempts >= 40) {
            clearInterval(interval);
          }
        }, 250);
      }
    };

    // Inject bridge into Folio tab, retrying if tab is still initializing
    const injectBridge = (tabId: number, retries = 0) => {
      chrome.scripting
        .executeScript({
          target: { tabId },
          world: "MAIN",
          args: [payload],
          func: deliverBridge
        })
        .catch((e) => {
          if (retries < 6) {
            setTimeout(() => injectBridge(tabId, retries + 1), 400);
          } else {
            console.error("Folio Clipper bridge execution failed:", e);
          }
        });
    };

    setTimeout(() => {
      injectBridge(targetTabId);
    }, 400);
  } catch (err) {
    console.error("Failed to clip tab:", err);
  }
}
