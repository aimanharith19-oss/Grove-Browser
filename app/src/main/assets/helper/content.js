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

  // Looks and moves like Android's own picture-in-picture window: rounded, tap for dimmed controls, snaps to the side.
  const NS = "http://www.w3.org/2000/svg";
  const ICON = { play: "M8 5v14l11-7z", pause: "M6 19h4V5H6v14zm8-14v14h4V5h-4z", audio: "M12 1a9 9 0 0 0-9 9v7a3 3 0 0 0 3 3h3v-8H5v-2a7 7 0 0 1 14 0v2h-4v8h3a3 3 0 0 0 3-3v-7a9 9 0 0 0-9-9z",
    expand: "M5 5h6v2H7v4H5V5zm8 0h6v6h-2V7h-4V5zM5 13h2v4h4v2H5v-6zm12 0h2v6h-6v-2h4v-4z", close: "M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z",
    pop: "M19 7h-8v6h8V7zm2-4H3a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h18a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2zm0 16H3V5h18v14z" };
  const css = (el, st) => { for (const k in st) el.style.setProperty(k, st[k], "important"); return el; };
  function icon(name, size) {
    const b = css(document.createElement("div"), { width: size + "px", height: size + "px", display: "flex", "align-items": "center", "justify-content": "center", cursor: "pointer", "border-radius": "50%" });
    const svg = document.createElementNS(NS, "svg"); svg.setAttribute("viewBox", "0 0 24 24"); svg.setAttribute("width", Math.round(size * .62)); svg.setAttribute("height", Math.round(size * .62));
    const path = document.createElementNS(NS, "path"); path.setAttribute("fill", "#fff"); path.setAttribute("d", ICON[name]); svg.appendChild(path);
    css(svg, { "pointer-events": "none" }); b.appendChild(svg); b.setIcon = n => path.setAttribute("d", ICON[n]); return b;
  }
  const EASE = "cubic-bezier(.2,.9,.25,1)";
  function openMini(v) {
    if (mini || saved) return;
    const w0 = Math.round(Math.min(innerWidth * 0.62, 340)), ratio = v.videoWidth && v.videoHeight ? v.videoHeight / v.videoWidth : 9 / 16;
    const rr = Math.min(Math.max(ratio, 0.4), 1.8), h0 = Math.round(w0 * rr);
    const r0 = v.getBoundingClientRect();
    const m = { v, css: v.style.cssText, x: innerWidth - w0 - 12, y: innerHeight - h0 - 96, w: w0, h: h0, big: false, undo: [] };
    for (let p = v.parentElement; p && p !== document.documentElement; p = p.parentElement) {
      const cs = getComputedStyle(p);
      if (cs.transform !== "none" || cs.filter !== "none" || cs.perspective !== "none" || /paint|layout|strict|content/.test(cs.contain) || cs.willChange.includes("transform")) {
        m.undo.push([p, p.style.cssText]);
        for (const k of ["transform", "filter", "perspective", "contain", "will-change"]) p.style.setProperty(k, k === "will-change" ? "auto" : "none", "important");
      }
    }
    const box = (x, y, w, h, t) => ({ position: "fixed", left: x + "px", top: y + "px", width: w + "px", height: h + "px", right: "auto", bottom: "auto", margin: "0", opacity: "1", transform: "none",
      transition: t ? `left ${t}ms ${EASE}, top ${t}ms ${EASE}, width ${t}ms ${EASE}, height ${t}ms ${EASE}, opacity ${t}ms, transform ${t}ms ${EASE}` : "none" });
    const apply = t => {
      css(v, Object.assign(box(m.x, m.y, m.w, m.h, t), { "max-width": "none", "max-height": "none", "min-width": "0", "min-height": "0", "z-index": "2147483645", background: "#000",
        "object-fit": "contain", "border-radius": "14px", "box-shadow": "0 8px 28px rgba(0,0,0,.5)" }));
      css(m.over, Object.assign(box(m.x, m.y, m.w, m.h, t), { "z-index": "2147483647", "touch-action": "none", background: "transparent", "border-radius": "14px", overflow: "hidden" }));
    };
    m.over = document.createElement("div");
    m.ctl = css(document.createElement("div"), { position: "absolute", inset: "0", background: "rgba(0,0,0,.45)", opacity: "0", transition: "opacity 180ms", "pointer-events": "none" });
    m.over.appendChild(m.ctl);
    const row = css(document.createElement("div"), { position: "absolute", left: "0", right: "0", top: "50%", transform: "translateY(-50%)", display: "flex", "justify-content": "center", gap: "10px" });
    const aud = icon("audio", 40), exp = icon("expand", 44), pp = icon(v.paused ? "play" : "pause", 40);
    aud.setAttribute("aria-label", "audio only"); exp.setAttribute("aria-label", "back to the player"); pp.setAttribute("aria-label", "play or pause");
    row.append(aud, exp, pp); m.ctl.appendChild(row);
    const x = css(icon("close", 36), { position: "absolute", top: "2px", right: "2px" }); x.setAttribute("aria-label", "close");
    const pop = css(icon("pop", 36), { position: "absolute", top: "2px", left: "2px" }); pop.setAttribute("aria-label", "float over other apps");
    m.ctl.append(x, pop);
    const sync = () => pp.setIcon(v.paused ? "play" : "pause");
    v.addEventListener("play", sync); v.addEventListener("pause", sync); m.sync = sync;
    let shown = false, hideT = 0;
    const show = on => { shown = on; m.ctl.style.setProperty("opacity", on ? "1" : "0", "important"); clearTimeout(hideT); if (on) hideT = setTimeout(() => show(false), 3000); };
    m.show = show;
    let sxp = 0, syp = 0, ox = 0, oy = 0, moved = false, down = false, hit = null, lastTap = 0;
    m.over.addEventListener("pointerdown", e => { down = true; moved = false; hit = shown ? [aud, exp, pp, x, pop].find(b => { const r = b.getBoundingClientRect(); return e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom; }) || null : null; sxp = e.clientX; syp = e.clientY; ox = m.x; oy = m.y; try { m.over.setPointerCapture(e.pointerId); } catch (er) {} e.preventDefault(); });
    m.over.addEventListener("pointermove", e => {
      if (!down) return;
      const dx = e.clientX - sxp, dy = e.clientY - syp;
      if (!moved && Math.abs(dx) + Math.abs(dy) > 8) { moved = true; show(false); }
      if (moved) { m.x = ox + dx; m.y = Math.max(0, Math.min(innerHeight - m.h, oy + dy)); apply(0); }
      e.preventDefault();
    });
    m.over.addEventListener("pointerup", e => {
      if (!down) return;
      down = false; e.preventDefault();
      if (moved) {                                    // fling off the side closes it, otherwise it snaps to the nearest side
        if (m.x + m.w < 60 || m.x > innerWidth - 60) { v.pause(); closeMini("fade"); return; }
        snap(); apply(260); return;
      }
      const now = Date.now();
      if (!hit && now - lastTap < 300) {              // double tap: bigger / smaller, like Android
        m.big = !m.big; const k = m.big ? 1.45 : 1 / 1.45;
        m.w = Math.round(Math.min(m.w * k, innerWidth - 24)); m.h = Math.round(m.w * rr); snap(); apply(260); lastTap = 0; show(false); return;
      }
      lastTap = now;
      if (!shown || !hit) { show(!shown); return; }
      show(true);
      if (hit === x) { v.pause(); closeMini("fade"); }
      else if (hit === aud) closeMini("fade");                                   // audio only: the window goes, the sound keeps playing
      else if (hit === exp) { closeMini("back"); }
      else if (hit === pop) { closeMini(); send({ t: "pip" }); }
      else if (hit === pp) { if (v.paused) v.play().catch(() => {}); else v.pause(); }
    });
    m.over.addEventListener("pointercancel", () => { down = false; if (moved) { snap(); apply(260); } });
    const snap = () => { m.x = m.x + m.w / 2 < innerWidth / 2 ? 12 : innerWidth - m.w - 12; m.y = Math.max(12, Math.min(innerHeight - m.h - 12, m.y)); };
    m.onResize = () => { snap(); apply(0); }; addEventListener("resize", m.onResize);
    (document.body || document.documentElement).appendChild(m.over);
    mini = m;
    // grow out of the video's spot on the page into the corner
    const tx = m.x, ty = m.y; m.x = r0.left; m.y = r0.top; m.w = Math.max(60, r0.width); m.h = Math.max(40, r0.height);
    apply(0); void m.over.offsetWidth;
    m.x = tx; m.y = ty; m.w = w0; m.h = h0; requestAnimationFrame(() => apply(300));
    placeBtn();
    m.gone = setInterval(() => { if (!v.isConnected) closeMini(); }, 1000);
  }
  function closeMini(how) {
    const m = mini;
    if (!m) return;
    mini = null;
    clearInterval(m.gone); removeEventListener("resize", m.onResize);
    m.v.removeEventListener("play", m.sync); m.v.removeEventListener("pause", m.sync);
    const done = () => {
      m.v.style.cssText = m.css;
      for (const [p, c] of m.undo) p.style.cssText = c;
      m.over.remove();
      if (how === "back") { try { m.v.scrollIntoView({ block: "center", behavior: "smooth" }); } catch (er) {} if (m.v.paused) m.v.play().catch(() => {}); }
      queuePlace();
    };
    if (!how) return done();
    for (const el of [m.v, m.over]) css(el, { transition: `opacity 200ms, transform 200ms ${EASE}`, opacity: "0", transform: how === "back" ? "scale(1.08)" : "scale(.85)" });
    setTimeout(done, 210);
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