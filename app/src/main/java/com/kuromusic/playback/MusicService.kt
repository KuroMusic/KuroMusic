@file:Suppress("DEPRECATION")

package com.kuromusic.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.SQLException
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.net.ConnectivityManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_POSITION_DISCONTINUITY
import androidx.media3.common.Player.EVENT_TIMELINE_CHANGED
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Player.STATE_IDLE
import androidx.media3.common.Timeline
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import androidx.media3.datasource.cache.SimpleCache

import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import android.widget.Toast
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.kuromusic.innertube.YouTube
import com.kuromusic.innertube.models.SongItem
import com.kuromusic.innertube.models.WatchEndpoint
import com.kuromusic.jossredconnect.JossRedClient
import com.kuromusic.MainActivity
import com.kuromusic.BuildConfig
import com.kuromusic.R
import com.kuromusic.constants.AudioQualityKey
import com.kuromusic.constants.AutoLoadMoreKey
import com.kuromusic.constants.AutoSkipNextOnErrorKey
import com.kuromusic.constants.ForceAacFallbackKey
import com.kuromusic.constants.DisableLoadMoreWhenRepeatAllKey
import com.kuromusic.constants.DiscordUseDetailsKey
import com.kuromusic.constants.EnableDiscordRPCKey
import com.kuromusic.constants.EnableLastFMScrobblingKey
import com.kuromusic.constants.LastFMEnabledKey
import com.kuromusic.constants.LastFMSessionKey
import com.kuromusic.constants.LastFMUsernameKey
import com.kuromusic.constants.LastFMUseNowPlaying
import com.kuromusic.constants.ScrobbleDelayPercentKey
import com.kuromusic.constants.ScrobbleMinSongDurationKey
import com.kuromusic.constants.ScrobbleDelaySecondsKey
import com.kuromusic.constants.HideExplicitKey
import com.kuromusic.constants.HistoryDuration
import com.kuromusic.constants.MediaSessionConstants.CommandToggleLike
import com.kuromusic.constants.MediaSessionConstants.CommandToggleRepeatMode
import com.kuromusic.constants.MediaSessionConstants.CommandToggleShuffle
import com.kuromusic.constants.PauseListenHistoryKey
import com.kuromusic.constants.PersistentQueueKey
import com.kuromusic.constants.PlayerVolumeKey
import com.kuromusic.constants.RepeatModeKey
import com.kuromusic.constants.ShowLyricsKey
import com.kuromusic.constants.SimilarContent
import com.kuromusic.constants.SkipSilenceKey
import com.kuromusic.constants.CrossfadeDurationKey
import com.kuromusic.constants.ProfileModeKey
import com.kuromusic.constants.SoundProfileKey
import com.kuromusic.db.MusicDatabase
import com.kuromusic.db.entities.Event
import com.kuromusic.db.entities.FormatEntity
import com.kuromusic.db.entities.LyricsEntity
import com.kuromusic.db.entities.RelatedSongMap
import com.kuromusic.di.PlayerCache
import com.kuromusic.extensions.SilentHandler
import com.kuromusic.extensions.collect
import com.kuromusic.extensions.collectLatest
import com.kuromusic.extensions.currentMetadata
import com.kuromusic.extensions.findNextMediaItemById
import com.kuromusic.extensions.mediaItems
import com.kuromusic.extensions.metadata
import com.kuromusic.extensions.toMediaItem
import com.kuromusic.extensions.toQueue
import com.kuromusic.lyrics.LyricsHelper
import com.kuromusic.models.PersistPlayerState
import com.kuromusic.models.PersistQueue
import com.kuromusic.models.toMediaMetadata
import com.kuromusic.playback.queues.EmptyQueue
import com.kuromusic.playback.AudioState
import com.kuromusic.playback.AudioStateHolder
import com.kuromusic.playback.GainAudioProcessor
import com.kuromusic.playback.SoundProfile
import com.kuromusic.playback.AutoSoundProfileEngine
import com.kuromusic.playback.DeviceAudioStateHolder
import com.kuromusic.playback.DeviceCompensationAudioProcessor
import com.kuromusic.playback.GenreDetector
import com.kuromusic.playback.LoudnessAnalyzer
import com.kuromusic.playback.LoudnessTap
import com.kuromusic.playback.ProfileCalibrationSystem
import com.kuromusic.playback.ProfileMode
import com.kuromusic.playback.SoundProfileAudioProcessor
import com.kuromusic.playback.SoundProfileStateHolder
import com.kuromusic.playback.queues.Queue
import com.kuromusic.playback.queues.YouTubeQueue
import com.kuromusic.playback.queues.filterExplicit
import com.kuromusic.utils.CoilBitmapLoader
import com.kuromusic.discord.DiscordRpcManager
import com.kuromusic.utils.DiscordRPC
import com.kuromusic.utils.NetworkConnectivityObserver
import com.kuromusic.utils.ScrobbleManager
import com.kuromusic.lastfm.LastFM
import com.kuromusic.utils.YTPlayerUtils
import com.kuromusic.utils.dataStore
import com.kuromusic.utils.enumPreference
import com.kuromusic.utils.get
import com.kuromusic.utils.reportException
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@AndroidEntryPoint
class MusicService :
    MediaLibraryService(),
    Player.Listener,
    PlaybackStatsListener.Callback {
    @Inject
    lateinit var database: MusicDatabase

    @Inject
    lateinit var lyricsHelper: LyricsHelper

    @Inject
    lateinit var mediaLibrarySessionCallback: MediaLibrarySessionCallback

    @Inject
    lateinit var equalizerService: com.kuromusic.eq.EqualizerService

    @Inject
    lateinit var eqProfileRepository: com.kuromusic.eq.data.EQProfileRepository

    // Media3 manages focus automatically when handleAudioFocus is true
    private var hasAudioFocus = false
    
    // SupervisorJob prevents one failure from cancelling others
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)
    
    private val binder = MusicBinder()

    // Thread-safe LRU cache for stream URLs. Evicts oldest entry when full.
    private val playbackCache = android.util.LruCache<String, YTPlayerUtils.PlaybackData>(500)
    private val ioJob = SupervisorJob()
    private val ioScope = CoroutineScope(ioJob + Dispatchers.IO)
    private var audioDeviceCallback: android.media.AudioDeviceCallback? = null


    private lateinit var connectivityManager: ConnectivityManager
    lateinit var connectivityObserver: NetworkConnectivityObserver
    val waitingForNetworkConnection = MutableStateFlow(false)
    val isNetworkConnected = MutableStateFlow(false)

    private val audioQuality by enumPreference(
        this,
        AudioQualityKey,
        com.kuromusic.constants.AudioQuality.AUTO
    )

    // Cached preference flows — avoids blocking Main thread with DataStore.get() runBlocking
    private val persistentQueueEnabled = dataStore.data
        .map { it[PersistentQueueKey] ?: true }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, true)

    private val autoLoadMoreEnabled = dataStore.data
        .map { it[AutoLoadMoreKey] ?: true }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, true)

    private val hideExplicitEnabled = dataStore.data
        .map { it[HideExplicitKey] ?: false }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val disableLoadMoreWhenRepeatAll = dataStore.data
        .map { it[DisableLoadMoreWhenRepeatAllKey] ?: false }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val discordUseDetails = dataStore.data
        .map { it[DiscordUseDetailsKey] ?: false }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val autoSkipNextOnError = dataStore.data
        .map { it[AutoSkipNextOnErrorKey] ?: false }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val pauseListenHistory = dataStore.data
        .map { it[PauseListenHistoryKey] ?: false }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    private var currentQueue: Queue = EmptyQueue
    var queueTitle: String? = null

    val currentMediaMetadata = MutableStateFlow<com.kuromusic.models.MediaMetadata?>(null)
    private val currentSong =
        currentMediaMetadata
            .flatMapLatest { mediaMetadata ->
                database.songDao.song(mediaMetadata?.id)
            }.stateIn(scope, SharingStarted.Lazily, null)

    // Cached preference to avoid blocking reads
    private var isJossRedEnabled = false
    private var isJossRedFallbackEnabled = false

    // Preference Keys
    private val JossRedMultimediaKey = booleanPreferencesKey("JossRedMultimedia")

    private val currentFormat =
        currentMediaMetadata.flatMapLatest { mediaMetadata ->
            database.format(mediaMetadata?.id)
        }

    val playerVolume = MutableStateFlow(dataStore.get(PlayerVolumeKey, 1f).coerceIn(0f, 1f))

    lateinit var sleepTimer: SleepTimer

    @Inject
    @PlayerCache
    lateinit var playerCache: SimpleCache


    lateinit var player: ExoPlayer
    val visualizerEngine = VisualizerEngine()
    private lateinit var mediaSession: MediaLibrarySession

    private val stateHolder = AudioStateHolder()
    private val gainProcessor = GainAudioProcessor(stateHolder)
    private val soundProfileHolder = SoundProfileStateHolder()
    private val deviceStateHolder = DeviceAudioStateHolder(this)
    private val soundProfileProcessor = SoundProfileAudioProcessor(soundProfileHolder, deviceStateHolder)
    private val deviceProcessor = DeviceCompensationAudioProcessor(deviceStateHolder)
    private val loudnessAnalyzer = LoudnessAnalyzer()
    private val calibrationSystem = ProfileCalibrationSystem(loudnessAnalyzer, soundProfileHolder)

    private var discordRpc: DiscordRPC? = null
    private var lastPlaybackSpeed = 1.0f
    private var discordUpdateJob: Job? = null

    private var scrobbleManager: ScrobbleManager? = null

    private var crossfadeDurationSec = 0
    private var crossfadeJob: Job? = null

    val automixItems = MutableStateFlow<List<MediaItem>>(emptyList())

    private var consecutivePlaybackErr = 0

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        
        // Initialize YTPlayerUtils cache
        YTPlayerUtils.initialize(applicationContext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.music_player),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.setShowBadge(true)
            channel.setSound(null, null)
            channel.enableVibration(false)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val placeholderNotification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("")
            .setSmallIcon(R.drawable.kuromusic_monochrome)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        startForeground(NOTIFICATION_ID, placeholderNotification)
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this,
                { NOTIFICATION_ID },
                CHANNEL_ID,
                R.string.music_player
            )
                .apply {
                    setSmallIcon(R.drawable.kuromusic_monochrome)
                },
        )
        // 1. INICIALIZACIÓN INMEDIATA (Orden Crítico)
        // 1. INICIALIZACIÓN INMEDIATA (Orden Crítico)
        val eqProcessor = com.kuromusic.eq.audio.CustomEqualizerAudioProcessor()
        equalizerService.addAudioProcessor(eqProcessor)

        val exoPlayer = androidx.media3.exoplayer.ExoPlayer.Builder(this)
            .setRenderersFactory(createRenderersFactory(eqProcessor))
            .setMediaSourceFactory(createMediaSourceFactory())
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        32000, // Min buffer 32s
                        64000, // Max buffer 64s
                        1000, // Buffer for playback 1s (Reduced for cold start)
                        1500 // Rebuffer 1.5s
                    ).build()
            )
            .build()

        exoPlayer.addListener(this@MusicService)
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Timber.tag(TAG).e(error, "Error de reproducción: ${error.message}")
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
                    // Notificar error de red o link expirado
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this@MusicService, R.string.error_no_internet, Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
        sleepTimer = SleepTimer(scope, exoPlayer)
        exoPlayer.addListener(sleepTimer)
        exoPlayer.addAnalyticsListener(PlaybackStatsListener(false, this@MusicService))
        
        player = exoPlayer
        visualizerEngine.attach(exoPlayer.audioSessionId)

        mediaLibrarySessionCallback.apply {
            toggleLike = ::toggleLike
            toggleLibrary = ::toggleLibrary
        }

        // 3. CONFIGURAR SESIÓN
        mediaSession =
            MediaLibrarySession
                .Builder(this, player, mediaLibrarySessionCallback)
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).setBitmapLoader(CoilBitmapLoader(this, scope))
                .build()

        player.repeatMode = dataStore.get(RepeatModeKey, REPEAT_MODE_OFF)

        // Keep a connected controller so that notification works (Async & Safe)
        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({
            try {
                // Safe get() guaranteed to be done here
                controllerFuture.get()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error initializing self-controller")
            }
        }, ContextCompat.getMainExecutor(this))

        connectivityManager = getSystemService()!!
        connectivityObserver = NetworkConnectivityObserver(this)

        deviceStateHolder.detect()

        LoudnessTap.attach(loudnessAnalyzer)

        soundProfileHolder.applyAutoGain(SoundProfile.WARM, 0.2f)
        soundProfileHolder.applyAutoGain(SoundProfile.BASS, 0.3f)
        soundProfileHolder.applyAutoGain(SoundProfile.LOFI, 0.5f)
        soundProfileHolder.applyAutoGain(SoundProfile.STUDIO, 0.2f)

        scope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(30_000L)
                calibrationSystem.calibrate()
            }
        }

        scope.launch {
            eqProfileRepository.activeProfile.collect { profile ->
                if (profile != null) {
                    equalizerService.applyProfile(profile)
                    if (player.playbackState == Player.STATE_READY && player.isPlaying) {
                        player.seekTo(player.currentPosition)
                    }
                } else {
                    equalizerService.disable()
                    if (player.playbackState == Player.STATE_READY && player.isPlaying) {
                        player.seekTo(player.currentPosition)
                    }
                }
            }
        }

        val audioManager = getSystemService(AudioManager::class.java)
        val deviceCallback = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<android.media.AudioDeviceInfo>) {
                deviceStateHolder.refresh()
                deviceProcessor.updateProfile()
                if (soundProfileHolder.state.mode == ProfileMode.AUTO) {
                    resolveAutoProfile()
                }
                val hasWiredHeadphones = addedDevices.any { info ->
                    info.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    info.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    info.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    info.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
                if (hasWiredHeadphones && !player.isPlaying && player.mediaItemCount > 0) {
                    player.play()
                }
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<android.media.AudioDeviceInfo>) {
                deviceStateHolder.refresh()
                deviceProcessor.updateProfile()
                if (soundProfileHolder.state.mode == ProfileMode.AUTO) {
                    resolveAutoProfile()
                }
            }
        }
        audioDeviceCallback = deviceCallback
        audioManager?.registerAudioDeviceCallback(deviceCallback, android.os.Handler(android.os.Looper.getMainLooper()))

        // Auto-play when headphones already connected on app start
        scope.launch {
            kotlinx.coroutines.delay(2000L)
            val audioDeviceInfo = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val hasHeadphonesConnected = audioDeviceInfo?.any { info ->
                info.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                info.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                info.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                info.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            } == true
            if (hasHeadphonesConnected && !player.isPlaying && player.mediaItemCount > 0) {
                player.play()
            }
        }

        // Observar conectividad de red
        scope.launch {
            connectivityObserver.networkStatus.collect { isConnected ->
                isNetworkConnected.value = isConnected
                if (isConnected && waitingForNetworkConnection.value) {
                    // Reintentar reproducción cuando vuelve la conexión
                    waitingForNetworkConnection.value = false
                    if (::player.isInitialized) {
                        if (player.currentMediaItem != null && player.playWhenReady) {
                            player.prepare()
                            player.play()
                        }
                    }
                }
            }
        }

        // Observe JossRedMultimedia preference
        scope.launch {
            dataStore.data.map { it[JossRedMultimediaKey] ?: false }.distinctUntilChanged().collect {
                isJossRedEnabled = it
            }
        }

        playerVolume.collectLatest(scope) {
            if (::player.isInitialized) {
                player.volume = it
            }
        }

        playerVolume.debounce(1000).collect(scope) { volume ->
            dataStore.edit { settings ->
                settings[PlayerVolumeKey] = volume
            }
        }

        currentSong.debounce(1000).collect(scope) { song ->
            updateNotification()
            if (::player.isInitialized) {
                if (song != null && player.playWhenReady && player.playbackState == Player.STATE_READY) {
                    discordRpc?.updateSong(song, player.currentPosition, player.playbackParameters.speed, discordUseDetails.value)
                    // Periodic position update every 15s while playing
                    discordUpdateJob?.cancel()
                    discordUpdateJob = scope.launch {
                        while (isActive) {
                            delay(15_000)
                            if (::player.isInitialized && player.isPlaying) {
                                currentSong.value?.let { s ->
                                    discordRpc?.updateSong(s, player.currentPosition, player.playbackParameters.speed, discordUseDetails.value)
                                }
                            }
                        }
                    }
                } else {
                    discordUpdateJob?.cancel()
                    discordRpc?.stopActivity()
                }
            }
            
            // 🚀 CONCURRENT PRE-FETCH (Next 3 songs)
            if (::player.isInitialized) {
                val currentIndex = player.currentMediaItemIndex
                if (currentIndex != C.INDEX_UNSET) {
                    // Launch in IO scope with SupervisorJob
                    scope.launch(Dispatchers.IO) {
                        try {
                            val queueSize = withContext(Dispatchers.Main) { player.mediaItemCount }
                            if (queueSize <= 0) return@launch
                            val nextSongs = withContext(Dispatchers.Main) {
                                (1..3).mapNotNull { offset ->
                                    val index = (currentIndex + offset) % queueSize
                                    if (index != currentIndex) player.getMediaItemAt(index) else null
                                }
                            }
                            
                            nextSongs.forEach { item ->
                                launch { // Concurrent launch
                                    try {
                                        val nextId = item.mediaId
                                        // Skip local files — no YouTube resolution needed
                                        if (nextId.startsWith("content://") || nextId.startsWith("file://")) return@launch
                                        Timber.d("🚀 Pre-fetching next song ($nextId) in background")
                                        val result = YTPlayerUtils.playerResponseForPlayback(
                                            videoId = nextId,
                                            audioQuality = audioQuality,
                                            connectivityManager = connectivityManager
                                        )
                                        result.getOrNull()?.let { playbackCache.put(nextId, it) }
                                    } catch (e: Exception) {
                                        Timber.e(e, "❌ Pre-fetch failed for ${item.mediaId}")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "❌ Pre-fetch supervisor error")
                        }
                    }
                }
            }
        }

        combine(
            currentMediaMetadata.distinctUntilChangedBy { it?.id },
            dataStore.data.map { it[ShowLyricsKey] ?: false }.distinctUntilChanged(),
        ) { mediaMetadata, showLyrics ->
            mediaMetadata to showLyrics
        }.collectLatest(scope) { (mediaMetadata, showLyrics) ->
            if (showLyrics && mediaMetadata != null && database.lyrics(mediaMetadata.id)
                    .first() == null
            ) {
                val lyrics = lyricsHelper.getLyrics(mediaMetadata)
                database.query {
                    upsert(
                        LyricsEntity(
                            id = mediaMetadata.id,
                            lyrics = lyrics,
                        ),
                    )
                }
            }
        }

        dataStore.data
            .map { it[SkipSilenceKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) {
                if (::player.isInitialized) {
                    player.skipSilenceEnabled = it
                }
            }

        dataStore.data
            .map { it[CrossfadeDurationKey] ?: 0 }
            .distinctUntilChanged()
            .collectLatest(scope) {
                crossfadeDurationSec = it
            }

        dataStore.data
            .map { it[ProfileModeKey]?.let(ProfileMode::valueOf) ?: ProfileMode.MANUAL }
            .distinctUntilChanged()
            .collectLatest(scope) { mode ->
                if (mode == ProfileMode.MANUAL) {
                    val savedProfile = dataStore.data.first()[SoundProfileKey]
                        ?.let(SoundProfile::valueOf) ?: SoundProfile.CLEAN
                    soundProfileHolder.setManual(savedProfile)
                } else {
                    soundProfileHolder.setAuto()
                    resolveAutoProfile()
                }
            }

        dataStore.data
            .map { it[SoundProfileKey]?.let(SoundProfile::valueOf) ?: SoundProfile.CLEAN }
            .distinctUntilChanged()
            .collectLatest(scope) { profile ->
                if (soundProfileHolder.state.mode == ProfileMode.MANUAL) {
                    soundProfileHolder.setManual(profile)
                }
            }

        currentFormat.collectLatest(scope) { format ->
            val loudnessDb = format?.loudnessDb?.toFloat()
            stateHolder.update(
                AudioState(
                    loudnessDb = loudnessDb,
                    gainDb = 0f,
                    targetLufs = -14f
                )
            )
        }

        // Inicializar Discord RPC (OAuth2 PKCE)
        DiscordRpcManager.init(this)
        discordRpc = DiscordRPC()

        // Last.fm Scrobbling
        scope.launch {
            val lastfmApiKey = BuildConfig.LASTFM_API_KEY
            val lastfmSecret = BuildConfig.LASTFM_SECRET
            if (lastfmApiKey.isNotEmpty() && lastfmSecret.isNotEmpty()) {
                LastFM.initialize(lastfmApiKey, lastfmSecret)
                val savedSession = dataStore.get(LastFMSessionKey, "")
                if (savedSession.isNotEmpty()) {
                    LastFM.sessionKey = savedSession
                }
            }
        }
        dataStore.data
            .map { it[EnableLastFMScrobblingKey] ?: false }
            .debounce(300)
            .distinctUntilChanged()
            .collect(scope) { enabled ->
                if (enabled && LastFM.isInitialized() && scrobbleManager == null) {
                    val delayPercent = dataStore.get(ScrobbleDelayPercentKey, LastFM.DEFAULT_SCROBBLE_DELAY_PERCENT)
                    val minSongDuration = dataStore.get(ScrobbleMinSongDurationKey, LastFM.DEFAULT_SCROBBLE_MIN_SONG_DURATION)
                    val delaySeconds = dataStore.get(ScrobbleDelaySecondsKey, LastFM.DEFAULT_SCROBBLE_DELAY_SECONDS)
                    scrobbleManager = ScrobbleManager(
                        scope,
                        minSongDuration = minSongDuration,
                        scrobbleDelayPercent = delayPercent,
                        scrobbleDelaySeconds = delaySeconds,
                        apiKey = BuildConfig.LASTFM_API_KEY,
                        apiSecret = BuildConfig.LASTFM_SECRET
                    )
                    scrobbleManager?.useNowPlaying = dataStore.get(LastFMUseNowPlaying, false)
                } else if (!enabled && scrobbleManager != null) {
                    scrobbleManager?.destroy()
                    scrobbleManager = null
                }
            }
        dataStore.data
            .map { it[LastFMUseNowPlaying] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) {
                scrobbleManager?.useNowPlaying = it
            }
        dataStore.data
            .map { prefs ->
                Triple(
                    prefs[ScrobbleDelayPercentKey] ?: LastFM.DEFAULT_SCROBBLE_DELAY_PERCENT,
                    prefs[ScrobbleMinSongDurationKey] ?: LastFM.DEFAULT_SCROBBLE_MIN_SONG_DURATION,
                    prefs[ScrobbleDelaySecondsKey] ?: LastFM.DEFAULT_SCROBBLE_DELAY_SECONDS,
                )
            }.distinctUntilChanged()
            .collect(scope) { (delayPercent, minSongDuration, delaySeconds) ->
                scrobbleManager?.let {
                    it.scrobbleDelayPercent = delayPercent
                    it.minSongDuration = minSongDuration
                    it.scrobbleDelaySeconds = delaySeconds
                }
            }

        // 4. CARGA ASÍNCRONA (Punto 3 de tu plan)
        scope.launch(Dispatchers.IO) {
            if (persistentQueueEnabled.value) {
                runCatching {
                    filesDir.resolve(PERSISTENT_QUEUE_FILE).inputStream().use { fis ->
                        ObjectInputStream(fis).use { oos ->
                            oos.readObject() as PersistQueue
                        }
                    }
                }.onSuccess { queue ->
                    withContext(Dispatchers.Main) {
                        if (::player.isInitialized) {
                            val restoredQueue = queue.toQueue()
                            playQueue(
                                queue = restoredQueue,
                                playWhenReady = false,
                            )
                        }
                    }
                }

                runCatching {
                    filesDir.resolve(PERSISTENT_AUTOMIX_FILE).inputStream().use { fis ->
                        ObjectInputStream(fis).use { oos ->
                            oos.readObject() as PersistQueue
                        }
                    }
                }.onSuccess { queue ->
                    automixItems.value = queue.items.map { it.toMediaItem() }
                }

                // Restaurar estado del reproductor
                runCatching {
                    filesDir.resolve(PERSISTENT_PLAYER_STATE_FILE).inputStream().use { fis ->
                        ObjectInputStream(fis).use { oos ->
                            oos.readObject() as PersistPlayerState
                        }
                    }
                }.onSuccess { playerState ->
                    // Restaurar configuración del reproductor después de cargar la cola
                    withContext(Dispatchers.Main) {
                        delay(1000) // Esperar a que la cola se cargue
                        if (::player.isInitialized) {
                            player.repeatMode = playerState.repeatMode
                            player.shuffleModeEnabled = playerState.shuffleModeEnabled
                            player.volume = playerState.volume

                            // Restaurar posición si sigue siendo válida
                            if (playerState.currentMediaItemIndex < player.mediaItemCount) {
                                player.seekTo(playerState.currentMediaItemIndex, playerState.currentPosition)
                            }
                        }
                    }
                }
            }
        }

        // Guardar cola periódicamente para prevenir pérdida por crash o force kill
        scope.launch {
            while (isActive) {
                delay(if (player.isPlaying) 15.seconds else 60.seconds)
                if (persistentQueueEnabled.value) {
                    saveQueueToDisk()
                }
            }
        }
    }


    private fun waitOnNetworkError() {
        waitingForNetworkConnection.value = true
    }

    private fun skipOnError() {
        /**
         * Auto skip to the next media item on error.
         *
         * To prevent a "runaway diesel engine" scenario, force the user to take action after
         * too many errors come up too quickly. Pause to show player "stopped" state
         */
        consecutivePlaybackErr += 2
        val nextWindowIndex = player.nextMediaItemIndex

        if (consecutivePlaybackErr <= MAX_CONSECUTIVE_ERR && nextWindowIndex != C.INDEX_UNSET) {
            player.seekTo(nextWindowIndex, C.TIME_UNSET)
            player.prepare()
            player.play()
            return
        }

        player.pause()
        consecutivePlaybackErr = 0
    }

    private fun stopOnError() {
        player.pause()
    }

    private fun resolveAutoProfile() {
        val title = player.currentMediaItem?.metadata?.title?.toString() ?: ""
        val genre = GenreDetector.detect(title)
        val device = deviceStateHolder.device
        val resolved = AutoSoundProfileEngine.resolve(device, genre)
        soundProfileHolder.update(resolved)
    }

    private fun updateNotification() {
        mediaSession.setCustomLayout(
            listOf(
                CommandButton
                    .Builder()
                    .setDisplayName(
                        getString(
                            if (currentSong.value?.song?.liked ==
                                true
                            ) {
                                R.string.action_remove_like
                            } else {
                                R.string.action_like
                            },
                        ),
                    )
                    .setIconResId(if (currentSong.value?.song?.liked == true) R.drawable.favorite else R.drawable.favorite_border)
                    .setSessionCommand(CommandToggleLike)
                    .setEnabled(currentSong.value != null)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(
                        getString(
                            when (player.repeatMode) {
                                REPEAT_MODE_OFF -> R.string.repeat_mode_off
                                REPEAT_MODE_ONE -> R.string.repeat_mode_one
                                REPEAT_MODE_ALL -> R.string.repeat_mode_all
                                else -> throw IllegalStateException()
                            },
                        ),
                    ).setIconResId(
                        when (player.repeatMode) {
                            REPEAT_MODE_OFF -> R.drawable.repeat
                            REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                            REPEAT_MODE_ALL -> R.drawable.repeat_on
                            else -> throw IllegalStateException()
                        },
                    ).setSessionCommand(CommandToggleRepeatMode)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(getString(if (player.shuffleModeEnabled) R.string.action_shuffle_off else R.string.action_shuffle_on))
                    .setIconResId(if (player.shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle)
                    .setSessionCommand(CommandToggleShuffle)
                    .build(),
            ),
        )
    }

    private suspend fun recoverSong(
        mediaId: String,
        playbackData: YTPlayerUtils.PlaybackData? = null
    ) {
        if (mediaId.startsWith("content://") || mediaId.startsWith("file://")) return
                                val song = database.songDao.song(mediaId).first()
        val mediaMetadata = withContext(Dispatchers.Main.immediate) {
            player.findNextMediaItemById(mediaId)?.metadata
        } ?: return
        val duration = song?.song?.duration?.takeIf { it != -1 }
            ?: mediaMetadata.duration.takeIf { it != -1 }
            ?: (playbackData?.videoDetails ?: YTPlayerUtils.playerResponseForMetadata(mediaId)
                .getOrNull()?.videoDetails)?.lengthSeconds?.toInt()
            ?: -1
        database.query {
            if (song == null) insert(mediaMetadata.copy(duration = duration))
            else if (song.song.duration == -1) update(song.song.copy(duration = duration))
        }
        if (!database.songDao.hasRelatedSongs(mediaId)) {
            val relatedEndpoint =
                YouTube.next(WatchEndpoint(videoId = mediaId)).getOrNull()?.relatedEndpoint
                    ?: return
            val relatedPage = YouTube.related(relatedEndpoint).getOrNull() ?: return
            database.query {
                relatedPage.songs
                    .map(SongItem::toMediaMetadata)
                    .onEach(::insert)
                    .map {
                        RelatedSongMap(
                            songId = mediaId,
                            relatedSongId = it.id
                        )
                    }
                    .forEach(::insert)
            }
        }
    }

    fun playQueue(
        queue: Queue,
        playWhenReady: Boolean = true,
    ) {
        currentQueue = queue
        queueTitle = null
        player.shuffleModeEnabled = false
        if (queue.preloadItem != null) {
            player.setMediaItem(queue.preloadItem!!.toMediaItem())
            player.prepare()
            player.playWhenReady = playWhenReady
        }
        scope.launch(SilentHandler) {
            val initialStatus =
                withContext(Dispatchers.IO) {
                    queue.getInitialStatus().filterExplicit(hideExplicitEnabled.value)
                }
            if (queue.preloadItem != null && player.playbackState == STATE_IDLE) return@launch
            if (initialStatus.title != null) {
                queueTitle = initialStatus.title
            }
            if (initialStatus.items.isEmpty()) return@launch
            if (queue.preloadItem != null) {
                // Pre-resolve the first item
                val firstItem = initialStatus.items.getOrNull(initialStatus.mediaItemIndex)
                if (firstItem != null) {
                    preparePlayback(firstItem.mediaId)
                }
                
                withContext(Dispatchers.Main) {
                    player.addMediaItems(
                        0,
                        initialStatus.items.subList(0, initialStatus.mediaItemIndex)
                    )
                    player.addMediaItems(
                        initialStatus.items.subList(
                            initialStatus.mediaItemIndex + 1,
                            initialStatus.items.size
                        )
                    )
                }
            } else {
                 val startIndex = if (initialStatus.mediaItemIndex > 0) initialStatus.mediaItemIndex else 0
                 
                 // Pre-resolve the start item
                 val startItem = initialStatus.items.getOrNull(startIndex)
                 if (startItem != null) {
                     preparePlayback(startItem.mediaId)
                 }

                 withContext(Dispatchers.Main) {
                    player.setMediaItems(
                        initialStatus.items,
                        startIndex,
                        initialStatus.position,
                    )
                    player.prepare()
                    player.playWhenReady = playWhenReady
                }
            }
        }
    }

    fun startRadioSeamlessly() {
        val currentMediaMetadata = player.currentMetadata ?: return

        // Guardar canción actual
        val currentSong = player.currentMediaItem

        // Remover otras canciones de la cola
        if (player.currentMediaItemIndex > 0) {
            player.removeMediaItems(0, player.currentMediaItemIndex)
        }
        if (player.currentMediaItemIndex < player.mediaItemCount - 1) {
            player.removeMediaItems(player.currentMediaItemIndex + 1, player.mediaItemCount)
        }

        scope.launch(SilentHandler) {
            val radioQueue = YouTubeQueue(
                endpoint = WatchEndpoint(
                    videoId = currentMediaMetadata.id,
                    playlistId = "RDAMVM${currentMediaMetadata.id}"
                )
            )
            val initialStatus = radioQueue.getInitialStatus()

            if (initialStatus.title != null) {
                queueTitle = initialStatus.title
            }

            // Agregar canciones de radio después de la canción actual
            player.addMediaItems(initialStatus.items.drop(1))
            currentQueue = radioQueue
        }
    }

    fun getAutomixAlbum(albumId: String) {
        scope.launch(SilentHandler) {
            YouTube
                .album(albumId)
                .onSuccess {
                    getAutomix(it.album.playlistId)
                }
        }
    }

    fun getAutomix(playlistId: String) {
        if (dataStore[SimilarContent] == true &&
            !(disableLoadMoreWhenRepeatAll.value && player.repeatMode == REPEAT_MODE_ALL)) {
            scope.launch(SilentHandler) {
                YouTube
                    .next(WatchEndpoint(playlistId = playlistId))
                    .onSuccess {
                        YouTube
                            .next(WatchEndpoint(playlistId = it.endpoint.playlistId))
                            .onSuccess {
                                automixItems.value =
                                    it.items.map { song ->
                                        song.toMediaItem()
                                    }
                            }
                    }
            }
        }
    }

    fun addToQueueAutomix(
        item: MediaItem,
        position: Int,
    ) {
        automixItems.value =
            automixItems.value.toMutableList().apply {
                removeAt(position)
            }
        addToQueue(listOf(item))
    }

    fun playNextAutomix(
        item: MediaItem,
        position: Int,
    ) {
        automixItems.value =
            automixItems.value.toMutableList().apply {
                removeAt(position)
            }
        playNext(listOf(item))
    }

    fun clearAutomix() {
        automixItems.value = emptyList()
    }

    fun playNext(items: List<MediaItem>) {
        // Si la cola está vacía o el reproductor está inactivo, reproducir inmediatamente
        // Si la cola está vacía o el reproductor está inactivo, reproducir inmediatamente
        if (player.mediaItemCount == 0 || player.playbackState == STATE_IDLE) {
            val firstItem = items.firstOrNull()
            if (firstItem != null) {
                scope.launch {
                    preparePlayback(firstItem.mediaId)
                    withContext(Dispatchers.Main) {
                        player.setMediaItems(items)
                        player.prepare()
                        player.play()
                    }
                }
            } else {
                 player.setMediaItems(items)
                 player.prepare()
                 player.play()
            }
            return
        }

        val insertIndex = player.currentMediaItemIndex + 1
        val shuffleEnabled = player.shuffleModeEnabled

        // Insertar items inmediatamente después del item actual en el espacio de ventana/índice
        player.addMediaItems(insertIndex, items)
        player.prepare()

        // Pre-fetch the first inserted item as it is likely to be played next
        items.firstOrNull()?.let { nextItem ->
             scope.launch(Dispatchers.IO) {
                 preparePlayback(nextItem.mediaId)
             }
        }

        if (shuffleEnabled) {
            // Reconstruir orden aleatorio para que los items recién insertados se reproduzcan a continuación
            val timeline = player.currentTimeline
            if (!timeline.isEmpty) {
                val size = timeline.windowCount
                val currentIndex = player.currentMediaItemIndex

                // Los índices recién insertados son un rango contiguo [insertIndex, insertIndex + items.size)
                val newIndices = (insertIndex until (insertIndex + items.size)).toSet()

                // Recopilar el orden de recorrido aleatorio existente excluyendo el índice actual
                val orderAfter = mutableListOf<Int>()
                var idx = currentIndex
                while (true) {
                    idx = timeline.getNextWindowIndex(idx, Player.REPEAT_MODE_OFF, /*shuffleModeEnabled=*/true)
                    if (idx == C.INDEX_UNSET) break
                    if (idx != currentIndex) orderAfter.add(idx)
                }

                val prevList = mutableListOf<Int>()
                var pIdx = currentIndex
                while (true) {
                    pIdx = timeline.getPreviousWindowIndex(pIdx, Player.REPEAT_MODE_OFF, /*shuffleModeEnabled=*/true)
                    if (pIdx == C.INDEX_UNSET) break
                    if (pIdx != currentIndex) prevList.add(pIdx)
                }
                prevList.reverse() // preservar el orden hacia adelante original

                val existingOrder = (prevList + orderAfter).filter { it != currentIndex && it !in newIndices }

                // Construir nuevo orden aleatorio: actual -> recién insertados (en orden de inserción) -> resto
                val nextBlock = (insertIndex until (insertIndex + items.size)).toList()
                val finalOrder = IntArray(size)
                var pos = 0
                finalOrder[pos++] = currentIndex
                nextBlock.forEach { if (it in 0 until size) finalOrder[pos++] = it }
                existingOrder.forEach { if (pos < size) finalOrder[pos++] = it }

                // Llenar cualquier índice faltante (seguridad) para asegurar una permutación completa
                if (pos < size) {
                    for (i in 0 until size) {
                        if (!finalOrder.contains(i)) {
                            finalOrder[pos++] = i
                            if (pos == size) break
                        }
                    }
                }

                player.setShuffleOrder(DefaultShuffleOrder(finalOrder, System.currentTimeMillis()))
            }
        }
    }

    fun addToQueue(items: List<MediaItem>) {
        player.addMediaItems(items)
        player.prepare()
    }

    private fun toggleLibrary() {
        database.query {
            currentSong.value?.let {
                update(it.song.toggleLibrary())
            }
        }
    }

    fun toggleLike() {
        database.query {
            currentSong.value?.let {
                update(it.song.toggleLike())
            }
        }
    }

    private var isAudioEffectSessionOpened = false

    private fun openAudioEffectSession() {
        if (isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = true
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            },
        )
    }

    private fun closeAudioEffectSession() {
        if (!isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = false
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            },
        )
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        // Immediate metadata update to sync UI
        currentMediaMetadata.value = mediaItem?.metadata
        
        // Last.fm scrobble: start timer for new song
        val scrobbleMd = mediaItem?.metadata
        if (scrobbleMd != null && scrobbleManager != null && scrobbleMd.duration > 0) {
            scrobbleManager?.onSongStart(scrobbleMd)
        }
        
        if (mediaItem != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    // Try to extract metadata and player duration to avoid 0:00
                    val mediaMetadata = mediaItem.metadata
                    if (mediaMetadata != null) {
                        // Capture duration from player if metadata says <= 0
                        // Convert millis to seconds for internal MediaMetadata
                        val actualDurationSeconds = if (mediaMetadata.duration <= 0) {
                            val playerDurationMs = withContext(Dispatchers.Main) { player.duration }
                            if (playerDurationMs > 0L && playerDurationMs != C.TIME_UNSET) {
                                (playerDurationMs / 1000).toInt()
                            } else {
                                mediaMetadata.duration // keep -1, don't store 0
                            }
                        } else {
                            mediaMetadata.duration
                        }
                        
                        val updatedMetadata = mediaMetadata.copy(duration = actualDurationSeconds)
                        
                        // Upsert the song so it exists for history/UI with correct duration
                        database.upsert(updatedMetadata)
                    }

                    // Delete existing history for this song to prevent duplicates (Recently Played = Unique Songs)
                    database.historyDao.deleteSongHistory(mediaItem.mediaId)

                    // Insert into history (no FK constraint, so it won't crash even if song insertion above failed)
                    database.historyDao.insert(
                        com.kuromusic.db.entities.SongHistory(
                            songId = mediaItem.mediaId,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                } catch (e: android.database.sqlite.SQLiteConstraintException) {
                    Timber.tag("MusicService").w(e, "Constraint error in history")
                } catch (e: Exception) {
                    Timber.tag("MusicService").e(e, "Error recording history/song")
                }
            }
        }
        if (soundProfileHolder.state.mode == ProfileMode.AUTO) {
            resolveAutoProfile()
        }

        calibrationSystem.onTrackPlayed()

        lastPlaybackSpeed = -1.0f // forzar actualización de canción

        discordUpdateJob?.cancel()

        // Resetear errores consecutivos cuando hay transición exitosa
        consecutivePlaybackErr = 0

        // Auto cargar más canciones
        if (autoLoadMoreEnabled.value &&
            reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
            player.mediaItemCount - player.currentMediaItemIndex <= 5 &&
            (currentQueue.hasNextPage() || autoLoadMoreEnabled.value) &&
            !(disableLoadMoreWhenRepeatAll.value && player.repeatMode == REPEAT_MODE_ALL)
        ) {
            scope.launch(SilentHandler) {
                if (currentQueue.hasNextPage()) {
                    val mediaItems =
                        currentQueue.nextPage().filterExplicit(hideExplicitEnabled.value)
                    if (player.playbackState != STATE_IDLE) {
                        player.addMediaItems(mediaItems.drop(1))
                    }
                } else {
                    // Fallback: Start radio from last song if queue doesn't support pagination
                    val lastItem = player.getMediaItemAt(player.mediaItemCount - 1)
                    val lastMetadata = lastItem.metadata
                    lastMetadata?.id?.let { videoId ->
                        if (!videoId.startsWith("content://") && !videoId.startsWith("file://")) {
                            // Use RDAMVM playlist ID to get genre-aware recommendations instead of random songs
                            val radioEndpoint = WatchEndpoint(
                                videoId = videoId,
                                playlistId = "RDAMVM$videoId"
                            )
                            val nextResult = YouTube.next(radioEndpoint).getOrNull()
                            nextResult?.items?.let { items ->
                                val mediaItems = items.drop(1).map { it.toMediaItem() }
                                    .filterExplicit(hideExplicitEnabled.value)
                                if (player.playbackState != STATE_IDLE) {
                                    player.addMediaItems(mediaItems)
                                    // Switch to a proper YouTubeQueue so future pages keep loading related songs
                                    currentQueue = YouTubeQueue(
                                        endpoint = nextResult.endpoint ?: radioEndpoint
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Guardar estado cuando cambia el item de medios
        if (persistentQueueEnabled.value) {
            scope.launch {
                delay(500) // Pequeño delay para asegurar que el estado esté estable
                saveQueueToDisk()
            }
        }

        crossfadeJob?.cancel()
        if (crossfadeDurationSec > 0 && reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
            val startVolume = 0.25f
            player.volume = startVolume
            crossfadeJob = scope.launch {
                val steps = 20
                val stepDelay = (crossfadeDurationSec * 1000L) / steps
                for (i in 1..steps) {
                    val progress = i.toFloat() / steps
                    player.volume = startVolume + (1f - startVolume) * (progress * progress)
                    delay(stepDelay)
                }
                player.volume = 1f
            }
        }
    }

    override fun onPlaybackStateChanged(
        @Player.State playbackState: Int,
    ) {
        // Guardar estado cuando cambia el estado de reproducción
        if (persistentQueueEnabled.value && playbackState != Player.STATE_BUFFERING) {
            scope.launch {
                delay(500)
                saveQueueToDisk()
            }
        }

        // Cuando termina la reproducción, ocultar notificación si la cola está vacía
        if (playbackState == Player.STATE_ENDED) {
            scrobbleManager?.onSongStop()
            scope.launch {
                delay(1000)
                if (!player.isPlaying && player.mediaItemCount == 0) {
                    // Limpiar metadata para forzar actualización de notificación
                    currentMediaMetadata.value = null
                }
            }
        }

        // Actualizar widget
        sendBroadcast(Intent("com.kuromusic.ACTION_STATE_CHANGED"))
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        // Actualizar notificación cuando cambia el estado de reproducción
        scope.launch {
            delay(300)
            updateNotification()
        }
        // Last.fm scrobble: pause/resume timer
        if (scrobbleManager != null) {
            val metadata = currentMediaMetadata.value
            if (playWhenReady && player.playbackState == Player.STATE_READY) {
                if (metadata != null) {
                    scrobbleManager?.onSongResume(metadata)
                }
            } else {
                scrobbleManager?.onSongPause()
            }
        }

        // Actualizar widget
        sendBroadcast(Intent("com.kuromusic.ACTION_STATE_CHANGED"))
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onEvents(
        player: Player,
        events: Player.Events,
    ) {
        if (events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED
            )
        ) {
            val isBufferingOrReady =
                player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY
            if (isBufferingOrReady && player.playWhenReady) {
                openAudioEffectSession()
            } else {
                closeAudioEffectSession()
            }
        }

        if (events.containsAny(EVENT_TIMELINE_CHANGED, EVENT_POSITION_DISCONTINUITY)) {
            currentMediaMetadata.value = player.currentMetadata
            // Forzar actualización de notificación para asegurar que la imagen se cargue
            scope.launch {
                delay(200)
                updateNotification()
            }
        }

        // Actualización de Discord RPC
        if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED)) {
            if (player.isPlaying) {
                currentSong.value?.let { song ->
                    scope.launch {
                        discordRpc?.updateSong(song, player.currentPosition, player.playbackParameters.speed, discordUseDetails.value)
                    }
                }
            }
            // Send empty activity to the Discord RPC if the player is not playing
            else if (!events.containsAny(Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_MEDIA_ITEM_TRANSITION)){
                scope.launch {
                    discordRpc?.stopActivity()
                }
            }
        }
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        updateNotification()
        if (shuffleModeEnabled) {
            // Si la cola está vacía, no mezclar
            if (player.mediaItemCount == 0) return

            // Siempre poner el item que se está reproduciendo primero
            val shuffledIndices = IntArray(player.mediaItemCount) { it }
            shuffledIndices.shuffle()
            shuffledIndices[shuffledIndices.indexOf(player.currentMediaItemIndex)] =
                shuffledIndices[0]
            shuffledIndices[0] = player.currentMediaItemIndex
            player.setShuffleOrder(DefaultShuffleOrder(shuffledIndices, System.currentTimeMillis()))
        }

        // Guardar estado cuando cambia el modo aleatorio
        if (persistentQueueEnabled.value) {
            scope.launch {
                delay(300)
                saveQueueToDisk()
            }
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        updateNotification()
        scope.launch {
            dataStore.edit { settings ->
                settings[RepeatModeKey] = repeatMode
            }
        }

        // Guardar estado cuando cambia el modo de repetición
        if (persistentQueueEnabled.value) {
            scope.launch {
                delay(300)
                saveQueueToDisk()
            }
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)

        Timber.tag(TAG).e(error, "Player error: %s, message: %s", error.errorCodeName, error.message)

        // Detectar error de codec no soportado (ZTE Blade V70, etc. sin Opus)
        if (error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED) {
            Timber.w(TAG, "Codec not supported on this device! Enabling AAC fallback for future plays.")
            scope.launch {
                dataStore.edit { it[ForceAacFallbackKey] = true }
            }
        }

        val isConnectionError = (error.cause?.cause is PlaybackException) &&
                (error.cause?.cause as PlaybackException).errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED

        if (!isNetworkConnected.value || isConnectionError) {
            waitOnNetworkError()
            return
        }

        // Detect expired stream URL (HTTP 4xx/5xx) — clear cache and retry current track
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
            val currentMediaId = player.currentMediaItem?.mediaId
            if (currentMediaId != null) {
                Timber.tag(TAG).w("Stream expired for $currentMediaId — re-fetching...")
                playbackCache.remove(currentMediaId)
                // Invalidate the cached player response and flag WEB_REMIX so the re-fetch gets a
                // FRESH URL from a working client instead of replaying the same rejected one.
                YTPlayerUtils.invalidatePlayerResponse(currentMediaId)
                YTPlayerUtils.markWebRemixFailed(currentMediaId)
                scope.launch {
                    preparePlayback(currentMediaId)?.let {
                        playbackCache.put(currentMediaId, it)
                        player.seekTo(player.currentMediaItemIndex, C.TIME_UNSET)
                        player.prepare()
                        player.play()
                    } ?: run {
                        if (autoSkipNextOnError.value) skipOnError() else stopOnError()
                    }
                }
                return
            }
        }

        if (autoSkipNextOnError.value) {
            skipOnError()
        } else {
            stopOnError()
        }
    }


    private suspend fun preparePlayback(mediaId: String): YTPlayerUtils.PlaybackData? {
        if (mediaId.startsWith("content://") || mediaId.startsWith("file://")) return null
        return withContext(Dispatchers.IO) {
            try {
                // Check cache first (including expiry)
                playbackCache.get(mediaId)?.let { cached ->
                    val expiresAt = cached.fetchedAt + cached.streamExpiresInSeconds * 1000L
                    if (expiresAt > System.currentTimeMillis()) {
                        return@withContext cached
                    }
                }

                val playback = YTPlayerUtils.playerResponseForPlayback(
                    mediaId,
                    audioQuality = audioQuality,
                    connectivityManager = connectivityManager
                ).getOrThrow()

                if (!playback.streamUrl.startsWith("https://")) {
                    Timber.tag(TAG).e("Invalid stream URL (not https): ${playback.streamUrl}")
                    return@withContext null
                }

                Timber.tag(TAG).i("Resolved stream URL for $mediaId | Length: ${playback.streamUrl.length}")
                playbackCache.put(mediaId, playback)

                // Side effects (fire-and-forget)
                scope.launch(Dispatchers.IO) {
                    try {
                        val format = playback.format
                        database.query {
                            upsert(FormatEntity(
                                id = mediaId,
                                itag = format.itag,
                                mimeType = format.mimeType.split(";")[0],
                                codecs = format.mimeType.split("codecs=")[1].removeSurrounding("\""),
                                bitrate = format.bitrate,
                                sampleRate = format.audioSampleRate,
                                contentLength = format.contentLength ?: 0L,
                                loudnessDb = playback.audioConfig?.loudnessDb,
                                playbackUrl = playback.streamUrl
                            ))
                        }
                        recoverSong(mediaId, playback)
                        val song = database.songDao.song(mediaId).first()
                        if (song?.song?.genre == null) {
                            YouTube.next(WatchEndpoint(videoId = mediaId)).onSuccess {
                                database.query { update(song?.song?.copy(genre = "Music") ?: return@query) }
                            }
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Error during playback side-effects")
                    }
                }

                return@withContext playback
            } catch (e: PlaybackException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to prepare playback for $mediaId")
                null
            }
        }
    }

    private fun createDataSourceFactory(): DataSource.Factory {
        val okHttpFactory = OkHttpDataSource.Factory(
            OkHttpClient.Builder()
                .proxy(YouTube.proxy)
                .addInterceptor(YouTubeSessionInterceptor())
                .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        ).setDefaultRequestProperties(mapOf("User-Agent" to "Kuromusic/1.0"))

        val defaultFactory = DefaultDataSource.Factory(this, okHttpFactory)

        val resolvingFactory = ResolvingDataSource.Factory(defaultFactory) { dataSpec ->
            val mediaId = dataSpec.key ?: return@Factory dataSpec

            // Bypass YouTube stream resolution for local media
            if (mediaId.startsWith("content://") || mediaId.startsWith("file://")) {
                return@Factory dataSpec
            }

            // Check cache first (including expiry)
            playbackCache.get(mediaId)?.let { cached ->
                val expiresAt = cached.fetchedAt + cached.streamExpiresInSeconds * 1000L
                if (expiresAt > System.currentTimeMillis()) {
                    return@Factory dataSpec.buildUpon()
                        .setUri(cached.streamUrl.toUri())
                        .setHttpRequestHeaders(cached.streamHeaders)
                        .build()
                }
            }

            // Resolve stream URL synchronously (same approach as Echo Music)
            val playbackData = try {
                runBlocking(Dispatchers.IO) { preparePlayback(mediaId) }
            } catch (e: PlaybackException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Stream resolution failed for $mediaId")
                throw PlaybackException(
                    "Failed to resolve stream for $mediaId",
                    e,
                    PlaybackException.ERROR_CODE_REMOTE_ERROR
                )
            }

            if (playbackData == null) {
                throw PlaybackException(
                    "Failed to resolve stream for $mediaId",
                    null,
                    PlaybackException.ERROR_CODE_REMOTE_ERROR
                )
            }

            if (playbackData.streamHeaders.isNotEmpty()) {
                Timber.i("Streaming via %s client with %d custom headers", playbackData.streamClient, playbackData.streamHeaders.size)
            }
            return@Factory dataSpec.buildUpon()
                .setUri(playbackData.streamUrl.toUri())
                .setHttpRequestHeaders(playbackData.streamHeaders)
                .build()
        }

        // Cache for general playback streaming (temporary cache)
        val playerCacheFactory = CacheDataSource.Factory()
            .setCache(playerCache)
            .setUpstreamDataSourceFactory(resolvingFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        // Local file data source for downloaded songs
        val localFileFactory = DefaultDataSource.Factory(this)

        // Composite Factory: checks local files first, falls back to streaming cache
        return DataSource.Factory {
            val playerDataSource = playerCacheFactory.createDataSource()

            object : DataSource {
                private var activeDataSource: DataSource? = null

                override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
                    playerDataSource.addTransferListener(transferListener)
                }

                override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
                    val mediaId = dataSpec.key

                    if (mediaId != null) {
                        val songUri = RealDownloader.getSongUri(this@MusicService, mediaId)
                        if (songUri != null) {
                            val fileSpec = dataSpec.buildUpon().setUri(songUri).build()
                            val fileSource = localFileFactory.createDataSource()
                            activeDataSource = fileSource
                            return fileSource.open(fileSpec)
                        }
                        val localFile = RealDownloader.getSongFile(this@MusicService, mediaId)
                        if (localFile != null) {
                            val fileUri = android.net.Uri.fromFile(localFile)
                            val fileSpec = dataSpec.buildUpon().setUri(fileUri).build()
                            val fileSource = localFileFactory.createDataSource()
                            activeDataSource = fileSource
                            return fileSource.open(fileSpec)
                        }
                    }

                    // Fall back to streaming (with player cache)
                    activeDataSource = playerDataSource
                    return playerDataSource.open(dataSpec)
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    return activeDataSource!!.read(buffer, offset, length)
                }

                override fun getUri(): android.net.Uri? {
                    return activeDataSource?.getUri()
                }

                override fun getResponseHeaders(): Map<String, List<String>> {
                    return activeDataSource?.getResponseHeaders() ?: emptyMap()
                }

                override fun close() {
                    activeDataSource?.close()
                    activeDataSource = null
                }
            }
        }
    }

    private fun createMediaSourceFactory() =
        DefaultMediaSourceFactory(
            createDataSourceFactory(),
            androidx.media3.extractor.DefaultExtractorsFactory()
        )

    private fun createRenderersFactory(
        eqProcessor: com.kuromusic.eq.audio.CustomEqualizerAudioProcessor
    ) = object : DefaultRenderersFactory(this) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ) = DefaultAudioSink
            .Builder(this@MusicService)
            .setEnableFloatOutput(true)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(
                    arrayOf(eqProcessor, gainProcessor, deviceProcessor, soundProfileProcessor),
                    SilenceSkippingAudioProcessor(2_000_000, 20_000, 256),
                    SonicAudioProcessor(),
                ),
            ).build()
    }

    override fun onPlaybackStatsReady(
        eventTime: AnalyticsListener.EventTime,
        playbackStats: PlaybackStats,
    ) {
        val mediaItem =
            eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem

        if (playbackStats.totalPlayTimeMs >= (
                    dataStore[HistoryDuration]?.times(1000f)
                        ?: 30000f
                    ) &&
            !pauseListenHistory.value
        ) {
            database.query {
                incrementTotalPlayTime(mediaItem.mediaId, playbackStats.totalPlayTimeMs)
                try {
                    insert(
                        Event(
                            songId = mediaItem.mediaId,
                            timestamp = LocalDateTime.now(),
                            playTime = playbackStats.totalPlayTimeMs,
                        ),
                    )
                } catch (_: SQLException) {
                }
            }
            val PauseRemoteListenHistoryKey = booleanPreferencesKey("pauseRemoteListenHistory")
            if (!dataStore.get(PauseRemoteListenHistoryKey, false)) {
                ioScope.launch {
                    val playbackUrl = database.format(mediaItem.mediaId).first()?.playbackUrl
                        ?: YTPlayerUtils.playerResponseForMetadata(mediaItem.mediaId, null)
                            .getOrNull()?.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                    playbackUrl?.let {
                        YouTube.registerPlayback(null, playbackUrl)
                            .onFailure {
                                reportException(it)
                            }
                    }
                }
            }
        }
    }

    // Mutex prevents concurrent file writes from corrupting persistence files
    private val saveQueueMutex = kotlinx.coroutines.sync.Mutex()

    private fun saveQueueToDisk() {
        if (!::player.isInitialized) return

        if (player.playbackState == STATE_IDLE && player.mediaItemCount == 0) {
            scope.launch(Dispatchers.IO) {
                filesDir.resolve(PERSISTENT_AUTOMIX_FILE).delete()
                filesDir.resolve(PERSISTENT_QUEUE_FILE).delete()
                filesDir.resolve(PERSISTENT_PLAYER_STATE_FILE).delete()
            }
            return
        }

        // Capture player state snapshot on calling thread (Main) before switching to IO
        val persistQueue =
            PersistQueue(
                title = queueTitle,
                items = player.mediaItems.mapNotNull { it.metadata },
                mediaItemIndex = player.currentMediaItemIndex.coerceAtLeast(0),
                position = if (player.currentPosition >= 0) player.currentPosition else 0,
            )
        val persistAutomix =
            PersistQueue(
                title = "automix",
                items = automixItems.value.mapNotNull { it.metadata },
                mediaItemIndex = 0,
                position = 0,
            )
        val playerState = PersistPlayerState(
            repeatMode = player.repeatMode,
            shuffleModeEnabled = player.shuffleModeEnabled,
            volume = player.volume,
            currentMediaItemIndex = player.currentMediaItemIndex.coerceAtLeast(0),
            currentPosition = if (player.currentPosition >= 0) player.currentPosition else 0,
            playWhenReady = player.playWhenReady,
            playbackState = player.playbackState
        )

        scope.launch(Dispatchers.IO) {
            saveQueueMutex.withLock {
                runCatching {
                    filesDir.resolve(PERSISTENT_QUEUE_FILE).outputStream().use { fos ->
                        ObjectOutputStream(fos).use { oos ->
                            oos.writeObject(persistQueue)
                        }
                    }
                }.onFailure {
                    Timber.tag(TAG).e(it, "Error saving queue to disk")
                    reportException(it)
                }

                runCatching {
                    filesDir.resolve(PERSISTENT_AUTOMIX_FILE).outputStream().use { fos ->
                        ObjectOutputStream(fos).use { oos ->
                            oos.writeObject(persistAutomix)
                        }
                    }
                }.onFailure {
                    Timber.tag(TAG).e(it, "Error saving automix to disk")
                    reportException(it)
                }

                runCatching {
                    filesDir.resolve(PERSISTENT_PLAYER_STATE_FILE).outputStream().use { fos ->
                        ObjectOutputStream(fos).use { oos ->
                            oos.writeObject(playerState)
                        }
                    }
                }.onFailure {
                    Timber.tag(TAG).e(it, "Error saving player state to disk")
                    reportException(it)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onDestroy() {
        if (persistentQueueEnabled.value) {
            saveQueueToDisk()
        }
        serviceJob.cancel()
        ioJob.cancel()
        audioDeviceCallback?.let { getSystemService<AudioManager>()?.unregisterAudioDeviceCallback(it) }
        audioDeviceCallback = null
        DiscordRpcManager.destroy()
        discordRpc = null
        mediaSession.release()
        equalizerService.release()
        if (::player.isInitialized) {
            player.removeListener(this)
            player.removeListener(sleepTimer)
            player.release()
        }
        
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = super.onBind(intent) ?: binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "com.kuromusic.ACTION_PLAY_PAUSE" -> player.playWhenReady = !player.playWhenReady
            "com.kuromusic.ACTION_PREV" -> player.seekToPreviousMediaItem()
            "com.kuromusic.ACTION_NEXT" -> player.seekToNext()
            "com.kuromusic.ACTION_SHUFFLE" -> player.shuffleModeEnabled = !player.shuffleModeEnabled
            "com.kuromusic.ACTION_LIKE" -> toggleLike()
            "com.kuromusic.ACTION_REPLAY" -> toggleRepeatMode()
        }
        if (intent?.action != null) {
            sendBroadcast(Intent("com.kuromusic.ACTION_STATE_CHANGED"))
        }
        return START_STICKY
    }

    private fun toggleRepeatMode() {
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_OFF
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    inner class MusicBinder : Binder() {
        val service: MusicService
            get() = this@MusicService
    }

    companion object {
        const val ROOT = "root"
        const val SONG = "song"
        const val ARTIST = "artist"
        const val ALBUM = "album"
        const val PLAYLIST = "playlist"

        const val CHANNEL_ID = "music_channel_01"
        const val NOTIFICATION_ID = 888
        const val PERSISTENT_PLAYER_STATE_FILE = "persistent_player_state.data"
        const val MAX_CONSECUTIVE_ERR = 5
        const val CHUNK_LENGTH = 512 * 1024L
        const val PERSISTENT_QUEUE_FILE = "persistent_queue.data"
        const val PERSISTENT_AUTOMIX_FILE = "persistent_automix.data"

        private const val TAG = "MusicService"
    }
}