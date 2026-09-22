package com.alex.catflix.adblock

import android.util.Log
import androidx.core.net.toUri
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import com.alex.catflix.net.SmartHttpCache


class AdBlockEngine(private val cacheDir: File) {

    companion object {
        private const val TAG = "AdBlockEngine"
        private const val MAX_CSS_LENGTH = 48_000
        private const val MAX_COSMETIC_RULES = 6_000


        private val COSMETIC_KEEP_KEYWORDS = listOf(
            "player", "video", "jw", "plyr", "vjs", "media",
            "movie", "episode", "watch", "display", "control",
            "caption", "subtitle", "stream", "embed"
        )


        val FILTER_LIST_URLS = listOf(
            "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
            "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/badware.txt",
            "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/privacy.txt",
            "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/quick-fixes.txt",
            "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/unbreak.txt",
            "https://easylist.to/easylist/easylist.txt",
            "https://easylist.to/easylist/easyprivacy.txt",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext"
        )


        private val BUNDLED_HOSTS = setOf(

            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adservice.google.com", "adservice.googleusercontent.com",
            "pagead2.googlesyndication.com", "pagead2.googleadservices.com",
            "tpc.googlesyndication.com", "googleads.g.doubleclick.net",
            "cm.g.doubleclick.net", "securepubads.g.doubleclick.net",
            "pubads.g.doubleclick.net", "static.doubleclick.net",
            "survey.g.doubleclick.net", "fls.doubleclick.net",

            "amazon-adsystem.com", "aax.amazon-adsystem.com", "s.amazon-adsystem.com",
            "criteo.com", "criteo.net", "taboola.com", "outbrain.com",
            "pubmatic.com", "rubiconproject.com", "moat.com", "moatads.com",
            "ads.yahoo.com", "advertising.com", "adsrvr.org", "rlcdn.com",
            "dtscout.com", "dtscdn.com", "popads.net", "popcash.net",
            "adnxs.com", "ads-twitter.com", "ads.linkedin.com",
            "media.net", "revcontent.com", "mgid.com", "adsterra.com",
            "propellerads.com", "exoclick.com", "juicyads.com",
            "adform.net", "smartadserver.com", "openx.net", "openx.com",
            "bidswitch.net", "bidbarrel.com", "lijit.com", "gumgum.com",
            "sharethrough.com", "triplelift.com", "indexww.com",
            "casalemedia.com", "contextweb.com", "lijit.com",

            "whos.amung.us", "histats.com", "s10.histats.com",
            "scorecardresearch.com", "quantserve.com", "hotjar.com",
            "fullstory.com", "mixpanel.com", "segment.io", "segment.com",
            "amplitude.com", "matomo.cloud", "statcounter.com",
            "clicky.com", "woopra.com", "crazyegg.com", "luckyorange.com",
            "mouseflow.com", "inspectlet.com", "sessioncam.com",


            "jwpltx.com", "bluekai.com", "crwdcntrl.net", "demdex.net",
            "mathtag.com", "agkn.com", "tapad.com", "spotxchange.com",
            "springserve.com", "vidazoo.com", "anyclip.com", "connatix.com",
            "primis.tech", "aniview.com", "exelator.com", "eyeota.net",
            "liveintent.com", "doubleverify.com", "iasds01.com",
            "googletagservices.com", "ad-delivery.net",

            "moonlighthathel.org",

            "2mdn.net", "adblade.com", "adtilt.com", "zedo.com"
        )


        private val BUNDLED_PATH_PATTERNS = listOf(
            "/ads/", "/adserver", "/ad_banner", "/popunder", "/popup-ad",
            "/track-float", "googleads", "doubleclick", "/pagead/",
            "/adsense", "/ad-loader", "/prebid", "/videoads",
            "/vpaid", "/vast", "preroll", "midroll", "ima3",
            "/companions", "jwpltx",

            "blockadblock", "fuckadblock", "adblock_detector",
            "adb_detector", "adblockchecker", "adblock_detect",
            "detectadblock",
            "whos.amung.us", "histats.com"
        )


        private val NEVER_BLOCK_HOSTS = setOf(
            "net77.cc", "netmirror.gg", "netmirror.app", "hineanime.lol",
            "hianime.lol",
            "cdn.jsdelivr.net", "jsdelivr.net",
            "cloudflare.com", "cdnjs.cloudflare.com",
            "gstatic.com", "fonts.gstatic.com", "fonts.googleapis.com",
            "jwplayer.com", "jwpsrv.com", "jwpcdn.com", "content.jwplatform.com",
            "cloudfront.net",

            "aniembed.se", "nukitashith.top",


            "recaptcha.net", "hcaptcha.com", "challenges.cloudflare.com",


            "accounts.google.com", "accounts.youtube.com"
        )
    }

