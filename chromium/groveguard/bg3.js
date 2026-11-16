// Groveguard browser extension — background: dangerous-site warning page + uBlock-style content blocking.
// SPDX-License-Identifier: AGPL-3.0-or-later
const api = chrome; // Chromium, Manifest V3
const api_unused = typeof browser !== 'undefined' ? browser : chrome;
const DANGER = 'https://maintainteam.github.io/HypatiaDatabases/hypatia-domains-bloom.bin';
const DAY = 24 * 3600 * 1000;
let bloom = null, rules = null, adult = null, loading = null;
const counts = new Map(); // tabId -> blocked requests on the current page
const LOG = []; const LOG_MAX = 1000; // recent requests, newest last (kept in memory only)
function logMem(type, url, page, verdict, why) {
  let host = ''; try { host = new URL(url).hostname; } catch (e) { }
  LOG.push({ t: Date.now(), type, host, url: url.length > 300 ? url.slice(0, 300) + '…' : url, page: page || '', verdict, why: why || '' });
  if (LOG.length > LOG_MAX) LOG.splice(0, LOG.length - LOG_MAX);
  // linked to the Groveguard app: pages and blocked requests also go to its web tab log
  if (cache && cache.appKey && (type === 'page' || verdict === 'blocked') && LOGBOX.length < 2000)
    LOGBOX.push({ t: Date.now(), type, h: host, u: url.length > 2000 ? url.slice(0, 2000) : url, page: page || '', b: verdict === 'blocked', w: why || '' });
}
const LOGBOX = [];
// ---------------------------------------------------------------- 18+ lists: a plain set of sites (hundreds of thousands — Rules would be too heavy)
function domainOf(line) {
  let s = line.trim();
  if (!s || '!#['.includes(s[0]) || s.includes('##') || s.includes('#@#') || s.startsWith('@@')) return null;
  const hash = s.indexOf('#'); if (hash > 0) s = s.slice(0, hash).trim();
  if (s.startsWith('||')) {
    const m = /^\|\|([^\^\/$|]+)(.*)$/.exec(s); if (!m) return null;
    if (!(m[2] === '' || m[2] === '^' || m[2].startsWith('^$') || m[2] === '^|')) return null;
    s = m[1];
  } else {
    const p = s.split(/\s+/);
    if (p.length >= 2 && ['0.0.0.0', '127.0.0.1', '::', '::1'].includes(p[0])) s = p[1]; else if (p.length === 1) s = p[0]; else return null;
    if (s.startsWith('*.')) s = s.slice(2); else if (s.startsWith('.')) s = s.slice(1);
  }
  s = s.toLowerCase();
  if (s.length < 3 || !s.includes('.') || s === 'localhost' || s === '0.0.0.0' || !/^[a-z0-9._-]+$/.test(s)) return null;
  return s;
}
/** the 18+ list (and its entry) that covers this site, or null */
function adultHit(host) {
  if (!adult || !host) return null;
  let s = host.toLowerCase();
  for (;;) {
    for (const [name, set] of adult) if (set.has(s)) return { name, matched: s };
    const d = s.indexOf('.'); if (d < 0 || s.indexOf('.', d + 1) < 0) return null;
    s = s.slice(d + 1);
  }
}
function mine(list, host) { return host && list ? list.find(p => host === p || host.endsWith('.' + p)) || null : null; }
/** the block page, with why (same wording as Grove Launcher's) */
function blockUrl(kind, host, why, matched) {
  return api.runtime.getURL('adult.html') + '?k=' + kind + '&h=' + encodeURIComponent(host) + '&w=' + encodeURIComponent(why || '') + '&m=' + encodeURIComponent(matched || '');
}
// ---------------------------------------------------------------- history (every website opened, kept in this browser) + sync to the Groveguard app
const HIST_MAX = 20000;
let HIST = null, histDirty = false, OUTBOX = null;
const BROWSER = navigator.userAgent.includes('Firefox') ? 'Firefox' : navigator.userAgent.includes('Edg/') ? 'Edge' : 'Chromium';
async function histLoad() { if (!HIST) { HIST = (await kv('history')) || []; OUTBOX = (await kv('outbox')) || []; } }
function visit(url, blocked, why) {
  let host = ''; try { host = new URL(url).hostname; } catch (e) { return; }
  const v = { t: Date.now(), h: host, u: url.length > 2000 ? url.slice(0, 2000) : url, b: !!blocked, w: why || '' };
  histLoad().then(() => {
    const last = HIST[HIST.length - 1];
    if (last && last.u === v.u && v.t - last.t < 3000) return;
    HIST.push(v); if (HIST.length > HIST_MAX) HIST.splice(0, HIST.length - HIST_MAX);
    if (cache && cache.appKey) { OUTBOX.push(v); if (OUTBOX.length > 5000) OUTBOX.splice(0, OUTBOX.length - 5000); }
    if (!histDirty) { histDirty = true; setTimeout(histFlush, 2000); }
  });
}
async function histFlush() {
  histDirty = false;
  try { await kv('history', HIST); await kv('outbox', OUTBOX); } catch (e) { }
  syncApp();
}
let syncing = false, appState = '';
/** sends new visits to the Groveguard app on this phone (it listens only on 127.0.0.1, and needs the pairing code) */
async function syncApp() {
  if (syncing || !cache || !cache.appKey) return;
  await histLoad();
  syncing = true;
  try {
    const st = await pullSettings();
    appState = st === 403 ? 'wrong code' : st === 200 ? 'ok' : 'error ' + st;
    while (appState === 'ok' && OUTBOX.length) {
      const batch = OUTBOX.slice(0, 200);
      const r = await appPost('/v1/visits', batch);
      if (!r.ok) { appState = r.status === 403 ? 'wrong code' : 'error ' + r.status; break; }
      OUTBOX.splice(0, batch.length);
      await kv('outbox', OUTBOX);
    }
    while (appState === 'ok' && LOGBOX.length) { const r = await appPost('/v1/log', LOGBOX.slice(0, 300)); if (!r.ok) break; LOGBOX.splice(0, 300); }
  } catch (e) { appState = 'app not reachable'; }
  syncing = false;
}
function appPost(path, body) {
  return fetch('http://127.0.0.1:47913' + path, { method: 'POST', cache: 'no-store', credentials: 'omit',
    headers: { 'Content-Type': 'text/plain', 'X-Groveguard-Key': cache.appKey, 'X-Groveguard-Browser': BROWSER }, body: JSON.stringify(body) });
}
/** use the app's switches, lists and rules (the app's web tab controls the extension once linked) */
async function pullSettings() {
  const r = await fetch('http://127.0.0.1:47913/v1/settings', { cache: 'no-store', credentials: 'omit', headers: { 'X-Groveguard-Key': cache.appKey } });
  if (!r.ok) return r.status;
  const a = await r.json(), s = await settings();
  let listsChanged = false;
  for (const l of LISTS) if (a.lists && a.lists[l.id] !== undefined && !!s.lists[l.id] !== !!a.lists[l.id]) { s.lists[l.id] = !!a.lists[l.id]; listsChanged = true; }
  s.blocking = !!a.blocking; s.myBlock = a.myBlock || []; s.myAllow = a.myAllow || []; s.paused = a.paused || [];
  s.fromApp = Date.now();
  await save(s); await refreshCache(); await userChanged();
  if (listsChanged) {
    for (const l of LISTS) if (s.lists[l.id] && !(await kv('list:' + l.id))) { try { await downloadList(l); } catch (e) { } }
    await rebuild();
  }
  return 200;
}
// ---------------------------------------------------------------- element hiding + scriptlets
/** a page (cosmetic.js) asking for its cosmetic filters, or reporting the class/id names it uses */
async function cosmetic(m, sender) {
  try {
    if (!sender || !sender.tab) return;
    if (!cache) await refreshCache();
    const host = String(m.cos !== undefined ? m.cos : m.host || '').toLowerCase();
    if (!host || !cache.blocking || pausedOn(host) || inList(cache.myAllow, host)) return;
    const r = await getCos();
    if (!r) return;
    const css = m.survey ? surveyCss(r, host, m.survey.slice(0, 5000)) : cssFor(r, host);
    if (css) insertCss(sender.tab.id, sender.frameId || 0, css);
  } catch (e) { }
}
/** one script holding every site's scriptlets; inside a page it picks that page's and runs them (registered to run before the page's own scripts) */
function scriptletCode(r) {
  const T = {}, X = {};
  for (const [h, l] of r.js) T[h] = l;
  for (const [h, l] of r.jsEx) X[h] = l.map(n => SCRIPTLET_ALIAS[n] || n);
  const j = o => JSON.stringify(o).replace(/</g, '\\u003c');
  return '(function(){var T=' + j(T) + ',X=' + j(X) + ',h=location.hostname.toLowerCase(),out=[],off={},seen={},c,d,i,l,k;' +
    'for(c=h;;){l=X[c];if(l)for(i=0;i<l.length;i++)off[l[i]]=1;d=c.indexOf(".");if(d<0)break;c=c.slice(d+1);}' +
    'for(c=h;;){l=T[c];if(l)for(i=0;i<l.length;i++){k=JSON.stringify(l[i]);if(!off[l[i][0]]&&!seen[k]){seen[k]=1;out.push(l[i]);}}d=c.indexOf(".");if(d<0)break;c=c.slice(d+1);}' +
    'if(out.length)(' + groveScriptlets.toString() + ')(out);})();';
}
const hostPattern = h => '*://*.' + h + '/*';
function scriptletMatches(r) { return [...r.js.keys()].filter(h => /^[a-z0-9.-]+$/.test(h)).map(hostPattern); }
function scriptletExcludes() { return cache.paused.concat(cache.myAllow).filter(h => /^[a-z0-9.-]+$/.test(h)).map(hostPattern); }
/** (fallback, can be late) runs this site's scriptlets inside the page */
async function scriptlets(tabId, frameId, url) {
  try {
    if (!cache) await refreshCache();
    const host = hostOf(url);
    if (!host || !/^https?:/.test(url) || !cache.blocking || pausedOn(host) || inList(cache.myAllow, host)) return;
    const r = await getCos();
    const list = r ? scriptletsFor(r, host) : [];
    if (!list.length) return;
    const viaTag = () => {
      const code = '(' + groveScriptlets.toString() + ')(' + JSON.stringify(list) + ');';
      const inject = 'try{var s=document.createElement("script");s.textContent=' + JSON.stringify(code) + ';(document.head||document.documentElement).appendChild(s);s.remove();}catch(e){}';
      try { api.tabs.executeScript(tabId, { frameId, runAt: 'document_start', code: inject }); } catch (e) { }
    };
    if (api.scripting && api.scripting.executeScript) {
      try {
        const p = api.scripting.executeScript({ target: { tabId, frameIds: [frameId] }, world: 'MAIN', injectImmediately: true, func: groveScriptlets, args: [list] });
        if (p && p.catch) p.catch(() => { if (api.tabs.executeScript) viaTag(); });
      } catch (e) { if (api.tabs.executeScript) viaTag(); }
    } else viaTag();
  } catch (e) { }
}
/** host or a parent of it is in the list */
function inList(list, host) { return !!host && !!list && list.some(p => host === p || host.endsWith('.' + p)); }

