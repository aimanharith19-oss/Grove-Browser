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
import androidx.compose.ui.graphics.asImageBitmap

class PendingPrompt(val p: BasePrompt, val res: GeckoResult<PromptResponse>)

class AppLinkAsk(val intent: Intent, val label: String, val url: String, val tab: Tab)

/** A website asking for location, camera, microphone or notifications. */
class PermAsk(val host: String, val what: String, val done: (Boolean) -> Unit)

class Closed(val title: String, val url: String)

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
    var pip by mutableStateOf(false); private set
    var appLink by mutableStateOf<AppLinkAsk?>(null)
    var needNotifPermission by mutableStateOf(false)
    var permAsk by mutableStateOf<PermAsk?>(null)
    /** Asks Android for app permissions (set by the activity). */
    var androidPerms: ((Array<String>, (Boolean) -> Unit) -> Unit)? = null
    /** Takes a picture of the page on screen (set by the browser view). */
    var capture: (() -> GeckoResult<Bitmap>?)? = null
    /** Pull-to-refresh: 0..1 while pulling, -1 when idle. */
    var pull by mutableStateOf(-1f)
    /** Pages saved for offline reading. */
    val saved = JsonList<SavedPage>(File(app.filesDir, "saved.json"),
        { SavedPage(it.optString("title"), it.optString("url"), it.optLong("time"), it.getString("file")) },
        { JSONObject().put("title", it.title).put("url", it.url).put("time", it.time).put("file", it.file) })
    /** Recently closed tabs, newest first. */
    val closed = mutableStateListOf<Closed>()
    /** Opens picture-in-picture now (set by the activity). */
    var enterPip: (() -> Unit)? = null
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
        when {
            select && open -> select(t)
            select -> selectWhenOpen(t) // Gecko opens it right after we hand it over
            open -> t.session.setActive(false)
        }
        return t
    }

    /** Opens a URL from outside: reuses an empty current tab instead of stacking blank tabs. */
    fun openUrl(url: String) {
        val c = cur
        if (c != null && c.home && !c.private) go(url) else newTab(url)
    }

    private fun selectWhenOpen(t: Tab, tries: Int = 0) {
        main.postDelayed({
            if (t !in tabs) return@postDelayed
            if (t.session.isOpen) select(t) else if (tries < 40) selectWhenOpen(t, tries + 1)
        }, 25)
    }

    fun select(t: Tab) {
        if (!t.session.isOpen) return selectWhenOpen(t)
        val prev = cur
        val ctl = runtime.webExtensionController
        if (prev !== t && prev != null && prev in tabs && prev.session.isOpen) { prev.session.setActive(false); ctl.setTabActive(prev.session, false) }
        cur = t
        // The tab you're looking at gets top priority from Android, so it stays smooth while others idle.
        t.session.setActive(true)
        ctl.setTabActive(t.session, true)
        if (t.pending) { t.pending = false; if (!t.home) t.session.loadUri(t.url) }
    }

    fun closeTab(t: Tab) {
        val i = tabs.indexOf(t)
        if (i < 0) return
        if (!t.private && Urls.isWeb(t.url)) { closed.add(0, Closed(t.title, t.url)); while (closed.size > 15) closed.removeAt(closed.lastIndex) }
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

    fun reopenClosed() { closed.removeFirstOrNull()?.let { newTab(it.url) } }

    /** Screenshot of the current page: tab preview, and the home tile if the page is a favorite. */
    fun snapshot() {
        val t = cur ?: return
        if (t.private || t.home || !Urls.isWeb(t.url) || t.progress != 100 || t.playing || t.videoPlaying) return // never while media plays: it costs a frame
        val url = t.url
        runCatching {
            capture?.invoke()?.accept({ bmp ->
                if (bmp == null || bmp.width < 50 || t.url != url) return@accept
                val small = runCatching { Thumbs.shrink(bmp) }.getOrNull() ?: return@accept
                t.thumb = small.asImageBitmap()
                val k = Thumbs.key(url)
                bookmarks.items.firstOrNull { Thumbs.key(it.url) == k }?.let { Thumbs.put(ctx, it.url, small) }
            }, { })
        }
    }

    // ---------- reader view / saved pages ----------

    /** Asks the page for its article (the helper script answers with one self-contained HTML page). mode: "view" or "save". */
    fun reader(t: Tab, mode: String) {
        if (!Urls.isWeb(t.url)) return
        val port = t.ports.firstOrNull() ?: return toast("reload the page and try again")
        toast(if (mode == "save") "saving for offline reading…" else "opening reader view…")
        t.ports.forEach { runCatching { it.postMessage(JSONObject().put("reader", mode)) } }
    }

    private fun readerResult(t: Tab, m: JSONObject) {
        val err = m.optString("err")
        if (err.isNotEmpty()) return toast(err)
        val html = m.optString("html")
        if (html.isEmpty()) return
        val title = m.optString("title").ifBlank { Urls.host(t.url) }.take(120)
        if (m.optString("mode") != "save") { openHtml(html, t); return }
        val name = "page-" + System.currentTimeMillis() + ".html"
        val safe = title.replace(Regex("[\\\\/:*?\"<>|\\n]"), " ").trim().take(60).ifBlank { "page" }
        thread {
            val ok = runCatching {
                File(ctx.filesDir, "reader").apply { mkdirs() }.resolve(name).writeText(html)
                // a copy in Downloads, so it can be opened in any browser, sent, or moved to another device
                val v = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "$safe (grove reader).html")
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/html")
                }
                ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)?.let { uri ->
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(html.toByteArray()) }
                }
            }.isSuccess
            main.post {
                if (!ok) return@post toast("couldn't save this page")
                saved.items.add(0, SavedPage(title, m.optString("url"), System.currentTimeMillis(), name)); saved.save()
                toast("saved: ••• > saved pages (a copy is in downloads)")
            }
        }
    }

    private fun openHtml(html: String, from: Tab? = null) {
        val t = newTab(parent = from)
        runCatching { t.session.load(GeckoSession.Loader().data(html, "text/html")) }.onFailure { toast("couldn't open this page") }
    }

    fun openSaved(p: SavedPage) {
        val html = runCatching { File(ctx.filesDir, "reader/" + p.file).readText() }.getOrNull() ?: return toast("this saved page is missing")
        openHtml(html)
    }

    fun deleteSaved(p: SavedPage) { saved.items.remove(p); saved.save(); File(ctx.filesDir, "reader/" + p.file).delete() }

    // ---------- automatic favorites backup ----------

    fun setBackupFolder(uri: Uri?) {
        if (uri == null) { prefs.sp.edit().remove("backupDir").apply(); return }
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        prefs.sp.edit().putString("backupDir", uri.toString()).apply()
        autoBackup(tell = true)
    }

    val backupFolder: String? get() = prefs.sp.getString("backupDir", null)?.let { Uri.parse(it).lastPathSegment?.substringAfterLast(':')?.ifBlank { "storage" } }

    /** Writes grove-favorites.json into the folder you chose, every time your favorites change. */
    private fun autoBackup(tell: Boolean = false) {
        val dir = prefs.sp.getString("backupDir", null) ?: return
        val a = JSONArray(); bookmarks.items.forEach { a.put(JSONObject().put("title", it.title).put("url", it.url)) }
        val json = JSONObject().put("app", "grove").put("version", 1).put("saved", System.currentTimeMillis()).put("bookmarks", a).toString(2)
        thread {
            val ok = runCatching {
                val tree = Uri.parse(dir)
                val cr = ctx.contentResolver
                val id = android.provider.DocumentsContract.getTreeDocumentId(tree)
                val kids = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
                var doc: Uri? = null
                cr.query(kids, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                    while (c.moveToNext()) if (c.getString(1) == BACKUP_NAME) doc = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                }
                val target = doc ?: android.provider.DocumentsContract.createDocument(cr, android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id), "application/json", BACKUP_NAME)!!
                cr.openOutputStream(target, "wt")!!.use { it.write(json.toByteArray()) }
            }.isSuccess
            if (tell || !ok) main.post { toast(if (ok) "favorites backed up to that folder; grove keeps the file up to date" else "automatic backup failed: choose the folder again in settings") }
        }
    }

    fun applyPrefs() = Gecko.applyPrefs(prefs.textSize, prefs.forceZoom == 1, prefs.doh)

    /** Saves the page as a PDF file in Downloads. */
    fun savePdf(t: Tab) {
        if (!Urls.isWeb(t.url)) return
        toast("making pdf…")
        val name = (t.title.ifBlank { Urls.host(t.url) }).replace(Regex("[\\\\/:*?\"<>|\\n]"), " ").trim().take(60).ifBlank { "page" } + ".pdf"
        t.session.saveAsPdf().accept({ input ->
            if (input == null) { toast("couldn't make a pdf of this page"); return@accept }
            thread {
                val ok = runCatching {
                    val v = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                        put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    }
                    val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
                    ctx.contentResolver.openOutputStream(uri)!!.use { out -> input.use { it.copyTo(out) } }
                }.isSuccess
                main.post { toast(if (ok) "saved to downloads: $name" else "couldn't save the pdf") }
            }
        }, { main.post { toast("couldn't make a pdf of this page") } })
    }

    fun print(t: Tab) { runCatching { t.session.printPageContent() }.onFailure { toast("can't print this page") } }

    fun openDownloads() {
        external?.invoke(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null)
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

    /** "Audio only" from the picture-in-picture window: the video window closes, the sound keeps playing. */
    var audioOnly = false; private set
    private val bgOn get() = prefs.bgPlay == 1 || audioOnly

    fun foreground(on: Boolean) {
        if (on && audioOnly) { audioOnly = false; if (prefs.bgPlay == 0) cur?.ports?.forEach { runCatching { it.postMessage(JSONObject().put("bg", false)) } } }
        val c = cur ?: return
        // With background play on, the playing tab keeps running while Grove is in the background.
        if (on || !(bgOn && c.playing)) c.session.setActive(on)
    }

    fun startAudioOnly() {
        val c = cur ?: return
        audioOnly = true
        c.ports.forEach { runCatching { it.postMessage(JSONObject().put("bg", true)) } }
        mediaChanged(c)
    }

    fun togglePlay() { val t = cur ?: return; t.media?.let { if (t.playing) it.pause() else it.play() } }

    fun setBgPlay(on: Boolean) {
        prefs.bgPlay = if (on) 1 else 0
        tabs.forEach { t -> t.ports.forEach { runCatching { it.postMessage(JSONObject().put("bg", on)) } } }
        if (!on) MediaHub.update(null, "", false)
    }

    /** Picture-in-picture window opened/closed: the playing video fills it. */
    fun pipMode(on: Boolean) {
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
        if (added > 0) { bookmarks.save(); autoBackup() }
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
        if (!bgOn || t.media == null) return
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
                android.util.Log.i("Grove", "helper connected")
                port.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, port: WebExtension.Port) {
                        val m = message as? JSONObject ?: return
                        if (m.optString("t") == "icon") Icons.hint(ctx, t.url, m.optString("h"))
                        if (m.optString("t") == "theme") t.theme = parseColor(m.optString("c"))
                        if (m.optString("t") == "reader") readerResult(t, m)
                        if (m.optString("t") == "pip" && t === cur) enterPip?.invoke() // ⧉ on the mini player
                        if (m.optString("t") == "video") {
                            t.videoFrames[port] = m.optBoolean("playing")
                            t.videoPlaying = t.videoFrames.values.any { it }
                            if (m.optLong("w") > 0 && m.optLong("h") > 0) { t.videoW = m.optLong("w"); t.videoH = m.optLong("h") }
                        }
                    }
                    override fun onDisconnect(port: WebExtension.Port) {
                        t.ports -= port
                        if (t.videoFrames.remove(port) != null) t.videoPlaying = t.videoFrames.values.any { it }
                    }
                })
                runCatching { port.postMessage(JSONObject().put("bg", prefs.bgPlay == 1).put("mini", prefs.miniBtn == 1)) }
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
        autoBackup()
        snapshot() // the page on screen may be this favorite: take its picture for the home tile
    }

    fun deleteBookmark(b: Bookmark) { bookmarks.items.removeAll { it.id == b.id }; bookmarks.save(); Thumbs.remove(ctx, b.url); autoBackup() }

    private fun addVisit(t: Tab) {
        if (t.private || !Urls.isWeb(t.url)) return
        val l = history.items
        val v = Visit(t.title, t.url, System.currentTimeMillis())
        if (l.lastOrNull()?.url == t.url) l[l.lastIndex] = v else l.add(v)
        while (l.size > 2000) l.removeAt(0)
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
        if (prefs.downloader == 1) { // hand the link to a download manager app
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            val chooser = Intent.createChooser(view, "download with")
                .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(android.content.ComponentName(ctx, MainActivity::class.java)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            external?.invoke(chooser, null) ?: toast("no app can download this")
            return
        }
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
            override fun onPageStart(session: GeckoSession, url: String) {
                t.progress = 5; t.manifest = null; t.videoPlaying = false; t.videoFrames.clear(); t.trackers = 0
                if (Urls.host(url) != Urls.host(t.url)) t.theme = null
            }
            override fun onProgressChange(session: GeckoSession, progress: Int) { t.progress = progress }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                t.progress = 100
                if (success) addVisit(t)
                if (success) main.postDelayed({ if (t === cur && t.progress == 100) snapshot() }, 1500)
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
            override fun onWebAppManifest(session: GeckoSession, manifest: JSONObject) {
                t.manifest = manifest
                if (t.theme == null) t.theme = parseColor(manifest.optString("theme_color"))
            }
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
                val who = listOfNotNull(meta.artist?.takeIf { it.isNotBlank() }, meta.album?.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { Urls.host(t.url) }
                MediaHub.details(mediaSession, who, null)
                runCatching { meta.artwork?.getBitmap(512)?.accept({ bmp -> MediaHub.details(mediaSession, who, bmp) }, { }) }
            }
            override fun onPlay(session: GeckoSession, mediaSession: MediaSession) { t.media = mediaSession; t.playing = true; mediaChanged(t) }
            override fun onPause(session: GeckoSession, mediaSession: MediaSession) { t.playing = false; mediaChanged(t) }
            override fun onStop(session: GeckoSession, mediaSession: MediaSession) { t.playing = false; mediaChanged(t) }
        }
        s.contentBlockingDelegate = object : org.mozilla.geckoview.ContentBlocking.Delegate {
            override fun onContentBlocked(session: GeckoSession, event: org.mozilla.geckoview.ContentBlocking.BlockEvent) { t.trackers++ }
        }
        s.permissionDelegate = object : GeckoSession.PermissionDelegate {
            private fun ask(what: String, uri: String, done: (Boolean) -> Unit) {
                // Background tabs can't ask.
                if (t !== cur) return done(false)
                permAsk?.done?.invoke(false)
                permAsk = PermAsk(Urls.host(uri), what, done)
            }
            override fun onContentPermissionRequest(
                session: GeckoSession, perm: GeckoSession.PermissionDelegate.ContentPermission,
            ): GeckoResult<Int>? {
                val allow = GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                val deny = GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                val what = when (perm.permission) {
                    GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> "know your location"
                    GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> "send you notifications"
                    GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> "play protected (drm) video"
                    GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> return GeckoResult.fromValue(allow)
                    else -> return GeckoResult.fromValue(deny) // autoplay, tracking, storage access, local network…
                }
                val r = GeckoResult<Int>()
                ask(what, perm.uri) { ok -> r.complete(if (ok) allow else deny) }
                return r
            }
            override fun onAndroidPermissionsRequest(
                session: GeckoSession, permissions: Array<String>?, callback: GeckoSession.PermissionDelegate.Callback,
            ) {
                val p = permissions?.takeIf { it.isNotEmpty() } ?: return callback.grant()
                val req = androidPerms ?: return callback.reject()
                req(p) { ok -> if (ok) callback.grant() else callback.reject() }
            }
            override fun onMediaPermissionRequest(
                session: GeckoSession, uri: String,
                video: Array<GeckoSession.PermissionDelegate.MediaSource>?, audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback,
            ) {
                val cam = video?.firstOrNull { it.source == GeckoSession.PermissionDelegate.MediaSource.SOURCE_CAMERA }
                val mic = audio?.firstOrNull { it.source == GeckoSession.PermissionDelegate.MediaSource.SOURCE_MICROPHONE }
                if (cam == null && mic == null) return callback.reject()
                val what = when { cam != null && mic != null -> "use your camera and microphone"; cam != null -> "use your camera"; else -> "use your microphone" }
                ask(what, uri) { ok -> if (ok) callback.grant(cam, mic) else callback.reject() }
            }
        }
        Gecko.helper?.let { bindHelper(t, it) }
    }

    /** CSS colour (#rgb, #rrggbb, rgb()) from a page -> ARGB, ignoring transparent ones. */
    private fun parseColor(c: String): Int? = runCatching {
        val s = c.trim().lowercase()
        when {
            s.startsWith("#") && s.length == 4 -> android.graphics.Color.parseColor("#" + s.drop(1).map { "$it$it" }.joinToString(""))
            s.startsWith("#") -> android.graphics.Color.parseColor(s.take(7))
            s.startsWith("rgb") -> {
                val n = Regex("[\\d.]+").findAll(s).map { it.value.toFloat() }.toList()
                if (n.size >= 4 && n[3] < 0.5f) null else android.graphics.Color.rgb(n[0].toInt(), n[1].toInt(), n[2].toInt())
            }
            else -> null
        }
    }.getOrNull()

    companion object {
        private const val BACKUP_NAME = "grove-favorites.json"
        private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript", "moz-extension", "resource")
    }
}
