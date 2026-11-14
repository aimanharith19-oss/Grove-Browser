package app.grove.browser

import android.app.Application
import java.io.File

/** Saves the last crash on the phone only (never sent anywhere) so it can be shown and copied. */
class GroveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, e ->
            runCatching { crashFile(this).writeText("Grove crash on ${th.name}\n\n" + e.stackTraceToString()) }
            prev?.uncaughtException(th, e)
        }
    }

    companion object {
        fun crashFile(app: Application) = File(app.filesDir, "last_crash.txt")
    }
}
