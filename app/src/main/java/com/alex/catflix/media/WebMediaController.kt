package com.alex.catflix.media

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import org.json.JSONObject


class WebMediaController : WebMediaCommands {

    companion object {
        private const val TAG = "WebMediaController"


        private const val PICK = "var __cfVs=(function(){var c=[];" +
            "function a(r){try{var q=r.querySelectorAll('video');" +
            "for(var k=0;k<q.length;k++)c.push(q[k]);}catch(e){}}" +
            "a(document);" +
            "try{var frs=document.querySelectorAll('iframe');" +
            "for(var f=0;f<frs.length;f++){" +
            "try{var d=frs[f].contentDocument;if(d)a(d);}catch(e){}}}catch(e){}" +
            "return c;})();" +
            "function __cfElig(e){try{" +
            "var w=e.videoWidth||0,h=e.videoHeight||0;" +
            "if(w>0&&w<140&&h>0&&h<140)return false;" +
            "return true;}catch(x){return true;}}" +
            "function __cfLive(e){try{" +
            "return !e.paused&&!e.ended&&e.readyState>0;}catch(x){return false;}}" +
            "var v=null;" +
            "for(var i=0;i<__cfVs.length;i++){" +
            "var e=__cfVs[i];if(!__cfLive(e))continue;" +
            "if(__cfElig(e)){v=e;break;}if(!v)v=e;}" +
            "if(!v){for(var j=0;j<__cfVs.length;j++){" +
            "var e2=__cfVs[j];try{if(e2.readyState>0){" +
            "if(__cfElig(e2)){v=e2;break;}if(!v)v=e2;}}catch(x){}}}" +
            "if(!v&&__cfVs.length>0){try{v=__cfVs[0];}catch(e){}}"

        private const val SNAPSHOT_JS = "(function(){try{" + PICK +
            "if(!v)return null;" +
            "var dur=(isFinite(v.duration)&&v.duration>0)?v.duration:0;" +
            "var mst='';try{if(navigator.mediaSession){mst=navigator.mediaSession.playbackState||'';}}catch(e){}" +
            "var mtitle='';try{var md=navigator.mediaSession?navigator.mediaSession.metadata:null;" +
            "if(md&&md.title)mtitle=md.title;}catch(e){}" +
            "return JSON.stringify({" +
            "playing:((!v.paused&&!v.ended)||mst==='playing')," +
            "ended:(!!v.ended)," +
            "currentTime:(v.currentTime||0)," +
            "duration:dur," +
            "title:((mtitle||document.title||'').substring(0,200))," +
            "poster:(v.poster||'')," +
            "src:((v.currentSrc||v.src||'').substring(0,512))," +
            "w:(v.videoWidth||0)," +
            "h:(v.videoHeight||0)})}catch(e){return null;}})()"

        private const val PLAY_JS = "(function(){" + PICK + "if(v){v.play();}})()"
        private const val PAUSE_JS = "(function(){" + PICK + "if(v){v.pause();}})()"

        private const val PIP_FOCUS_JS = "(function(){try{" + PICK +
            "if(!v)return 'none';" +
            "try{v.scrollIntoView({block:'center',inline:'center'});}catch(e){try{v.scrollIntoView();}catch(e2){}}" +
            "var els=document.querySelectorAll('header,nav,footer');" +
            "for(var i=0;i<els.length;i++){var el=els[i];" +
            "try{if(!el.dataset.cfFocus){el.dataset.cfFocus='1';" +
            "el.dataset.cfDisp=el.style.display||'';el.style.display='none';}}catch(e){}}" +
            "return 'ok';}catch(e){return 'err';}})()"

        private const val PIP_UNFOCUS_JS = "(function(){try{" +
            "var els=document.querySelectorAll('[data-cf-focus]');" +
            "for(var i=0;i<els.length;i++){var el=els[i];" +
            "try{el.style.display=el.dataset.cfDisp||'';" +
            "delete el.dataset.cfFocus;delete el.dataset.cfDisp;}catch(e){}}" +
            "return 'ok';}catch(e){return 'err';}})()"

        private const val VIEWPORT_JS = "(function(){" +
            "try{" +
            "if(window.__cfViewport&&window.__cfViewport.armed)return 'armed';" +
            "var CSS='width=device-width,initial-scale=1.0,maximum-scale=10.0,user-scalable=yes';" +
            "function fix(){" +
            "try{" +
            "var ms=document.querySelectorAll('meta[name=\"viewport\"]');" +
            "if(!ms.length){" +
            "var h=document.createElement('meta');h.setAttribute('name','viewport');" +
            "(document.head||document.documentElement).appendChild(h);ms=[h];}" +
            "for(var i=0;i<ms.length;i++){" +
            "if(ms[i].getAttribute('content')!==CSS)ms[i].setAttribute('content',CSS);}" +
            "var st=document.getElementById('catflix-touch');" +
            "if(!st){st=document.createElement('style');st.id='catflix-touch';" +
            "(document.head||document.documentElement).appendChild(st);" +
            "st.textContent='html,body{touch-action:pan-x pan-y pinch-zoom !important;}';}" +
            "}catch(e){}}" +
            "fix();" +
            "try{" +
            "var mo=new MutationObserver(function(){fix();});" +
            "mo.observe(document.head||document.documentElement," +
            "{childList:true,attributes:true,attributeFilter:['content'],subtree:true});" +
            "window.__cfViewport={armed:true};" +
            "}catch(e){}" +
            "return 'ok';}catch(e){return 'err';}})()"

        private const val NEXT_JS = "(function(){" + PICK + "if(!v)return 'none';" +
            "var sels=['[data-uia=\"next-episode\"]','[data-uia*=\"next\"]'," +
            "'[aria-label=\"Next\"]','[aria-label=\"Next Episode\"]'," +
            "'.jw-display-icon-next','.jw-icon-next','button[class*=\"next\"]'];" +
            "for(var i=0;i<sels.length;i++){" +
            "var b=document.querySelector(sels[i]);" +
            "if(b&&b.offsetParent!==null){b.click();return 'clicked';}}" +
            "v.currentTime=Math.min(v.duration||1e9,v.currentTime+30);" +
            "return 'seeked';})()"

        private const val PREV_JS = "(function(){" + PICK + "if(!v)return 'none';" +
            "if(v.currentTime>5){v.currentTime=0;return 'restarted';}" +
            "var sels=['[aria-label=\"Previous\"]','[aria-label=\"Previous Episode\"]'," +
            "'.jw-display-icon-prev','button[class*=\"prev\"]'];" +
            "for(var i=0;i<sels.length;i++){" +
            "var b=document.querySelector(sels[i]);" +
            "if(b&&b.offsetParent!==null){b.click();return 'clicked';}}" +
            "v.currentTime=Math.max(0,v.currentTime-10);" +
            "return 'seeked';})()"

    }