    private data class Rules(
        val blockedHosts: Set<String>,
        val blockedPatterns: List<String>,
        val allowPatterns: List<String>
    )


    private val NEUTER_HOSTS = setOf(
        "moonlighthathel.org"
    )


    fun neuterResponse(url: String): android.webkit.WebResourceResponse? {
        if (!enabled) return null
        return try {
            val host = url.toUri().host?.lowercase(Locale.US)?.trimStart('.')
                ?: return null
            if (NEUTER_HOSTS.none { host == it || host.endsWith(".$it") }) {
                return null
            }
            android.webkit.WebResourceResponse(
                "application/json",
                "utf-8",
                200,
                "OK",
                mutableMapOf("Access-Control-Allow-Origin" to "*"),
                java.io.ByteArrayInputStream("{}".toByteArray())
            )
        } catch (_: Exception) {
            null
        }
    }

    private data class CosmeticRule(
        val domains: List<String>,
        val negations: List<String>,
        val selector: String
    )

    private val rules = AtomicReference(
        Rules(BUNDLED_HOSTS, BUNDLED_PATH_PATTERNS, emptyList())
    )


    private val cosmetics = AtomicReference(
        listOf(
            CosmeticRule(emptyList(), emptyList(), ".adsbygoogle"),
            CosmeticRule(emptyList(), emptyList(), "[id^=\"div-gpt-ad\"]"),
            CosmeticRule(emptyList(), emptyList(), "[id^=\"google_ads\"]"),
            CosmeticRule(emptyList(), emptyList(), ".ad-slot")
        )
    )


    @Volatile
    var enabled: Boolean = true
    private val executor = Executors.newSingleThreadExecutor()
    private val cacheFile: File = File(cacheDir, "ublock-filters.cache")


    private val http = SmartHttpCache(
        cacheDir,
        "CatFlix/1.0 (uBlock-compatible filter updater)"
    )

    init {
        loadCacheAsync()
    }



    private val BASE_TRACKER_HOSTS = setOf(
        "whos.amung.us", "histats.com",
        "scorecardresearch.com", "quantserve.com",
        "google-analytics.com", "analytics.google.com",
        "mixpanel.com", "amplitude.com", "segment.io",
        "statcounter.com", "hotjar.com", "fullstory.com"
    )

    fun shouldBlock(url: String, pageHost: String?, isMainFrame: Boolean): Boolean {
        if (isMainFrame) return false
        if (url.isBlank()) return false
        val uri = try {
            url.toUri()
        } catch (_: Exception) {
            return false
        }
        val scheme = (uri.scheme ?: "").lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") return false 

        val host = (uri.host ?: "").lowercase(Locale.US).trimStart('.')
        if (host.isEmpty()) return false
        if (isNeverBlock(host)) return false


        if (isBaseTracker(host)) return true
        if (!enabled) return false

        val snapshot = rules.get()
        val lowerUrl = url.lowercase(Locale.US)


        for (allow in snapshot.allowPatterns) {
            if (allow.isNotEmpty() && lowerUrl.contains(allow)) return false
        }

        val isFirstParty = pageHost != null &&
            (host == pageHost.lowercase(Locale.US) || host.endsWith(".$pageHost"))
        val hostBlocked = isHostBlocked(host, snapshot.blockedHosts)

        if (hostBlocked) {


            if (!isFirstParty) return true
            if (looksLikeAdPath(lowerUrl, snapshot.blockedPatterns)) return true
        }


        if (looksLikeAdPath(lowerUrl, snapshot.blockedPatterns)) {

            if (!isFirstParty) return true
            if (lowerUrl.contains("/ads/") || lowerUrl.contains("pagead") ||
                lowerUrl.contains("doubleclick") || lowerUrl.contains("googleads")
            ) return true
        }
        return false
    }


