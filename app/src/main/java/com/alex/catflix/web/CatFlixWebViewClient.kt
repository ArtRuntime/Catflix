package com.alex.catflix.web

import android.graphics.Bitmap
import android.net.http.SslError
import android.util.Log
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.alex.catflix.adblock.AdBlockEngine
import com.alex.catflix.auth.GoogleAuthHandler
import com.alex.catflix.media.MediaStateBus
import java.io.ByteArrayInputStream
import androidx.core.net.toUri


class CatFlixWebViewClient(
    private val adBlock: AdBlockEngine,
    private val events: WebEvents
) : WebViewClient() {

    interface WebEvents {
        fun onPageStartedLocal(url: String?)
        fun onPageFinishedLocal(url: String?)
        fun onHistoryChanged()
        fun onMainFrameError(url: String?, description: String)
        fun onGoogleOAuthBlocked(url: String)
        fun onExternalRedirect(url: String)
        fun onAdBlockWall()
        fun onChallengePage(url: String)
        fun clearMainFrameError()
    }


    @Volatile
    private var lastPageHost: String? = null

    companion object {
        private const val TAG = "CatFlixWebClient"


        fun redacted(url: String): String {
            return try {
                val uri = url.toUri()
                "${uri.scheme}://${uri.host}${uri.path}"
            } catch (_: Exception) {
                "(unparseable)"
            }
        }

        private const val BLOCKED_CHECK_JS =            "(function(){try{var t=((document.title||'')+' '+" +
                "((document.body&&document.body.innerText)||'')).toLowerCase();" +
                "var blocked=t.indexOf('disallowed_useragent')>=0||" +
                "(t.indexOf('error 403')>=0&&t.indexOf('google')>=0);" +
                "return blocked?'true':'false';}catch(e){return 'false';}})()"

        private const val WALL_CHECK_JS =
            "(function(){try{var t=(((document.body&&document.body.innerText)||'')).toLowerCase();" +
                "var wall=t.indexOf('adblock detected')>=0||t.indexOf('disable adblock')>=0||" +
                "t.indexOf('adblocker')>=0||t.indexOf('dns blocking')>=0;" +
                "return wall?'true':'false';}catch(e){return 'false';}})()"

        private const val CHALLENGE_CHECK_JS =
            "(function(){try{" +
                "var t=((document.title||'')+' '+((document.body&&document.body.innerText)||'')).toLowerCase();" +
                "var hit=t.indexOf('just a moment')>=0||t.indexOf('verifying you are human')>=0||" +
                "t.indexOf('verify you are human')>=0||t.indexOf('attention required')>=0;" +
                "return hit?'true':'false';}catch(e){return 'false';}})()"
        val BLOCKED_RESPONSE: WebResourceResponse
            get() = WebResourceResponse(
                "text/plain", "utf-8", 204, "No Content",
                mutableMapOf(), ByteArrayInputStream(ByteArray(0))
            )
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url?.toString() ?: return false
        if (!request.isForMainFrame) return false

        Log.i(TAG, "Top nav -> ${redacted(url)}")
        return when (NavigationPolicy.decide(url, adBlock)) {
            NavigationPolicy.Decision.Allow -> false
            NavigationPolicy.Decision.CancelBlocked -> {
                Log.i(TAG, "Dropping ad-popup top nav")
                true
            }
            NavigationPolicy.Decision.ExternalPage -> {


                events.onExternalRedirect(url)
                true
            }
            NavigationPolicy.Decision.OpenOutside -> {

                GoogleAuthHandler.openInSystemBrowser(view.context, url)
                true
            }
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {        lastPageHost = GoogleAuthHandler.hostOf(url ?: "")
        Log.i(TAG, "Page started: ${redacted(url ?: "")}")
        events.clearMainFrameError()
        events.onPageStartedLocal(url)
        super.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        lastPageHost = GoogleAuthHandler.hostOf(url ?: "")
        Log.i(TAG, "Page finished: ${redacted(url ?: "")}")
        CookieManager.getInstance().flush()
        events.onPageFinishedLocal(url)
        detectGoogleEmbeddedBlock(view, url)
        detectAdBlockWall(view, url)
        detectChallengePage(view, url)

        try {
            if (url != null && url == view.url && GoogleAuthHandler.isSiteUrl(url)) {
                PopupKiller.inject(view)
                SiteCleanup.inject(view)
            }
        } catch (_: Exception) {
        }
        super.onPageFinished(view, url)
    }


        private fun detectChallengePage(view: WebView, url: String?) {        try {
            if (url.isNullOrBlank()) return
            view.evaluateJavascript(CHALLENGE_CHECK_JS) { raw ->
                try {
                    if (raw != null && raw.contains("true")) {
                        events.onChallengePage(url)
                    }
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Challenge detection failed", e)
        }
    }

    private fun detectAdBlockWall(view: WebView, url: String?) {
        try {
            if (!GoogleAuthHandler.isSiteUrl(url ?: "")) return
            view.evaluateJavascript(WALL_CHECK_JS) { raw ->
                try {
                    if (raw != null && raw.contains("true")) {
                        Log.w(TAG, "Adblock wall detected")
                        events.onAdBlockWall()
                    }
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Wall detection failed", e)
        }
    }


    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        try {
            events.onHistoryChanged()
        } catch (_: Exception) {
        }
        super.doUpdateVisitedHistory(view, url, isReload)
    }


    private fun detectGoogleEmbeddedBlock(view: WebView, url: String?) {
        try {
            val host = GoogleAuthHandler.hostOf(url ?: "") ?: return
            val isGoogleHost = host == "accounts.google.com" ||
                host.endsWith(".accounts.google.com") ||
                host.endsWith(".google.com")
            if (!isGoogleHost) return
            view.evaluateJavascript(BLOCKED_CHECK_JS) { raw ->
                try {
                    if (raw != null && raw.contains("true")) {
                        Log.w(TAG, "Google embedded sign-in refused for $url")
                        events.onGoogleOAuthBlocked(url ?: "")
                    }
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Block detection failed", e)
        }
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        try {
            val url = request.url?.toString() ?: return null
            if (request.isForMainFrame) return null



            try {
                if (NavigationPolicy.isMediaUrl(url)) {
                    MediaStateBus.noteMediaTraffic()
                }
            } catch (_: Exception) {
            }


            adBlock.neuterResponse(url)?.let { return it }
            if (adBlock.shouldBlock(url, lastPageHost, isMainFrame = false)) {
                Log.d(TAG, "Blocked: ${redacted(url)}")
                return BLOCKED_RESPONSE
            }
        } catch (e: Exception) {
            Log.w(TAG, "AdBlock intercept failed", e)
        }
        return super.shouldInterceptRequest(view, request)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        if (!request.isForMainFrame) return
        val desc = "ERR_${error.errorCode}: ${error.description}"
        Log.w(TAG, "Main frame error: $desc for ${redacted(request.url?.toString() ?: "")}")
        events.onMainFrameError(request.url?.toString(), desc)
        super.onReceivedError(view, request, error)
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse
    ) {
        if (!request.isForMainFrame) return
        val url = request.url?.toString() ?: ""
        Log.w(TAG, "HTTP ${errorResponse.statusCode} for ${redacted(url)}")

        if (errorResponse.statusCode == 403 && GoogleAuthHandler.hostOf(url)
                ?.contains("google.com") == true
        ) {



            events.onGoogleOAuthBlocked(url)
            return
        }
        if (errorResponse.statusCode >= 400) {
            events.onMainFrameError(url, "HTTP ${errorResponse.statusCode}")
        }
        super.onReceivedHttpError(view, request, errorResponse)
    }

    override fun onReceivedSslError(
        view: WebView,
        handler: SslErrorHandler,
        error: SslError
    ) {

        Log.e(TAG, "SSL error, cancelling: $error")
        handler.cancel()
        events.onMainFrameError(view.url, "TLS certificate error — connection blocked for your safety.")
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView,
        handler: HttpAuthHandler,
        host: String,
        realm: String
    ) {

        handler.cancel()
    }

    override fun onRenderProcessGone(
        view: WebView,
        detail: RenderProcessGoneDetail
    ): Boolean {
        Log.e(TAG, "Render process gone, didCrash=${detail.didCrash()}")

        events.onMainFrameError(view.url, "Renderer crashed — reload to continue.")
        return true
    }
}
