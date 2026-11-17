package app.grove.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

/** The media that keeps playing in the background, shared between the browser and the service. */
object MediaHub {
    @Volatile var session: org.mozilla.geckoview.MediaSession? = null; private set
    @Volatile var title = ""; private set
    @Volatile var playing = false; private set
    /** Album art / video picture from the website, and who is playing. */
    @Volatile var art: android.graphics.Bitmap? = null; private set
    @Volatile var artist = ""; private set
    var listener: (() -> Unit)? = null

    fun update(s: org.mozilla.geckoview.MediaSession?, t: String, p: Boolean) {
        if (s !== session) art = null
        session = s; title = t; playing = p && s != null
        listener?.invoke()
    }

    fun details(s: org.mozilla.geckoview.MediaSession?, who: String, picture: android.graphics.Bitmap?) {
        if (s !== session) return
        artist = who; art = picture
        listener?.invoke()
    }
}

/** Keeps web audio/video playing in the background, with lock-screen and headset controls. */
class MediaService : Service() {
    private lateinit var ms: MediaSession
    private val main = Handler(Looper.getMainLooper())
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        nm.createNotificationChannel(NotificationChannel(CH, "Media playback", NotificationManager.IMPORTANCE_LOW))
        ms = MediaSession(this, "grove").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { MediaHub.session?.play() }
                override fun onPause() { MediaHub.session?.pause() }
                override fun onStop() { MediaHub.session?.pause(); MediaHub.update(null, "", false) }
                override fun onSkipToNext() { MediaHub.session?.nextTrack() }
                override fun onSkipToPrevious() { MediaHub.session?.previousTrack() }
            })
            isActive = true
        }
        MediaHub.listener = { main.post { refresh() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PLAY -> MediaHub.session?.play()
            PAUSE -> MediaHub.session?.pause()
            STOP -> { MediaHub.session?.pause(); MediaHub.update(null, "", false) }
        }
        begun = true
        refresh(started = true)
        return START_NOT_STICKY
    }

    private fun pi(action: String) =
        PendingIntent.getService(this, action.hashCode(), Intent(this, MediaService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    /** started: called from startForegroundService, so Android needs startForeground() now, whatever the state (else it kills Grove). */
    private var begun = false
    private fun refresh(started: Boolean = false) {
        if (!begun) return // wait for onStartCommand (stopping before startForeground also crashes)
        if (MediaHub.session == null) {
            if (started) runCatching {
                startForeground(ID, Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_launcher_fg).setContentTitle("Grove").build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            }
            stopForeground(STOP_FOREGROUND_REMOVE); nm.cancel(ID); stopSelf(); return
        }
        val playing = MediaHub.playing
        ms.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
            .build())
        val art = MediaHub.art
        val who = MediaHub.artist.ifBlank { "Grove" }
        ms.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, MediaHub.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, who)
            .apply { if (art != null) putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art) }.build())
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val toggle = if (playing)
            Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_pause), "pause", pi(PAUSE)).build()
        else Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_play), "play", pi(PLAY)).build()
        val close = Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "close", pi(STOP)).build()
        val n = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle(MediaHub.title)
            .setContentText(MediaHub.artist.ifBlank { "playing in grove" })
            .setLargeIcon(art ?: appIcon()) // the song's album art, or Grove's icon
            .setContentIntent(open)
            .setDeleteIntent(pi(STOP))
            .setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(toggle).addAction(close)
            .setStyle(Notification.MediaStyle().setMediaSession(ms.sessionToken).setShowActionsInCompactView(0, 1))
            .build()
        if (playing || started) runCatching { startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }.onFailure { nm.notify(ID, n) }
        if (!playing) { stopForeground(STOP_FOREGROUND_DETACH); nm.notify(ID, n) }
    }

    private var icon: android.graphics.Bitmap? = null
    private fun appIcon(): android.graphics.Bitmap? = icon ?: runCatching {
        val d = packageManager.getApplicationIcon(packageName)
        val b = android.graphics.Bitmap.createBitmap(192, 192, android.graphics.Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 192, 192); d.draw(android.graphics.Canvas(b)); b
    }.getOrNull().also { icon = it }

    override fun onDestroy() {
        MediaHub.listener = null
        ms.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        private const val CH = "media"
        private const val ID = 7
        private const val PLAY = "play"
        private const val PAUSE = "pause"
        private const val STOP = "stop"
    }
}
