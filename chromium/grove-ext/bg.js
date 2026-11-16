// Grove: tile pictures for the home page, reader view / offline pages.
'use strict';
const key = u => { try { const x = new URL(u); return x.hostname.replace(/^www\./, '') + x.pathname.replace(/\/$/, ''); } catch (e) { return u; } };
const pending = new Map();

async function isFavorite(url) {
  const k = key(url);
  try { const r = await chrome.bookmarks.search({ url }); if (r.length) return true; } catch (e) { }
  const all = await chrome.bookmarks.search({ query: new URL(url).hostname.replace(/^www\./, '') }).catch(() => []);
  return all.some(b => b.url && key(b.url) === k);
}
const b64 = buf => { let s = ''; const a = new Uint8Array(buf); for (let i = 0; i < a.length; i += 0x8000) s += String.fromCharCode.apply(null, a.subarray(i, i + 0x8000)); return btoa(s); };
/** picture of the page on screen, if it is one of your favorites (or top sites) */
async function snap(tabId) {
  try {
    const tab = await chrome.tabs.get(tabId);
    if (!tab.active || tab.status !== 'complete' || !/^https?:/.test(tab.url || '') || tab.incognito) return;
    if (!(await isFavorite(tab.url))) return;
    const url = tab.url;
    const shot = await chrome.tabs.captureVisibleTab(tab.windowId, { format: 'jpeg', quality: 70 });
    const bmp = await createImageBitmap(await (await fetch(shot)).blob());
    const w = 480, h = Math.min(360, Math.round(bmp.height * w / bmp.width));
    const srcH = Math.round(h * bmp.width / w);
    const c = new OffscreenCanvas(w, h);
    c.getContext('2d').drawImage(bmp, 0, 0, bmp.width, srcH, 0, 0, w, h);
    const blob = await c.convertToBlob({ type: 'image/jpeg', quality: 0.8 });
    await chrome.storage.local.set({ ['shot:' + key(url)]: 'data:image/jpeg;base64,' + b64(await blob.arrayBuffer()) });
  } catch (e) { }
}
function later(tabId) { clearTimeout(pending.get(tabId)); pending.set(tabId, setTimeout(() => { pending.delete(tabId); snap(tabId); }, 1500)); }
chrome.tabs.onUpdated.addListener((id, ch, tab) => { if (ch.status === 'complete' && tab.active) later(id); });
chrome.tabs.onActivated.addListener(i => later(i.tabId));
chrome.bookmarks.onCreated.addListener(() => chrome.tabs.query({ active: true }).then(t => t.forEach(x => later(x.id))));

// ---- reader view / save for offline reading
async function readerOf(tabId) {
  await chrome.scripting.executeScript({ target: { tabId }, files: ['Readability.js'] });
  const [r] = await chrome.scripting.executeScript({ target: { tabId }, files: ['reader-make.js'] });
  return r && r.result;
}
async function save(page, temp) {
  const id = temp ? 'view' : 'p' + Date.now(); // reader view reuses one slot; saved pages are kept
  await chrome.storage.local.set({ ['page:' + id]: page.html });
  if (!temp) {
    const saved = (await chrome.storage.local.get('saved')).saved || [];
    saved.unshift({ id, title: page.title, url: page.url, time: Date.now() });
    await chrome.storage.local.set({ saved });
  }
  return id;
}
chrome.runtime.onMessage.addListener((m, sender, reply) => {
  (async () => {
    if (m.reader) {
      const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
      if (!tab || !/^https?:/.test(tab.url || '')) return reply({ err: 'open a web page first' });
      let page;
      try { page = await readerOf(tab.id); } catch (e) { return reply({ err: "this page can't be turned into a reader page" }); }
      if (!page || page.err) return reply({ err: (page && page.err) || 'no article found on this page' });
      const id = await save(page, m.reader === 'view');
      if (m.reader === 'save') {
        const name = (page.title || 'page').replace(/[\\/:*?"<>|\n]/g, ' ').trim().slice(0, 60) + ' (grove reader).html';
        chrome.downloads.download({ url: 'data:text/html;charset=utf-8,' + encodeURIComponent(page.html), filename: name }).catch(() => {});
      }
      await chrome.tabs.create({ url: chrome.runtime.getURL('reader.html') + '?id=' + id, index: tab.index + 1 });
      return reply({ ok: true });
    }
    if (m.unsave) {
      const saved = ((await chrome.storage.local.get('saved')).saved || []).filter(p => p.id !== m.unsave);
      await chrome.storage.local.set({ saved }); await chrome.storage.local.remove('page:' + m.unsave);
      return reply({ ok: true });
    }
    if (m.tabCmd) {
      const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
      if (tab) chrome.tabs.sendMessage(tab.id, { cmd: m.tabCmd }).catch(() => {});
      return reply({ ok: true });
    }
  })();
  return true;
});
