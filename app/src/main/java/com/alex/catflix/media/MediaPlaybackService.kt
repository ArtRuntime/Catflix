package com.alex.catflix.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.alex.catflix.MainActivity
import com.alex.catflix.net.SmartHttpCache


class MediaPlaybackService : Service() {

    companion object {
        private const val TAG = "MediaPlaybackService"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "catflix_playback"
        private const val POLL_MS = 1000L


        private const val SLOW_POLL_MS = 5000L
        private const val NO_VIDEO_LIMIT = 3

        const val ACTION_PLAY = "com.alex.catflix.media.PLAY"
        const val ACTION_PAUSE = "com.alex.catflix.media.PAUSE"
        const val ACTION_TOGGLE = "com.alex.catflix.media.TOGGLE"
        const val ACTION_NEXT = "com.alex.catflix.media.NEXT"
        const val ACTION_PREV = "com.alex.catflix.media.PREV"
        const val ACTION_SEEK = "com.alex.catflix.media.SEEK"
        const val ACTION_STOP = "com.alex.catflix.media.STOP"
        const val EXTRA_POSITION = "position_ms"

        fun actionIntent(context: Context, action: String): Intent =
            Intent(context, MediaPlaybackService::class.java).setAction(action)
    }

    inner class LocalBinder : Binder() {
        fun service(): MediaPlaybackService = this@MediaPlaybackService
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())

    private val httpCache by lazy { SmartHttpCache(cacheDir, "CatFlix/1.0") }

    private lateinit var session: MediaSessionCompat
    private lateinit var notificationManager: NotificationManager

    @Volatile
    private var commands: WebMediaCommands? = null

    private var polling = false
    private var foregrounded = false
    private var noVideoStreak = 0
    private var noHandlerStreak = 0
    private var lastHadVideo = false


