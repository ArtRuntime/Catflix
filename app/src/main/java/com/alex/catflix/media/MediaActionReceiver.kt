package com.alex.catflix.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log


class MediaActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MediaActionReceiver"


        private val ALLOWED_ACTIONS = setOf(
            MediaPlaybackService.ACTION_PLAY,
            MediaPlaybackService.ACTION_PAUSE,
            MediaPlaybackService.ACTION_TOGGLE,
            MediaPlaybackService.ACTION_NEXT,
            MediaPlaybackService.ACTION_PREV,
            MediaPlaybackService.ACTION_SEEK,
            MediaPlaybackService.ACTION_STOP
        )
    }

    override fun onReceive(context: Context, intent: Intent) {



        val action = intent.action
        if (action == null || action !in ALLOWED_ACTIONS) {
            Log.w(TAG, "Blocked non-allowlisted action: $action")
            return
        }
        Log.i(TAG, "Forwarding $action to playback service")
        try {
            val service = MediaPlaybackService.actionIntent(context, action)
            if (action == MediaPlaybackService.ACTION_SEEK) {

                service.putExtra(
                    MediaPlaybackService.EXTRA_POSITION,
                    intent.getLongExtra(MediaPlaybackService.EXTRA_POSITION, -1L)
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service)
            } else {
                context.startService(service)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to forward $action", e)
        }
    }
}
