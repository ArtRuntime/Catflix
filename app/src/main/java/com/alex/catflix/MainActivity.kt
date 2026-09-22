package com.alex.catflix

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.alex.catflix.adblock.AdBlockEngine
import com.alex.catflix.audio.VolumeBooster
import com.alex.catflix.auth.GoogleAuthHandler
import com.alex.catflix.media.MediaActionReceiver
import com.alex.catflix.media.MediaPlaybackService
import com.alex.catflix.media.MediaSnapshot
import com.alex.catflix.media.MediaStateBus
import com.alex.catflix.media.WebMediaController
import com.alex.catflix.settings.AppSettings
import com.alex.catflix.ui.theme.CatFlixTheme
import com.alex.catflix.web.CatFlixWebChromeClient
import com.alex.catflix.web.CatFlixWebViewClient
import com.alex.catflix.web.FullscreenHolder
import com.alex.catflix.web.NavigationPolicy
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val HOME_URL = "https://net77.cc/"


        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    val appSettings: AppSettings by lazy { AppSettings(this) }
    val webMediaController = WebMediaController()
    val volumeBooster = VolumeBooster()


    private val splashReady = java.util.concurrent.atomic.AtomicBoolean(false)

    fun markContentReady() {
        splashReady.set(true)
    }


    fun restartApp() {
        try {
            val launch = packageManager.getLaunchIntentForPackage(packageName)
                ?: run {
                    recreate()
                    return
                }
            val pending = PendingIntent.getActivity(
                this, 0, launch,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            am.set(
                android.app.AlarmManager.RTC,
                System.currentTimeMillis() + 400,
                pending
            )
            finishAffinity()
            kotlin.system.exitProcess(0)
        } catch (e: Exception) {
            Log.w(TAG, "restart failed, recreating", e)
            try {
                recreate()
            } catch (_: Exception) {
            }
        }
    }


    val challengeRetried = HashSet<String>()

    fun clearChallengeRetries() {
        synchronized(challengeRetried) { challengeRetried.clear() }
    }


    val pipMode = mutableStateOf(false)


    val videoFullscreen = mutableStateOf(false)


    var lastTouchMs: Long = 0L

    private var playbackService: MediaPlaybackService? = null
    private var lastAutoEnter: Boolean = false


    fun enterImmersive() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let {
                    it.hide(
                        android.view.WindowInsets.Type.statusBars() or
                            android.view.WindowInsets.Type.navigationBars()
                    )
                    it.systemBarsBehavior = android.view.WindowInsetsController
                        .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )
            }
        } catch (_: Exception) {
        }
    }

    fun exitImmersive() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.show(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars()
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        } catch (_: Exception) {
        }
    }


    @Suppress("DEPRECATION")
    private val trimCallback = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
                try {
                    webMediaController.webView?.clearCache(false)
                } catch (_: Exception) {
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() {
            try {
                webMediaController.webView?.clearCache(false)
            } catch (_: Exception) {
            }
        }

        override fun onConfigurationChanged(newConfig: Configuration) {}
    }

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? MediaPlaybackService.LocalBinder)?.service()
            playbackService = svc
            try {
                svc?.registerCommands(webMediaController)
            } catch (e: Exception) {
                Log.w(TAG, "registerCommands failed", e)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
        }
    }

        private val busListener: (MediaSnapshot) -> Unit = { snap ->
        runOnUiThread {
            try {
                if (pipMode.value) {
                    refreshPipParams()
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val shouldAuto =
                        appSettings.autoPip && snap.hasVideo && snap.playing
                    if (shouldAuto != lastAutoEnter) {
                        lastAutoEnter = shouldAuto
                        setPictureInPictureParams(pipParams())
                    }

                    val boost = appSettings.volumeBoost
                    try {
                        if (snap.hasVideo && snap.playing && boost > 100) {
                            volumeBooster.setBoost(boost)
                        } else {
                            volumeBooster.release()
                        }
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { !splashReady.get() }
        splashScreen.setOnExitAnimationListener { provider ->
            try {
                provider.view.animate()
                    .alpha(0f)
                    .scaleX(1.08f)
                    .scaleY(1.08f)
                    .setDuration(350L)
                    .withEndAction {
                        try {
                            provider.remove()
                        } catch (_: Exception) {
                        }
                    }
                    .start()
            } catch (_: Exception) {
                try {
                    provider.remove()
                } catch (_: Exception) {
                }
            }
        }

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { splashReady.set(true) }, 4000
        )
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        CookieManager.getInstance().setAcceptCookie(true)
        MediaStateBus.addListener(busListener)
        try {
            registerComponentCallbacks(trimCallback)
        } catch (_: Exception) {
        }
        try {
            startService(Intent(this, MediaPlaybackService::class.java))
            bindService(
                Intent(this, MediaPlaybackService::class.java),
                serviceConn,
                Context.BIND_AUTO_CREATE
            )
        } catch (e: Exception) {
            Log.w(TAG, "Media service bind failed", e)
        }
        setContent {
            CatFlixTheme {
                BrowserScreen()
            }
        }
    }


    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        intent = newIntent
    }
    override fun onDestroy() {
        try {
            volumeBooster.release()
        } catch (_: Exception) {
        }
        try {
            MediaStateBus.removeListener(busListener)
        } catch (_: Exception) {
        }
        try {
            unregisterComponentCallbacks(trimCallback)
        } catch (_: Exception) {
        }
        try {
            unbindService(serviceConn)
        } catch (_: Exception) {
        }
        if (isFinishing) {
            try {
                playbackService?.shutdown()
            } catch (_: Exception) {
            }
        }
        playbackService = null
        super.onDestroy()
    }



    override fun onUserLeaveHint() {
        super.onUserLeaveHint()




        val snap = MediaStateBus.last
        val busy = snap?.hasVideo == true && snap.playing ||
            videoFullscreen.value ||
            isAudioActive()
        if (appSettings.autoPip && busy) {
            enterPip()
        }
    }


    private fun isAudioActive(): Boolean {
        return try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.isMusicActive
        } catch (_: Exception) {
            false
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipMode.value = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            refreshPipParams()
            try {
                webMediaController.focusVideoForPip()
            } catch (_: Exception) {
            }
        } else {
            try {
                webMediaController.unfocusVideoForPip()
            } catch (_: Exception) {
            }
        }
    }

    fun enterPip(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            val params = pipParams()
            setPictureInPictureParams(params)
            val ok = enterPictureInPictureMode(params)
            Log.i(TAG, "enterPip result=$ok")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "enterPip failed", e)
            false
        }
    }

    private fun refreshPipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            setPictureInPictureParams(pipParams())
        } catch (_: Exception) {
        }
    }

    private fun pipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()


            .setAspectRatio(pipAspect())
            .setActions(
                listOf(pipToggleAction(MediaStateBus.last?.playing == true))
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val snap = MediaStateBus.last
            builder.setAutoEnterEnabled(
                appSettings.autoPip && snap?.hasVideo == true && snap.playing
            )
        }
        return builder.build()
    }

    private fun pipAspect(): Rational {
        val snap = MediaStateBus.last
        val w = snap?.videoWidth ?: 0
        val h = snap?.videoHeight ?: 0
        if (w > 0 && h > 0) {
            val ratio = (w.toFloat() / h).coerceIn(0.5f, 2.0f)
            return Rational((ratio * 1000).toInt().coerceAtLeast(1), 1000)
        }
        return Rational(16, 9)
    }

    private fun pipToggleAction(playing: Boolean): android.app.RemoteAction {
        val icon = Icon.createWithResource(
            this,
            if (playing) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
        val intent = PendingIntent.getBroadcast(
            this, 100,
            Intent(this, MediaActionReceiver::class.java)
                .setAction(MediaPlaybackService.ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return android.app.RemoteAction(
            icon,
            if (playing) "Pause" else "Play",
            if (playing) "Pause video" else "Play video",
            intent
        )
    }
}


private fun cosmeticInjectJs(css: String): String {
    val esc = css.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
    return "(function(){try{var d=document;var s=d.getElementById('catflix-cos');" +
        "if(!s){s=d.createElement('style');s.id='catflix-cos';" +
        "(d.head||d.documentElement).appendChild(s);}s.textContent='$esc';}catch(e){}})()"
}


private fun persistSession(context: android.content.Context) {
    try {
        CookieManager.getInstance().flush()
    } catch (_: Exception) {
    }
}


private fun splashFile(context: android.content.Context): java.io.File =
    java.io.File(context.filesDir, "last_page.png")


private fun captureSplash(wv: WebView) {
    try {
        val w = wv.width
        val h = wv.height
        if (w <= 0 || h <= 0) return
        val scale = (480f / w).coerceAtMost(1f)
        val bmp = android.graphics.Bitmap.createBitmap(
            (w * scale).toInt().coerceAtLeast(1),
            (h * scale).toInt().coerceAtLeast(1),
            android.graphics.Bitmap.Config.RGB_565
        )
        val canvas = android.graphics.Canvas(bmp)
        canvas.scale(scale, scale)
        wv.draw(canvas)
        java.io.FileOutputStream(splashFile(wv.context)).use { out ->
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 80, out)
        }
        try {
            bmp.recycle()
        } catch (_: Exception) {
        }
    } catch (_: Exception) {
    }
}

private class FilePickerBridge {    var callback: android.webkit.ValueCallback<Array<Uri>>? = null
    var launcher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>? = null

    fun launch(intent: android.content.Intent, cb: android.webkit.ValueCallback<Array<Uri>>) {
        callback?.onReceiveValue(null)
        callback = cb
        try {
            launcher?.launch(intent)
        } catch (e: Exception) {
            Log.w("FilePicker", "launch failed", e)
            callback?.onReceiveValue(null)
            callback = null
        }
    }

    fun deliver(resultCode: Int, data: android.content.Intent?) {
        val cb = callback ?: return
        callback = null
        try {
            if (resultCode != android.app.Activity.RESULT_OK) {
                cb.onReceiveValue(null)
                return
            }
            val uris = ArrayList<Uri>()
            if (data != null) {
                val clip = data.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                } else {
                    data.data?.let { uris.add(it) }
                }
            }
            cb.onReceiveValue(uris.toTypedArray())
        } catch (e: Exception) {
            Log.w("FilePicker", "deliver failed", e)
            try {
                cb.onReceiveValue(null)
            } catch (_: Exception) {
            }
        }
    }
}


