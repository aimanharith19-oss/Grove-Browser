// Grove: mini player button on videos (tap it and the video floats in a corner, draggable, while you keep browsing),
// picture-in-picture, and no YouTube "still watching?" interruptions.
(() => {
  if (window.top !== window) return;
  // ---------- mini player: a small button on every video; tap it and the video floats in a corner, draggable, while you keep browsing the page
  let miniOn = true, btn = null, btnFor = null, mini = null, saved = null;
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
    let sxp = 0, syp = 0, ox = 0, oy = 0, moved = false, down = false;
    m.over.addEventListener("pointerdown", e => { down = true; moved = false; sxp = e.clientX; syp = e.clientY; ox = m.x; oy = m.y; try { m.over.setPointerCapture(e.pointerId); } catch (er) {} e.preventDefault(); });
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
      if (!moved) { if (e.target === x) closeMini(); else if (v.paused) v.play().catch(() => {}); else v.pause(); }
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


  const playingVideo = () => [...document.querySelectorAll('video')].find(v => !v.paused && !v.ended && v.readyState > 1);
  const bestVideo = () => playingVideo() || [...document.querySelectorAll('video')].sort((a, b) => b.clientWidth * b.clientHeight - a.clientWidth * a.clientHeight)[0];
  chrome.runtime.onMessage.addListener(m => {
    const v = bestVideo();
    if (!v) return;
    if (m.cmd === 'mini') { if (mini) closeMini(); else openMini(v); }
    if (m.cmd === 'pip') { (document.pictureInPictureElement ? document.exitPictureInPicture() : v.requestPictureInPicture()).catch(() => openMini(v)); }
  });
})();
