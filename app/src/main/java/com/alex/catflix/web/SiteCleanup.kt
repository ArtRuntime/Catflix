package com.alex.catflix.web

import android.util.Log
import android.webkit.WebView


object SiteCleanup {

    private const val TAG = "SiteCleanup"

    private val SCRIPT = """
        (function(){
          if(window.__cfSiteCleanup)return;window.__cfSiteCleanup=true;
          var HEAD_PATTS=['important note','join our telegram'];
          var LINK_PATTS=['t.me/','netmirror.gg','app-info.php','mobile/app'];
          var PROMO_PATTS=['first mirror copy','download apk','make on browser'];
          var DIRTY=['continue watching','netflix today','relentless crime'];
          function norm(s){return (s||'').toLowerCase().replace(/\s+/g,' ');}
          function hasAny(t,patts){for(var i=0;i<patts.length;i++){if(t.indexOf(patts[i])>=0)return true;}return false;}
          function isClean(box,skip){
            try{
              var heads=box.querySelectorAll('h1,h2,h3');
              for(var i=0;i<heads.length;i++){if(heads[i]!==skip&&norm(heads[i].innerText).length>0)return false;}
              var t=norm(box.innerText);
              if(t.length>2500)return false;
              return !hasAny(t,DIRTY);
            }catch(e){return false;}
          }
          function hasMedia(el){try{if(!el||!el.querySelector)return false;return !!(el.querySelector('video')||el.querySelector('audio'));}catch(e){return false;}}
          function hideBox(el){try{if(hasMedia(el))return false;el.style.setProperty('display','none','important');return true;}catch(e){return false;}}
          function cleanHeadings(){
            var n=0;
            try{
              var heads=document.querySelectorAll('h1,h2,h3,h4');
              for(var i=0;i<heads.length;i++){
                var h=heads[i];
                if(h.dataset&&h.dataset.cfCleaned)continue;
                if(!hasAny(norm(h.innerText),HEAD_PATTS))continue;
                if(h.dataset)h.dataset.cfCleaned='1';
                var box=h,ok=false;
                for(var d=0;d<4;d++){
                  var p=box.parentElement;if(!p||p===document.body||p===document.documentElement)break;
                  box=p;
                  var tag=(p.tagName||'').toLowerCase();
                  if((tag==='section'||tag==='footer'||tag==='aside'||tag==='div')&&isClean(p,h)){ok=true;break;}
                }
                if(ok&&hideBox(box)){n++;continue;}
                // Fallback: hide the heading plus following promo siblings only.
                hideBox(h);n++;
                var sib=h.nextElementSibling,guard=0;
                while(sib&&guard<8){
                  guard++;
                  var tag2=(sib.tagName||'').toLowerCase();
                  if(tag2.match(/^h[1-4]$/))break;
                  var st=norm(sib.innerText);
                  if(hasAny(st,DIRTY))break;
                  var tmp=sib.nextElementSibling;
                  if(st.length<1200)hideBox(sib);
                  sib=tmp;
                }
              }
            }catch(e){}
            return n;
          }
          function cleanLinks(){
            var n=0;
            try{
              var links=document.querySelectorAll('a[href]');
              for(var i=0;i<links.length;i++){
                var a=links[i];
                if(a.dataset&&a.dataset.cfCleaned)continue;
                var href=(a.getAttribute('href')||'').toLowerCase();
                var hit=false;
                for(var j=0;j<LINK_PATTS.length;j++){if(href.indexOf(LINK_PATTS[j])>=0){hit=true;break;}}
                if(!hit)continue;
                if(a.dataset)a.dataset.cfCleaned='1';
                var box=a,ok=false;
                for(var d=0;d<3;d++){
                  var p=box.parentElement;if(!p||p===document.body||p===document.documentElement)break;
                  box=p;
                  var tag=(p.tagName||'').toLowerCase();
                  if((tag==='section'||tag==='footer'||tag==='aside'||tag==='div')&&isClean(p,null)){ok=true;break;}
                }
                if(ok&&hideBox(box))n++;
              }
            }catch(e){}
            return n;
          }
          var last=0;
          function sweep(){
            var now=Date.now();if(now-last<800)return;last=now;
            var n=0;
            try{n+=cleanHeadings();}catch(e){}
            try{n+=cleanLinks();}catch(e){}
            try{n+=cleanPromo();}catch(e){}
            if(n>0){try{console.log('[CatFlix] site cleanup hid '+n+' block(s)');}catch(e2){}}
          }
          // Footer/app-promo text blocks (no headings): hide the smallest
          // clean leaf block carrying unmistakable promo phrases.
          function cleanPromo(){
            var n=0;
            try{
              var els=document.querySelectorAll('div,section,footer,p,span');
              for(var i=0;i<els.length&&n<10;i++){
                var el=els[i];
                if(el.dataset&&el.dataset.cfCleaned)continue;
                var t=norm(el.innerText);
                if(t.length<8||t.length>1200)continue;
                if(!hasAny(t,PROMO_PATTS))continue;
                if(el.dataset)el.dataset.cfCleaned='1';
                var box=el,ok=false;
                for(var d=0;d<3;d++){
                  var p=box.parentElement;if(!p||p===document.body||p===document.documentElement)break;
                  box=p;
                  var tag=(p.tagName||'').toLowerCase();
                  if((tag==='section'||tag==='footer'||tag==='aside'||tag==='div')&&isClean(p,null)){ok=true;break;}
                }
                if(ok&&hideBox(box)){n++;continue;}
                // Fallback: hide the leaf block itself when it carries no
                // headings and no catalog content.
                try{
                  if(el.querySelectorAll('h1,h2,h3').length===0&&!hasAny(t,DIRTY)){if(hideBox(el))n++;}
                }catch(e){}
              }
            }catch(e){}
            return n;
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
