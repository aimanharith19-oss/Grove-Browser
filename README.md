# Grove Browser (Chromium)

This repository builds **Grove Browser on the Chromium engine, with Chrome extensions** (Chrome Web Store, uBlock Origin, …).
It starts from the newest release of [Titanium Browser](https://github.com/jqssun/android-titanium-browser), an open-source
(GPL-2.0) Chromium fork for Android based on GrapheneOS's Vanadium, and `chromium/rebrand.py` turns it into Grove Browser:

- package `app.grove.browser` and the Grove signing key, so it installs over the Firefox-based Grove Browser
- the name Grove Browser and the Grove icon
- **Groveguard built in** (ads, trackers, 18+, dangerous sites; link it to the Groveguard app from its history page)

Building Chromium itself needs a large machine, so the GitHub workflow (`.github/workflows/chromium.yml`) rebrands
Titanium's published build instead; it checks daily for new Chromium versions. 

Installing extensions: open the Chrome Web Store, turn on **Desktop site** in the menu, then "Add to Chrome".

The earlier Firefox-engine (GeckoView) Grove Browser is kept on the [`gecko`](../../tree/gecko) branch.

Download: [Releases](../../releases) (`grove-browser-chromium-arm64.apk`).
