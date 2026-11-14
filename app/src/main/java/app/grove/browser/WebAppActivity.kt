package app.grove.browser

import android.app.ActivityManager
import android.os.Bundle

/** An installed web app (PWA): its own window and recents entry, no browser bars. */
class WebAppActivity : MainActivity() {
    override val webApp: Pair<String, String?>?
        get() = intent?.dataString?.takeIf { Urls.isWeb(it) }?.let { it to intent.getStringExtra("scope")?.takeIf { s -> Urls.isWeb(s) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("name")?.let { @Suppress("DEPRECATION") setTaskDescription(ActivityManager.TaskDescription(it)) }
    }
}
