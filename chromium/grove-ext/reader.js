'use strict';
// shows a reader page (saved or just made) in a sandbox: no scripts run inside it
const id = new URLSearchParams(location.search).get('id');
chrome.storage.local.get('page:' + id).then(r => {
  const html = r['page:' + id];
  if (!html) { document.body.textContent = 'this saved page is missing'; return; }
  const f = document.getElementById('f');
  f.srcdoc = html.replace('<head>', '<head><base target="_top">');
  const t = /<title>([^<]*)<\/title>/.exec(html); if (t) document.title = t[1].replace(/&amp;/g, '&').replace(/&lt;/g, '<');
});
