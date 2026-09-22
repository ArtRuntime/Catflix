package com.alex.catflix.media

import java.util.concurrent.CopyOnWriteArrayList


data class MediaSnapshot(
    val hasVideo: Boolean,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val title: String,
    val poster: String,
    val src: String,
    val videoWidth: Int,
    val videoHeight: Int,

    val confirmed: Boolean = true
) {
    companion object {
        fun noVideo() = MediaSnapshot(
            hasVideo = false,
            playing = false,
            positionMs = 0L,
            durationMs = 0L,
            title = "",
            poster = "",
            src = "",
            videoWidth = 0,
            videoHeight = 0,
            confirmed = true
        )
    }
}


interface WebMediaCommands {
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long)
    fun next()
    fun previous()
    fun snapshot(callback: (MediaSnapshot?) -> Unit)
}


object MediaStateBus {
    @Volatile
    var last: MediaSnapshot? = null
        private set


    @Volatile
    var lastMediaTrafficMs: Long = 0L
        private set

    fun noteMediaTraffic() {
        lastMediaTrafficMs = System.currentTimeMillis()
    }

    fun trafficFresh(windowMs: Long = 15_000L): Boolean =
        System.currentTimeMillis() - lastMediaTrafficMs < windowMs

    private val listeners = CopyOnWriteArrayList<(MediaSnapshot) -> Unit>()

    fun post(snapshot: MediaSnapshot) {
        last = snapshot
        for (listener in listeners) {
            try {
                listener(snapshot)
            } catch (_: Exception) {

            }
        }
    }

    fun addListener(listener: (MediaSnapshot) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (MediaSnapshot) -> Unit) {
        listeners.remove(listener)
    }
}