    @Volatile
    var webView: WebView? = null


    @Volatile
    var domVideoPresent: Boolean = false

    private val main = Handler(Looper.getMainLooper())

    private fun runOnUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    private fun exec(js: String) {
        val wv = webView ?: return
        runOnUi {
            try {
                wv.evaluateJavascript(js, null)
            } catch (e: Exception) {
                Log.w(TAG, "JS exec failed", e)
            }
        }
    }

    override fun snapshot(callback: (MediaSnapshot?) -> Unit) {
        val wv = webView
        if (wv == null) {
            callback(null) 
            return
        }
        runOnUi {
            try {
                wv.evaluateJavascript(SNAPSHOT_JS) { raw ->
                    val parsed = parseSnapshot(raw)
                    domVideoPresent = parsed.hasVideo
                    callback(parsed)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Snapshot failed", e)
                callback(null)
            }
        }
    }

    override fun play() {



        exec(PLAY_JS)
        runOnUi {
            try {
                webView?.onResume()
            } catch (_: Exception) {
            }
        }
    }

    override fun pause() {
        exec(PAUSE_JS)
        runOnUi {
            try {
                webView?.onPause()
            } catch (_: Exception) {
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        val sec = positionMs.coerceAtLeast(0L) / 1000.0
        exec("(function(){" + PICK + "if(v){v.currentTime=" + sec + ";}})()")
    }

    override fun seekBy(deltaMs: Long) {
        val sec = deltaMs / 1000.0
        exec("(function(){" + PICK + "if(v){v.currentTime=Math.max(0,v.currentTime+(" + sec + "));}})()")
    }

    override fun next() = exec(NEXT_JS)

    override fun previous() = exec(PREV_JS)


    fun focusVideoForPip() = exec(PIP_FOCUS_JS)

    fun unfocusVideoForPip() = exec(PIP_UNFOCUS_JS)


    fun fixViewport() {
        exec(VIEWPORT_JS)
    }

    private fun parseSnapshot(raw: String?): MediaSnapshot {
        if (raw.isNullOrBlank() || raw.trim() == "null") return MediaSnapshot.noVideo()
        return try {


            var text = raw.trim()
            if (text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
                text = try {
                    org.json.JSONTokener(text).nextValue().toString()
                } catch (_: Exception) {
                    text
                }
            }
            if (text == "null") return MediaSnapshot.noVideo()
            val o = JSONObject(text)
            val durationSec = o.optDouble("duration", 0.0)
            MediaSnapshot(
                hasVideo = true,
                playing = o.optBoolean("playing", false) && !o.optBoolean("ended", false),
                positionMs = (o.optDouble("currentTime", 0.0) * 1000).toLong()
                    .coerceAtLeast(0L),
                durationMs = (durationSec * 1000).toLong().coerceAtLeast(0L),
                title = o.optString("title", "").take(200),
                poster = o.optString("poster", "").take(512),
                src = o.optString("src", "").take(512),
                videoWidth = o.optInt("w", 0),
                videoHeight = o.optInt("h", 0)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Snapshot parse failed: ${raw.take(120)}", e)
            MediaSnapshot.noVideo()
        }
    }
}
