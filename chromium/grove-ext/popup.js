'use strict';
const $ = id => document.getElementById(id);
const say = t => { $('msg').textContent = t; };
for (const m of ['view', 'save']) $(m).onclick = () => { say(m === 'save' ? 'saving…' : 'opening…'); chrome.runtime.sendMessage({ reader: m }, r => { if (r && r.err) say(r.err); else window.close(); }); };
for (const m of ['mini', 'pip']) $(m).onclick = () => chrome.runtime.sendMessage({ tabCmd: m }, () => window.close());
$('home').onclick = () => { chrome.tabs.create({ url: chrome.runtime.getURL('home.html') }); window.close(); };
