package app.grove.browser

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.URLUtil
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

class BrowserVM(app: Application) : AndroidViewModel(app) {
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

    init {
        restore()
        if (tabs.isEmpty()) newTab()
    }

    // ---------- tabs ----------

    private fun makeSession(private: Boolean, desktop: Boolean) = GeckoSession(
        GeckoSessionSettings.Builder()
            .usePrivateMode(private)
            .suspendMediaWhenInactive(true)
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
        if (prev !== t && prev != null && prev in tabs) prev.session.setActive(false)
        cur = t
        t.session.setActive(true)
        if (t.pending) { t.pending = false; if (!t.home) t.session.loadUri(t.url) }
    }

    fun closeTab(t: Tab) {
        val i = tabs.indexOf(t)
        if (i < 0) return
        tabs.removeAt(i)
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
        attach(t)
        t.session.open(runtime)
        t.progress = 100
        if (t === cur) { t.session.setActive(true); if (!t.home) t.session.loadUri(t.url) }
        else { t.session.setActive(false); t.pending = !t.home }
    }

    fun foreground(on: Boolean) { cur?.session?.setActive(on) }

    fun save() {
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

    override fun onCleared() { tabs.forEach { runCatching { it.session.close() } } }

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

    fun openUblock() {
        val e = Gecko.ublock ?: return toast("ublock origin is still loading")
        newTab(e.metaData.optionsPageUrl ?: (e.metaData.baseUrl + "dashboard.html"))
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
                val scheme = Uri.parse(request.uri).scheme?.lowercase()
                if (scheme == null || scheme in WEB_SCHEMES) return GeckoResult.allow()
                // Other schemes (tel:, mailto:, intent:, market:) only open apps after a real tap.
                if (request.hasUserGesture) openExternal(request.uri)
                return GeckoResult.deny()
            }
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> =
                GeckoResult.fromValue(newTab(private = t.private, parent = t, open = false).session)
        }
        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) { t.progress = 5 }
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
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) { download(response) }
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
        // No permission delegate: camera, mic, location and notifications are denied by default.
    }

    companion object {
        private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript", "moz-extension", "resource")
    }
}
