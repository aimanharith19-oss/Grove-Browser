// Grove reader: the article only, as one self-contained page (pictures included). Returns {title, url, html}.
(async () => {
  const esc = s => String(s || "").replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
  const dataUrl = b => new Promise((ok, no) => { const r = new FileReader(); r.onload = () => ok(r.result); r.onerror = no; r.readAsDataURL(b); });
  async function makeReader() {
    try {
      if (typeof Readability === "undefined") return { err: "reader view isn't available" };
      const art = new Readability(document.cloneNode(true)).parse();
      if (!art || !art.content || (art.textContent || "").length < 250) return { err: "this page has no article to read" };
      const box = new DOMParser().parseFromString("<div id='g'>" + art.content + "</div>", "text/html").getElementById("g");
      for (const e of box.querySelectorAll("script, style, link, iframe, object, embed, form")) e.remove();
      for (const e of box.querySelectorAll("*")) for (const a of [...e.attributes]) if (/^on/i.test(a.name) || a.name === "style" || a.name === "class" || a.name === "id") e.removeAttribute(a.name);
      for (const a of box.querySelectorAll("a[href]")) { try { const u = new URL(a.getAttribute("href"), location.href); if (/^https?:$/.test(u.protocol)) a.setAttribute("href", u.href); else a.removeAttribute("href"); } catch (e) { a.removeAttribute("href"); } }
      let total = 0, n = 0;
      for (const img of box.querySelectorAll("img")) {
        img.removeAttribute("srcset"); img.removeAttribute("loading"); img.removeAttribute("sizes");
        let src = img.getAttribute("src") || img.getAttribute("data-src") || "";
        try { src = new URL(src, location.href).href; } catch (e) { img.remove(); continue; }
        if (!/^https?:/.test(src)) { if (!src.startsWith("data:image/")) img.remove(); continue; }
        img.setAttribute("src", src);
        if (++n > 60 || total > 12e6) continue; // the rest stay as links to the web
        try {
          const r = await fetch(src, { credentials: "omit", cache: "force-cache" });
          const b = await r.blob();
          if (r.ok && /^image\//.test(b.type) && b.size < 2e6) { img.setAttribute("src", await dataUrl(b)); total += b.size; }
        } catch (e) {}
      }
      const title = art.title || document.title || location.hostname, when = new Date().toLocaleDateString(undefined, { year: "numeric", month: "long", day: "numeric" });
      const html = "<!doctype html><html lang=\"" + esc(art.lang || document.documentElement.lang || "") + "\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
        "<meta name=\"referrer\" content=\"no-referrer\"><title>" + esc(title) + "</title><style>" +
        "html{background:#fbf9f4;color:#1c1c1c}body{font:19px/1.65 Georgia,'Noto Serif',serif;max-width:40em;margin:0 auto;padding:22px 20px 60px;overflow-wrap:break-word}" +
        "h1{font:300 34px/1.2 'Segoe UI',Roboto,sans-serif;margin:8px 0 10px}.m{font:14px/1.5 'Segoe UI',Roboto,sans-serif;color:#666;margin-bottom:26px;border-left:3px solid #60a917;padding-left:10px}" +
        "img,video,svg{max-width:100%;height:auto}figure{margin:1.2em 0}figcaption{font-size:14px;color:#666}pre,code{font:15px/1.5 monospace;white-space:pre-wrap}a{color:#2e7d0f}" +
        "blockquote{margin:1em 0;padding-left:14px;border-left:3px solid #bbb;color:#444}table{border-collapse:collapse;max-width:100%}td,th{border:1px solid #ccc;padding:4px 8px}" +
        "@media(prefers-color-scheme:dark){html{background:#000;color:#e6e6e6}.m,figcaption{color:#9a9a9a}a{color:#8fd14f}blockquote{color:#bbb;border-color:#555}td,th{border-color:#444}}" +
        "</style></head><body><h1>" + esc(title) + "</h1><div class=\"m\">" + (art.byline ? esc(art.byline) + " · " : "") + esc(art.siteName || location.hostname) +
        "<br><a href=\"" + esc(location.href) + "\">" + esc(location.href.length > 90 ? location.href.slice(0, 90) + "…" : location.href) + "</a><br>saved with Grove on " + esc(when) + "</div>" +
        box.innerHTML + "</body></html>";
      return { title, url: location.href, html };
    } catch (e) { return { err: "couldn't make a reader page here" }; }
  }
  return await makeReader();
})();
