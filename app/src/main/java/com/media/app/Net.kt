package com.media.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection

import java.net.URLEncoder

// ============================================================================
//  NETWORK
//
//  The only code in the app that talks to anything but the ad SDK, and only
//  for enrichment the listener turned on or asked for. Playback never waits
//  on it: every caller is a background coroutine, and every failure comes
//  back as a value rather than an exception.
//
//  Per-host rate limits are the providers' own published guidance -
//  MusicBrainz in particular blocks clients that exceed one request a second -
//  and every request carries a User-Agent naming the app, as they ask.
// ============================================================================

sealed interface NetResult {
    class Ok(val body: ByteArray) : NetResult {
        val text: String get() = String(body, Charsets.UTF_8)
    }
    /** The service answered: there is nothing there. Cacheable. */
    object NotFound : NetResult
    /** The service could not be asked. Worth trying again later. */
    class Failed(val code: Int) : NetResult
}

private class RateLimiter(private val gapMs: Long) {
    private val mutex = Mutex()
    private var last = 0L
    suspend fun acquire() = mutex.withLock {
        val now = System.currentTimeMillis()
        val wait = last + gapMs - now
        if (wait > 0) delay(wait)
        last = System.currentTimeMillis()
    }
}

object Net {

    /** Set once at startup from BuildConfig; kept here so tests need no Android. */
    @Volatile var userAgent: String = "Media ( https://mebs.app )"

    private val limits = mapOf(
        "musicbrainz.org" to RateLimiter(1100),
        "coverartarchive.org" to RateLimiter(250),
        "lrclib.net" to RateLimiter(300)
    )
    private val fallback = RateLimiter(100)

    private const val TIMEOUT_MS = 12_000
    private const val ATTEMPTS = 3

    fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun query(vararg params: Pair<String, Any?>): String =
        params.filter { it.second != null && it.second.toString().isNotBlank() }
            .joinToString("&") { (k, v) -> "$k=${encode(v.toString())}" }

    suspend fun get(
        url: String,
        maxBytes: Int = 2 * 1024 * 1024,
        accept: String = "application/json"
    ): NetResult = withContext(Dispatchers.IO) {
        val host = runCatching { java.net.URI(url).host }.getOrDefault("")
        val limiter = limits.entries.firstOrNull { host.endsWith(it.key) }?.value ?: fallback
        var backoff = 1500L
        var lastCode = 0
        repeat(ATTEMPTS) { attempt ->
            limiter.acquire()
            val conn = runCatching { java.net.URI(url).toURL().openConnection() as HttpURLConnection }.getOrNull()
                ?: return@withContext NetResult.Failed(0)
            try {
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty("Accept", accept)
                val code = conn.responseCode
                lastCode = code
                when {
                    code in 200..299 -> {
                        val body = conn.inputStream.use { readCapped(it, maxBytes) }
                            ?: return@withContext NetResult.Failed(code)
                        return@withContext NetResult.Ok(body)
                    }
                    code == 404 || code == 410 -> return@withContext NetResult.NotFound
                    code == 429 || code == 503 || code >= 500 -> {
                        // Retry-After is seconds for these providers.
                        val retryAfter = conn.getHeaderField("Retry-After")?.toLongOrNull()
                        if (attempt < ATTEMPTS - 1) delay(retryAfter?.times(1000)?.coerceAtMost(10_000) ?: backoff)
                        backoff *= 2
                    }
                    else -> return@withContext NetResult.Failed(code)
                }
            } catch (e: IOException) {
                lastCode = 0
                if (attempt < ATTEMPTS - 1) delay(backoff)
                backoff *= 2
            } finally {
                conn.disconnect()
            }
        }
        NetResult.Failed(lastCode)
    }

    private fun readCapped(input: java.io.InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) return null
        }
        return out.toByteArray()
    }
}
