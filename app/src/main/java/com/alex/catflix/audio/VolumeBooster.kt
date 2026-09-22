package com.alex.catflix.audio

import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import kotlin.math.log10


class VolumeBooster {

    companion object {
        private const val TAG = "VolumeBooster"


        private const val GLOBAL_MIX_SESSION = 0
    }

    private var enhancer: LoudnessEnhancer? = null
    private var currentGainMb: Int = 0


    @Synchronized
    fun setBoost(percent: Int) {
        if (percent <= 100) {
            release()
            return
        }
        val targetMb = gainMbFor(percent)
        try {
            var e = enhancer
            if (e == null) {


                e = LoudnessEnhancer(GLOBAL_MIX_SESSION)
                enhancer = e
            }
            try {
                e.setEnabled(true)
            } catch (_: Exception) {
            }
            if (targetMb != currentGainMb) {
                setGainClamped(e, targetMb)
                currentGainMb = targetMb
            }
        } catch (e: Exception) {
            Log.w(TAG, "Global-mix boost not allowed on this device", e)
            release()
        }
    }

    @Synchronized
    fun release() {
        currentGainMb = 0
        val e = enhancer
        enhancer = null
        if (e == null) return
        try {
            e.setEnabled(false)
        } catch (_: Exception) {
        }
        try {
            e.release()
        } catch (_: Exception) {
        }
    }


    private fun gainMbFor(percent: Int): Int =
        (20.0 * log10((percent.coerceIn(100, 5000)) / 100.0) * 100).toInt()
            .coerceIn(0, 4000)


    private fun setGainClamped(e: LoudnessEnhancer, targetMb: Int) {
        var gain = targetMb
        while (true) {
            try {
                e.setTargetGain(gain)
                return
            } catch (_: RuntimeException) {
                gain /= 2
                if (gain <= 0) {
                    Log.w(TAG, "No usable gain on this device")
                    return
                }
            }
        }
    }
}
