package com.alex.catflix.auth

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import java.util.Locale


object GoogleAuthHandler {

    private const val TAG = "GoogleAuth"


    private val TRUSTED_HOSTS = setOf(
        "net77.cc",
        "netmirror.gg",
        "netmirror.app",
        "hineanime.lol",
        "hianime.lol"
    )


    private val GOOGLE_OAUTH_HOSTS = setOf(
        "accounts.google.com",
        "accounts.youtube.com"
    )

    private val OAUTH_PATH_HINTS = listOf(
        "/o/oauth2/", "/signin/oauth", "/gsi/", "/servicelogin",
        "/accountchooser", "/v3/signin", "/fedcm"
    )

    fun isSiteUrl(url: String): Boolean {
        val host = hostOf(url) ?: return false
        if (TRUSTED_HOSTS.any { host == it || host.endsWith(".$it") }) return true
        if (url.startsWith("about:")) return true
        return false
    }


    fun isGoogleOAuthUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        val host = hostOf(url) ?: return false
        val isGoogleHost = GOOGLE_OAUTH_HOSTS.any { host == it || host.endsWith(".$it") } ||

            host.startsWith("accounts.google.") ||
            (host.endsWith(".google.com") && lower.contains("oauth"))
        if (!isGoogleHost) return false
        if (lower.contains("oauth") || lower.contains("gsi") || lower.contains("fedcm")) return true
        return OAUTH_PATH_HINTS.any { lower.contains(it) }
    }

    fun hostOf(url: String): String? = try {
        url.toUri().host?.lowercase(Locale.US)
    } catch (_: Exception) {
        null
    }


    fun openInSystemBrowser(context: Context, url: String) {
        try {
            val view = android.content.Intent(
                android.content.Intent.ACTION_VIEW, url.toUri()
            ).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ContextCompat.startActivity(context, view, null)
            Log.i(TAG, "Opened in system browser: ${redacted(url)}")
        } catch (e: Exception) {
            Log.e(TAG, "No browser available", e)
        }
    }

    private fun redacted(url: String): String {
        val uri = try {
            url.toUri()
        } catch (_: Exception) {
            return "(unparseable)"
        }
        return "${uri.scheme}://${uri.host}${uri.path}"
    }
}
