export default defineContentScript({
  matches: ["<all_urls>"],
  main() {
    console.log("Folio Clipper content script loaded.");
  }
});
