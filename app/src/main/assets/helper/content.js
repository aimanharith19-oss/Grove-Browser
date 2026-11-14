// Grove helper: tells Grove when a video plays (for picture-in-picture), fills the window with the
// video while in picture-in-picture, and keeps media playing when Grove is in the background.
(() => {
  let bg = false, port = null, last = null, saved = null;
  try { port = browser.runtime.connectNative("grove"); } catch (e) { return; }
  const send = m => { try { port.postMessage(m); } catch (e) {} };

  port.onMessage.addListener(m => {
    if ("bg" in m) bg = !!m.bg;
    if ("pip" in m) pip(!!m.pip);
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
})();