// ---------------------------------------------------------------- storage (IndexedDB)
function db() {
  return new Promise((ok, no) => {
    const r = indexedDB.open('groveguard', 1);
    r.onupgradeneeded = () => r.result.createObjectStore('kv');
    r.onsuccess = () => ok(r.result); r.onerror = () => no(r.error);
  });
}
async function kv(key, val) {
  const d = await db();
  return new Promise((ok, no) => {
    const tx = d.transaction('kv', val === undefined ? 'readonly' : 'readwrite');
    const s = tx.objectStore('kv');
    const r = val === undefined ? s.get(key) : (val === null ? s.delete(key) : s.put(val, key));
    r.onsuccess = () => ok(r.result); r.onerror = () => no(r.error);
  });
}
async function settings() {
  const s = (await kv('settings')) || {};
  if (!s.lists) { s.lists = {}; for (const l of LISTS) s.lists[l.id] = l.on; }
  for (const l of LISTS) if (l.adult) s.lists[l.id] = true; // 18+ lists are locked on
  if (s.blocking === undefined) s.blocking = true;
  if (s.danger === undefined) s.danger = true;
  if (!s.paused) s.paused = [];
  if (!s.myBlock) s.myBlock = [];
  if (!s.myAllow) s.myAllow = [];
  if (!s.allowed) s.allowed = [];
  if (s.custom === undefined) s.custom = '';
  return s;
}
async function save(s) { await kv('settings', s); }