    fun cosmeticCssFor(pageHost: String?): String {
        if (!enabled || pageHost.isNullOrBlank()) return ""
        val host = pageHost.lowercase(Locale.US)
        val sb = StringBuilder()
        var count = 0
        for (rule in cosmetics.get()) {
            if (sb.length > MAX_CSS_LENGTH) break
            if (rule.domains.isNotEmpty() &&
                rule.domains.none { matchesDomain(host, it) }
            ) continue
            if (rule.negations.any { matchesDomain(host, it) }) continue
            if (count > 0) sb.append(',')
            sb.append(rule.selector)
            count++
        }
        if (count == 0) return ""
        sb.append("{display:none!important}")
        return sb.toString()
    }

    fun blockedHostCount(): Int = rules.get().blockedHosts.size


    fun isBlockedHost(host: String): Boolean {
        if (!enabled) return false
        val h = host.lowercase(Locale.US).trimStart('.')
        if (h.isEmpty() || isNeverBlock(h)) return false
        return isHostBlocked(h, rules.get().blockedHosts)
    }

    private fun matchesDomain(host: String, rule: String): Boolean =
        host == rule || host.endsWith(".$rule")

    private fun isBaseTracker(host: String): Boolean =
        isHostBlocked(host, BASE_TRACKER_HOSTS)


    fun refreshAsync(onDone: ((Boolean) -> Unit)? = null) {
        executor.execute {
            try {
                val hosts = HashSet(BUNDLED_HOSTS)
                val patterns = ArrayList(BUNDLED_PATH_PATTERNS)
                val allows = ArrayList<String>()
                val cosmetic = ArrayList<CosmeticRule>()
                var ok = false
                for (listUrl in FILTER_LIST_URLS) {
                    try {
                        val text = http.fetchText(listUrl, SmartHttpCache.TTL_LISTS_MS)
                            ?: continue
                        parseFilterText(text, hosts, patterns, allows, cosmetic)
                        ok = true
                    } catch (e: Exception) {
                        Log.w(TAG, "Filter list failed: $listUrl", e)
                    }
                }
                if (ok) {
                    rules.set(Rules(hosts, patterns, allows))
                    if (cosmetic.isNotEmpty()) cosmetics.set(cosmetic)
                    try {
                        cacheFile.parentFile?.mkdirs()
                        cacheFile.writeText(
                            hosts.joinToString("\n", prefix = "#hosts\n") +
                                "\n#patterns\n" + patterns.take(5000).joinToString("\n")
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Filter cache write failed", e)
                    }
                }
                try {
                    onDone?.invoke(ok)
                } catch (_: Exception) {
                }
            } catch (e: Exception) {
                Log.w(TAG, "Filter refresh failed", e)
                try {
                    onDone?.invoke(false)
                } catch (_: Exception) {
                }
            }
        }
    }



    private fun isNeverBlock(host: String): Boolean {

        if (host.startsWith("accounts.google.")) return true
        for (safe in NEVER_BLOCK_HOSTS) {
            if (host == safe || host.endsWith(".$safe")) return true
        }
        return false
    }

    private fun isHostBlocked(host: String, blocked: Set<String>): Boolean {
        var h: String? = host
        while (h != null) {
            if (blocked.contains(h)) return true
            val dot = h.indexOf('.')
            h = if (dot >= 0) h.substring(dot + 1) else null
        }
        return false
    }

    private fun looksLikeAdPath(lowerUrl: String, patterns: List<String>): Boolean {
        for (p in patterns) {
            if (p.length > 3 && lowerUrl.contains(p)) return true
        }
        return false
    }

    private fun loadCacheAsync() {
        executor.execute {
            try {
                if (!cacheFile.exists()) {
                    refreshAsync()
                    return@execute
                }
                val hosts = HashSet(BUNDLED_HOSTS)
                val patterns = ArrayList(BUNDLED_PATH_PATTERNS)
                val allows = ArrayList<String>()
                parseFilterText(cacheFile.readText(), hosts, patterns, allows, ArrayList())
                if (hosts.isNotEmpty()) rules.set(Rules(hosts, patterns, allows))

                if (System.currentTimeMillis() - cacheFile.lastModified() > 7L * 24 * 60 * 60 * 1000) {
                    refreshAsync()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Filter cache load failed", e)
            }
        }
    }


    private fun parseFilterText(
        text: String,
        hosts: MutableSet<String>,
        patterns: MutableList<String>,
        allows: MutableList<String>,
        cosmeticsOut: MutableList<CosmeticRule>
    ) {
        var count = 0
        for (raw in text.lineSequence()) {
            if (count > 120_000) break 
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("#")) continue

            if (line.startsWith("127.0.0.1") || line.startsWith("0.0.0.0")) {
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 2) {
                    val h = parts[1].lowercase(Locale.US).trim()
                    if (h.isNotEmpty() && h != "localhost" && !h.contains(" ")) {
                        hosts.add(h); count++
                    }
                }
                continue
            }
            if (line.startsWith("@@")) {
                val domain = extractDomain(line.removePrefix("@@"))
                if (domain != null) allows.add(domain) else if (line.length < 120) {
                    allows.add(simplifyPattern(line.removePrefix("@@")))
                }
                continue
            }
            if (line.contains("#@#") || line.contains("#?#")) continue
            if (line.contains("##")) {
                collectCosmetic(line, cosmeticsOut)
                continue
            }
            if (line.startsWith("[") || line.contains("\$generichide") || line.contains("\$elemhide")) continue

            if (line.startsWith("||")) {
                val domain = extractDomain(line)
                if (domain != null) {


                    hosts.add(domain); count++
                } else {
                    val p = simplifyPattern(line)
                    if (p.length > 3 && patterns.size < 8000) patterns.add(p)
                }
                continue
            }
            if (line.startsWith("|") || line.startsWith("http")) {
                val p = simplifyPattern(line)
                if (p.length > 4 && patterns.size < 8000) {
                    patterns.add(p); count++
                }
                continue
            }
        }
    }


