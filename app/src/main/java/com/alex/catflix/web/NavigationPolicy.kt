package com.alex.catflix.web

import androidx.core.net.toUri
import com.alex.catflix.adblock.AdBlockEngine
import com.alex.catflix.auth.GoogleAuthHandler


object NavigationPolicy {

    sealed interface Decision {

        data object Allow : Decision


        data object CancelBlocked : Decision


        data object ExternalPage : Decision


        data object OpenOutside : Decision
    }


    fun isMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        val path = lower.substringBefore('?')
        if (path.endsWith(".mp4") || path.endsWith(".webm") ||
            path.endsWith(".mkv") || path.endsWith(".mov") ||
            path.endsWith(".m4v") || path.endsWith(".3gp") ||
            path.endsWith(".ts") || path.endsWith(".m4s") ||
            path.endsWith(".cmfv") || path.endsWith(".cmfa") ||
            path.endsWith(".aac") || path.endsWith(".mp3") ||
            path.endsWith(".ogg") || path.endsWith(".oga") ||
            path.endsWith(".opus")
        ) return true
        if (lower.contains(".m3u8") || lower.contains(".mpd")) return true
        return lower.contains("videoplayback") ||
            lower.contains("mime=video") ||
            lower.contains("/hls/") ||
            lower.contains("/dash/")
    }

    fun decide(url: String, adBlock: AdBlockEngine): Decision {
        val uri = try {
            url.toUri()
        } catch (_: Exception) {
            return Decision.OpenOutside
        }
        val scheme = (uri.scheme ?: "").lowercase()
        if (scheme != "http" && scheme != "https") return Decision.OpenOutside
        val host = (uri.host ?: "").lowercase().trimStart('.')
        if (host.isEmpty()) return Decision.OpenOutside




        if (scheme != "https") {
            return Decision.CancelBlocked
        }


        if (GoogleAuthHandler.isSiteUrl(url) || GoogleAuthHandler.isGoogleOAuthUrl(url)) {
            return Decision.Allow
        }

        if (adBlock.isBlockedHost(host)) {
            return Decision.CancelBlocked
        }

        return Decision.ExternalPage
    }
}
