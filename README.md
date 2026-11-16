# Grove Browser

A light, private Android browser with a Windows Phone-style interface, running on Mozilla's Gecko engine (GeckoView), the same engine Firefox uses.

- **Windows Phone IE look**: address bar at the bottom, tab counter, `•••` menu with lowercase items, square accent tiles, 20 WP accent colours, dark or light theme
- **Home screen = all your favorites** as speed-dial tiles (Vivaldi-style). Long-press a tile to edit it.
- **uBlock Origin included**: the official Mozilla-signed release is installed on first launch and blocks ads, trackers, malware, scam and crypto-mining sites. Its popup is in the `•••` menu.
- **Tint design**: every screen glows with your accent colour from the top; bars, menus and dialogs are tinted to match
- **Smooth Windows Phone motion**: screens slide and fade in, tiles sink under your finger, the bar colour and loading line animate, pages scroll with spring physics and overscroll
- **Home tiles with a screenshot and the site's icon**; the tabs screen shows a live preview of each tab
- **Firefox features**: site permissions (location, camera, microphone, notifications: each site asks first), save page as PDF, print, text size and zoom on every site, DNS over HTTPS, reopen closed tabs, downloads, search in favorites and recent, address bar at the top or bottom, swipe the bar to switch tabs, set as default browser, delete everything on quit
- **1.3**: swipe a tab to close it · pull down to refresh · full screen (Android's bars hidden) · mini player (a small button on every video floats it in a corner, draggable, without leaving the page) · reader view and "save for offline reading" (one self-contained HTML file, also copied to Downloads) · tracker blocker level and a per-page count · downloads through another app (download manager) · media notification with album art · automatic favorites backup to a folder you pick · YouTube "continue watching?" prompts dismissed · Android autofill enabled for password managers (Grove Vault) · history saved off the main thread and the visible tab given top priority, for smoother playback
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
