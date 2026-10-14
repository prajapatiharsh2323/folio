// Intercepts PWA share_target POST requests to /share-target on static hosts
// so static file servers don't return 405 Method Not Allowed.
self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (event.request.method === "POST" && url.pathname === "/share-target") {
    event.respondWith(
      (async () => {
        try {
          const formData = await event.request.formData();
          const title = formData.get("title");
          const text = formData.get("text");
          const shareUrl = formData.get("url");
          const files = formData.getAll("files");

          let hasSharedFiles = false;
          if (files && files.length > 0) {
            const cache = await caches.open("folio-share-target");
            let idx = 0;
            for (const file of files) {
              if (file instanceof File && file.size > 0) {
                const response = new Response(file, {
                  headers: {
                    "Content-Type": file.type || "application/octet-stream",
                    "X-Filename": encodeURIComponent(file.name || `shared-file-${idx}`)
                  }
                });
                await cache.put(`/_shared_file_${Date.now()}_${idx}`, response);
                idx++;
                hasSharedFiles = true;
              }
            }
          }

          const queryParams = new URLSearchParams();
          if (title) queryParams.set("title", title.toString());
          if (text) queryParams.set("text", text.toString());
          if (shareUrl) queryParams.set("url", shareUrl.toString());
          if (hasSharedFiles) queryParams.set("hasSharedFiles", "1");

          const targetPath = "/import" + (queryParams.toString() ? "?" + queryParams.toString() : "");
          const targetUrl = new URL(targetPath, self.location.origin).href;
          return Response.redirect(targetUrl, 303);
        } catch (err) {
          console.error("sw-share-target handler error:", err);
          return Response.redirect(new URL("/import", self.location.origin).href, 303);
        }
      })()
    );
  }
});
