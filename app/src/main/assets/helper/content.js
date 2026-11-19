// Grove helper: tells Grove when a video plays (for picture-in-picture) and keeps media playing when Grove is in the background.
// Picture-in-picture itself is Firefox's way: the video goes full screen and that full-screen player becomes the floating window.
(() => {
  let bg = false, port = null;
  try { port = browser.runtime.connectNative("grove"); } catch (e) { return; }
  const send = m => { try { port.postMessage(m); } catch (e) {} };

  port.onMessage.addListener(m => {
    if ("bg" in m) bg = !!m.bg;
    if (m.reader && window === window.top) makeReader(m.reader);
    if ("mini" in m) miniOn = !!m.mini;
    if ("fx" in m && window === window.top) pageFx(m.fx || []);
    if (m.sel && window === window.top) send({ t: "sel", text: String(getSelection() || "").trim() });
    if (m.pipnow && window === window.top) pipNow();
  });

  // Background play: the page keeps seeing itself as visible, so sites don't pause.
  try {
    const d = document.wrappedJSObject;
    Object.defineProperty(d, "hidden", { configurable: true, get: exportFunction(() => bg ? false : document.hidden, window) });
    Object.defineProperty(d, "visibilityState", { configurable: true, get: exportFunction(() => bg ? "visible" : document.visibilityState, window) });
  } catch (e) {}
  const stop = e => { if (bg && document.hidden) e.stopImmediatePropagation(); };
  window.addEventListener("visibilitychange", stop, true);
  window.addEventListener("webkitvisibilitychange", stop, true);

  // Video state for picture-in-picture.
  const playingVideo = () => [...document.querySelectorAll("video")].find(v => !v.paused && !v.ended && v.readyState > 1);
  const report = () => {
    const v = playingVideo();
    send({ t: "video", playing: !!v, w: v ? v.videoWidth : 0, h: v ? v.videoHeight : 0 });
  };
  for (const ev of ["play", "playing", "pause", "ended", "emptied", "loadedmetadata"]) document.addEventListener(ev, report, true);
  // events can be missed (videos made by scripts, shadow DOM): check again every second while a page has video
  let lastSent = null;
  setInterval(() => {
    if (!document.querySelector("video") && lastSent !== true) return;
    const v = playingVideo(), now = !!v;
    if (now !== lastSent) { lastSent = now; report(); }
  }, 1000);

  // Site icon for favorites (top frame only).
  const icons = () => {
    if (window !== window.top) return;
    const l = [...document.querySelectorAll("link[rel~='icon' i], link[rel='apple-touch-icon' i], link[rel='apple-touch-icon-precomposed' i]")]
      .map(e => ({ h: e.href, s: parseInt((e.getAttribute("sizes") || "0").split("x")[0]) || 0, a: /apple/i.test(e.rel) }))
      .filter(i => i.h.startsWith("https://") && !/\.svg(\?|$)/i.test(i.h));
    l.sort((x, y) => (y.a - x.a) || (y.s - x.s));
    if (l.length) send({ t: "icon", h: l[0].h });
  };
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", icons, { once: true }); else icons();

  // Website theme colour, to tint Grove's bars like the page.
  if (window === window.top) {
    let sent = null;
    const theme = () => {
      const metas = [...document.querySelectorAll("meta[name='theme-color' i]")];
      const m = metas.find(e => !e.media || matchMedia(e.media).matches);
      const c = m ? (m.content || "").trim() : "";
      if (c !== sent) { sent = c; send({ t: "theme", c }); }
    };
    const watch = () => {
      theme();
      if (document.head) new MutationObserver(theme).observe(document.head, { childList: true, subtree: true, attributes: true, attributeFilter: ["content", "media"] });
    };
    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", watch, { once: true }); else watch();
  }

  // ---------- picture-in-picture button on every video: the video goes full screen, then Grove opens Android's floating window
  let miniOn = true, btn = null, btnFor = null;
  const big = v => { const r = v.getBoundingClientRect(); return r.width >= 180 && r.height >= 100 && r.bottom > 40 && r.top < innerHeight - 40 && r.right > 0 && r.left < innerWidth; };
  function placeBtn() {
    if (!miniOn || document.fullscreenElement) { if (btn) btn.style.display = "none"; return; }
    let best = null, area = 0;
    for (const v of document.querySelectorAll("video")) {
      if (!big(v) || v.readyState < 1) continue;
      const r = v.getBoundingClientRect(), a = r.width * r.height;
      if (a > area) { area = a; best = v; }
    }
    if (!best) { if (btn) btn.style.display = "none"; return; }
    if (!btn) {
      btn = document.createElement("div");
      btn.setAttribute("aria-label", "picture in picture");
      const st = { position: "fixed", width: "34px", height: "34px", "z-index": "2147483646", background: "rgba(0,0,0,.62)", border: "2px solid #fff",
        "box-sizing": "border-box", cursor: "pointer", "touch-action": "manipulation" };
      for (const k in st) btn.style.setProperty(k, st[k], "important");
      const inner = document.createElement("div"); // a small window inside a big one
      const si = { position: "absolute", right: "4px", bottom: "4px", width: "13px", height: "9px", background: "#fff" };
      for (const k in si) inner.style.setProperty(k, si[k], "important");
      btn.appendChild(inner);
      const go = e => {
        e.preventDefault(); e.stopPropagation();
        const v = btnFor; if (!v) return;
        if (v.paused) v.play().catch(() => {});
        send({ t: "pip" });                     // Grove opens the floating window as soon as the video is full screen
        const fs = v.requestFullscreen || v.mozRequestFullScreen;
        try { const r = fs.call(v); if (r && r.catch) r.catch(() => send({ t: "pipcancel" })); } catch (er) { send({ t: "pipcancel" }); }
      };
      btn.addEventListener("click", go, true);
      btn.addEventListener("touchend", go, true);
      (document.body || document.documentElement).appendChild(btn);
    }
    const r = best.getBoundingClientRect();
    btnFor = best;
    btn.style.setProperty("display", "block", "important");
    btn.style.setProperty("left", Math.max(4, Math.min(innerWidth - 40, r.right - 42)) + "px", "important");
    btn.style.setProperty("top", Math.max(4, r.top + 8) + "px", "important");
  }
  // Placing the button measures the page, so it never runs while scrolling (that made scrolling stutter): the button hides
  // during a scroll and comes back 150 ms after it stops. Pages without video do no work at all.
  let placeQueued = false, scrollT = 0, scrolling = false;
  const queuePlace = () => { if (scrolling || !document.querySelector("video")) return; if (!placeQueued) { placeQueued = true; requestAnimationFrame(() => { placeQueued = false; placeBtn(); }); } };
  addEventListener("scroll", () => {
    if (!btn || btn.style.display === "none") { if (!btnFor) return; }
    scrolling = true; if (btn) btn.style.setProperty("display", "none", "important");
    clearTimeout(scrollT); scrollT = setTimeout(() => { scrolling = false; queuePlace(); }, 150);
  }, { passive: true, capture: true });
  addEventListener("resize", queuePlace, { passive: true });
  document.addEventListener("fullscreenchange", queuePlace, true);
  for (const ev of ["play", "playing", "loadedmetadata", "emptied"]) document.addEventListener(ev, queuePlace, true);
  setInterval(() => { if (scrolling || document.hidden) return; if (document.querySelector("video")) placeBtn(); else if (btn) btn.style.display = "none"; }, 1500);

  // ---------- picture in picture from the menu (like Google's picture-in-picture extension): the biggest video floats
  function pipNow() {
    const vs = [...document.querySelectorAll("video")].filter(v => v.readyState > 0);
    const area = v => { const r = v.getBoundingClientRect(); return r.width * r.height + (v.paused ? 0 : 1e9); };
    const v = vs.sort((a, b) => area(b) - area(a))[0];
    if (!v) { send({ t: "pipcancel", none: true }); return; }
    if (v.paused) v.play().catch(() => {});
    try { const r = v.requestFullscreen(); if (r && r.catch) r.catch(() => send({ t: "pipcancel" })); } catch (er) { send({ t: "pipcancel" }); }
  }

  // ---------- page actions (Vivaldi-style): filters and fonts for this page, kept while the tab is open
  const FX = {
    grayscale: ["grayscale(1)"], dark: ["invert(.9) hue-rotate(180deg)"], sepia: ["sepia(.6)"], contrast: ["contrast(1.4)"],
  };
  const FXCSS = {
    dark: "img,video,picture,canvas,iframe,[style*='background-image']{filter:invert(1) hue-rotate(180deg) !important}",
    serif: "body,body *:not(i):not([class*=icon]):not([class*=Icon]){font-family:Georgia,'Noto Serif',serif !important}",
    mono: "body,body *:not(i):not([class*=icon]):not([class*=Icon]){font-family:'Roboto Mono',monospace !important}",
    bigtext: "html{font-size:125% !important}body{font-size:1.15em !important}",
    noimages: "img,picture,svg image,[style*='background-image']{visibility:hidden !important}",
    noanim: "*,*::before,*::after{animation:none !important;transition:none !important;scroll-behavior:auto !important}",
  };
  let fxEl = null;
  function pageFx(list) {
    const filt = list.filter(k => FX[k]).map(k => FX[k][0]).join(" ");
    const css = (filt ? "html{filter:" + filt + " !important}" : "") + list.map(k => FXCSS[k] || "").join("");
    if (!css) { if (fxEl) { fxEl.remove(); fxEl = null; } return; }
    if (!fxEl) { fxEl = document.createElement("style"); fxEl.id = "grove-page-actions"; }
    fxEl.textContent = css;
    (document.head || document.documentElement).appendChild(fxEl);
  }

  // ---------- YouTube: no "Video paused. Continue watching?" interruptions
  if (/(^|\.)youtube(-nocookie)?\.com$/.test(location.hostname)) {
    setInterval(() => {
      try { window.wrappedJSObject._lact = Date.now(); } catch (e) {} // YouTube's "last activity" clock
      for (const d of document.querySelectorAll("yt-confirm-dialog-renderer, ytmusic-you-there-renderer, ytm-confirm-dialog-renderer, .ytm-confirm-dialog, tp-yt-paper-dialog, dialog")) {
        if (!d.offsetParent && getComputedStyle(d).display === "none") continue;
        if (!/continue watching|still watching|still listening|video paused|are you still/i.test(d.textContent || "")) continue;
        const b = d.querySelector("#confirm-button button, #confirm-button, .yt-spec-button-shape-next--call-to-action, button[aria-label='Yes' i], yt-button-renderer button, button");
        if (b) b.click();
        const v = document.querySelector("video");
        if (v && v.paused) v.play().catch(() => {});
      }
    }, 4000);
  }

  // ---------- reader view: the article only, as one self-contained page (also saved for offline reading)
  const esc = s => String(s || "").replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
  const dataUrl = b => new Promise((ok, no) => { const r = new FileReader(); r.onload = () => ok(r.result); r.onerror = no; r.readAsDataURL(b); });
  async function makeReader(mode) {
    try {
      if (typeof Readability === "undefined") return send({ t: "reader", mode, err: "reader view isn't available in this build" });
      const art = new Readability(document.cloneNode(true)).parse();
      if (!art || !art.content || (art.textContent || "").length < 250) return send({ t: "reader", mode, err: "this page has no article to read" });
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
        "<br><a href=\"" + esc(location.href) + "\">" + esc(location.href.length > 90 ? location.href.slice(0, 90) + "…" : location.href) + "</a><br>saved with Grove Browser on " + esc(when) + "</div>" +
        box.innerHTML + "</body></html>";
      send({ t: "reader", mode, title, url: location.href, html });
    } catch (e) { send({ t: "reader", mode, err: "couldn't make a reader page here" }); }
  }

  // ---------- pull to refresh: pull down at the very top of the page, let go when the bar is full
  if (window === window.top) {
    let y0 = null, x0 = 0, bar = null, full = false;
    const atTop = () => (document.scrollingElement || document.documentElement).scrollTop <= 0 && scrollY <= 0;
    const scrolledInside = el => { for (; el && el !== document.body && el !== document.documentElement; el = el.parentElement) { if (el.scrollTop > 0) return true; } return false; };
    const show = p => {
      if (!bar) {
        bar = document.createElement("div");
        const st = { position: "fixed", left: "0", top: "0", height: "4px", width: "100%", background: "#60a917", "z-index": "2147483647", "transform-origin": "center", "pointer-events": "none", transition: "none" };
        for (const k in st) bar.style.setProperty(k, st[k], "important");
        document.documentElement.appendChild(bar);
      }
      bar.style.setProperty("transform", "scaleX(" + p + ")", "important");
      bar.style.setProperty("opacity", String(0.4 + p * 0.6), "important");
    };
    const hide = () => { if (bar) { bar.remove(); bar = null; } };
    addEventListener("touchstart", e => {
      y0 = e.touches.length === 1 && atTop() && !scrolledInside(e.target) ? e.touches[0].clientY : null;
      x0 = e.touches.length ? e.touches[0].clientX : 0; full = false;
    }, { passive: true, capture: true });
    addEventListener("touchmove", e => {
      if (y0 === null) return;
      const dy = e.touches[0].clientY - y0, dx = Math.abs(e.touches[0].clientX - x0);
      if (e.touches.length !== 1 || !atTop() || dx > dy) { y0 = null; hide(); return; }
      if (dy < 24) return;
      const p = Math.min(1, (dy - 24) / 140); full = p >= 1; show(p);
    }, { passive: true, capture: true });
    const end = () => { const go = y0 !== null && full; y0 = null; hide(); if (go) location.reload(); };
    addEventListener("touchend", end, { passive: true, capture: true });
    addEventListener("touchcancel", () => { y0 = null; hide(); }, { passive: true, capture: true });
  }
})();