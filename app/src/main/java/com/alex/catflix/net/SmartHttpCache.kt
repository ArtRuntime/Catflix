package com.alex.catflix.net

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject


class SmartHttpCache(
    cacheDir: File,
    private val userAgent: String
) {

    data class Result(
        val bytes: ByteArray,
        val fromCache: Boolean,
        val revalidated: Boolean
    ) {
        fun text(): String = bytes.toString(Charsets.UTF_8)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Result) return false
            return bytes.contentEquals(other.bytes) &&
                fromCache == other.fromCache &&
                revalidated == other.revalidated
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + fromCache.hashCode()
            result = 31 * result + revalidated.hashCode()
            return result
        }
    }

    companion object {
        private const val TAG = "SmartHttpCache"


        const val TTL_LISTS_MS = 24L * 60 * 60 * 1000 
        const val TTL_IMAGES_MS = 7L * 24 * 60 * 60 * 1000 

        private const val MAX_ENTRIES = 300
        private const val MAX_BYTES = 32L * 1024 * 1024
        private const val WAIT_MS = 30_000L
    }

    private val dir: File = File(cacheDir, "smart-http").apply { mkdirs() }


    private val inFlight = HashMap<String, CountDownLatch>()

    fun fetchText(
        url: String,
        ttlMs: Long,
        maxBytes: Long = 8L * 1024 * 1024
    ): String? {
        return try {
            fetch(url, ttlMs, maxBytes)?.text()
        } catch (e: Exception) {
            Log.w(TAG, "fetchText failed for $url", e)
            null
        }
    }

    fun fetch(
        url: String,
        ttlMs: Long,
        maxBytes: Long = 8L * 1024 * 1024,
        extraHeaders: Map<String, String> = emptyMap()
    ): Result? {
        if (url.isBlank()) return null

        var owner = false
        val latch: CountDownLatch = synchronized(inFlight) {
            val existing = inFlight[url]
            existing
                ?: CountDownLatch(1).also {
                    inFlight[url] = it
                    owner = true
                }
        }
        if (!owner) {
            try {
                latch.await(WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return readFresh(url, ttlMs, Long.MAX_VALUE)
                ?: readStale(url)
        }
        try {
            return fetchLocked(url, ttlMs, maxBytes, extraHeaders)
        } finally {
            synchronized(inFlight) { inFlight.remove(url) }
            latch.countDown()
        }
    }


    fun invalidate(url: String) {
        try {
            bodyFile(url).delete()
            metaFile(url).delete()
        } catch (_: Exception) {
        }
    }



    private fun fetchLocked(
        url: String,
        ttlMs: Long,
        maxBytes: Long,
        extraHeaders: Map<String, String>
    ): Result? {

        readFresh(url, ttlMs, maxBytes)?.let { return it }

        val meta = readMeta(url)


        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", userAgent)
                for ((k, v) in extraHeaders) conn.setRequestProperty(k, v)
                meta?.etag?.takeIf { it.isNotEmpty() }?.let {
                    conn.setRequestProperty("If-None-Match", it)
                }
                meta?.lastModified?.takeIf { it.isNotEmpty() }?.let {
                    conn.setRequestProperty("If-Modified-Since", it)
                }
                when (conn.responseCode) {
                    HttpURLConnection.HTTP_NOT_MODIFIED -> {
                        touchMeta(url, ttlMs)
                        readBody(url, maxBytes)?.let {
                            return Result(it, fromCache = true, revalidated = true)
                        }
                    }
                    in 200..299 -> {
                        val body = readCapped(conn, maxBytes) ?: return readStale(url)
                        val contentType = conn.contentType ?: ""
                        writeEntry(url, body, ttlMs, conn, contentType)
                        return Result(body, fromCache = false, revalidated = false)
                    }
                    else -> {
                        Log.w(TAG, "HTTP ${conn.responseCode} for $url, stale fallback")
                    }
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fetch failed for $url, stale fallback", e)
        }

        return readStale(url)
    }

    private data class Meta(
        val etag: String,
        val lastModified: String,
        val fetchedAt: Long,
        val ttlMs: Long,
        val contentType: String
    )

    private fun key(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(url.toByteArray(Charsets.UTF_8))
        return buildString(hash.size * 2) {
            for (b in hash) append("%02x".format(Locale.US, b))
        }
    }

    private fun bodyFile(url: String): File = File(dir, "${key(url)}.body")
    private fun metaFile(url: String): File = File(dir, "${key(url)}.json")

    private fun readMeta(url: String): Meta? {
        return try {
            val f = metaFile(url)
            if (!f.exists()) return null
            val o = JSONObject(f.readText())
            Meta(
                etag = o.optString("etag", ""),
                lastModified = o.optString("lastModified", ""),
                fetchedAt = o.optLong("fetchedAt", 0L),
                ttlMs = o.optLong("ttlMs", 0L),
                contentType = o.optString("contentType", "")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun readBody(url: String, maxBytes: Long): ByteArray? {
        return try {
            val f = bodyFile(url)
            if (!f.exists() || f.length() > maxBytes) return null
            f.readBytes()
        } catch (_: Exception) {
            null
        }
    }

    private fun readFresh(url: String, ttlMs: Long, maxBytes: Long): Result? {
        val meta = readMeta(url) ?: return null
        val effectiveTtl = if (meta.ttlMs > 0) meta.ttlMs else ttlMs
        if (System.currentTimeMillis() - meta.fetchedAt > effectiveTtl) return null
        touchAccess(url)
        return readBody(url, maxBytes)?.let {
            Result(it, fromCache = true, revalidated = false)
        }
    }

    private fun readStale(url: String): Result? {
        return readBody(url, Long.MAX_VALUE)?.let {
            Result(it, fromCache = true, revalidated = false)
        }
    }

    private fun touchMeta(url: String, ttlMs: Long) {
        try {
            val f = metaFile(url)
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            o.put("fetchedAt", System.currentTimeMillis())
            o.put("ttlMs", ttlMs)
            o.put("lastAccess", System.currentTimeMillis())
            f.writeText(o.toString())
        } catch (_: Exception) {
        }
    }

    private fun touchAccess(url: String) {
        try {
            val f = metaFile(url)
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            o.put("lastAccess", System.currentTimeMillis())
            f.writeText(o.toString())
        } catch (_: Exception) {
        }
    }

    private fun writeEntry(
        url: String,
        body: ByteArray,
        ttlMs: Long,
        conn: HttpURLConnection,
        contentType: String
    ) {
        try {
            bodyFile(url).writeBytes(body)
            val meta = JSONObject()
                .put("url", url)
                .put("etag", headerCaseInsensitive(conn, "ETag") ?: "")
                .put("lastModified", headerCaseInsensitive(conn, "Last-Modified") ?: "")
                .put("fetchedAt", System.currentTimeMillis())
                .put("ttlMs", ttlMs)
                .put("contentType", contentType)
                .put("lastAccess", System.currentTimeMillis())
            metaFile(url).writeText(meta.toString())
            evictIfNeeded()
        } catch (e: Exception) {
            Log.w(TAG, "Cache write failed for $url", e)
        }
    }

    private fun headerCaseInsensitive(conn: HttpURLConnection, name: String): String? {
        try {
            for ((k, v) in conn.headerFields) {
                if (k != null && k.equals(name, ignoreCase = true)) return v?.firstOrNull()
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun readCapped(conn: HttpURLConnection, maxBytes: Long): ByteArray? {
        return try {
            conn.inputStream.use { ins ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun evictIfNeeded() {
        try {
            val metas = dir.listFiles { f -> f.extension == "json" } ?: return
            if (metas.size <= MAX_ENTRIES) {
                var total = 0L
                for (m in metas) {
                    total += m.length()
                    try {
                        total += File(dir, "${m.nameWithoutExtension}.body").length()
                    } catch (_: Exception) {
                    }
                }
                if (total <= MAX_BYTES) return
            }
            data class Entry(val key: String, val lastAccess: Long, val size: Long)
            val entries = metas.mapNotNull { m ->
                try {
                    val o = JSONObject(m.readText())
                    val key = m.nameWithoutExtension
                    var size = m.length()
                    try {
                        size += File(dir, "$key.body").length()
                    } catch (_: Exception) {
                    }
                    Entry(key, o.optLong("lastAccess", 0L), size)
                } catch (_: Exception) {
                    null
                }
            }.sortedBy { it.lastAccess }
            var total = entries.sumOf { it.size }
            var count = entries.size
            for (e in entries) {
                if (count <= MAX_ENTRIES && total <= MAX_BYTES) break
                try {
                    File(dir, "${e.key}.body").delete()
                    File(dir, "${e.key}.json").delete()
                } catch (_: Exception) {
                }
                total -= e.size
                count--
            }
        } catch (_: Exception) {
        }
    }
}
