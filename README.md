# Grove Browser

A light, private Android browser with a Windows Phone-style interface, running on Mozilla's Gecko engine (GeckoView), the same engine Firefox uses.

- **Windows Phone IE look**: address bar at the bottom, tab counter, `•••` menu with lowercase items, square accent tiles, 20 WP accent colours, dark or light theme
- **Home screen = all your favorites** as speed-dial tiles (Vivaldi-style). Long-press a tile to edit it.
- **uBlock Origin included**: the official Mozilla-signed release is installed on first launch and blocks ads, trackers, malware, scam and crypto-mining sites. Its popup is in the `•••` menu.
- **Groveguard built in**: the Groveguard web protection extension (ads, trackers, 18+ sites, dangerous sites, website history and log) is part of the app, always on, and links to the Groveguard app with its pairing code (`•••` → extensions → groveguard → open → history).
- **Firefox add-ons**: install any add-on from addons.mozilla.org (`•••` → extensions → get firefox add-ons). Each one asks before getting permissions and is never allowed to send usage data.
- **HTTPS options**: HTTPS-First (default), HTTPS-Only, or allow HTTP
- **Open links in apps**: ask, always, or never (e.g. youtube.com → YouTube app)
- **Web apps (PWA)**: `•••` → install app puts a site on your home screen as its own app window
- **Picture-in-picture for any video**: going home while a video plays pops it into a floating window
- **Background play for any media**: audio and video keep playing with Grove in the background, with lock-screen and headset controls
- **Back up and restore favorites**: also imports bookmark files from Firefox, Chrome, Vivaldi and others
- **Removed**: telemetry and data collection, studies and remote experiments ("remote improvements"), Firefox AI features, VPN, sponsored shortcuts, Pocket and Mozilla accounts
- **Hardened**: strict tracking protection, cookies isolated per site, HTTPS-First, tracking parameters stripped from links (utm_, fbclid, gclid…), Global Privacy Control, pop-up blocking, autoplay blocked, WebRTC IP-leak protection, no link prefetching, camera, mic, location and notifications denied
- **Safety fixes**: pages can't launch apps without a tap, can't target private app components through `intent:` links, and can't spam dialogs from background tabs. Other apps can only send http(s) links.
- **Linked with Grove Launcher**: bookmarks and history are shared both ways with Grove Launcher on the same phone (only that app is allowed, nothing is deleted, nothing leaves the phone)
- **Light**: one APK per CPU type, R8 shrinking, compressed native libraries, background tabs paused, restored tabs load only when opened

## Download
Open **Actions → latest build → Artifacts → grove-browser**. Install `app-arm64-v8a-release.apk` on almost any modern phone.
Push a tag such as `v1.0.0` to publish the APKs as a GitHub Release.

## Build locally
`gradle assembleRelease` needs JDK 17 and the Android SDK. Put the unpacked uBlock Origin Firefox `.xpi` in `app/src/main/assets/extensions/ublock/` first; the CI workflow does this automatically.
