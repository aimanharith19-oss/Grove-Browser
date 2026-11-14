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
import org.mozilla.geckoview.GeckoSession
import java.io.File

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

class Prefs(ctx: Context) {
    val sp: SharedPreferences = ctx.getSharedPreferences("grove", Context.MODE_PRIVATE)
    private val e = mutableIntStateOf(sp.getInt("engine", 0))
    private val t = mutableIntStateOf(sp.getInt("theme", 0))
    private val a = mutableIntStateOf(sp.getInt("accent", 2))
    var engine: Int get() = e.intValue; set(v) { e.intValue = v; sp.edit().putInt("engine", v).apply() }
    var theme: Int get() = t.intValue; set(v) { t.intValue = v; sp.edit().putInt("theme", v).apply() }
    var accent: Int get() = a.intValue; set(v) { a.intValue = v; sp.edit().putInt("accent", v).apply() }
    val engineUrl get() = ENGINES.getOrElse(engine) { ENGINES[0] }.second
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