// ---------------------------------------------------------------- lists
async function downloadDanger() {
  const res = await fetch(DANGER, { cache: 'no-store' });
  if (!res.ok) throw new Error('HTTP ' + res.status);
  const buf = await res.arrayBuffer();
  const b = new Bloom(buf);
  await kv('danger', buf); await kv('danger-updated', Date.now());
  bloom = b;
}
async function downloadList(l) {
  if (l.adult) {
    const text = await fetchList(l.url), out = [];
    for (const line of text.split('\n')) { const d = domainOf(line); if (d) out.push(d); }
    if (out.length < 100) throw new Error('the list looks empty');
    await kv('list:' + l.id, { domains: out });
    const meta = (await kv('meta')) || {};
    meta[l.id] = { updated: Date.now(), rules: out.length };
    await kv('meta', meta);
    return;
  }
  const r = new Rules().parse(await fetchList(l.url));
  await kv('list:' + l.id, r.toJSON());
  const meta = (await kv('meta')) || {};
  meta[l.id] = { updated: Date.now(), rules: r.size() };
  await kv('meta', meta);
}
/** Rebuilds the active rules from the switched-on lists (+ your own filters). */
async function loadRules() {
  const s = await settings(), r = new Rules(), ad = new Map();
  let anyAdult = false;
  for (const l of LISTS) {
    if (!s.lists[l.id]) continue;
    const j = await kv('list:' + l.id);
    if (!j) continue;
    if (l.adult) { if (j.domains) { ad.set(l.name, new Set(j.domains)); anyAdult = true; } else await kv('list:' + l.id, null); } // old format: download again
    else r.merge(Rules.fromJSON(j));
  }
  if (s.custom) r.parse(s.custom);
  rules = r;
  adult = anyAdult ? ad : null;
  if (typeof registerScriptlets === 'function' && typeof DNR === 'undefined') registerScriptlets();
  return r;
}
/** Downloads what's missing or older than a day (force = everything). */
async function updateAll(force) {
  const s = await settings(), meta = (await kv('meta')) || {}, errors = [];
  for (const l of LISTS) {
    if (!s.lists[l.id]) continue;
    if (!force && meta[l.id] && Date.now() - meta[l.id].updated < DAY) continue;
    try { await downloadList(l); } catch (e) { errors.push(l.name + ': ' + e.message); }
  }
  if (s.danger && (force || Date.now() - ((await kv('danger-updated')) || 0) > DAY)) {
    try { await downloadDanger(); } catch (e) { errors.push('Dangerous-site list: ' + e.message); }
  }
  await kv('last-update', Date.now());
  await rebuild();
  return errors;
}
async function ensureOld() {
  if (rules) return;
  if (!loading) loading = (async () => {
    const buf = await kv('danger');
    if (buf) bloom = new Bloom(buf);
    await rebuild();
    if ((await kv('fmt')) !== 2) { await kv('fmt', 2); if (await kv('meta')) { await updateAll(true); return; } } // older downloads have no generic cosmetic filters or scriptlets
    if (!(await kv('meta')) || !buf) await updateAll(false);
  })().finally(() => { loading = null; });
  return loading;
}

