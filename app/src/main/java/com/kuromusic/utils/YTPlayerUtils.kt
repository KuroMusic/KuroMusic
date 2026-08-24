package com.kuromusic.utils

import android.net.ConnectivityManager
import androidx.media3.common.PlaybackException
import com.kuromusic.innertube.models.response.PlayerResponse
import com.kuromusic.innertube.pages.NewPipeExtractor
import com.kuromusic.constants.AudioQuality
import com.kuromusic.innertube.YouTube
import com.kuromusic.innertube.models.YouTubeClient
import com.kuromusic.innertube.models.YouTubeClient.Companion.ANDROID_MUSIC
import com.kuromusic.innertube.models.YouTubeClient.Companion.IOS
import com.kuromusic.innertube.models.YouTubeClient.Companion.MOBILE
import com.kuromusic.innertube.models.YouTubeClient.Companion.WEB_REMIX
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import org.json.JSONObject
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import com.kuromusic.BuildConfig
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Response
import okhttp3.Cache
import okhttp3.ConnectionPool
import android.content.Context
import java.net.URLDecoder
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.kuromusic.constants.InnerTubeCookieKey
import com.kuromusic.constants.ForceAacFallbackKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.kuromusic.utils.dataStore
import com.kuromusic.utils.potoken.PoTokenGenerator
import com.kuromusic.utils.potoken.PoTokenResult
import com.kuromusic.utils.cipher.CipherDeobfuscator
import com.kuromusic.utils.sabr.EjsNTransformSolver


object YTPlayerUtils {
    private const val logTag = "YTPlayerUtils"
    private val poTokenGenerator = PoTokenGenerator()

    private val MUSIC_CLIENT_HEADERS = mapOf(
        "User-Agent" to "com.google.android.apps.youtube.music/9.25.50 (Linux; U; Android 15; Pixel 9 Pro)",
        "X-YouTube-Client-Name" to "21",
        "X-YouTube-Client-Version" to "9.25.50",
        "X-YouTube-API-Key" to BuildConfig.INNER_TUBE_API_KEY,
        "Accept-Language" to "es-419,es;q=0.9,en;q=0.8"
    )

    private var cacheDir: File? = null
    private var httpClient: OkHttpClient? = null
    private var appContext: Context? = null

    fun initialize(context: Context) {
        if (httpClient != null) return
        appContext = context
        CipherDeobfuscator.initialize(context)
        cacheDir = context.cacheDir
        httpClient = createMusicClient()
    }

