package com.alex.catflix.web

import android.util.Log
import android.webkit.WebView


object PopupKiller {

    private const val TAG = "PopupKiller"

    private val SCRIPT = """
        (function(){
          if(window.__cfPopupKiller)return;window.__cfPopupKiller=true;
          var PATTS=['adblock detected','adblocker','ad blocker','disable adblock',
            'please disable','dns blocking','blocking detected','turn off adblock',
            'whitelist us','refresh page','invalid domain for site key',
            'error for site owner'];
          var AD_MARKERS=['ad','ads','banner','sponsor','taboola','outbrain',
            'promo','popunder','advert','chumbox'];
          var last=0;
          function norm(s){return (s||'').toLowerCase().replace(/\s+/g,' ');}
          function hits(t){var n=0;for(var i=0;i<PATTS.length;i++){if(t.indexOf(PATTS[i])>=0)n++;}return n;}
          function cls(el){
            try{
              var c=el.className;
              if(typeof c!=='string')c=(el.getAttribute&&el.getAttribute('class'))||'';
              return (((c||'')+' '+(el.id||'')).toLowerCase());
            }catch(e){return '';}
          }
          function hasAdMarker(el){
            var s=' '+cls(el)+' ';
            for(var i=0;i<AD_MARKERS.length;i++){if(s.indexOf(AD_MARKERS[i])>=0)return true;}
            return false;
          }
          function hasIframe(el){try{return !!el.querySelector('iframe');}catch(e){return false;}}
          function hasVideo(el){try{return !!el.querySelector('video');}catch(e){return false;}}
          function hasAudio(el){try{return !!el.querySelector('audio');}catch(e){return false;}}
          // Player guard: never hide a subtree that carries media itself.
          function hasMedia(el){
            if(!el||!el.querySelector)return false;
            return hasVideo(el)||hasAudio(el);
          }
          function isOverlay(el){
            try{
              var s=getComputedStyle(el);
              if(s.position==='fixed'||s.position==='sticky')return true;
              var r=el.getBoundingClientRect();
              var vw=window.innerWidth||1, vh=window.innerHeight||1;
              var z=parseInt(s.zIndex||'0',10);
              if(!isNaN(z)&&z>=999)return true;
              if(r.width>vw*0.5&&r.height>vh*0.3&&r.width<vw*0.99&&r.height<vh*0.99)return true;
            }catch(e){}
            return false;
          }
          // Bottom-anchored ad banners (like chumbox strips): fixed near the
          // viewport bottom, modest height, carrying an iframe or ad markers.
          // Sticky mini-players docked the same way are explicitly spared.
          function isBottomBanner(el){
            try{
              var tag=(el.tagName||'').toLowerCase();
              if(tag==='video'||tag==='canvas'||tag==='body'||tag==='html')return false;
              if(looksLikePlayer(el))return false;
              var r=el.getBoundingClientRect();
              var vw=window.innerWidth||1, vh=window.innerHeight||1;
              if(r.height<=0||r.height>vh*0.45||r.width<vw*0.35)return false;
              if(r.bottom<vh-4)return false;
              var s=getComputedStyle(el);
              if(s.position!=='fixed'&&s.position!=='sticky')return false;
              return hasIframe(el)||hasAdMarker(el);
            }catch(e){return false;}
          }
          var PLAYER_WORDS=['player','video','jw','plyr','vjs','media','watch',
            'embed','stream','episode','pip','mini','dock'];
          // Player-like containers (classes/ids, media iframes): hands off,
          // so sticky mini-players survive scrolling.
          function looksLikePlayer(el){
            try{
              if(hasVideo(el)||hasAudio(el))return true;
              var s=' '+cls(el)+' ';
              for(var i=0;i<PLAYER_WORDS.length;i++){
                if(s.indexOf(PLAYER_WORDS[i])>=0)return true;
              }
              var frs=el.querySelectorAll('iframe');
              for(var f=0;f<frs.length;f++){
                var src=((frs[f].getAttribute('src')||'')).toLowerCase();
                if(src.indexOf('.m3u8')>=0||src.indexOf('.mpd')>=0||
                   src.indexOf('.mp4')>=0||src.indexOf('embed')>=0||
                   src.indexOf('player')>=0||src.indexOf('watch')>=0)return true;
              }
            }catch(e){}
            return false;
          }
          // Invisible click-capture layers (transparent, covering, on top).
          // Never touch subtrees containing a <video> element.
          function isInvisibleOverlay(el){
            try{
              var tag=(el.tagName||'').toLowerCase();
              if(tag==='video'||tag==='canvas'||tag==='body'||tag==='html')return false;
              if(hasVideo(el))return false;
              var r=el.getBoundingClientRect();
              var vw=window.innerWidth||1, vh=window.innerHeight||1;
              if(r.width<vw*0.7||r.height<vh*0.7)return false;
              var s=getComputedStyle(el);
              var op=parseFloat(s.opacity||'1');
              var bg=(s.backgroundColor||'');
              var transparent=isNaN(op)||op<0.15||bg==='transparent'||bg.indexOf('rgba(0, 0, 0, 0)')===0;
              if(!transparent)return false;
              var z=parseInt(s.zIndex||'0',10);
              if(isNaN(z)||z<10)return false;
              if(s.pointerEvents==='none')return false;
              var t=norm(el.innerText);
              if(t.length>500)return false;
              return true;
            }catch(e){return false;}
          }
          // Finds the X / close control inside an ad container, preferring
          // top-right, short-text and close-named candidates.
          function findCloser(root){
            var best=null,bestScore=-1;
            try{
              var r=root.getBoundingClientRect();
              var cands=root.querySelectorAll('button,a,span,div,svg,[role="button"]');
              for(var i=0;i<cands.length;i++){
                var c=cands[i];
                var label=norm(c.innerText||c.getAttribute('aria-label')||'');
                var mark=cls(c);
                var score=-1;
                if(label==='×'||label==='✕'||label==='✖'||label==='x'||label==='close')score=100;
                else if(label==='dismiss'||label==='no thanks'||label==='no')score=80;
                else if(label.length<=6&&(mark.indexOf('close')>=0||mark.indexOf('dismiss')>=0))score=70;
                else continue;
                try{
                  var cr=c.getBoundingClientRect();
                  if(cr.width>0&&r.width>0){
                    var rightness=(cr.left+cr.width/2-(r.left))/r.width;
                    if(rightness>0.6)score+=10;
                    if(cr.top<r.top+r.height*0.35)score+=10;
                  }
                }catch(e){}
                if(score>bestScore){bestScore=score;best=c;}
              }
            }catch(e){}
            return best;
          }
          function kill(el){
            var done=false;
            try{
              var closer=findCloser(el);
              if(closer){closer.click();done=true;}
            }catch(e){}
            // Never hide media-carrying subtrees (player roots); the close
            // click above already ran, audio keeps playing regardless.
            if(hasMedia(el))return done;
            try{el.style.setProperty('display','none','important');done=true;}catch(e){}
            return done;
          }
          // Lone X/close controls painted over the video frame itself.
          // Scoped strictly inside video rects so page-level closes survive.
          function sweepLoneX(){
            var n=0;
            try{
              var vrs=videoRects();
              if(!vrs.length)return 0;
              var els=document.querySelectorAll('button,a,span,div');
              for(var i=0;i<els.length&&n<3;i++){
                var el=els[i];
                if(el.dataset&&(el.dataset.cfKilled||el.dataset.cfKeep))continue;
                var t=norm(el.innerText);
                if(t!=='×'&&t!=='✕'&&t!=='✖'&&t!=='x'&&t!=='close')continue;
                try{if(el.querySelector('video'))continue;}catch(e){}
                var r=el.getBoundingClientRect();
                if(r.width<=0||r.height<=0)continue;
                var inside=false;
                for(var k=0;k<vrs.length;k++){
                  var v=vrs[k];
                  var cx=r.left+r.width/2, cy=r.top+r.height/2;
                  if(cx>=v.left&&cx<=v.right&&cy>=v.top&&cy<=v.bottom){inside=true;break;}
                }
                if(!inside)continue;
                if(el.dataset)el.dataset.cfKilled='1';
                try{el.style.setProperty('display','none','important');n++;}catch(e){}
              }
            }catch(e){}
            return n;
          }
          // Video rectangles (top doc + same-origin iframes).
          function videoRects(){
            var rs=[];
            function col(root){
              try{
                var vs=root.querySelectorAll('video');
                for(var i=0;i<vs.length;i++){
                  try{
                    var r=vs[i].getBoundingClientRect();
                    if(r.width>40&&r.height>40)rs.push(r);
                  }catch(e){}
                }
              }catch(e){}
            }
            col(document);
            try{
              var frs=document.querySelectorAll('iframe');
              for(var f=0;f<frs.length;f++){
                try{var d=frs[f].contentDocument;if(d)col(d);}catch(e){}
              }
            }catch(e){}
            return rs;
          }
          function covers(a,b){
            try{
              var ix=Math.max(0,Math.min(a.right,b.right)-Math.max(a.left,b.left));
              var iy=Math.max(0,Math.min(a.bottom,b.bottom)-Math.max(a.top,b.top));
              var area=b.width*b.height;
              if(area<=0)return false;
              return (ix*iy)/area>=0.8;
            }catch(e){return false;}
          }
          function isTransparent(el){
            try{
              var s=getComputedStyle(el);
              var op=parseFloat(s.opacity||'1');
              var bg=(s.backgroundColor||'');
              return isNaN(op)||op<0.3||bg==='transparent'||bg.indexOf('rgba(0, 0, 0, 0)')===0;
            }catch(e){return false;}
          }
          // Removes transparent layers smothering a playing video (the
          // "audio plays, picture hidden" failure). Control strips (short
          // bars docked to the video bottom) and media subtrees are kept.
          function sweepVideoOverlays(){
            var n=0;
            try{
              var vrs=videoRects();
              if(!vrs.length)return 0;
              var els=document.querySelectorAll('div,section,aside,span');
              for(var i=0;i<els.length&&n<5;i++){
                var el=els[i];
                if(el.dataset&&(el.dataset.cfKilled||el.dataset.cfKeep))continue;
                var tag=(el.tagName||'').toLowerCase();
                if(tag==='video'||tag==='canvas')continue;
                if(hasMedia(el))continue;
                var t=norm(el.innerText);
                if(t.length>100)continue;
                if(!isTransparent(el))continue;
                var r=el.getBoundingClientRect();
                for(var k=0;k<vrs.length;k++){
                  if(!covers(r,vrs[k]))continue;
                  // Likely control bar: short strip docked to video bottom.
                  if(r.height<=vrs[k].height*0.25&&r.bottom>=vrs[k].bottom-4)continue;
                  if(el.dataset)el.dataset.cfKilled='1';
                  try{el.style.setProperty('display','none','important');n++;}catch(e){}
                  break;
                }
              }
            }catch(e){}
            return n;
          }
          // Tap-traps over the control strip itself (expand/gear row dead):
          // transparent layers covering the bottom band of the video that
          // carry almost no text. Genuine button rows are visible, so they
          // never match the transparency gate.
          function sweepControlTraps(){
            var n=0;
            try{
              var vrs=videoRects();
              if(!vrs.length)return 0;
              var els=document.querySelectorAll('div,section,aside,span,a');
              for(var i=0;i<els.length&&n<5;i++){
                var el=els[i];
                if(el.dataset&&(el.dataset.cfKilled||el.dataset.cfKeep))continue;
                var tag=(el.tagName||'').toLowerCase();
                if(tag==='video'||tag==='canvas')continue;
                if(hasMedia(el))continue;
                var t=norm(el.innerText);
                if(t.length>60)continue;
                if(!isTransparent(el))continue;
                var r=el.getBoundingClientRect();
                for(var k=0;k<vrs.length;k++){
                  var v=vrs[k];
                  var bandTop=v.bottom-v.height*0.3;
                  var ix=Math.max(0,Math.min(r.right,v.right)-Math.max(r.left,v.left));
                  if(ix<v.width*0.4)continue;
                  if(r.bottom<v.bottom-4)continue;
                  if(r.top<bandTop-8)continue;
                  if(el.dataset)el.dataset.cfKilled='1';
                  try{el.style.setProperty('display','none','important');n++;}catch(e){}
                  break;
                }
              }
            }catch(e){}
            return n;
          }
          function sweep(){
            var now=Date.now();if(now-last<500)return;last=now;
            var n=0;
            try{
              var els=document.querySelectorAll('div,section,aside,[role="dialog"],[role="alertdialog"]');
              for(var i=0;i<els.length&&n<10;i++){
                var el=els[i];
                if(el.dataset&&el.dataset.cfKilled)continue;
                var t=norm(el.innerText);
                if(t.length>3000||t.length<10)continue;
                if(hits(t)>=2&&isOverlay(el)){
                  if(el.dataset)el.dataset.cfKilled='1';
                  if(kill(el))n++;
                }
              }
            }catch(e){}
            try{
              var els2=document.querySelectorAll('div,section,aside');
              for(var j=0;j<els2.length&&n<10;j++){
                var el2=els2[j];
                if(el2.dataset&&el2.dataset.cfKilled)continue;
                if(isBottomBanner(el2)||isInvisibleOverlay(el2)){
                  if(el2.dataset)el2.dataset.cfKilled='1';
                  if(kill(el2))n++;
                }
              }
            }catch(e){}
            try{n+=sweepVideoOverlays();}catch(e){}
            try{n+=sweepControlTraps();}catch(e){}
            try{n+=sweepLoneX();}catch(e){}
            if(n>0){try{console.log('[CatFlix] auto-hid '+n+' popup(s)');}catch(e2){}}
          }
          function arm(){
            sweep();
            try{
              var mo=new MutationObserver(function(){sweep();});
              mo.observe(document.documentElement||document.body,{childList:true,subtree:true});
            }catch(e){}
          }
          if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',arm);}else{arm();}
        })()
    """.trimIndent()


    fun inject(view: WebView) {
        try {
            view.evaluateJavascript(SCRIPT, null)
        } catch (e: Exception) {
            Log.w(TAG, "inject failed", e)
        }
    }
}
