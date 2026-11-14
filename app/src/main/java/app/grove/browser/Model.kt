package app.grove.browser

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import android.text.Html
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.MediaSession
import org.mozilla.geckoview.WebExtension
import java.io.File
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

data class Bookmark(val id: Long, val title: String, val url: String)
data class Visit(val title: String, val url: String, val time: Long)

class Tab(session: GeckoSession, val private: Boolean, var parent: Tab? = null) {
    var session by mutableStateOf(session)
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var canBack by mutableStateOf(false)
    var canFwd by mutableStateOf(false)
    var desktop by mutableStateOf(false)
    /** URL is known but not loaded yet (restored or killed in background). Loads on select. */
    var pending = false
    // media
    var media: MediaSession? = null
    var playing by mutableStateOf(false)
    var mediaTitle = ""
    var videoPlaying by mutableStateOf(false)
    var videoW = 16L
    var videoH = 9L
    /** Connections to the Grove helper script in this tab's frames (picture-in-picture, background play). */
    val ports = mutableListOf<WebExtension.Port>()
    /** Web app manifest of the current page, if it has one (PWA). */
    var manifest by mutableStateOf<JSONObject?>(null)
    /** The website's theme colour (meta theme-color), used to tint Grove's bars. */
    var theme by mutableStateOf<Int?>(null)
    val home get() = url.isBlank() || url == "about:blank"
}

/** Tiny JSON-file backed list (bookmarks, history). */
class JsonList<T>(private val file: File, private val read: (JSONObject) -> T, private val write: (T) -> JSONObject) {
    val items = mutableStateListOf<T>()
    init {
        runCatching {
            val a = JSONArray(file.readText())
            for (i in 0 until a.length()) items += read(a.getJSONObject(i))
        }
    }
    /** Picks up changes written by the launcher link while the browser was in the background. */
    fun reload() {
        runCatching {
            val a = JSONArray(file.readText())
            val n = ArrayList<T>()
            for (i in 0 until a.length()) n += read(a.getJSONObject(i))
            items.clear(); items.addAll(n)
        }
    }
    fun save() {
        val a = JSONArray(); items.forEach { a.put(write(it)) }
        val tmp = File(file.path + ".tmp"); tmp.writeText(a.toString()); tmp.renameTo(file)
    }
}

val ENGINES = listOf(
    "DuckDuckGo" to "https://duckduckgo.com/?q=%s",
    "Startpage" to "https://www.startpage.com/do/search?q=%s",
    "Brave Search" to "https://search.brave.com/search?q=%s",
    "Google" to "https://www.google.com/search?q=%s",
    "Bing" to "https://www.bing.com/search?q=%s",
)

private class IntPref(private val sp: SharedPreferences, private val key: String, def: Int) : ReadWriteProperty<Any?, Int> {
    private val s = mutableIntStateOf(sp.getInt(key, def))
    override fun getValue(thisRef: Any?, property: KProperty<*>) = s.intValue
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { s.intValue = value; sp.edit().putInt(key, value).apply() }
}

class Prefs(ctx: Context) {
    val sp: SharedPreferences = ctx.getSharedPreferences("grove", Context.MODE_PRIVATE)
    var engine by IntPref(sp, "engine", 0)
    var theme by IntPref(sp, "theme", 0)
    var accent by IntPref(sp, "accent", 2)
    /** 0 = HTTPS-First, 1 = HTTPS-Only, 2 = allow HTTP */
    var https by IntPref(sp, "https", 0)
    /** 0 = ask, 1 = always open in the app, 2 = never */
    var appLinks by IntPref(sp, "appLinks", 0)
    var bgPlay by IntPref(sp, "bgPlay", 1)
    var pip by IntPref(sp, "pip", 1)
    val engineUrl get() = ENGINES.getOrElse(engine) { ENGINES[0] }.second
}

/** Reads favorites from a Grove/Firefox/Chrome JSON backup or any browser's exported bookmarks .html file. */
fun parseBookmarks(text: String): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    val t = text.trimStart()
    if (t.startsWith("{") || t.startsWith("[")) {
        fun walk(o: Any?) {
            when (o) {
                is JSONArray -> for (i in 0 until o.length()) walk(o.opt(i))
                is JSONObject -> {
                    val u = o.optString("url").ifEmpty { o.optString("uri") }
                    if (u.startsWith("http")) out += o.optString("title").ifEmpty { o.optString("name") } to u
                    o.keys().forEach { k -> o.opt(k).let { v -> if (v is JSONArray || v is JSONObject) walk(v) } }
                }
            }
        }
        runCatching { walk(if (t.startsWith("[")) JSONArray(t) else JSONObject(t)) }
        return out
    }
    Regex("<a\\s[^>]*href\\s*=\\s*\"([^\"]+)\"[^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .findAll(text).forEach { m ->
            out += Html.fromHtml(m.groupValues[2], 0).toString().trim() to Html.fromHtml(m.groupValues[1], 0).toString()
        }
    return out
}

object Urls {
    private val TRACK = Regex(
        "^(utm_.*|fbclid|gclid|gclsrc|dclid|gbraid|wbraid|msclkid|mc_eid|mc_cid|igshid|igsh|yclid|twclid|ttclid|" +
            "_hsenc|_hsmi|mkt_tok|oly_anon_id|oly_enc_id|vero_id|__s|_openstat|rb_clickid|s_cid|epik|li_fat_id)$",
        RegexOption.IGNORE_CASE,
    )
    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")
    private val HOSTLIKE = Regex("^([\\w-]+\\.)+[a-zA-Z]{2,}(:\\d+)?(/.*)?$|^localhost(:\\d+)?(/.*)?$|^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$")

    /** Strips known tracking parameters from http(s) links. */
    fun clean(url: String): String = runCatching {
        val u = Uri.parse(url)
        if (u.scheme !in setOf("http", "https") || u.encodedQuery == null) return url
        val host = u.host.orEmpty()
        val names = u.queryParameterNames
        val keep = names.filterNot { TRACK.matches(it) || (it == "si" && (host.contains("youtu") || host.contains("spotify"))) }
        if (keep.size == names.size) return url
        val b = u.buildUpon().clearQuery()
        keep.forEach { k -> u.getQueryParameters(k).forEach { b.appendQueryParameter(k, it) } }
        b.build().toString()
    }.getOrDefault(url)

    /** Address-bar text -> URL. Only web schemes are allowed; anything else becomes a search. */
    fun fromInput(input: String, engine: String): String {
        val t = input.trim()
        if (t.isEmpty()) return ""
        val scheme = SCHEME.find(t)?.groupValues?.get(1)?.lowercase()
        return when {
            scheme == "http" || scheme == "https" -> clean(t)
            scheme == "moz-extension" -> t
            !t.contains(' ') && HOSTLIKE.matches(t) -> clean("https://$t")
            else -> engine.replace("%s", Uri.encode(t))
        }
    }

    fun isWeb(url: String?) = url != null && Uri.parse(url).scheme?.lowercase().let { it == "http" || it == "https" }

    fun host(url: String) = runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull() ?: url
}