// ---------------------------------------------------------------- checks
let cache = null; // settings snapshot for the synchronous request handler
async function refreshCache() { cache = await settings(); }
function hostOf(u) { try { return new URL(u).hostname; } catch (e) { return ''; } }
function pausedOn(host) { return !!host && cache && cache.paused.some(p => host === p || host.endsWith('.' + p)); }

// ================================================================ Manifest V3 (Chromium: Vivaldi, Chrome, Edge, Brave)
// Chromium no longer runs Manifest V2 extensions, so blocking is done with declarativeNetRequest rules that the
// browser itself applies. This file turns the lists and your rules into those rules.
const DNR = chrome.declarativeNetRequest;
const SUB = ['sub_frame', 'stylesheet', 'script', 'image', 'font', 'object', 'xmlhttprequest', 'ping', 'media', 'websocket', 'webtransport', 'webbundle', 'other'];
const ALL = ['main_frame'].concat(SUB);
const P_FILTER = 10, P_FALLOW = 12, P_OFF = 15, P_PAUSE = 20, P_BLOCK = 30, P_ALLOW = 40, P_ADULT = 50;
const ID_LISTS = 1000, ID_ADULT = 1000, ID_FILTER = 3000; // < 1000: your rules; 1000-2999: 18+; 3000+: filter lists
const CHUNK = 20000, MAX_RULES = 29000;
const EXT = chrome.runtime.getURL('');
function pageRule(id, priority, domains, kind, why) {
  return { id, priority, action: { type: 'redirect', redirect: { regexSubstitution: EXT + 'adult.html?k=' + kind + '&w=' + encodeURIComponent(why) + '#\\0' } },
    condition: { regexFilter: '^https?://.*', requestDomains: domains, resourceTypes: ['main_frame'] } };
}
/** your rules, paused sites and the main switch (cheap: a handful of rules) */
async function applyUser() {
  const s = cache || await settings(), add = [];
  if (s.myAllow.length) add.push({ id: 10, priority: P_ALLOW, action: { type: 'allow' }, condition: { requestDomains: s.myAllow, resourceTypes: ALL } });
  if (s.myBlock.length) {
    add.push(pageRule(11, P_BLOCK, s.myBlock, 'you', 'your block list'));
    add.push({ id: 12, priority: P_BLOCK, action: { type: 'block' }, condition: { requestDomains: s.myBlock, resourceTypes: SUB } });
  }
  if (s.paused.length) add.push({ id: 13, priority: P_PAUSE, action: { type: 'allowAllRequests' }, condition: { requestDomains: s.paused, resourceTypes: ['main_frame', 'sub_frame'] } });
  if (!s.blocking) add.push({ id: 14, priority: P_OFF, action: { type: 'allow' }, condition: { urlFilter: '*', resourceTypes: ALL } });
  const old = (await DNR.getDynamicRules()).map(r => r.id).filter(id => id < ID_LISTS);
  try { await DNR.updateDynamicRules({ removeRuleIds: old, addRules: add }); } catch (e) { console.warn('Groveguard: your rules', e); }
}
function okFilter(f) { return f && f.length < 1000 && /^[\x21-\x7e]+$/.test(f) && !f.startsWith('||*') && f !== '*' && f !== '|'; }
/** adds rules in batches; a batch the browser refuses is split until the bad rule is found and dropped */
async function addRules(list) {
  let added = 0;
  const put = async part => {
    if (!part.length) return;
    try { await DNR.updateDynamicRules({ addRules: part }); added += part.length; }
    catch (e) { if (part.length === 1) return; const m = part.length >> 1; await put(part.slice(0, m)); await put(part.slice(m)); }
  };
  for (let i = 0; i < list.length; i += 1000) await put(list.slice(i, i + 1000));
  return added;
}
/** the 18+ lists and the filter lists → browser rules (heavy: only when lists change) */
async function applyLists() {
  const r = rules, ad = adult, add = [];
  let id = ID_ADULT;
  if (ad) for (const [name, set] of ad) {
    const d = [...set];
    for (let i = 0; i < d.length; i += CHUNK) {
      const part = d.slice(i, i + CHUNK);
      add.push(pageRule(id++, P_ADULT, part, 'adult', '18+ list: ' + name));
      add.push({ id: id++, priority: P_ADULT, action: { type: 'block' }, condition: { requestDomains: part, resourceTypes: SUB } });
    }
  }
  const adultRules = add.length;
  id = ID_FILTER;
  let skipped = 0;
  if (r) {
    const dom = (set, extra, action, priority) => {
      const d = [...set].filter(h => /^[a-z0-9._-]+$/.test(h));
      for (let i = 0; i < d.length; i += CHUNK) add.push({ id: id++, priority, action: { type: action }, condition: Object.assign({ requestDomains: d.slice(i, i + CHUNK), resourceTypes: action === 'allow' ? ALL : SUB }, extra) });
    };
    dom(r.allow, {}, 'allow', P_FALLOW);
    dom(r.block, {}, 'block', P_FILTER);
    dom(r.block3p, { domainType: 'thirdParty' }, 'block', P_FILTER);
    const pat = (f, extra, action, priority) => {
      if (!okFilter(f)) return;
      if (id - ID_FILTER + adultRules >= MAX_RULES) { skipped++; return; }
      add.push({ id: id++, priority, action: { type: action }, condition: Object.assign({ urlFilter: f, isUrlFilterCaseSensitive: false, resourceTypes: action === 'allow' ? ALL : SUB }, extra) });
    };
    const g = p => p[0] === '|' ? p : p; // "|…" = anchored at the start of the address (same meaning in both engines)
    for (const l of r.genAllow.values()) for (const p of l) pat(g(p), {}, 'allow', P_FALLOW);
    for (const [h, l] of r.paths) for (const p of l) pat('||' + h + p, {}, 'block', P_FILTER);
    for (const [h, l] of r.paths3p) for (const p of l) pat('||' + h + p, { domainType: 'thirdParty' }, 'block', P_FILTER);
    for (const l of r.gen.values()) for (const p of l) pat(g(p), {}, 'block', P_FILTER);
    for (const l of r.gen3p.values()) for (const p of l) pat(g(p), { domainType: 'thirdParty' }, 'block', P_FILTER);
    await kv('cosmetic', r.cosmeticJSON());
    cos = null;
  }
  const old = (await DNR.getDynamicRules()).map(x => x.id).filter(x => x >= ID_LISTS);
  for (let i = 0; i < old.length; i += 5000) await DNR.updateDynamicRules({ removeRuleIds: old.slice(i, i + 5000) });
  const added = await addRules(add);
  await kv('rule-count', { filters: r ? r.size() : 0, browserRules: added, skipped, adult: ad ? [...ad.values()].reduce((n, s) => n + s.size, 0) : 0 });
  return added;
}
/** load the lists, hand them to the browser, then free the memory (the service worker is short-lived anyway) */
let applying = null;
async function rebuild() {
  if (applying) { await applying.catch(() => {}); }
  applying = (async () => { await loadRules(); await applyLists(); await applyUser(); await registerScriptlets(); rules = null; adult = null; })();
  try { await applying; } finally { applying = null; }
}
/** Chromium: the scriptlets are registered as a user script in the page's own world, so they run before the page's scripts.
 *  Needs "Allow user scripts" switched on for Groveguard on the extensions page; without it they still run, but can be late. */