    private fun collectCosmetic(line: String, out: MutableList<CosmeticRule>) {
        if (out.size >= MAX_COSMETIC_RULES) return
        val sep = line.lastIndexOf("##")
        if (sep <= 0) return
        val selector = line.substring(sep + 2).trim()
        if (selector.isEmpty() || selector.length > 200 || selector == "*") return
        if (selector.contains(":has(") || selector.contains(":has-text(") ||
            selector.contains(":matches-") || selector.contains(":xpath(") ||
            selector.contains(":nth-ancestor(") || selector.contains(":upward(") ||
            selector.contains("+js(") || selector.contains(":style(") ||
            selector.contains(":remove(")
        ) return
        if (!selector.all {
                it in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#._-[]=:\"'~+>^$*|(), "
            }
        ) return



        val lowered = selector.lowercase(Locale.US)
        for (keep in COSMETIC_KEEP_KEYWORDS) {
            if (lowered.contains(keep)) return
        }
        val domains = ArrayList<String>()
        val negations = ArrayList<String>()
        for (part in line.substring(0, sep).split(',')) {
            val t = part.trim().lowercase(Locale.US)
            if (t.isEmpty()) continue
            if (t.startsWith("~")) {
                val n = t.removePrefix("~")
                if (n.contains('.')) negations.add(n)
            } else if (t.contains('.') && !t.contains('*') && !t.contains('/') &&
                !t.contains(' ') && !t.contains('|')
            ) {
                domains.add(t)
            }
        }
        out.add(CosmeticRule(domains, negations, selector))
    }

    private fun extractDomain(rule: String): String? {
        var r = rule.trim()
        if (r.startsWith("||")) r = r.removePrefix("||")
        val end = r.indexOfAny(charArrayOf('^', '/', '?', '#', '$', '*', '|', ' '))
        var domain = if (end >= 0) r.substring(0, end) else r
        domain = domain.lowercase(Locale.US).trimStart('.')
        if (domain.isEmpty() || domain.contains("*") || domain.contains("!")) return null
        if (!domain.contains(".")) return null
        return domain
    }

    private fun simplifyPattern(rule: String): String {
        var p = rule.lowercase(Locale.US).trim()

        val dollar = p.lastIndexOf('$')
        if (dollar > 0 && p.substring(dollar).matches(Regex("\\$[a-z,\\-=~]+"))) {
            p = p.substring(0, dollar)
        }
        p = p.trimStart('|').trimStart('*').trimEnd('*').trimEnd('^').trim()

        if (p.length > 80) p = p.takeLast(80)
        return p
    }
}
