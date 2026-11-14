package app.grove.browser

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.webkit.URLUtil
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import org.mozilla.geckoview.MediaSession
import org.mozilla.geckoview.WebExtension
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ContentDelegate.ContextElement
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AlertPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BasePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ButtonPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PopupPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSession.PromptDelegate.TextPrompt
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebResponse
import java.io.File

class PendingPrompt(val p: BasePrompt, val res: GeckoResult<PromptResponse>)

class AppLinkAsk(val intent: Intent, val label: String, val url: String, val tab: Tab)

class BrowserVM(app: Application, val webApp: String? = null, private val webScope: String? = null) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    private val main = Handler(Looper.getMainLooper())
    val runtime: GeckoRuntime = Gecko.runtime(app)
    val prefs = Prefs(app)
    val bookmarks = JsonList<Bookmark>(File(app.filesDir, "bookmarks.json"),
        { Bookmark(it.getLong("id"), it.optString("title"), it.getString("url")) },
        { JSONObject().put("id", it.id).put("title", it.title).put("url", it.url) })
    val history = JsonList<Visit>(File(app.filesDir, "history.json"),
        { Visit(it.optString("title"), it.getString("url"), it.optLong("time")) },
        { JSONObject().put("title", it.title).put("url", it.url).put("time", it.time) })

    val tabs = mutableStateListOf<Tab>()
    var cur by mutableStateOf<Tab?>(null); private set
    var fullscreen by mutableStateOf(false)
    var prompt by mutableStateOf<PendingPrompt?>(null)
    var contextMenu by mutableStateOf<Pair<Tab, ContextElement>?>(null)
    var filePicker: ((FilePrompt, GeckoResult<PromptResponse>) -> Unit)? = null
    var external: ((Intent, String?) -> Unit)? = null
    var pip by mutableStateOf(false)
    var appLink by mutableStateOf<AppLinkAsk?>(null)
    var needNotifPermission by mutableStateOf(false)
    private var skipAppCheck: String? = null
    private val helperListener: (WebExtension) -> Unit = { ext -> tabs.forEach { bindHelper(it, ext) } }
    private val popupHost: () -> GeckoSession? = { newTab(open = false).session }

    init {
        Gecko.helperListeners += helperListener
        if (webApp == null) Gecko.popupHost = popupHost
        if (webApp != null) newTab(webApp)
        else { restore(); if (tabs.isEmpty()) newTab() }
    }

    /** Host of a standalone web app (PWA); pages outside it get a small "leave" strip. */
    val webAppHost get() = (webScope ?: webApp)?.let { Urls.host(it) }

    // ---------- tabs ----------

    private fun makeSession(private: Boolean, desktop: Boolean) = GeckoSession(
        GeckoSessionSettings.Builder()
            .usePrivateMode(private)
            .suspendMediaWhenInactive(prefs.bgPlay == 0)
            .userAgentMode(if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .build()
    )

    fun newTab(
        url: String? = null, private: Boolean = false, select: Boolean = true,
        parent: Tab? = null, open: Boolean = true, lazy: Boolean = false,
    ): Tab {
        val t = Tab(makeSession(private, false), private, parent)
        attach(t)
        if (open) t.session.open(runtime)
        val idx = parent?.let { tabs.indexOf(it) + 1 }?.takeIf { it > 0 } ?: tabs.size
        tabs.add(idx, t)
        if (!url.isNullOrBlank()) {
            t.url = url
            if (lazy) t.pending = true else t.session.loadUri(url)
        }
        if (select) select(t) else if (open) t.session.setActive(false)
        return t
    }

    /** Opens a URL from outside: reuses an empty current tab instead of stacking blank tabs. */
    fun openUrl(url: String) {
        val c = cur
        if (c != null && c.home && !c.private) go(url) else newTab(url)
    }

    fun select(t: Tab) {
        val prev = cur
        val ctl = runtime.webExtensionController
        if (prev !== t && prev != null && prev in tabs) { prev.session.setActive(false); ctl.setTabActive(prev.session, false) }
        cur = t
        t.session.setActive(true)
        ctl.setTabActive(t.session, true)
        if (t.pending) { t.pending = false; if (!t.home) t.session.loadUri(t.url) }
    }

    fun closeTab(t: Tab) {
        val i = tabs.indexOf(t)
        if (i < 0) return
        tabs.removeAt(i)
        if (MediaHub.session === t.media && t.media != null) MediaHub.update(null, "", false)
        tabs.forEach { if (it.parent === t) it.parent = null }
        if (cur === t) {
            val next = t.parent?.takeIf { it in tabs } ?: tabs.getOrNull(minOf(i, tabs.size - 1))
            if (next != null) select(next) else cur = null
        }
        if (tabs.isEmpty()) newTab()
        // Close after the view has swapped to another session.
        main.postDelayed({ runCatching { t.session.close() } }, 400)
    }

    fun go(input: String) {
        val url = Urls.fromInput(input, prefs.engineUrl)
        if (url.isEmpty()) return
        val t = cur ?: newTab()
        t.url = url; t.pending = false
        t.session.loadUri(url)
    }

    fun toggleDesktop(t: Tab) {
        t.desktop = !t.desktop
        t.session.settings.userAgentMode =
            if (t.desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        t.session.settings.viewportMode =
            if (t.desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        t.session.reload()
    }

    /** Content process crashed or was killed by Android: rebuild the session. */
    private fun revive(t: Tab) {
        if (t !in tabs) return
        runCatching { t.session.close() }
        t.session = makeSession(t.private, t.desktop)
        t.ports.clear()
        attach(t)
        t.session.open(runtime)
        t.progress = 100
        if (t === cur) { t.session.setActive(true); if (!t.home) t.session.loadUri(t.url) }
        else { t.session.setActive(false); t.pending = !t.home }
    }

    fun foreground(on: Boolean) {
        val c = cur ?: return
        // With background play on, the playing tab keeps running while Grove is in the background.
        if (on || !(prefs.bgPlay == 1 && c.playing)) c.session.setActive(on)
    }

    fun setBgPlay(on: Boolean) {
        prefs.bgPlay = if (on) 1 else 0
        tabs.forEach { t -> t.ports.forEach { runCatching { it.postMessage(JSONObject().put("bg", on)) } } }
        if (!on) MediaHub.update(null, "", false)
    }

    /** Picture-in-picture window opened/closed: the playing video fills it. */
    fun setPip(on: Boolean) {
        pip = on
        cur?.ports?.forEach { runCatching { it.postMessage(JSONObject().put("pip", on)) } }
    }

    fun save() {
        if (webApp != null) return
        val urls = tabs.filter { !it.private && !it.home && Urls.isWeb(it.url) }
        prefs.sp.edit()
            .putString("tabs", JSONArray(urls.map { it.url }).toString())
            .putInt("curTab", urls.indexOf(cur))
            .apply()
    }

    private fun restore() {
        runCatching {
            val a = JSONArray(prefs.sp.getString("tabs", "[]"))
            for (i in 0 until a.length()) newTab(a.getString(i), select = false, lazy = true)
            tabs.getOrNull(prefs.sp.getInt("curTab", -1))?.let { select(it) } ?: tabs.lastOrNull()?.let { select(it) }
        }
    }

    override fun onCleared() {
        Gecko.helperListeners -= helperListener
        if (Gecko.popupHost === popupHost) Gecko.popupHost = null
        tabs.forEach { runCatching { it.session.close() } }
    }

    // ---------- favorites backup ----------

    fun exportBookmarks(uri: Uri) {
        runCatching {
            val a = JSONArray(); bookmarks.items.forEach { a.put(JSONObject().put("title", it.title).put("url", it.url)) }
            val json = JSONObject().put("app", "grove").put("version", 1).put("bookmarks", a).toString(2)
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(json.toByteArray()) }
            toast("${bookmarks.items.size} favorites backed up")
        }.onFailure { toast("backup failed") }
    }

    fun importBookmarks(uri: Uri) {
        val text = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { String(it.readBytes()) } }.getOrNull()
            ?: return toast("can't read this file")
        val found = parseBookmarks(text)
        val have = bookmarks.items.map { it.url }.toMutableSet()
        var id = System.currentTimeMillis()
        var added = 0
        found.forEach { (title, url) ->
            if (Urls.isWeb(url) && url.length < 2000 && have.add(url)) {
                bookmarks.items.add(Bookmark(id++, title.ifBlank { Urls.host(url) }.take(200), url)); added++
            }
        }
        if (added > 0) bookmarks.save()
        toast(if (found.isEmpty()) "no favorites found in this file" else "$added favorites restored")
    }

    // ---------- web apps (PWA) ----------

    fun installWebApp(t: Tab) {
        val m = t.manifest
        val start = m?.optString("start_url")?.takeIf { Urls.isWeb(it) } ?: t.url
        if (!Urls.isWeb(start)) return
        val scope = m?.optString("scope")?.takeIf { Urls.isWeb(it) } ?: start
        val name = (m?.optString("short_name").orEmpty().ifBlank { m?.optString("name").orEmpty() })
            .ifBlank { t.title.ifBlank { Urls.host(start) } }.take(30)
        var iconUrl: String? = null; var best = 0; var maskable = false
        m?.optJSONArray("icons")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val src = o.optString("src"); if (!src.startsWith("https://")) continue
                val size = o.optString("sizes").split(' ').maxOfOrNull { it.substringBefore('x').toIntOrNull() ?: 0 } ?: 0
                if (size > best) { best = size; iconUrl = src; maskable = o.optString("purpose").contains("maskable") }
            }
        }
        toast("adding $name to home screen…")
        thread {
            val bmp = iconUrl?.let { fetchIcon(it) }
            val icon = when {
                bmp == null -> IconCompat.createWithBitmap(letterIcon(name))
                maskable -> IconCompat.createWithAdaptiveBitmap(bmp)
                else -> IconCompat.createWithBitmap(bmp)
            }
            main.post {
                if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) return@post toast("your launcher can't add apps")
                val intent = Intent(ctx, WebAppActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(start))
                    .putExtra("scope", scope).putExtra("name", name)
                val info = ShortcutInfoCompat.Builder(ctx, "app_" + scope.hashCode()).setShortLabel(name).setIcon(icon).setIntent(intent).build()
                ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
            }
        }
    }

    private fun fetchIcon(url: String): Bitmap? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 6000; c.instanceFollowRedirects = true
        c.inputStream.use { BitmapFactory.decodeStream(it) }?.let { Bitmap.createScaledBitmap(it, 192, 192, true) }
    }.getOrNull()

    private fun letterIcon(name: String): Bitmap {
        val b = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(app.grove.browser.ui.ACCENTS.getOrElse(prefs.accent) { 0xFF008A00 }.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 96f; textAlign = Paint.Align.CENTER }
        c.drawText(name.take(1).uppercase(), 96f, 96f - (p.descent() + p.ascent()) / 2, p)
        return b
    }

    // ---------- open links in apps ----------

    private val browsers by lazy {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid/")).addCategory(Intent.CATEGORY_BROWSABLE)
        ctx.packageManager.queryIntentActivities(i, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }.toSet()
    }

    /** An installed app (not a browser) that opens this link, e.g. YouTube for youtube.com. */
    private fun appFor(url: String): Pair<Intent, String>? = runCatching {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        val r = pm.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull { it.activityInfo.packageName !in browsers && it.activityInfo.packageName != ctx.packageName } ?: return null
        Intent(i).setPackage(r.activityInfo.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) to r.loadLabel(pm).toString()
    }.getOrNull()

    fun answerAppLink(open: Boolean) {
        val a = appLink ?: return
        appLink = null
        if (open) external?.invoke(a.intent, null)
        else if (a.tab in tabs) { skipAppCheck = a.url; a.tab.url = a.url; a.tab.session.loadUri(a.url) }
    }

    // ---------- media ----------

    private fun mediaChanged(t: Tab) {
        if (prefs.bgPlay == 0 || t.media == null) return
        if (t.playing || MediaHub.session === t.media) MediaHub.update(t.media, t.mediaTitle.ifBlank { t.title.ifBlank { Urls.host(t.url) } }, t.playing)
        if (t.playing) {
            if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !prefs.sp.getBoolean("askedNotif", false)) {
                prefs.sp.edit().putBoolean("askedNotif", true).apply(); needNotifPermission = true
            }
            runCatching { ctx.startForegroundService(Intent(ctx, MediaService::class.java)) }
        }
    }

    private fun bindHelper(t: Tab, ext: WebExtension) {
        t.session.webExtensionController.setMessageDelegate(ext, object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                t.ports += port
                port.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, port: WebExtension.Port) {
                        val m = message as? JSONObject ?: return
                        if (m.optString("t") == "video") {
                            t.videoPlaying = m.optBoolean("playing")
                            if (m.optLong("w") > 0 && m.optLong("h") > 0) { t.videoW = m.optLong("w"); t.videoH = m.optLong("h") }
                        }
                    }
                    override fun onDisconnect(port: WebExtension.Port) { t.ports -= port }
                })
                runCatching { port.postMessage(JSONObject().put("bg", prefs.bgPlay == 1)) }
            }
        }, "grove")
    }

    // ---------- bookmarks / history ----------

    fun saveBookmark(b: Bookmark) {
        val fixed = b.copy(url = Urls.fromInput(b.url, prefs.engineUrl), title = b.title.trim().ifBlank { Urls.host(b.url) })
        if (fixed.url.isBlank()) return
        val i = bookmarks.items.indexOfFirst { it.id == b.id }
        if (i >= 0) bookmarks.items[i] = fixed else bookmarks.items.add(fixed)
        bookmarks.save()
    }

    fun deleteBookmark(b: Bookmark) { bookmarks.items.removeAll { it.id == b.id }; bookmarks.save() }

    private fun addVisit(t: Tab) {
        if (t.private || !Urls.isWeb(t.url)) return
        val l = history.items
        val v = Visit(t.title, t.url, System.currentTimeMillis())
        if (l.lastOrNull()?.url == t.url) l[l.lastIndex] = v else l.add(v)
        while (l.size > 300) l.removeAt(0)
        history.save()
    }

    fun clearHistory() { history.items.clear(); history.save() }

    fun clearData() {
        runtime.storageController.clearData(StorageController.ClearFlags.ALL)
        clearHistory()
        toast("history and site data deleted")
    }

    fun openOptions(e: WebExtension) {
        e.metaData.optionsPageUrl?.let { newTab(it) } ?: toast("this add-on has no settings")
    }

    // ---------- downloads / external apps ----------

    fun downloadUrl(url: String, disposition: String? = null, mimeRaw: String? = null) {
        if (!Urls.isWeb(url)) return toast("this file can't be downloaded")
        val mime = mimeRaw?.substringBefore(';')?.trim()
        val name = URLUtil.guessFileName(url, disposition, mime)
        runCatching {
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setMimeType(mime)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            ctx.getSystemService(DownloadManager::class.java).enqueue(req)
            toast("downloading $name")
        }.onFailure { toast("download failed") }
    }

    private fun download(r: WebResponse) {
        runCatching { r.body?.close() }
        val h = r.headers
        fun hdr(k: String) = h[k] ?: h.entries.firstOrNull { it.key.equals(k, true) }?.value
        downloadUrl(r.uri, hdr("Content-Disposition"), hdr("Content-Type"))
    }

    private fun openExternal(uri: String) {
        val intent = runCatching {
            if (uri.startsWith("intent:", true)) Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        }.getOrNull() ?: return
        // Pages may only open public app links, never a specific (possibly private) component.
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        if (intent.`package` == ctx.packageName) return
        val fallback = intent.getStringExtra("browser_fallback_url")?.takeIf { Urls.isWeb(it) }
        external?.invoke(intent, fallback)
    }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    // ---------- Gecko delegates ----------

    private fun attach(t: Tab) {
        val s = t.session
        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession, url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean,
            ) { t.url = url ?: "" }
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) { t.canBack = canGoBack }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) { t.canFwd = canGoForward }
            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
                val uri = Uri.parse(request.uri)
                val scheme = uri.scheme?.lowercase()
                if (Urls.isWeb(request.uri)) {
                    // Firefox add-on files install as add-ons instead of downloading.
                    if (uri.path?.endsWith(".xpi", true) == true) { Gecko.install(request.uri); return GeckoResult.deny() }
                    val skip = skipAppCheck == request.uri
                    if (skip) skipAppCheck = null
                    if (!skip && request.hasUserGesture && prefs.appLinks != 2 && webApp == null && !t.private &&
                        Urls.host(request.uri) != Urls.host(t.url)) {
                        appFor(request.uri)?.let { (intent, label) ->
                            if (prefs.appLinks == 1) external?.invoke(intent, null) else appLink = AppLinkAsk(intent, label, request.uri, t)
                            return GeckoResult.deny()
                        }
                    }
                    return GeckoResult.allow()
                }
                if (scheme == null || scheme in WEB_SCHEMES) return GeckoResult.allow()
                // Other schemes (tel:, mailto:, intent:, market:) only open apps after a real tap.
                if (request.hasUserGesture) openExternal(request.uri)
                return GeckoResult.deny()
            }
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                if (webApp != null) { // new windows from a web app open in the main browser
                    if (Urls.isWeb(uri)) external?.invoke(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null)
                    return null
                }
                return GeckoResult.fromValue(newTab(private = t.private, parent = t, open = false).session)
            }
        }
        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) { t.progress = 5; t.manifest = null; t.videoPlaying = false }
            override fun onProgressChange(session: GeckoSession, progress: Int) { t.progress = progress }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                t.progress = 100
                if (success) addVisit(t)
            }
        }
        s.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) { t.title = title ?: "" }
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) { fullscreen = fullScreen }
            override fun onCloseRequest(session: GeckoSession) { closeTab(t) }
            override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: ContextElement) {
                if (element.linkUri != null || (element.type == ContextElement.TYPE_IMAGE && element.srcUri != null)) contextMenu = t to element
            }
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                val type = response.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value.orEmpty()
                if (type.contains("x-xpinstall")) { runCatching { response.body?.close() }; Gecko.install(response.uri) } else download(response)
            }
            override fun onWebAppManifest(session: GeckoSession, manifest: JSONObject) { t.manifest = manifest }
            override fun onCrash(session: GeckoSession) { main.post { revive(t) } }
            override fun onKill(session: GeckoSession) { main.post { revive(t) } }
        }
        s.promptDelegate = object : GeckoSession.PromptDelegate {
            fun ask(p: BasePrompt): GeckoResult<PromptResponse> {
                // Background tabs can't spam dialogs.
                if (t !== cur) return GeckoResult.fromValue(p.dismiss())
                prompt?.let { old -> if (!old.p.isComplete) old.res.complete(old.p.dismiss()) }
                return GeckoResult<PromptResponse>().also { prompt = PendingPrompt(p, it) }
            }
            override fun onAlertPrompt(session: GeckoSession, prompt: AlertPrompt) = ask(prompt)
            override fun onButtonPrompt(session: GeckoSession, prompt: ButtonPrompt) = ask(prompt)
            override fun onTextPrompt(session: GeckoSession, prompt: TextPrompt) = ask(prompt)
            override fun onChoicePrompt(session: GeckoSession, prompt: ChoicePrompt) = ask(prompt)
            // Pop-ups the page opens without a tap are blocked.
            override fun onPopupPrompt(session: GeckoSession, prompt: PopupPrompt): GeckoResult<PromptResponse> =
                GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))
            override fun onFilePrompt(session: GeckoSession, prompt: FilePrompt): GeckoResult<PromptResponse> {
                val r = GeckoResult<PromptResponse>()
                filePicker?.invoke(prompt, r) ?: r.complete(prompt.dismiss())
                return r
            }
        }
        s.mediaSessionDelegate = object : MediaSession.Delegate {
            override fun onActivated(session: GeckoSession, mediaSession: MediaSession) { t.media = mediaSession }
            override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) {
                if (MediaHub.session === mediaSession) MediaHub.update(null, "", false)
                t.media = null; t.playing = false
            }
            override fun onMetadata(session: GeckoSession, mediaSession: MediaSession, meta: MediaSession.Metadata) {
                t.mediaTitle = meta.title.orEmpty(); mediaChanged(t)
            }
            override fun onPlay(session: GeckoSession, mediaSession: MediaSession) { t.media = mediaSession; t.playing = true; mediaChanged(t) }
            override fun onPause(session: GeckoSession, mediaSession: MediaSession) { t.playing = false; mediaChanged(t) }
            override fun onStop(session: GeckoSession, mediaSession: MediaSession) { t.playing = false; mediaChanged(t) }
        }
        Gecko.helper?.let { bindHelper(t, it) }
        // No permission delegate: camera, mic, location and notifications are denied by default.
    }

    companion object {
        private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript", "moz-extension", "resource")
    }
}