function userScriptsApi() { try { return chrome.userScripts && chrome.userScripts.register ? chrome.userScripts : null; } catch (e) { return null; } }
async function registerScriptlets() {
  const us = userScriptsApi();
  await kv('user-scripts', !!us);
  if (!us) return false;
  try { await us.unregister({ ids: ['gg-scriptlets'] }); } catch (e) { }
  if (!cache) await refreshCache();
  if (!cache.blocking) return true;
  const r = await getCos(), matches = scriptletMatches(r);
  if (!matches.length) return true;
  const o = { id: 'gg-scriptlets', matches, js: [{ code: scriptletCode(r) }], runAt: 'document_start', world: 'MAIN', allFrames: true };
  const ex = scriptletExcludes();
  if (ex.length) o.excludeMatches = ex;
  try { await us.register([o]); } catch (e) { console.warn('Groveguard: scriptlets', e); return false; }
  return true;
}
async function userChanged() { await applyUser(); await registerScriptlets(); }
async function ensure() {
  if (!bloom) { const buf = await kv('danger'); if (buf) bloom = new Bloom(buf); }
  if ((await kv('fmt')) !== 2) { await kv('fmt', 2); if (await kv('meta')) { await updateAll(true); return; } } // older downloads have no generic cosmetic filters or scriptlets
  if (!(await kv('meta')) || !(await kv('danger-updated'))) await updateAll(false);
}
/** the locked-on 18+ lists: download any that aren't here yet */
async function ensureAdult() {
  const meta = (await kv('meta')) || {};
  let got = false;
  for (const l of LISTS) if (l.adult && !meta[l.id]) { try { await downloadList(l); got = true; } catch (e) { } }
  if (got) await rebuild();
}
async function matchedCount(tabId) {
  if (tabId === undefined) return 0;
  try { const m = await DNR.getMatchedRules({ tabId }); return m.rulesMatchedInfo.length; } catch (e) { return 0; }
}
// ---- log (kept across service-worker restarts)
let logReady = null, logSave = 0;
function logLoad() { if (!logReady) logReady = kv('log').then(l => { if (l && l.length) LOG.unshift(...l.slice(-LOG_MAX)); }).catch(() => {}); return logReady; }
function log(type, url, page, verdict, why) {
  logLoad().then(() => {
    logMem(type, url, page, verdict, why);
    if (!logSave) logSave = setTimeout(() => { logSave = 0; kv('log', LOG).catch(() => {}); if (LOGBOX.length) syncApp(); }, 1500);
  });
}
function whyOf(id) {
  if (id === 11 || id === 12) return 'your block list';
  if (id >= ID_ADULT && id < ID_FILTER) return '18+ list';
  return 'filter lists';
}
// blocked requests (only reported for an unpacked "developer mode" install, which is how this extension is loaded)
if (DNR.onRuleMatchedDebug) DNR.onRuleMatchedDebug.addListener(info => {
  const q = info.request, t = info.rule.ruleId;
  if (t === 10 || t === 13 || t === 14 || q.type === 'main_frame') return;
  DNR.getDynamicRules({ ruleIds: [t] }).then(rs => {
    if (rs[0] && rs[0].action.type !== 'block') return;
    log(q.type, q.url, hostOf(q.initiator || q.documentUrl || ''), 'blocked', whyOf(t));
  }).catch(() => {});
});
try { DNR.setExtensionActionOptions({ displayActionCountAsBadgeText: true }); } catch (e) { }

