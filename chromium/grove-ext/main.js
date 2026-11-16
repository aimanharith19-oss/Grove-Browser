// Grove (runs in the page itself): media keeps playing when you switch apps or turn the screen off, and YouTube's
// "last activity" clock stays fresh so it doesn't pause with "Video paused. Continue watching?".
(() => {
  try {
    Object.defineProperty(Document.prototype, 'hidden', { configurable: true, get() { return false; } });
    Object.defineProperty(Document.prototype, 'visibilityState', { configurable: true, get() { return 'visible'; } });
    Object.defineProperty(Document.prototype, 'webkitHidden', { configurable: true, get() { return false; } });
    Object.defineProperty(Document.prototype, 'webkitVisibilityState', { configurable: true, get() { return 'visible'; } });
  } catch (e) { }
  const stop = e => e.stopImmediatePropagation();
  window.addEventListener('visibilitychange', stop, true);
  window.addEventListener('webkitvisibilitychange', stop, true);
  document.addEventListener('visibilitychange', stop, true);
  if (/(^|\.)youtube(-nocookie)?\.com$/.test(location.hostname)) setInterval(() => { try { window._lact = Date.now(); } catch (e) { } }, 60000);
})();
