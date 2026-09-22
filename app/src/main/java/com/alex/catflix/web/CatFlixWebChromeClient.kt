package com.alex.catflix.web

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Message
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout


class CatFlixWebChromeClient(
    private val activity: Activity,
    private val events: ChromeEvents
) : WebChromeClient() {

    interface ChromeEvents {
        fun onProgressLocal(progress: Int)
        fun registerFileChooserLauncher(intent: Intent, callback: ValueCallback<Array<Uri>>)
        fun onPopupUrl(url: String)
        fun showCustomViewLocal(view: View, callback: CustomViewCallback)
        fun hideCustomViewLocal()
    }

    companion object {
        private const val TAG = "CatFlixChromeClient"
    }

    private var filePathCallback: ValueCallback<Array<Uri>>? = null


    private var popupView: WebView? = null


    fun launchFilePicker(intent: Intent, callback: ValueCallback<Array<Uri>>?) {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = callback
        try {
            events.registerFileChooserLauncher(intent, ValueCallback { uris ->
                filePathCallback?.onReceiveValue(uris)
                filePathCallback = null
            })
        } catch (e: Exception) {
            Log.w(TAG, "File picker launch failed", e)
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
        }
    }

    fun cancelFilePicker() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
    }

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        events.onProgressLocal(newProgress)
        super.onProgressChanged(view, newProgress)
    }


    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean {
        return try {
            val intent = fileChooserParams.createIntent()
            launchFilePicker(intent, filePathCallback)
            true
        } catch (e: Exception) {
            Log.w(TAG, "onShowFileChooser failed", e)
            false
        }
    }


    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message
    ): Boolean {
        return try {
            destroyPopup()
            val popup = WebView(view.context)


            popup.settings.javaScriptEnabled = true
            popup.settings.domStorageEnabled = true
            popup.settings.javaScriptCanOpenWindowsAutomatically = true
            try {
                popup.settings.safeBrowsingEnabled = true
            } catch (_: Exception) {
            }
            try {
                CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)
            } catch (e: Exception) {
                Log.w(TAG, "popup cookie setting failed", e)
            }
            popup.webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    v: WebView,
                    request: android.webkit.WebResourceRequest
                ): Boolean {
                    val url = request.url?.toString() ?: return false
                    Log.i(TAG, "Popup navigating, folding into main view")
                    events.onPopupUrl(url)
                    destroyPopup()
                    return true
                }

                override fun onPageFinished(v: WebView, url: String?) {
                    super.onPageFinished(v, url)


                    v.postDelayed({
                        if (popupView === v) {
                            Log.i(TAG, "Popup idle, releasing")
                            destroyPopup()
                        }
                    }, 120_000)
                }
            }
            popupView = popup
            val transport = resultMsg.obj as? WebView.WebViewTransport
                ?: return false
            transport.webView = popup
            resultMsg.sendToTarget()
            true
        } catch (e: Exception) {
            Log.w(TAG, "onCreateWindow failed", e)
            false
        }
    }

    override fun onCloseWindow(window: WebView) {

        if (window === popupView) destroyPopup()
        super.onCloseWindow(window)
    }

    private fun destroyPopup() {
        val p = popupView
        popupView = null
        if (p == null) return
        try {
            p.stopLoading()
            p.destroy()
        } catch (_: Exception) {
        }
    }



    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        try {
            Log.d(
                TAG,
                "JS[${consoleMessage.messageLevel()}] " +
                    "${consoleMessage.message()?.take(300)} " +
                    "@ ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}"
            )
        } catch (_: Exception) {
        }
        return super.onConsoleMessage(consoleMessage)
    }


    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        events.showCustomViewLocal(view, callback)
    }

    override fun onHideCustomView() {
        events.hideCustomViewLocal()
    }



    override fun onJsAlert(
        view: WebView,
        url: String,
        message: String,
        result: JsResult
    ): Boolean {
        Log.i(TAG, "Auto-dismissed JS alert: ${message.take(120)}")
        result.cancel()
        return true
    }

    override fun onJsConfirm(
        view: WebView,
        url: String,
        message: String,
        result: JsResult
    ): Boolean {
        Log.i(TAG, "Auto-dismissed JS confirm: ${message.take(120)}")
        result.cancel()
        return true
    }

    override fun onJsPrompt(
        view: WebView,
        url: String,
        message: String,
        defaultValue: String,
        result: JsPromptResult
    ): Boolean {
        Log.i(TAG, "Auto-dismissed JS prompt: ${message.take(120)}")
        result.cancel()
        return true
    }

    override fun onJsBeforeUnload(
        view: WebView,
        url: String,
        message: String,
        result: JsResult
    ): Boolean {

        Log.i(TAG, "Auto-confirmed beforeunload")
        result.confirm()
        return true
    }


    override fun onPermissionRequest(request: PermissionRequest) {
        Log.i(TAG, "Denying content permission: ${request.resources.contentToString()}")
        try {
            request.deny()
        } catch (e: Exception) {
            Log.w(TAG, "Permission deny failed", e)
        }
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String,
        callback: android.webkit.GeolocationPermissions.Callback
    ) {
        callback.invoke(origin, false, false)
    }
}


class FullscreenHolder(private val root: FrameLayout) {
    private var customView: View? = null
    private var callback: WebChromeClient.CustomViewCallback? = null
    val isFullscreen: Boolean get() = customView != null

    fun show(view: View, cb: WebChromeClient.CustomViewCallback) {
        hide()
        customView = view
        callback = cb
        root.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.bringChildToFront(view)
    }

    fun hide(): Boolean {
        val v = customView ?: return false
        return try {
            root.removeView(v)
            callback?.onCustomViewHidden()
            customView = null
            callback = null
            true
        } catch (_: Exception) {
            customView = null
            callback = null
            false
        }
    }
}
