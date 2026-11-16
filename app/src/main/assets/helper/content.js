// Grove helper: tells Grove when a video plays (for picture-in-picture), fills the window with the
// video while in picture-in-picture, and keeps media playing when Grove is in the background.
(() => {
  let bg = false, port = null, last = null, saved = null;
  try { port = browser.runtime.connectNative("grove"); } catch (e) { return; }
  const send = m => { try { port.postMessage(m); } catch (e) {} };

  port.onMessage.addListener(m => {
    if ("bg" in m) bg = !!m.bg;
    if ("pip" in m) pip(!!m.pip);
    if (m.reader && window === window.top) makeReader(m.reader);
    if ("mini" in m) miniOn = !!m.mini;
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
    if (v) last = v;
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

  // Picture-in-picture: make the playing video fill the (small) window. Uses inline styles so page CSP can't block it.
  const FILL = { position: "fixed", inset: "0", width: "100vw", height: "100vh", "max-width": "none", "max-height": "none",
    "z-index": "2147483647", background: "#000", "object-fit": "contain", transform: "none", margin: "0" };
  function pip(on) {
    if (saved) {
      saved.v.style.cssText = saved.css;
      document.documentElement.style.overflow = saved.html;
      saved = null;
    }
    if (!on) return;
    const v = playingVideo() || last;
    if (!v) return;
    saved = { v, css: v.style.cssText, html: document.documentElement.style.overflow };
    for (const k in FILL) v.style.setProperty(k, FILL[k], "important");
    document.documentElement.style.setProperty("overflow", "hidden", "important");
  }

  // ---------- mini player: a small button on every video; tap it and the video floats in a corner, draggable, while you keep browsing the page
  let miniOn = true, btn = null, btnFor = null, mini = null;
  const big = v => { const r = v.getBoundingClientRect(); return r.width >= 180 && r.height >= 100 && r.bottom > 40 && r.top < innerHeight - 40 && r.right > 0 && r.left < innerWidth; };
  function placeBtn() {
    if (!miniOn || mini || saved) { if (btn) btn.style.display = "none"; return; }
    let best = null, area = 0;
    for (const v of document.querySelectorAll("video")) {
      if (!big(v) || v.readyState < 1) continue;
      const r = v.getBoundingClientRect(), a = r.width * r.height;
      if (a > area) { area = a; best = v; }
    }
    if (!best) { if (btn) btn.style.display = "none"; return; }
    if (!btn) {
      btn = document.createElement("div");
      btn.setAttribute("aria-label", "mini player");
      const st = { position: "fixed", width: "34px", height: "34px", "z-index": "2147483646", background: "rgba(0,0,0,.62)", border: "2px solid #fff",
        "box-sizing": "border-box", cursor: "pointer", "touch-action": "manipulation" };
      for (const k in st) btn.style.setProperty(k, st[k], "important");
      const inner = document.createElement("div"); // a small window inside a big one
      const si = { position: "absolute", right: "4px", bottom: "4px", width: "13px", height: "9px", background: "#fff" };
      for (const k in si) inner.style.setProperty(k, si[k], "important");
      btn.appendChild(inner);
      const go = e => { e.preventDefault(); e.stopPropagation(); if (btnFor) openMini(btnFor); };
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
  let placeQueued = false;
  const queuePlace = () => { if (!placeQueued) { placeQueued = true; requestAnimationFrame(() => { placeQueued = false; placeBtn(); }); } };
  addEventListener("scroll", queuePlace, { passive: true, capture: true });
  addEventListener("resize", queuePlace, { passive: true });
  for (const ev of ["play", "playing", "loadedmetadata", "emptied"]) document.addEventListener(ev, queuePlace, true);
  setInterval(() => { if (document.querySelector("video")) placeBtn(); else if (btn) btn.style.display = "none"; }, 1500);

  function openMini(v) {
    if (mini || saved) return;
    const w = Math.round(Math.min(innerWidth * 0.62, 340)), ratio = v.videoWidth && v.videoHeight ? v.videoHeight / v.videoWidth : 9 / 16;
    const h = Math.round(w * Math.min(Math.max(ratio, 0.4), 1.8));
    const m = { v, css: v.style.cssText, x: innerWidth - w - 10, y: innerHeight - h - 90, w, h, undo: [] };
    // an ancestor with a transform/filter would trap "position: fixed": switch those off while floating
    for (let p = v.parentElement; p && p !== document.documentElement; p = p.parentElement) {
      const cs = getComputedStyle(p);
      if (cs.transform !== "none" || cs.filter !== "none" || cs.perspective !== "none" || /paint|layout|strict|content/.test(cs.contain) || cs.willChange.includes("transform")) {
        m.undo.push([p, p.style.cssText]);
        for (const k of ["transform", "filter", "perspective", "contain", "will-change"]) p.style.setProperty(k, k === "will-change" ? "auto" : "none", "important");
      }
    }
    const apply = () => {
      const st = { position: "fixed", left: m.x + "px", top: m.y + "px", right: "auto", bottom: "auto", width: m.w + "px", height: m.h + "px", "max-width": "none",
        "max-height": "none", "min-width": "0", "min-height": "0", "z-index": "2147483645", background: "#000", "object-fit": "contain", transform: "none", margin: "0",
        "box-shadow": "0 0 0 2px #fff, 0 6px 24px rgba(0,0,0,.6)" };
      for (const k in st) v.style.setProperty(k, st[k], "important");
      const so = { position: "fixed", left: m.x + "px", top: m.y + "px", width: m.w + "px", height: m.h + "px", "z-index": "2147483647", "touch-action": "none", background: "transparent" };
      for (const k in so) m.over.style.setProperty(k, so[k], "important");
    };
    // a clear layer on top: drag it to move the player, tap to play/pause, ✕ puts the video back
    m.over = document.createElement("div");
    const x = document.createElement("div");
    x.textContent = "✕";
    const sx = { position: "absolute", top: "0", right: "0", width: "36px", height: "36px", "line-height": "36px", "text-align": "center", color: "#fff",
      background: "rgba(0,0,0,.62)", font: "18px sans-serif", cursor: "pointer" };
    for (const k in sx) x.style.setProperty(k, sx[k], "important");
    m.over.appendChild(x);
    // "back to the player": puts the video back where it was on the page and scrolls to it (keeps playing)
    const back = document.createElement("div");
    back.textContent = "\u2922";
    back.setAttribute("aria-label", "back to the player");
    const sb = Object.assign({}, sx, { left: "0", right: "auto", "font-size": "20px" });
    for (const k in sb) back.style.setProperty(k, sb[k], "important");
    m.over.appendChild(back);
    // "picture in picture": the system's floating window (stays on top of other apps)
    const pop = document.createElement("div");
    pop.textContent = "\u29c9";
    pop.setAttribute("aria-label", "picture in picture");
    const sp = Object.assign({}, sx, { left: "auto", right: "40px", "font-size": "18px" });
    for (const k in sp) pop.style.setProperty(k, sp[k], "important");
    m.over.appendChild(pop);
    let sxp = 0, syp = 0, ox = 0, oy = 0, moved = false, down = false, hit = null;
    m.over.addEventListener("pointerdown", e => { down = true; moved = false; hit = e.target; /* the button pressed: pointer capture retargets the later events */ sxp = e.clientX; syp = e.clientY; ox = m.x; oy = m.y; try { m.over.setPointerCapture(e.pointerId); } catch (er) {} e.preventDefault(); });
    m.over.addEventListener("pointermove", e => {
      if (!down) return;
      const dx = e.clientX - sxp, dy = e.clientY - syp;
      if (Math.abs(dx) + Math.abs(dy) > 8) moved = true;
      if (moved) { m.x = Math.max(0, Math.min(innerWidth - m.w, ox + dx)); m.y = Math.max(0, Math.min(innerHeight - m.h, oy + dy)); apply(); }
      e.preventDefault();
    });
    m.over.addEventListener("pointerup", e => {
      if (!down) return;
      down = false;
      if (!moved) {
        if (hit === x) { v.pause(); closeMini(); }                       // \u2715 stops it
        else if (hit === back) { closeMini(); try { v.scrollIntoView({ block: "center", behavior: "smooth" }); } catch (er) {} if (v.paused) v.play().catch(() => {}); }
        else if (hit === pop) { closeMini(); send({ t: "pip" }); }
        else if (v.paused) v.play().catch(() => {}); else v.pause();
      }
      e.preventDefault();
    });
    m.over.addEventListener("pointercancel", () => { down = false; });
    (document.body || document.documentElement).appendChild(m.over);
    mini = m;
    apply();
    placeBtn();
    m.gone = setInterval(() => { if (!v.isConnected) closeMini(); }, 1000);
  }
  function closeMini() {
    const m = mini;
    if (!m) return;
    mini = null;
    clearInterval(m.gone);
    m.v.style.cssText = m.css;
    for (const [p, css] of m.undo) p.style.cssText = css;
    m.over.remove();
    queuePlace();
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