    private fun createMusicClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(20, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
        
        // Cache 50MB
        cacheDir?.let {
            val cacheSize = 50L * 1024 * 1024 // 50MB
            val cache = Cache(File(it, "http_cache"), cacheSize)
            builder.cache(cache)
        }

        return builder
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    if (cookies.isEmpty()) return
                    val host = url.host
                    // Solo guardar cookies de YouTube
                    if (!host.contains("youtube.com") && !host.contains("googlevideo.com") && !host.contains("ytimg.com")) return
                    
                    val existing = YouTube.cookie ?: BuildConfig.YOUTUBE_SESSION_COOKIES
                    val cookieMap = mutableMapOf<String, String>()
                    if (existing.isNotBlank()) {
                        existing.split("; ").forEach {
                            val parts = it.split("=", limit = 2)
                            if (parts.size == 2) cookieMap[parts[0]] = parts[1]
                        }
                    }
                    cookies.forEach { cookie ->
                        cookieMap[cookie.name] = cookie.value
                    }
                    val merged = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    YouTube.cookie = merged
                    
                    // Persistir a DataStore (fire-and-forget, in-memory cookie already set above)
                    appContext?.let { ctx ->
                        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                            ctx.dataStore.edit { it[InnerTubeCookieKey] = merged }
                        }
                    }
                }
                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    val cookiesStr = YouTube.cookie ?: BuildConfig.YOUTUBE_SESSION_COOKIES
                    return if (cookiesStr.isNotBlank()) {
                         cookiesStr.split("; ").mapNotNull { 
                             Cookie.parse(url, it)
                         }
                    } else emptyList()
                }
            })
            .addNetworkInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .apply { MUSIC_CLIENT_HEADERS.forEach { addHeader(it.key, it.value) } }
                    // Force cache usage for 1 hour
                    .header("Cache-Control", "public, max-age=3600")
                    .build()
                chain.proceed(request)
            }
            .proxy(YouTube.proxy)
            .build()
    }

    /**
     * The main client is used for metadata and initial streams.
     * Do not use other clients for this because it can result in inconsistent metadata.
     * For example other clients can have different normalization targets (loudnessDb).
     *
     * WEB_REMIX is preferred because it is the only client which provides:
     * - the correct metadata (like loudnessDb)
     * - premium formats
     * ANDROID_VR was removed as MAIN_CLIENT: YouTube started rejecting its
     * stream URLs with 403 in August 2026 (see yt-dlp#17456).
     */
    private val MAIN_CLIENT: YouTubeClient = WEB_REMIX
    /**
     * Clients used for fallback streams in case the streams of the main client do not work.
     */
    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        ANDROID_MUSIC,
        MOBILE,
        IOS,
    )

    private const val WEB_REMIX_FAILURE_TTL_MS = 5 * 60 * 1000L

    // Temporarily skip WEB_REMIX after its stream is rejected (403) so the re-fetch can fall
    // through to fallback clients instead of looping on the same rejected URL.
    private val webRemixFailures = ConcurrentHashMap<String, Long>()

    fun markWebRemixFailed(videoId: String) {
        webRemixFailures[videoId] = System.currentTimeMillis()
    }

    fun clearWebRemixFailures() {
        webRemixFailures.clear()
    }

    private fun hasRecentWebRemixFailure(videoId: String): Boolean {
        val failedAt = webRemixFailures[videoId] ?: return false
        if ((System.currentTimeMillis() - failedAt) !in 0 until WEB_REMIX_FAILURE_TTL_MS) {
            webRemixFailures.remove(videoId, failedAt)
            return false
        }
        return true
    }
    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
        val fetchedAt: Long = System.currentTimeMillis(),
        val streamClient: String = "unknown",
        val streamHeaders: Map<String, String> = emptyMap(),
    )

    /**
     * Removes all cached player responses for [videoId] so the next playback attempt fetches
     * fresh URLs. Must be called when a stream is rejected (403) to avoid replaying a dead URL.
     */
    fun invalidatePlayerResponse(videoId: String) {
        val staleKeys = playerCache.keys.filter { it.startsWith("$videoId-") }
        staleKeys.forEach { key ->
            playerCache.remove(key)
            cacheTime.remove(key)
        }
        if (staleKeys.isNotEmpty()) {
            Timber.tag(logTag).d("Invalidated %d cached player responses for %s", staleKeys.size, videoId)
        }
    }

    // Cache to prevent re-fetching PlayerResponse (Speed up by ~1s)
    private val playerCache = mutableMapOf<String, Result<PlayerResponse>>()
    private val cacheTime = mutableMapOf<String, Long>()
    private const val MAX_CACHE_ENTRIES = 200
    private const val CACHE_TTL_MS = 60 * 60 * 1000L

    fun trimCache() {
        val now = System.currentTimeMillis()
        val staleKeys = cacheTime.filter { (_, time) -> now - time > CACHE_TTL_MS }.keys
        staleKeys.forEach { key ->
            playerCache.remove(key)
            cacheTime.remove(key)
        }
        if (playerCache.size > MAX_CACHE_ENTRIES) {
            val extra = playerCache.size - MAX_CACHE_ENTRIES
            val toRemove = cacheTime.entries.sortedBy { it.value }.take(extra).map { it.key }
            toRemove.forEach { key ->
                playerCache.remove(key)
                cacheTime.remove(key)
            }
        }
    }

    private suspend fun getCachedPlayerResponse(
        videoId: String, 
        playlistId: String?, 
        client: YouTubeClient, 
        signatureTimestamp: Int?,
        poToken: String? = null,
    ): Result<PlayerResponse> {
        val now = System.currentTimeMillis()
        val key = "$videoId-${client.clientName}"
        
        if (playerCache.containsKey(key)) {
            val timestamp = cacheTime[key] ?: 0L
            val cached = playerCache[key]!!
            val ttl = if (cached.isSuccess) CACHE_TTL_MS else 30_000L
            if (now - timestamp < ttl) {
                Timber.tag(logTag).v("⚡ Using cached response for $key")
                return cached
            }
        }
        
        val response = YouTube.player(videoId, playlistId, client, signatureTimestamp, poToken)
        playerCache[key] = response
        cacheTime[key] = now
        return response
    }
    /**
     * Custom player response intended to use for playback.
     * Metadata like audioConfig and videoDetails are from [MAIN_CLIENT].
     * Format & stream can be from [MAIN_CLIENT] or [STREAM_FALLBACK_CLIENTS].
     */
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): Result<PlaybackData> = withContext(Dispatchers.IO) {
        runCatching {
            // Ensure client is initialized (fallback if not called)
            if (httpClient == null) httpClient = createMusicClient() 

        /**
         * This is required for some clients to get working streams however
         * it should not be forced for the [MAIN_CLIENT] because the response of the [MAIN_CLIENT]
         * is required even if the streams won't work from this client.
         * This is why it is allowed to be null.
         */
        val signatureTimestamp = getSignatureTimestampOrNull(videoId)
        Timber.tag(logTag).v("Signature timestamp: $signatureTimestamp")

        val isLoggedIn = YouTube.cookie != null
        val sessionId =
            if (isLoggedIn) {
                // signed in sessions use dataSyncId as identifier
                YouTube.dataSyncId
            } else {
                // signed out sessions use visitorData as identifier
                YouTube.visitorData
            }
        Timber.tag(logTag).v("Session authentication status: ${if (isLoggedIn) "Logged in" else "Not logged in"}")

        // Generate PoToken for WEB_REMIX (only client that uses it)
        var poToken: PoTokenResult? = null
        if (MAIN_CLIENT.useWebPoTokens && sessionId != null) {
            try {
                poToken = poTokenGenerator.getWebClientPoToken(videoId, sessionId)
            } catch (e: Exception) {
                Timber.tag(logTag).w(e, "PoToken generation failed (non-fatal)")
            }
        }

        Timber.tag(logTag).v("Attempting to get player response using MAIN_CLIENT: ${MAIN_CLIENT.clientName}")
        val mainPlayerResponse =
            getCachedPlayerResponse(videoId, playlistId, MAIN_CLIENT, signatureTimestamp, poToken?.playerRequestPoToken).getOrThrow()
        val audioConfig = mainPlayerResponse.playerConfig?.audioConfig
        val videoDetails = mainPlayerResponse.videoDetails
        val playbackTracking = mainPlayerResponse.playbackTracking
        var format: PlayerResponse.StreamingData.Format? = null
        var streamUrl: String? = null
        var streamExpiresInSeconds: Int? = null
        var streamPlayerResponse: PlayerResponse? = null

        var successClient: YouTubeClient? = null

        for (clientIndex in (-1 until STREAM_FALLBACK_CLIENTS.size)) {
            // reset for each client
            format = null
            streamUrl = null
            streamExpiresInSeconds = null

            // decide which client to use for streams and load its player response
            val client: YouTubeClient
            if (clientIndex == -1) {
                // try with streams from main client first
                client = MAIN_CLIENT
                if (hasRecentWebRemixFailure(videoId)) {
                    Timber.tag(logTag).v("Skipping MAIN_CLIENT ${client.clientName} - recent stream rejection")
                    continue
                }
                streamPlayerResponse = mainPlayerResponse
                Timber.tag(logTag).v("Trying stream from MAIN_CLIENT: ${client.clientName}")
            } else {
                // after main client use fallback clients
                client = STREAM_FALLBACK_CLIENTS[clientIndex]
                Timber.tag(logTag).v("Trying fallback client ${clientIndex + 1}/${STREAM_FALLBACK_CLIENTS.size}: ${client.clientName}")

                if (client.loginRequired && !isLoggedIn && YouTube.cookie == null) {
                    // skip client if it requires login but user is not logged in
                    Timber.tag(logTag).v("Skipping client ${client.clientName} - requires login but user is not logged in")
                    continue
                }

                // Lazily generate PoToken for web-based fallback clients
                if (client.useWebPoTokens && poToken == null && sessionId != null) {
                    try {
                        poToken = poTokenGenerator.getWebClientPoToken(videoId, sessionId)
                    } catch (e: Exception) {
                        Timber.tag(logTag).w(e, "Lazy PoToken generation failed (non-fatal)")
                    }
                }

                val clientPoToken = if (client.useWebPoTokens) poToken?.playerRequestPoToken else null
                Timber.tag(logTag).v("Fetching player response for fallback client: ${client.clientName}")
                streamPlayerResponse =
                    getCachedPlayerResponse(videoId, playlistId, client, signatureTimestamp, clientPoToken).getOrNull()
            }

            // process current client response
            if (streamPlayerResponse?.playabilityStatus?.status == "OK") {
                Timber.tag(logTag).v("Player response status OK for client: ${if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")

                Timber.tag(logTag).v("✅ Player response received. hasStreamingData: ${streamPlayerResponse?.streamingData != null}")

                if (streamPlayerResponse?.streamingData == null) {
                    Timber.tag(logTag).e("❌ NO STREAMING DATA - YouTube blocked extraction or obfuscation changed.")
                    Timber.tag(logTag).e("Response status: ${streamPlayerResponse?.playabilityStatus?.status}")
                }

                // Splice NewPipe-derived stream URLs into the response by itag (Metrolist approach).
                // InnerTube WEB_REMIX CDN URLs 403 on some networks even with valid pot=; NewPipe's
                // own extraction pipeline produces working URLs.
                val baseResponse = streamPlayerResponse ?: continue
                val responseToUse = YouTube.newPipePlayer(videoId, baseResponse) ?: baseResponse

                format =
                    findFormat(
                        responseToUse,
                        audioQuality,
                        connectivityManager,
                    )

                if (format == null) {
                    Timber.tag(logTag).e("❌ No suitable format found for client: ${if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")
                    continue
                }

                Timber.tag(logTag).v("Format found: ${format.mimeType}, bitrate: ${format.bitrate}, itag: ${format.itag}")

                streamUrl = findUrlOrNull(format, videoId, responseToUse)
                if (streamUrl == null) {
                    Timber.tag(logTag).e("❌ Stream URL not found for format itag: ${format.itag}")
                    continue
                }
                Timber.tag(logTag).v("✅ Stream URL obtained successfully")

                // N-transform for web clients
                if (client.useWebPoTokens) {
                    try {
                        Timber.tag(logTag).d("Applying n-transform via CipherDeobfuscator")
                        var transformed = CipherDeobfuscator.transformNParamInUrl(streamUrl!!)
                        if (transformed == streamUrl && streamUrl!!.contains("n=")) {
                            Timber.tag(logTag).d("Cipher n-transform unavailable, trying EjsNTransformSolver")
                            transformed = EjsNTransformSolver.transformNParamInUrl(streamUrl!!)
                        }
                        if (transformed != streamUrl) {
                            streamUrl = transformed
                            Timber.tag(logTag).d("N-transform applied successfully")
                        }
                    } catch (e: Exception) {
                        Timber.tag(logTag).e(e, "N-transform failed: ${e.message}")
                    }
                }

                // Append pot= parameter if we have a streamingDataPoToken
                if (client.useWebPoTokens && poToken?.streamingDataPoToken != null) {
                    val separator = if ("?" in streamUrl!!) "&" else "?"
                    streamUrl = "${streamUrl}${separator}pot=${poToken.streamingDataPoToken}"
                    Timber.tag(logTag).d("Appended pot= parameter to stream URL")
                }

                streamExpiresInSeconds = streamPlayerResponse.streamingData?.expiresInSeconds
                if (streamExpiresInSeconds == null) {
                    Timber.tag(logTag).d("Stream expiration time not found")
                    continue
                }

                Timber.tag(logTag).v("Stream expires in: $streamExpiresInSeconds seconds")

                // MAIN_CLIENT (WEB_REMIX): skip HEAD validation like upstream Metrolist — its URLs
                // can 403 on HEAD yet serve fine on the byte-range GET ExoPlayer performs. A truly
                // rejected WEB_REMIX stream is recovered by MusicService's 403 handler via
                // markWebRemixFailed() + invalidatePlayerResponse(), which makes the retry fall
                // through to the validated fallback clients below.
                if (clientIndex == -1) {
                    successClient = client
                    break
                }

                if (clientIndex == STREAM_FALLBACK_CLIENTS.size - 1) {
                    /** skip [validateStatus] for last client */
                    Timber.tag(logTag).v("Using last fallback client without validation: ${STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")
                    successClient = client
                    break
                }

                if (validateStatus(streamUrl!!, client.streamHeaders())) {
                    // working stream found
                    Timber.tag(logTag).d("Stream validated successfully with client: ${client.clientName}")
                    successClient = client
                    break
                } else {
                    Timber.tag(logTag).d("Stream validation failed for client: ${client.clientName}")
                    // A failing web-based fallback can mean a stale/wrong cipher config — ask the
                    // cipher to re-fetch it (rate-limited internally, off this coroutine) so the
                    // next resolution recovers without an app restart.
                    if (client.useWebPoTokens || client.clientName.startsWith("WEB")) {
                        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                            runCatching { CipherDeobfuscator.onStreamRejected() }
                        }
                    }
                }
            } else {
                Timber.tag(logTag).d("Player response status not OK: ${streamPlayerResponse?.playabilityStatus?.status}, reason: ${streamPlayerResponse?.playabilityStatus?.reason}")
            }
        }

        if (streamPlayerResponse == null) {
            Timber.tag(logTag).e("Bad stream player response - all clients failed")
            throw Exception("Bad stream player response")
        }

        if (streamPlayerResponse.playabilityStatus.status != "OK") {
            val errorReason = streamPlayerResponse.playabilityStatus.reason
            Timber.tag(logTag).e("Playability status not OK: $errorReason")
            throw PlaybackException(
                errorReason,
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR
            )
        }

        if (streamExpiresInSeconds == null) {
            Timber.tag(logTag).e("Missing stream expire time")
            throw Exception("Missing stream expire time")
        }

        if (format == null) {
            Timber.tag(logTag).e("Could not find format")
            throw Exception("Could not find format")
        }

        if (streamUrl == null) {
            Timber.tag(logTag).e("Could not find stream url")
            throw Exception("Could not find stream url")
        }

        Timber.tag(logTag).v("Successfully obtained playback data with format: ${format.mimeType}, bitrate: ${format.bitrate}")
        PlaybackData(
            audioConfig,
            videoDetails,
            playbackTracking,
            format,
            streamUrl,
            streamExpiresInSeconds,
            streamClient = successClient?.clientName ?: "unknown",
            streamHeaders = successClient?.streamHeaders().orEmpty(),
        )
    }
}
    /**
     * Simple player response intended to use for metadata only.
     * Stream URLs of this response might not work so don't use them.
     */
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> {
        Timber.tag(logTag).d("Fetching metadata-only player response for videoId: $videoId using MAIN_CLIENT: ${MAIN_CLIENT.clientName}")
        return YouTube.player(videoId, playlistId, client = WEB_REMIX)
            .onSuccess { Timber.tag(logTag).v("Successfully fetched metadata") }
            .onFailure { Timber.tag(logTag).e(it, "Failed to fetch metadata") }
    }

    private fun findFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): PlayerResponse.StreamingData.Format? {
        Timber.tag(logTag).v("Finding format with audioQuality: $audioQuality, network metered: ${connectivityManager.isActiveNetworkMetered}")

        val adaptiveFormats = playerResponse.streamingData?.adaptiveFormats
            ?.filter { it.isAudio } ?: emptyList()

        // Si este dispositivo no soporta Opus (detectado automáticamente
        // en onPlayerError por ERROR_CODE_DECODER_QUERY_FAILED),
        // priorizamos AAC para evitar silencio/error (ZTE Blade V70, etc.)
        val forceAacFallback = appContext?.dataStore?.get(ForceAacFallbackKey, false) ?: false
        if (forceAacFallback) {
            Timber.tag(logTag).d("AAC fallback active — prioritizing AAC over Opus for compatibility")
        }

        val safeFormat = when {
            forceAacFallback -> adaptiveFormats.find { it.itag == 141 }
                ?: adaptiveFormats.find { it.itag == 140 }
                ?: adaptiveFormats.find { it.itag == 251 }
            audioQuality == AudioQuality.HIGH -> adaptiveFormats.find { it.itag == 141 }
                ?: adaptiveFormats.find { it.itag == 251 }
                ?: adaptiveFormats.find { it.itag == 140 }
            else -> adaptiveFormats.find { it.itag == 251 }
                ?: adaptiveFormats.find { it.itag == 140 }
        } ?: adaptiveFormats.maxByOrNull {
            it.bitrate * when (audioQuality) {
                AudioQuality.AUTO -> if (connectivityManager.isActiveNetworkMetered) -1 else 1
                AudioQuality.HIGH -> 1
                AudioQuality.LOW -> -1
            } + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0)
        }

        if (safeFormat != null) {
            Timber.tag(logTag).v("Selected format: ${safeFormat.mimeType}, bitrate: ${safeFormat.bitrate}, itag: ${safeFormat.itag}")
        } else {
            Timber.tag(logTag).d("No suitable audio format found")
        }

        return safeFormat
    }
    /**
     * Checks if the stream url returns a successful status.
     * If this returns true the url is likely to work.
     * If this returns false the url might cause an error during playback.
     */
    private fun validateStatus(url: String, requestHeaders: Map<String, String> = emptyMap()): Boolean {
        Timber.tag(logTag).d("Validating stream URL status")
        try {
            val requestBuilder = Request.Builder()
                .head()
                .url(url)
            requestHeaders.forEach { (name, value) -> requestBuilder.header(name, value) }
            validationClient.newCall(requestBuilder.build()).execute().use { response ->
                val isSuccessful = response.isSuccessful
                Timber.tag(logTag).d("Stream URL validation result: ${if (isSuccessful) "Success" else "Failed"} (${response.code})")
                return isSuccessful
            }
        } catch (e: Exception) {
            Timber.tag(logTag).e(e, "Stream URL validation failed with exception")
            reportException(e)
        }
        return false
    }

    /**
     * Plain client for stream validation — no MUSIC_CLIENT_HEADERS network interceptor (which
     * would append a second User-Agent) and no forced HTTP caching.
     */
    private val validationClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .proxy(YouTube.proxy)
            .build()
    }

    /**
     * Per-client headers required by the googlevideo CDN. Web-based URLs are rejected with 403
     * unless the correct Origin/Referer/User-Agent are sent when downloading the stream.
     */
    private fun YouTubeClient.streamHeaders(): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        put("Accept", "*/*")
        put("Accept-Language", "en-US,en;q=0.9")

        when {
            clientName == "WEB_REMIX" -> {
                put("Referer", "https://music.youtube.com/")
                put("Origin", "https://music.youtube.com")
            }
            clientName.startsWith("WEB") || clientName.startsWith("TVHTML5") -> {
                put("Referer", "https://www.youtube.com/")
                put("Origin", "https://www.youtube.com")
            }
        }
    }
    /**
     * Wrapper around the [NewPipeUtils.getSignatureTimestamp] function which reports exceptions
     */
    private fun getSignatureTimestampOrNull(
        videoId: String
    ): Int? {
        return NewPipeExtractor.getSignatureTimestamp(videoId)
            .onSuccess { Timber.tag(logTag).v("Signature timestamp obtained: $it") }
            .onFailure { Timber.tag(logTag).w(it, "Failed to get signature timestamp") }
            .getOrNull()
    }
    /**
     * Wrapper around the [NewPipeUtils.getStreamUrl] function which reports exceptions
     */
    private suspend fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
        playerResponse: PlayerResponse? = null,
    ): String? {
        Timber.tag(logTag).d("Finding stream URL for format: ${format.mimeType}, videoId: $videoId")

        if (!format.url.isNullOrEmpty()) {
            Timber.tag(logTag).d("Using URL from format directly")
            return format.url
        }

        val signatureCipher = format.signatureCipher

        // Step 2: signatureCipher via CipherDeobfuscator (WebView)
        if (signatureCipher != null) {
            Timber.tag(logTag).d("Format has signatureCipher, using CipherDeobfuscator")
            val customDeobfuscatedUrl = CipherDeobfuscator.deobfuscateStreamUrl(signatureCipher, videoId)
            if (customDeobfuscatedUrl != null) {
                Timber.tag(logTag).d("Stream URL obtained via CipherDeobfuscator")
                return customDeobfuscatedUrl
            }
            Timber.tag(logTag).d("CipherDeobfuscator deobfuscation failed")
        }

        if (signatureCipher != null) {
            Timber.tag(logTag).d("Format has signatureCipher, using NewPipeExtractor")
            val deobfuscatedUrl = NewPipeExtractor.getStreamUrl(format, videoId)
            if (deobfuscatedUrl != null) {
                Timber.tag(logTag).d("Stream URL obtained via NewPipeExtractor")
                return deobfuscatedUrl
            }
            Timber.tag(logTag).d("NewPipeExtractor deobfuscation failed")
        }

        Timber.tag(logTag).d("Trying StreamInfo fallback for URL")
        val streamUrls = NewPipeExtractor.getNewPipeStreamUrls(videoId)
        if (streamUrls.isNotEmpty()) {
            val streamUrl = streamUrls.find { it.first == format.itag }?.second
            if (streamUrl != null) {
                Timber.tag(logTag).d("Stream URL obtained from StreamInfo")
                return streamUrl
            }
            val audioStream = streamUrls.find { urlPair ->
                playerResponse?.streamingData?.adaptiveFormats?.any {
                    it.itag == urlPair.first && it.isAudio
                } == true
            }?.second
            if (audioStream != null) {
                Timber.tag(logTag).d("Audio stream URL obtained from StreamInfo (different itag)")
                return audioStream
            }
        }

        Timber.tag(logTag).e("Failed to get stream URL")
        return null
    }

    private fun parseSignatureCipher(sigCipher: String): String? {
        return try {
            val params = sigCipher.split('&').associate {
                val parts = it.split('=', limit = 2)
                if (parts.size == 2) {
                    parts[0] to URLDecoder.decode(parts[1], "UTF-8")
                } else {
                    parts[0] to ""
                }
            }
            params["url"]?.let { "$it&${params["s"]?.let { s -> "sig=$s" } ?: ""}" }
        } catch (e: Exception) {
             Timber.tag(logTag).e(e, "Failed to parse signature cipher")
             null
        }
    }

    private val PIPED_INSTANCES = listOf(
        "https://pipedapi.kavin.rocks/streams/",
        "https://pipedapi.garudalinux.org/streams/",
        "https://api.pipepipe.pw/streams/",
        "https://pipedapi.librex.me/streams/",
        "https://vid.puffyan.us/api/v1/streams/"  // Backup
    )

    private suspend fun getPipedStreamUrl(videoId: String): String? {
        for (baseUrl in PIPED_INSTANCES) {
            val url = "$baseUrl$videoId"
            try {
                Timber.tag("PipedDebug").d("🔍 $url")

                val client = httpClient!!.newBuilder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                Timber.tag("PipedDebug").d("$baseUrl → ${response.code}")

                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body == null) continue

                    // Check for HTML response (invalid)
                    if (body.trim().startsWith("<!DOCTYPE") || body.contains("<html", true)) {
                        Timber.tag("PipedDebug").w("$baseUrl → HTML instead of JSON")
                        continue
                    }

                    val json = JSONObject(body)
                    val audioStreams = json.optJSONArray("audioStreams")
                    if (audioStreams != null && audioStreams.length() > 0) {
                        val stream = audioStreams.getJSONObject(0)
                        val quality = stream.optString("quality", "unknown")
                        Timber.tag(logTag).i("✅ Piped $quality from $baseUrl")
                        return stream.getString("url")
                    }
                }
            } catch (e: Exception) {
                Timber.tag("PipedDebug").w("$baseUrl → ${e.message}")
            }
            delay(150) // Rate limit
        }
        return null
    }
    private fun refreshCookiesIfNeeded(response: Response): Boolean {
        if (response.code == 401 || response.code == 403) {
            Timber.tag(logTag).w("Cookies refresh needed (401/403)")
            return true
        }
        return false
    }

    fun cleanStreamUrl(url: String): String {
        return url
            .replace("\\s+".toRegex(), "")  // Quita espacios
            .replace("%0A", "")             // Quita newlines
            .replace("\n", "").replace("\r", "") // CR/LF
            .replace("\t", "")              // Tabs
            .trim()
    }

    fun getValidStreamUrl(format: PlayerResponse.StreamingData.Format): String {
        val url = format.url
        Timber.tag(logTag).v("Raw URL: $url")

        val validUrl = if (url.isValidUrl()) {
            url!!
        } else {
            // FIX: reconstruye con signatureCipher si existe
            format.signatureCipher?.let { parseSignatureCipher(it) } ?: url ?: ""
        }
        return cleanStreamUrl(validUrl)
    }

    private fun String?.isValidUrl(): Boolean {
        if (this == null) return false
        return this.contains("googlevideo.com/videoplayback?") &&
                this.length > 100 && // sanity check
                !this.contains("undefined") &&
                URI.create(this).isAbsolute
    }
}