@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .defaultMinSize(minWidth = 76.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint)
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}


@Composable
private fun SheetSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp)
    )
}


@Composable
private fun SheetSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION") 
@Composable
private fun BrowserScreen() {
    val context = LocalContext.current
    val activity = context as MainActivity
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var progress by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf(MainActivity.HOME_URL) }
    var error by remember { mutableStateOf<String?>(null) }
    var oauthNotice by remember { mutableStateOf(false) }
    var wallNotice by remember { mutableStateOf(false) }
    var restartAsk by remember { mutableStateOf(false) }
    var sawGoogleAuth by remember { mutableStateOf(false) }


    var coldStart by remember { mutableStateOf(true) }
    val splashBitmap = remember {
        try {
            val f = splashFile(context)
            if (f.exists()) {
                android.graphics.BitmapFactory.decodeFile(f.absolutePath)
                    ?.asImageBitmap()
            } else null
        } catch (_: Exception) {
            null
        }
    }
    var oauthPendingReturn by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    val isInPip by activity.pipMode

    var sheetOpen by remember { mutableStateOf(false) }
    var swipeZoomOn by remember { mutableStateOf(activity.appSettings.swipeZoom) }
    var desktopOn by remember { mutableStateOf(activity.appSettings.desktopMode) }
    var volBoost by remember { mutableStateOf(activity.appSettings.volumeBoost) }
    var defaultUa by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var adBlockOn by remember { mutableStateOf(activity.appSettings.adBlockEnabled) }
    var autoPipOn by remember { mutableStateOf(activity.appSettings.autoPip) }
    var bgPlayOn by remember { mutableStateOf(activity.appSettings.backgroundPlayback) }
    var keepAwakeOn by remember { mutableStateOf(activity.appSettings.keepAwake) }
    var darkOn by remember { mutableStateOf(activity.appSettings.darkMode) }
    var textZoom by remember { mutableStateOf(activity.appSettings.textZoom) }


    var hasVideo by remember { mutableStateOf(MediaStateBus.last?.hasVideo == true) }
    var playing by remember { mutableStateOf(MediaStateBus.last?.playing == true) }
    var redirectAsk by remember { mutableStateOf<String?>(null) }



    DisposableEffect(Unit) {
        val listener: (MediaSnapshot) -> Unit = { snap ->
            hasVideo = snap.hasVideo
            playing = snap.playing
        }
        MediaStateBus.addListener(listener)
        onDispose { MediaStateBus.removeListener(listener) }
    }

    // Keep the screen alive while video plays (window-held wake lock:
    // no permission needed, released automatically with the window).
    DisposableEffect(playing, keepAwakeOn) {
        val win = try {
            (context as? MainActivity)?.window
        } catch (_: Exception) {
            null
        }
        try {
            if (playing || keepAwakeOn) {
                win?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                win?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        } catch (_: Exception) {
        }
        onDispose {
            try {
                win?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } catch (_: Exception) {
            }
        }
    }

    val webRef = remember { arrayOfNulls<WebView>(1) }
    val holderRef = remember { arrayOfNulls<FullscreenHolder>(1) }
    val bridge = remember { FilePickerBridge() }
    val adBlock = remember {
        AdBlockEngine(context.filesDir).also {
            it.enabled = activity.appSettings.adBlockEnabled
            it.refreshAsync()
        }
    }
    val startUrl = remember {
        activity.appSettings.lastUrl
            .takeIf { it.isNotBlank() && GoogleAuthHandler.isSiteUrl(it) }
            ?: MainActivity.HOME_URL
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        bridge.deliver(result.resultCode, result.data)
    }
    bridge.launcher = pickerLauncher

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    fun syncNavState(wv: WebView) {
        canGoBack = wv.canGoBack()
        canGoForward = wv.canGoForward()
        wv.url?.let { currentUrl = it }
    }

    fun showSnack(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }

    fun goBack() {
        webRef[0]?.let {
            if (it.canGoBack()) {
                it.goBack()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    { syncNavState(it) }, 300
                )
            }
        }
    }

    fun goForward() {
        webRef[0]?.let {
            if (it.canGoForward()) {
                it.goForward()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    { syncNavState(it) }, 300
                )
            }
        }
    }


    fun handleExternal(url: String) {
        val wv = webRef[0] ?: return
        val recent = SystemClock.elapsedRealtime() - activity.lastTouchMs < 3000
        if (recent) {
            try {
                wv.loadUrl(url)
            } catch (_: Exception) {
            }
        } else {
            redirectAsk = url
        }
    }



    DisposableEffect(Unit) {
        var asked = false
        val listener: (MediaSnapshot) -> Unit = { snap ->
            if (!asked && snap.hasVideo &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                asked = true
                try {
                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (_: Exception) {
                }
            }
        }
        MediaStateBus.addListener(listener)
        onDispose { MediaStateBus.removeListener(listener) }
    }



    var lastBackPress by remember { mutableLongStateOf(0L) }
    BackHandler(enabled = true) {
        val holder = holderRef[0]
        if (holder?.isFullscreen == true) {
            holder.hide()
            return@BackHandler
        }
        val wv = webRef[0]
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { syncNavState(wv) }, 300
            )
            return@BackHandler
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackPress < 2000) {
            (context as? MainActivity)?.finish()
        } else {
            lastBackPress = now
            showSnack("Press back again to exit")
        }
    }

    Scaffold(




        snackbarHost = { SnackbarHost(snackbar) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (loading && progress in 1..99 && !isInPip && !isFullscreen) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                )
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val root = FrameLayout(ctx)
                    val wv = WebView(ctx)
                    webRef[0] = wv
                    holderRef[0] = FullscreenHolder(root)
                    activity.webMediaController.webView = wv

                    val s: WebSettings = wv.settings

                    s.javaScriptEnabled = true
                    s.domStorageEnabled = true
                    s.databaseEnabled = true
                    s.loadsImagesAutomatically = true
                    s.mediaPlaybackRequiresUserGesture = false
                    s.cacheMode = WebSettings.LOAD_DEFAULT
                    s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    s.safeBrowsingEnabled = true

                    s.allowFileAccess = false
                    s.allowContentAccess = true
                    s.allowFileAccessFromFileURLs = false
                    s.allowUniversalAccessFromFileURLs = false
                    s.setSupportMultipleWindows(true)
                    s.javaScriptCanOpenWindowsAutomatically = true

                    s.setSupportZoom(activity.appSettings.swipeZoom)
                    s.builtInZoomControls = activity.appSettings.swipeZoom
                    s.displayZoomControls = false
                    if (defaultUa == null) defaultUa = s.userAgentString
                    if (activity.appSettings.desktopMode) {
                        s.userAgentString = MainActivity.DESKTOP_UA
                    }
                    s.offscreenPreRaster = true
                    s.textZoom = activity.appSettings.textZoom



                    s.useWideViewPort = true
                    s.loadWithOverviewMode = true
                    try {
                        s.layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                    } catch (_: Exception) {
                    }
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                        WebSettingsCompat.setAlgorithmicDarkeningAllowed(
                            s, activity.appSettings.darkMode
                        )
                    }
                    wv.isFocusableInTouchMode = true
                    wv.isFocusable = true
                    wv.isSaveEnabled = true

                    wv.setBackgroundColor(0xFF121212.toInt())




                    CookieManager.getInstance()
                        .setAcceptThirdPartyCookies(wv, true)

                    val clientEvents = object : CatFlixWebViewClient.WebEvents {
                        override fun onPageStartedLocal(url: String?) {
                            loading = true
                            wallNotice = false
                            url?.let {
                                currentUrl = it
                                if (GoogleAuthHandler.isGoogleOAuthUrl(it)) {
                                    sawGoogleAuth = true
                                }
                            }


                            MediaStateBus.post(MediaSnapshot.noVideo())
                        }

                        override fun onPageFinishedLocal(url: String?) {
                            loading = false
                            progress = 100
                            if (coldStart) coldStart = false
                            activity.markContentReady()


                            if (sawGoogleAuth && url != null &&
                                GoogleAuthHandler.isSiteUrl(url)
                            ) {
                                sawGoogleAuth = false
                                restartAsk = true
                            }
                            url?.let {
                                currentUrl = it

                                if (GoogleAuthHandler.isSiteUrl(it)) {
                                    activity.appSettings.lastUrl = it
                                }
                            }
                            syncNavState(wv)


                            try {
                                val target = wv
                                android.os.Handler(
                                    android.os.Looper.getMainLooper()
                                ).postDelayed({
                                    try {
                                        captureSplash(target)
                                    } catch (_: Exception) {
                                    }
                                }, 800)
                            } catch (_: Exception) {
                            }

                            if (activity.appSettings.swipeZoom) {
                                try {
                                    activity.webMediaController.fixViewport()
                                } catch (_: Exception) {
                                }
                            }

                            if (url != null && url == wv.url) {
                                try {
                                    val css = adBlock.cosmeticCssFor(
                                        GoogleAuthHandler.hostOf(url)
                                    )
                                    if (css.isNotEmpty()) {
                                        wv.evaluateJavascript(cosmeticInjectJs(css), null)
                                    }
                                } catch (_: Exception) {
                                }
                            }
                        }

                        override fun onMainFrameError(url: String?, description: String) {
                            loading = false
                            error = description
                        }

                        override fun onGoogleOAuthBlocked(url: String) {
                            oauthNotice = true
                            oauthPendingReturn = true
                            showSnack("Google refused embedded sign-in — see options below.")
                        }

                        override fun onAdBlockWall() {
                            wallNotice = true
                        }

                        override fun onHistoryChanged() {
                            webRef[0]?.let { syncNavState(it) }
                        }


                        override fun onChallengePage(url: String) {
                            val host = GoogleAuthHandler.hostOf(url) ?: return
                            if (!activity.appSettings.desktopMode) return
                            val firstTry = synchronized(activity.challengeRetried) {
                                activity.challengeRetried.add(host)
                            }
                            if (!firstTry) return
                            try {
                                webRef[0]?.settings?.userAgentString = defaultUa
                                webRef[0]?.reload()
                                showSnack("Retrying verification in mobile view…")
                            } catch (_: Exception) {
                            }
                        }

                        override fun onExternalRedirect(url: String) {
                            handleExternal(url)
                        }

                        override fun clearMainFrameError() {
                            error = null
                        }
                    }
                    wv.webViewClient = CatFlixWebViewClient(adBlock, clientEvents)

                    fun reportProgress(value: Int) {
                        progress = value
                        loading = value in 1..99
                        if (value == 100) syncNavState(wv)
                    }

                    val chromeEvents = object : CatFlixWebChromeClient.ChromeEvents {
                        override fun onProgressLocal(progress: Int) {
                            reportProgress(progress)
                        }

                        override fun registerFileChooserLauncher(
                            intent: android.content.Intent,
                            callback: android.webkit.ValueCallback<Array<Uri>>
                        ) {
                            bridge.launch(intent, callback)
                        }

                        override fun onPopupUrl(url: String) {




                            when (NavigationPolicy.decide(url, adBlock)) {
                                NavigationPolicy.Decision.Allow -> wv.loadUrl(url)
                                NavigationPolicy.Decision.CancelBlocked -> {
                                    Log.i("Browser", "Dropped ad popup")
                                }
                                NavigationPolicy.Decision.ExternalPage ->
                                    handleExternal(url)
                                NavigationPolicy.Decision.OpenOutside ->
                                    GoogleAuthHandler.openInSystemBrowser(ctx, url)
                            }
                        }

                        override fun showCustomViewLocal(
                            view: View,
                            callback: WebChromeClient.CustomViewCallback
                        ) {
                            holderRef[0]?.show(view, callback)
                            isFullscreen = true
                            activity.videoFullscreen.value = true
                            activity.enterImmersive()
                        }

                        override fun hideCustomViewLocal() {
                            holderRef[0]?.hide()
                            isFullscreen = false
                            activity.videoFullscreen.value = false
                            activity.exitImmersive()
                        }
                    }
                    val chrome = CatFlixWebChromeClient(
                        ctx as android.app.Activity, chromeEvents
                    )
                    wv.webChromeClient = chrome

                    WebView.setWebContentsDebuggingEnabled(false)


                    wv.layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    root.addView(wv)



                    wv.setOnTouchListener { v, ev ->
                        if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
                            try {
                                v.requestFocus(View.FOCUS_DOWN)
                            } catch (_: Exception) {
                            }
                            try {
                                (ctx as? MainActivity)?.lastTouchMs =
                                    SystemClock.elapsedRealtime()
                            } catch (_: Exception) {
                            }
                        }
                        false
                    }
                    wv.loadUrl(startUrl)
                    root
                },
                update = {},
                onRelease = {

                    try {
                        CookieManager.getInstance().flush()
                    } catch (_: Exception) {
                    }
                    activity.webMediaController.webView = null
                    try {
                        webRef[0]?.destroy()
                    } catch (_: Exception) {
                    }
                    webRef[0] = null
                }
            )




            // Always reachable, even mid-playback; drag it clear of controls.
            if (!isInPip && !isFullscreen) {
                androidx.compose.foundation.layout.BoxWithConstraints(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val fabPad = 56.dp
                    val maxWPx = with(density) {
                        (maxWidth - fabPad).toPx().coerceAtLeast(1f)
                    }
                    val maxHPx = with(density) {
                        (maxHeight - fabPad).toPx().coerceAtLeast(1f)
                    }
                    var fabFx by remember {
                        mutableStateOf(activity.appSettings.fabX)
                    }
                    var fabFy by remember {
                        mutableStateOf(activity.appSettings.fabY)
                    }
                    SmallFloatingActionButton(
                        onClick = { sheetOpen = true },
                        modifier = Modifier
                            .offset {
                                androidx.compose.ui.unit.IntOffset(
                                    (maxWPx * fabFx).roundToInt(),
                                    (maxHPx * fabFy).roundToInt()
                                )
                            }
                            .pointerInput(maxWPx, maxHPx) {
                                detectDragGestures(
                                    onDragEnd = {
                                        activity.appSettings.fabX = fabFx
                                        activity.appSettings.fabY = fabFy
                                    }
                                ) { change, drag ->
                                    change.consume()
                                    fabFx = ((maxWPx * fabFx + drag.x) / maxWPx)
                                        .coerceIn(0f, 1f)
                                    fabFy = ((maxHPx * fabFy + drag.y) / maxHPx)
                                        .coerceIn(0f, 1f)
                                }
                            },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = "App controls")
                    }
                }
            }




            if (coldStart && splashBitmap != null && !isInPip && !isFullscreen && error == null) {
                Image(
                    bitmap = splashBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }


            if (loading && !isInPip && !isFullscreen && error == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            if (!isInPip && !isFullscreen) {

                error?.let { msg ->
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Page couldn't load", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            msg.take(220),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(modifier = Modifier.padding(top = 16.dp)) {
                            Button(onClick = {
                                error = null
                                webRef[0]?.reload()
                            }) { Text("Retry") }
                            Spacer(Modifier.width(12.dp))
                            OutlinedButton(onClick = {
                                error = null
                                webRef[0]?.loadUrl(MainActivity.HOME_URL)
                            }) { Text("Home") }
                        }
                    }
                }


                if (wallNotice && adBlockOn) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                        ) {
                            Column(Modifier.padding(20.dp)) {
                                Text(
                                    "Ad blocking detected",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "This site paused playback until the blocker is off.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(16.dp))
                                Button(
                                    onClick = {
                                        wallNotice = false
                                        adBlockOn = false
                                        activity.appSettings.adBlockEnabled = false
                                        adBlock.enabled = false
                                        webRef[0]?.reload()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Turn off & reload") }
                                TextButton(
                                    onClick = { wallNotice = false },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Keep blocking") }
                            }
                        }
                    }
                }





                if (oauthNotice) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                        ) {
                            Column(Modifier.padding(20.dp)) {
                                Text(
                                    "Google sign-in blocked",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Google refused embedded sign-in (Error 403). " +
                                        "Finish in the system browser, then return and reload.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(16.dp))
                                Button(
                                    onClick = {
                                        GoogleAuthHandler.openInSystemBrowser(
                                            context, currentUrl
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Open browser") }
                                TextButton(
                                    onClick = {
                                        oauthNotice = false
                                        webRef[0]?.reload()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Back in app — reload") }
                                TextButton(
                                    onClick = { oauthNotice = false },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Dismiss") }
                            }
                        }
                    }
                }
            }



            if (restartAsk) {
                AlertDialog(
                    onDismissRequest = { restartAsk = false },
                    title = { Text("You're signed in") },
                    text = {
                        Text("Restart the app to load your account cleanly.")
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            restartAsk = false
                            activity.restartApp()
                        }) { Text("Restart now") }
                    },
                    dismissButton = {
                        TextButton(onClick = { restartAsk = false }) {
                            Text("Later")
                        }
                    }
                )
            }


            redirectAsk?.let { pending ->
                AlertDialog(
                    onDismissRequest = { redirectAsk = null },
                    title = { Text("Open link?") },
                    text = {
                        Text(
                            "This page tried to open:\n" +
                                (GoogleAuthHandler.hostOf(pending) ?: pending)
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            redirectAsk = null
                            try {
                                webRef[0]?.loadUrl(pending)
                            } catch (_: Exception) {
                            }
                        }) { Text("Open") }
                    },
                    dismissButton = {
                        TextButton(onClick = { redirectAsk = null }) {
                            Text("Block")
                        }
                    }
                )
            }




            if (sheetOpen) {
                ModalBottomSheet(
                    onDismissRequest = { sheetOpen = false },
                    sheetState = sheetState
                ) {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SheetAction(
                                    Icons.AutoMirrored.Filled.ArrowBack, "Back", canGoBack
                                ) {
                                    sheetOpen = false
                                    goBack()
                                }
                                SheetAction(
                                    Icons.AutoMirrored.Filled.ArrowForward, "Forward", canGoForward
                                ) {
                                    sheetOpen = false
                                    goForward()
                                }
                                SheetAction(Icons.Filled.Refresh, "Reload") {
                                    sheetOpen = false
                                    error = null
                                    webRef[0]?.reload()
                                }
                                SheetAction(Icons.Filled.Home, "Home") {
                                    sheetOpen = false
                                    error = null
                                    webRef[0]?.loadUrl(MainActivity.HOME_URL)
                                }
                            }
                        }

                        if (hasVideo || playing) {
                            item {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            sheetOpen = false
                                            if (!activity.enterPip()) {
                                                showSnack("Couldn't pop out right now")
                                            }
                                        }
                                        .padding(horizontal = 20.dp, vertical = 12.dp)
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ExitToApp,
                                        contentDescription = null
                                    )
                                    Spacer(Modifier.width(16.dp))
                                    Text("Pop-out player")
                                }
                            }
                        }
                        item {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                        item {
                            Column {
                                SheetSwitchRow("Block ads & trackers", adBlockOn) {
                                    adBlockOn = it
                                    activity.appSettings.adBlockEnabled = it
                                    adBlock.enabled = it
                                    webRef[0]?.reload()
                                }
                                SheetSwitchRow("Auto Picture-in-Picture", autoPipOn) {
                                    autoPipOn = it
                                    activity.appSettings.autoPip = it
                                }
                                SheetSwitchRow("Background playback", bgPlayOn) {
                                    bgPlayOn = it
                                    activity.appSettings.backgroundPlayback = it
                                }
                                SheetSwitchRow("Keep screen awake", keepAwakeOn) {
                                    keepAwakeOn = it
                                    activity.appSettings.keepAwake = it
                                }
                                SheetSwitchRow("Dark mode", darkOn) {
                                    darkOn = it
                                    activity.appSettings.darkMode = it
                                    webRef[0]?.let { wv ->
                                        if (WebViewFeature.isFeatureSupported(
                                                WebViewFeature.ALGORITHMIC_DARKENING
                                            )
                                        ) {
                                            WebSettingsCompat.setAlgorithmicDarkeningAllowed(
                                                wv.settings, it
                                            )
                                        }
                                        wv.reload()
                                    }
                                }
                                SheetSwitchRow("Swipe zoom", swipeZoomOn) {
                                    swipeZoomOn = it
                                    activity.appSettings.swipeZoom = it
                                    try {
                                        webRef[0]?.settings?.let { ws ->
                                            ws.setSupportZoom(it)
                                            ws.builtInZoomControls = it
                                        }
                                        if (it) {
                                            activity.webMediaController.fixViewport()
                                        }
                                    } catch (_: Exception) {
                                    }
                                }
                                SheetSwitchRow("Desktop mode", desktopOn) {
                                    desktopOn = it
                                    activity.appSettings.desktopMode = it
                                    activity.clearChallengeRetries()
                                    try {
                                        webRef[0]?.settings?.userAgentString =
                                            if (it) MainActivity.DESKTOP_UA
                                            else defaultUa
                                        webRef[0]?.reload()
                                    } catch (_: Exception) {
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        "Boost $volBoost%",
                                        modifier = Modifier.width(92.dp),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Slider(
                                        value = volBoost.toFloat(),
                                        onValueChange = { v ->
                                            val next = ((v / 100f).roundToInt() * 100)
                                                .coerceIn(100, 5000)
                                            volBoost = next
                                            activity.appSettings.volumeBoost = volBoost
                                            try {
                                                activity.volumeBooster.setBoost(volBoost)
                                            } catch (_: Exception) {
                                            }
                                        },
                                        valueRange = 100f..5000f,
                                        steps = 48,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                        item {
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        "Text $textZoom%",
                                        modifier = Modifier.width(92.dp),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Slider(
                                        value = textZoom.toFloat(),
                                        onValueChange = { v ->
                                            val next = ((v / 25f).roundToInt() * 25)
                                                .coerceIn(50, 200)
                                            textZoom = next
                                            activity.appSettings.textZoom = next
                                            try {
                                                webRef[0]?.settings?.textZoom = next
                                            } catch (_: Exception) {
                                            }
                                        },
                                        valueRange = 50f..200f,
                                        steps = 5,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                        item { Spacer(Modifier.height(20.dp)) }
                    }
                }
            }
        }
    }



    DisposableEffect(Unit) {
        val listener = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    persistSession(context)


                    if (!loading) {
                        try {
                            webRef[0]?.let { captureSplash(it) }
                        } catch (_: Exception) {
                        }
                    }
                    val act = context as MainActivity
                    if (!act.appSettings.backgroundPlayback && !act.pipMode.value) {
                        try {
                            webRef[0]?.onPause()
                            webRef[0]?.pauseTimers()
                            webRef[0]?.settings?.offscreenPreRaster = false
                        } catch (_: Exception) {
                        }
                    }
                }
                Lifecycle.Event.ON_STOP -> {


                    persistSession(context)
                }
                Lifecycle.Event.ON_RESUME -> {
                    try {
                        webRef[0]?.onResume()
                        webRef[0]?.resumeTimers()
                        webRef[0]?.settings?.offscreenPreRaster = true
                    } catch (_: Exception) {
                    }
                    if (oauthPendingReturn) {
                        oauthPendingReturn = false
                        webRef[0]?.reload()
                    }
                }
                else -> {}
            }
        }
        val lifecycle = (context as? androidx.lifecycle.LifecycleOwner)?.lifecycle
        lifecycle?.addObserver(listener)
        onDispose { lifecycle?.removeObserver(listener) }
    }
}