// ---- whole pages: history, log, dangerous-site warning
const warned = new Map();
chrome.webNavigation.onBeforeNavigate.addListener(async d => {
  if (d.frameId !== 0) return;
  let u; try { u = new URL(d.url); } catch (e) { return; }
  if (u.protocol !== 'http:' && u.protocol !== 'https:') return;
  try {
    if (!cache) await refreshCache();
    const s = cache;
    if (!s.danger || inList(s.myAllow, u.hostname)) return;
    if (!bloom) { const buf = await kv('danger'); if (buf) bloom = new Bloom(buf); }
    if (!bloom) return;
    const hit = listed(bloom, u.hostname, s.allowed);
    if (!hit) return;
    warned.set(d.tabId, d.url);
    log('page', d.url, '', 'blocked', 'dangerous-site list'); visit(d.url, true, 'dangerous-site list');
    chrome.tabs.update(d.tabId, { url: chrome.runtime.getURL('warning.html') + '?u=' + encodeURIComponent(d.url) + '&l=' + encodeURIComponent(hit) });
  } catch (e) { }
});
// element hiding and scriptlets: a light copy of the cosmetic rules, loaded when the service worker wakes
let cos = null;
async function getCos() { if (!cos) { const j = await kv('cosmetic'); cos = j ? Rules.fromJSON(j) : new Rules(); } return cos; }
function insertCss(tabId, frameId, css) { chrome.scripting.insertCSS({ target: { tabId, frameIds: [frameId] }, css, origin: 'USER' }).catch(() => {}); }
chrome.webNavigation.onCommitted.addListener(async d => {
  try {
    if (!cache) await refreshCache();
    if (d.frameId === 0) {
      if (d.url.startsWith(EXT + 'adult.html')) { // a page the browser rules stopped: the real address is after the #
        const q = new URL(d.url), real = decodeURIComponent(q.hash.slice(1)) || q.searchParams.get('h') || '';
        const why = q.searchParams.get('w') || 'blocked';
        if (real) { log('page', real, '', 'blocked', why); visit(real, true, why); }
        return;
      }
      if (/^https?:/.test(d.url)) {
        if (warned.get(d.tabId) === d.url) warned.delete(d.tabId);
        else { const h = hostOf(d.url), w = inList(cache.myAllow, h) ? 'your allow list' : ''; log('page', d.url, '', 'allowed', w); visit(d.url, false, w); }
      }
    }
    scriptlets(d.tabId, d.frameId, d.url);
  } catch (e) { }
});
// ---------------------------------------------------------------- messages from the popup, options and warning pages
api.runtime.onMessage.addListener((m, sender, reply) => {
  if (m && (m.cos !== undefined || m.survey)) { cosmetic(m, sender); return false; } // from cosmetic.js in a page
  if (sender && sender.tab && sender.url && !sender.url.startsWith(api.runtime.getURL(''))) return false; // only Groveguard's own pages may change settings
  (async () => {
    if (cache && cache.appKey && (m.rule || m.setList || m.pause || m.blocking !== undefined)) {
      // linked: the app is in charge — save the change there too
      const body = m.rule ? { rule: m.rule } : m.setList ? { list: m.setList } : m.pause ? { pause: m.pause } : { blocking: m.blocking };
      appPost('/v1/set', body).catch(() => {});
    }
    const s = await settings();
    if (m.allow) { s.allowed.push(m.allow); await save(s); }
    if (m.setList && (LISTS.find(x => x.id === m.setList.id) || {}).adult) m.setList.on = true; // locked on
    if (m.setList) { s.lists[m.setList.id] = m.setList.on; await save(s); if (m.setList.on) { const l = LISTS.find(x => x.id === m.setList.id); if (l && !(await kv('list:' + l.id))) { try { await downloadList(l); } catch (e) { } } } await rebuild(); }
    if (m.blocking !== undefined) { s.blocking = m.blocking; await save(s); }
    if (m.danger !== undefined) { s.danger = m.danger; await save(s); }
    if (m.pause) { const i = s.paused.indexOf(m.pause.host); if (m.pause.on && i < 0) s.paused.push(m.pause.host); if (!m.pause.on && i >= 0) s.paused.splice(i, 1); await save(s); }
    if (m.custom !== undefined) { s.custom = m.custom; await save(s); await rebuild(); }
    if (m.unallow) { s.allowed = s.allowed.filter(x => x !== m.unallow); await save(s); }
    if (m.rule) { // { host, list: 'block'|'allow'|'none' } — your own rules, stronger than every filter list
      const h = String(m.rule.host || '').trim().toLowerCase().replace(/^https?:\/\//, '').replace(/[\/:].*$/, '').replace(/^\*\./, '');
      if (h) {
        s.myBlock = s.myBlock.filter(x => x !== h); s.myAllow = s.myAllow.filter(x => x !== h);
        if (m.rule.list === 'block') s.myBlock.push(h); else if (m.rule.list === 'allow') s.myAllow.push(h);
        await save(s);
      }
    }
    if (m.clearLog) { await logLoad(); LOG.length = 0; await kv('log', []); }
    if (m.history) { await histLoad(); return reply({ history: HIST.slice().reverse(), myBlock: s.myBlock, myAllow: s.myAllow, appKey: s.appKey || '', appState, pending: OUTBOX.length }); }
    if (m.clearHistory !== undefined) { await histLoad(); const cut = m.clearHistory ? Date.now() - m.clearHistory : Infinity; HIST = m.clearHistory ? HIST.filter(v => v.t >= cut) : []; await kv('history', HIST); }
    if (m.clearRecent) { await histLoad(); const cut = Date.now() - m.clearRecent; HIST = HIST.filter(v => v.t < cut); await kv('history', HIST); }
    if (m.deleteVisit) { await histLoad(); HIST = HIST.filter(v => !(v.t === m.deleteVisit.t && v.u === m.deleteVisit.u)); await kv('history', HIST); }
    if (m.appKey !== undefined) {
      const k = String(m.appKey).trim().toUpperCase().replace(/[^A-Z0-9]/g, '');
      s.appKey = k; await save(s); await refreshCache(); await histLoad();
      if (k && m.sendAll) { OUTBOX = HIST.slice(-5000); await kv('outbox', OUTBOX); }
      if (!k) { OUTBOX = []; await kv('outbox', OUTBOX); appState = ''; }
      await syncApp();
      return reply({ appState, pending: OUTBOX.length });
    }
    if (m.log) { await logLoad(); await refreshCache(); return reply({ log: LOG.slice(-500).reverse(), myBlock: cache.myBlock, myAllow: cache.myAllow }); }
    if (m.rule || m.pause || m.blocking !== undefined) { await refreshCache(); await userChanged(); }
    let errors = [];
    if (m.update) errors = await updateAll(true);
    await refreshCache();
    reply({
      ok: !errors.length, errors, userScripts: !!userScriptsApi(), settings: cache, lists: LISTS, meta: (await kv('meta')) || {},
      rules: ((await kv('rule-count')) || {}).filters || 0, ruleInfo: (await kv('rule-count')) || null, danger: !!(await kv('danger-updated')), dangerUpdated: (await kv('danger-updated')) || 0,
      lastUpdate: (await kv('last-update')) || 0, blockedHere: await matchedCount(m.tabId),
    });
  })();
  return true;
});


// ---------------------------------------------------------------- start-up and automatic updates
chrome.alarms.create('update', { periodInMinutes: 60 * 6, delayInMinutes: 2 });
chrome.alarms.create('sync', { periodInMinutes: 1 });
chrome.alarms.onAlarm.addListener(a => {
  if (a.name === 'update') updateAll(false).then(refreshCache).catch(() => {});
  if (a.name === 'sync') refreshCache().then(syncApp).then(ensureAdult).then(async () => {
    if (!!userScriptsApi() !== !!(await kv('user-scripts'))) await registerScriptlets(); // "Allow user scripts" was just switched on or off
  }).catch(() => {});
});
chrome.runtime.onInstalled.addListener(() => { refreshCache().then(ensure).then(() => updateAll(false)).then(ensureAdult).catch(e => console.warn('Groveguard: first update', e)); });
chrome.runtime.onStartup.addListener(() => { refreshCache().then(ensure).then(userChanged).then(syncApp).catch(() => {}); });
refreshCache().catch(() => {});
