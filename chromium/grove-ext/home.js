// Grove home: your favorites (bookmarks) as tiles with the site's picture and icon, one list per folder.
'use strict';
const $ = id => document.getElementById(id);
const fav = u => chrome.runtime.getURL('/_favicon/') + '?pageUrl=' + encodeURIComponent(u) + '&size=64';
const key = u => { try { const x = new URL(u); return x.hostname.replace(/^www\./, '') + x.pathname.replace(/\/$/, ''); } catch (e) { return u; } };
const host = u => { try { return new URL(u).hostname.replace(/^www\./, ''); } catch (e) { return u; } };
let pick = null;
try { pick = localStorage.getItem('folder'); } catch (e) { }

$('f').onsubmit = e => {
  e.preventDefault();
  const v = $('q').value.trim();
  if (!v) return;
  if (/^[a-z][a-z0-9+.-]*:\/\//i.test(v)) location.href = v;
  else if (/^[^\s/]+\.[a-z]{2,}(\/\S*)?$/i.test(v)) location.href = 'https://' + v;
  else if (chrome.search && chrome.search.query) chrome.search.query({ text: v, disposition: 'CURRENT_TAB' });
  else location.href = 'https://duckduckgo.com/?q=' + encodeURIComponent(v);
};

function tile(b, shots, i) {
  const a = document.createElement('a');
  a.className = 't'; a.href = b.url; a.style.animationDelay = Math.min(i, 12) * 30 + 'ms';
  const shot = shots['shot:' + key(b.url)];
  if (shot) { const im = new Image(); im.className = 'shot'; im.src = shot; im.alt = ''; a.appendChild(im); }
  else { const d = document.createElement('div'); d.className = 'big'; const im = new Image(); im.src = fav(b.url); im.alt = ''; d.appendChild(im); a.appendChild(d); }
  const bar = document.createElement('div'); bar.className = 'bar';
  const ic = new Image(); ic.src = fav(b.url); ic.alt = '';
  const s = document.createElement('span'); s.textContent = b.title || host(b.url);
  bar.append(ic, s); a.appendChild(bar);
  return a;
}

async function draw() {
  const [tree] = await chrome.bookmarks.getTree();
  const folders = [];
  const walk = (n, path) => {
    const marks = (n.children || []).filter(c => c.url && /^https?:/.test(c.url));
    if (marks.length) folders.push({ id: n.id, title: n.title || 'favorites', marks });
    for (const c of n.children || []) if (!c.url) walk(c, path.concat(c.title));
  };
  walk(tree, []);
  const shots = await chrome.storage.local.get(null);
  const lists = $('lists'); lists.textContent = '';
  const chips = $('folders'); chips.textContent = '';
  if (folders.length > 1) {
    for (const f of [{ id: null, title: 'all' }].concat(folders)) {
      const b = document.createElement('button'); b.textContent = f.title.toLowerCase(); b.className = (pick || null) === f.id ? 'on' : '';
      b.onclick = () => { pick = f.id; try { pick ? localStorage.setItem('folder', pick) : localStorage.removeItem('folder'); } catch (e) { } draw(); };
      chips.appendChild(b);
    }
  }
  let n = 0;
  for (const f of folders) {
    if (pick && f.id !== pick) continue;
    const h = document.createElement('h2'); h.textContent = f.title;
    const g = document.createElement('div'); g.className = 'grid';
    f.marks.forEach(b => g.appendChild(tile(b, shots, n++)));
    lists.append(h, g);
  }
  if (!folders.length) {
    const top = await chrome.topSites.get().catch(() => []);
    if (top.length) {
      const h = document.createElement('h2'); h.textContent = 'frequent';
      const g = document.createElement('div'); g.className = 'grid';
      top.slice(0, 8).forEach(b => g.appendChild(tile(b, shots, n++)));
      lists.append(h, g);
    }
    $('hint').textContent = 'star a page (☆ in the menu) and it shows up here as a tile with its picture. bookmark folders become separate lists.';
  } else $('hint').textContent = 'tiles show a picture of the site after you open it once. bookmark folders become separate lists.';
  // pages saved for offline reading
  const saved = (await chrome.storage.local.get('saved')).saved || [];
  const sv = $('saved'); sv.textContent = '';
  if (saved.length) {
    const h = document.createElement('h2'); h.textContent = 'saved pages'; sv.appendChild(h);
    for (const p of saved) {
      const r = document.createElement('div'); r.className = 'row';
      r.innerHTML = '<div><b></b><small></small></div><span class="dim">✕</span>';
      r.querySelector('b').textContent = p.title; r.querySelector('small').textContent = host(p.url) + ' · ' + new Date(p.time).toLocaleDateString();
      r.onclick = e => {
        if (e.target.classList.contains('dim')) { chrome.runtime.sendMessage({ unsave: p.id }, draw); return; }
        location.href = chrome.runtime.getURL('reader.html') + '?id=' + p.id;
      };
      sv.appendChild(r);
    }
  }
}
// "manifest" chrome:// pages are not tiles; topSites needs the permission — fall back silently
if (!chrome.topSites) chrome.topSites = { get: () => Promise.resolve([]) };
draw();
chrome.bookmarks.onCreated.addListener(draw); chrome.bookmarks.onRemoved.addListener(draw); chrome.bookmarks.onChanged.addListener(draw);