    private var slowMode = false
    private var artSrc: String = ""
    private var artBitmap: Bitmap? = null

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            commands?.play()
            refreshNow()
        }

        override fun onPause() {
            commands?.pause()
            refreshNow()
        }

        override fun onSeekTo(pos: Long) {
            commands?.seekTo(pos)
            refreshNow()
        }

        override fun onSkipToNext() {
            commands?.next()
            refreshNow()
        }

        override fun onSkipToPrevious() {
            commands?.previous()
            refreshNow()
        }

        override fun onStop() {
            commands?.pause()
            shutdown()
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            val cmds = commands
            if (cmds == null) {

                noHandlerStreak++
                if (noHandlerStreak >= 30) shutdown() else scheduleNext()
                return
            }
            noHandlerStreak = 0
            try {
                cmds.snapshot { snap -> handleSnapshot(snap) }
            } catch (e: Exception) {
                Log.w(TAG, "Poll failed", e)
            }
            scheduleNext()
        }

        private fun scheduleNext() {
            if (polling) main.postDelayed(this, if (slowMode) SLOW_POLL_MS else POLL_MS)
        }
    }



    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Now playing",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Playback controls for the embedded browser" }
        )
        session = MediaSessionCompat(this, "CatFlix").apply {
            setCallback(sessionCallback, main)
            @Suppress("DEPRECATION")
            setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            isActive = false
        }




        startForegroundSafe(buildPlaceholderNotification())
        startPoll()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if (!foregrounded) startForegroundSafe(buildPlaceholderNotification())
        when (intent?.action) {
            ACTION_PLAY -> {
                commands?.play()
                refreshNow()
            }
            ACTION_PAUSE -> {
                commands?.pause()
                refreshNow()
            }
            ACTION_TOGGLE -> {
                val cmds = commands
                val playing = MediaStateBus.last?.playing == true
                Log.i(TAG, "TOGGLE received (playing=$playing, commands=${cmds != null})")
                if (playing) cmds?.pause() else cmds?.play()
                refreshNow()
            }
            ACTION_NEXT -> {
                commands?.next()
                refreshNow()
            }
            ACTION_PREV -> {
                commands?.previous()
                refreshNow()
            }
            ACTION_SEEK -> {
                val pos = intent.getLongExtra(EXTRA_POSITION, -1L)
                if (pos >= 0) commands?.seekTo(pos)
                refreshNow()
            }
            ACTION_STOP -> shutdown()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {


        shutdown()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopPoll()
        try {
            session.isActive = false
            session.release()
        } catch (_: Exception) {
        }
        try {
            notificationManager.cancel(NOTIF_ID)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }



    fun registerCommands(cmds: WebMediaCommands) {
        commands = cmds
        refreshNow()
    }


    fun shutdown() {
        stopPoll()
        if (lastHadVideo) {
            lastHadVideo = false
            try {
                MediaStateBus.post(MediaSnapshot.noVideo())
            } catch (_: Exception) {
            }
        }
        try {
            session.isActive = false
        } catch (_: Exception) {
        }
        if (foregrounded) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {
            }
            foregrounded = false
        }
        try {
            notificationManager.cancel(NOTIF_ID)
        } catch (_: Exception) {
        }
        stopSelf()
    }



    private fun startForegroundSafe(notif: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
            foregrounded = true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
        }
    }

    private fun buildPlaceholderNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("CatFlix")
            .setContentText("Ready for playback")
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    private fun startPoll() {
        if (polling) return
        polling = true
        main.post(pollRunnable)
    }

    private fun stopPoll() {
        polling = false
        main.removeCallbacks(pollRunnable)
    }

    private fun refreshNow() {
        if (!polling) return
        main.removeCallbacks(pollRunnable)
        main.post(pollRunnable)
    }


    private fun isAudioActive(): Boolean {
        return try {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive
        } catch (_: Exception) {
            false
        }
    }

    private fun handleSnapshot(snap: MediaSnapshot?) {
        if (snap == null) return 
        if (!snap.hasVideo) {



            if (MediaStateBus.trafficFresh() && isAudioActive()) {
                val prev = MediaStateBus.last
                val likely = MediaSnapshot(
                    hasVideo = true,
                    playing = true,
                    positionMs = prev?.positionMs ?: 0L,
                    durationMs = prev?.durationMs ?: 0L,
                    title = prev?.title ?: "",
                    poster = prev?.poster ?: "",
                    src = prev?.src ?: "",
                    videoWidth = prev?.videoWidth ?: 0,
                    videoHeight = prev?.videoHeight ?: 0,
                    confirmed = false
                )
                lastHadVideo = true
                noVideoStreak = 0
                slowMode = false
                MediaStateBus.post(likely)
                updateSession(likely)
                showNotification(likely)
                return
            }

            if (lastHadVideo) {
                lastHadVideo = false
                MediaStateBus.post(MediaSnapshot.noVideo())
            }
            noVideoStreak++
            if (noVideoStreak >= NO_VIDEO_LIMIT && !slowMode) {


                slowMode = true
                try {
                    session.isActive = false
                } catch (_: Exception) {
                }
                if (foregrounded) {
                    try {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } catch (_: Exception) {
                    }
                    foregrounded = false
                }
                try {
                    notificationManager.cancel(NOTIF_ID)
                } catch (_: Exception) {
                }
            }
            return
        }
        lastHadVideo = true
        noVideoStreak = 0
        slowMode = false
        MediaStateBus.post(snap)
        updateSession(snap)
        showNotification(snap)
    }

    private fun updateSession(snap: MediaSnapshot) {
        val playing = snap.playing
        val state = if (playing) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, snap.positionMs, if (playing) 1.0f else 0f)
                .setBufferedPosition(snap.durationMs)
                .build()
        )
        val meta = MediaMetadataCompat.Builder()
            .putString(
                MediaMetadataCompat.METADATA_KEY_TITLE,
                snap.title.ifBlank { "NetMirror" }
            )
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, snap.title.ifBlank { "NetMirror" })
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, snap.durationMs)
            .apply { artBitmap?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it) } }
            .build()
        session.setMetadata(meta)
        if (!session.isActive) {
            try {
                session.isActive = true
            } catch (_: Exception) {
            }
        }
        maybeLoadArt(snap)
    }

    private fun showNotification(snap: MediaSnapshot) {
        val playing = snap.playing
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(
                if (playing) android.R.drawable.ic_media_play
                else android.R.drawable.ic_media_pause
            )
            .setContentTitle(snap.title.ifBlank { "NetMirror" })
            .setContentText(if (playing) "Playing" else "Paused")
            .setContentIntent(contentIntent())
            .setDeleteIntent(servicePendingIntent(ACTION_STOP, 40))
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                android.R.drawable.ic_media_previous, "Previous",
                servicePendingIntent(ACTION_PREV, 41)
            )
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play",
                servicePendingIntent(ACTION_TOGGLE, 42)
            )
            .addAction(
                android.R.drawable.ic_media_next, "Next",
                servicePendingIntent(ACTION_NEXT, 43)
            )
            .setStyle(
                MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
        if (!foregrounded) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIF_ID, notif,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NOTIF_ID, notif)
                }
                foregrounded = true
            } catch (e: Exception) {
                Log.w(TAG, "startForeground failed", e)
            }
        } else {
            notificationManager.notify(NOTIF_ID, notif)
        }
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode, actionIntent(this, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun contentIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )


    private fun maybeLoadArt(snap: MediaSnapshot) {
        val poster = snap.poster
        if (poster.isBlank() || !(poster.startsWith("http://") || poster.startsWith("https://"))) return
        if (poster == artSrc) return
        artSrc = poster
        val key = snap.src.ifBlank { poster }
        Thread {
            try {


                val bytes = httpCache.fetch(
                    poster,
                    SmartHttpCache.TTL_IMAGES_MS,
                    maxBytes = 2L * 1024 * 1024
                )?.bytes ?: return@Thread
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while ((bounds.outWidth / sample) > 512 || (bounds.outHeight / sample) > 512) {
                    sample *= 2
                }
                val bmp = BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
                if (bmp != null && MediaStateBus.last?.src == key) {
                    artBitmap = bmp
                    main.post {
                        try {
                            MediaStateBus.last?.let { updateSession(it) }
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Art load failed", e)
            }
        }.start()
    }
}
