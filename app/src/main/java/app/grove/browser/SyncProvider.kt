package app.grove.browser

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Lets Grove Launcher (and only it) share bookmarks and history with this browser. Nothing is deleted by a sync;
 * new entries from either side are added. The data never leaves the phone.
 */
class SyncProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        if (callingPackage != LAUNCHER) throw SecurityException("only Grove Launcher may use this")
        val dir = ctx.filesDir
        synchronized(LOCK) {
            val bm = File(dir, "bookmarks.json")
            val hi = File(dir, "history.json")
            return when (method) {
                "get" -> Bundle().apply {
                    putString("bookmarks", read(bm).toString())
                    putString("history", read(hi).toString())
                }
                "put" -> {
                    mergeBookmarks(bm, extras?.getString("bookmarks"))
                    mergeHistory(hi, extras?.getString("history"))
                    Bundle()
                }
                else -> null
            }
        }
    }

    private fun read(f: File): JSONArray = runCatching { JSONArray(f.readText()) }.getOrDefault(JSONArray())

    private fun write(f: File, a: JSONArray) {
        val tmp = File(f.path + ".tmp"); tmp.writeText(a.toString()); tmp.renameTo(f)
    }

    private fun incoming(s: String?): JSONArray = runCatching { JSONArray(s ?: "[]") }.getOrDefault(JSONArray())

    private fun mergeBookmarks(f: File, json: String?) {
        val mine = read(f)
        val urls = HashSet<String>()
        for (i in 0 until mine.length()) urls += mine.getJSONObject(i).optString("url")
        val inc = incoming(json)
        var next = System.currentTimeMillis()
        var changed = false
        for (i in 0 until inc.length()) {
            val o = inc.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (!(url.startsWith("http://") || url.startsWith("https://")) || url.length > 2000 || url in urls) continue
            urls += url
            mine.put(JSONObject().put("id", next++).put("title", o.optString("title").take(200)).put("url", url))
            changed = true
        }
        if (changed) write(f, mine)
    }

    private fun mergeHistory(f: File, json: String?) {
        val mine = read(f)
        val seen = HashSet<String>()
        for (i in 0 until mine.length()) { val o = mine.getJSONObject(i); seen += o.optString("url") + "|" + o.optLong("time") }
        val inc = incoming(json)
        val all = ArrayList<JSONObject>()
        for (i in 0 until mine.length()) all += mine.getJSONObject(i)
        var changed = false
        for (i in 0 until inc.length()) {
            val o = inc.optJSONObject(i) ?: continue
            val url = o.optString("url")
            val time = o.optLong("time")
            if (!(url.startsWith("http://") || url.startsWith("https://")) || url.length > 2000 || !seen.add("$url|$time")) continue
            all += JSONObject().put("title", o.optString("title").take(200)).put("url", url).put("time", time)
            changed = true
        }
        if (!changed) return
        all.sortBy { it.optLong("time") }
        val out = JSONArray()
        all.takeLast(300).forEach { out.put(it) }
        write(f, out)
    }

    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        private const val LAUNCHER = "org.canopy.launcher"
        private val LOCK = Any()
    }
}
