package app.grove.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Website icons for favorites, cached on the phone per site. */
object Icons {
    val images = mutableStateMapOf<String, ImageBitmap>()
    private val hints = HashMap<String, String>()
    private val busy = HashSet<String>()
    private val failed = HashSet<String>()
    private val pool = Executors.newFixedThreadPool(3)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun dir(ctx: Context) = File(ctx.filesDir, "icons").apply { mkdirs() }
    private fun file(ctx: Context, host: String) = File(dir(ctx), host.replace(Regex("[^a-zA-Z0-9.-]"), "_") + ".png")

    /** Icon for a page, loading it in the background the first time. Null until ready. */
    fun get(ctx: Context, url: String): ImageBitmap? {
        val host = Urls.host(url)
        images[host]?.let { return it }
        if (host.isBlank() || !Urls.isWeb(url)) return null
        synchronized(this) { if (host in busy || host in failed) return null; busy += host }
        val app = ctx.applicationContext
        pool.execute {
            val f = file(app, host)
            val bmp = (if (f.exists()) BitmapFactory.decodeFile(f.path) else null) ?: fetch(app, host, url)
            main.post {
                synchronized(this) { busy -= host; if (bmp == null) failed += host }
                bmp?.let { images[host] = it.asImageBitmap() }
            }
        }
        return null
    }

    /** The page told us its best icon (from the Grove helper). Refreshes favorites of that site. */
    fun hint(ctx: Context, pageUrl: String, iconUrl: String) {
        val host = Urls.host(pageUrl)
        if (!iconUrl.startsWith("https://") || hints[host] == iconUrl) return
        hints[host] = iconUrl
        val f = file(ctx, host)
        if (f.exists() && images.containsKey(host)) return
        synchronized(this) { failed -= host }
        f.delete()
        get(ctx, pageUrl)
    }

    private fun fetch(ctx: Context, host: String, url: String): Bitmap? {
        val origin = runCatching { URL(url).let { "https://${it.host}" } }.getOrNull() ?: return null
        val candidates = listOfNotNull(hints[host], "$origin/apple-touch-icon.png", "$origin/favicon.ico")
        for (c in candidates) {
            val b = download(c) ?: continue
            if (b.width < 16) continue
            val s = Bitmap.createScaledBitmap(b, 96, 96, true)
            runCatching { file(ctx, host).outputStream().use { s.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            return s
        }
        return null
    }

    private fun download(u: String): Bitmap? = runCatching {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 5000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile) Gecko Firefox Grove")
        if (c.responseCode !in 200..299 || c.contentLengthLong > 2_000_000) return null
        c.inputStream.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()
}
