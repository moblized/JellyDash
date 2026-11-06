package com.moblized.jellydash.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.moblized.jellydash.BuildConfig
import com.moblized.jellydash.data.Prefs
import com.moblized.jellydash.data.WhenMoving
import com.moblized.jellydash.jellyfin.Item
import com.moblized.jellydash.jellyfin.Jellyfin
import com.moblized.jellydash.jellyfin.JellyfinException
import com.moblized.jellydash.jellyfin.Kind
import com.moblized.jellydash.jellyfin.PlaybackReport
import com.moblized.jellydash.jellyfin.PlaybackRequest
import com.moblized.jellydash.jellyfin.ReportKind
import com.moblized.jellydash.jellyfin.Ticks
import com.moblized.jellydash.safety.MotionGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What's playing, for the car's player screen and the picture on the car display. */
data class NowPlaying(
    val item: Item,
    val title: String,
    val subtitle: String?,
    val plan: StreamPlan? = null,
    val loading: Boolean = true,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val error: String? = null,
)

/**
 * Plays one video at a time from your server, keeps Jellyfin up to date on where you are, and
 * pauses for driving when you've asked it to.
 */
@OptIn(UnstableApi::class)
class PlaybackController(
    private val context: Context,
    private val prefs: Prefs,
    private val jellyfin: Jellyfin,
    private val guard: MotionGuard,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<NowPlaying?>(null)
    val state: StateFlow<NowPlaying?> = _state.asStateFlow()

    var player: ExoPlayer? = null
        private set

    private val http = DefaultHttpDataSource.Factory()
        .setUserAgent("JellyDash/${BuildConfig.VERSION_NAME}")
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)

    private var progressJob: Job? = null
    private var loadJob: Job? = null
    private var pausedForMotion = false
    private var triedServerStream = false

    init {
        scope.launch { guard.status.collect { onGuard(it) } }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it?.copy(isPlaying = isPlaying) }
            report(ReportKind.Progress, if (isPlaying) "Unpause" else "Pause")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update { s ->
                s?.let {
                    it.copy(
                        buffering = playbackState == Player.STATE_BUFFERING,
                        loading = it.loading && playbackState != Player.STATE_READY,
                        ended = playbackState == Player.STATE_ENDED,
                    )
                }
            }
        }

        override fun onTracksChanged(tracks: Tracks) = pickSideSubtitle(tracks)

        override fun onPlayerError(error: PlaybackException) = recover(error)
    }

    private fun ensurePlayer(): ExoPlayer = player ?: ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(30_000)
        .build()
        .also {
            it.addListener(listener)
            player = it
        }

    /** Starts [item], from where you left off unless [fromStart]. */
    fun play(item: Item, fromStart: Boolean = false) {
        finish()
        begin(item, fromStart)
    }

    /** Plays the next episode of a show or season you haven't finished. */
    fun playNextOf(show: Item) {
        finish()
        _state.value = NowPlaying(show, show.seriesName ?: show.name, "Finding the next episode")
        loadJob = scope.launch {
            try {
                val seriesId = if (show.kind == Kind.Series) show.id else show.seriesId ?: show.id
                val seasonId = show.id.takeIf { show.kind == Kind.Season }
                val next = (if (seasonId == null) jellyfin.nextUpFor(seriesId) else null)
                    ?: jellyfin.episodes(seriesId, seasonId).let { eps -> eps.firstOrNull { !it.played } ?: eps.firstOrNull() }
                    ?: throw JellyfinException("There are no episodes to play")
                begin(next, fromStart = false)
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** Plays the episode after the one that just finished. */
    fun playNextEpisode() {
        val current = _state.value?.item ?: return
        val seriesId = current.seriesId ?: return
        scope.launch {
            try {
                val all = jellyfin.episodes(seriesId, null)
                val i = all.indexOfFirst { it.id == current.id }
                val next = all.getOrNull(i + 1) ?: throw JellyfinException("That was the last episode")
                finish()
                begin(next, fromStart = true)
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** True when the video that just ended has an episode after it worth offering. */
    val canPlayNext: Boolean
        get() = _state.value?.let { it.ended && it.item.kind == Kind.Episode && it.item.seriesId != null } == true

    private fun begin(item: Item, fromStart: Boolean) {
        triedServerStream = false
        val startMs = if (fromStart || item.played) 0L else item.positionMs
        _state.value = NowPlaying(item, titleOf(item), subtitleOf(item))
        loadJob = scope.launch {
            try {
                val info = jellyfin.playbackInfo(item.id, PlaybackRequest(prefs.currentSettings.quality.bitsPerSecond))
                val plan = StreamPlanner.plan(info, item.id, jellyfin)
                load(plan, startMs, playWhenReady = true)
                report(ReportKind.Start)
                startProgress()
                PlaybackService.start(context)
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private fun load(plan: StreamPlan, startMs: Long, playWhenReady: Boolean) {
        val state = _state.value ?: return
        val p = ensurePlayer()
        http.setDefaultRequestProperties(jellyfin.streamHeaders())
        val builder = MediaItem.Builder()
            .setUri(plan.url)
            .setMediaId(state.item.id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(state.title).setSubtitle(state.subtitle).build())
        if (plan.isHls) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        plan.subtitle?.let { s ->
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url))
                        .setId(SIDE_SUBTITLE_ID)
                        .setMimeType(s.mimeType)
                        .setLanguage(s.language)
                        .setLabel(s.label)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build(),
                ),
            )
        }
        // Only the subtitles you picked: never ones tucked inside the file as well.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, plan.subtitle == null)
            .build()
        p.setMediaItem(builder.build(), startMs)
        p.prepare()
        val holdForMotion = prefs.currentSettings.whenMoving == WhenMoving.Pause && guard.status.value.reason == MotionGuard.Reason.Moving
        pausedForMotion = playWhenReady && holdForMotion
        p.playWhenReady = playWhenReady && !holdForMotion
        _state.update { it?.copy(plan = plan, error = null, ended = false) }
    }

    private fun pickSideSubtitle(tracks: Tracks) {
        val p = player ?: return
        if (_state.value?.plan?.subtitle == null) return
        val group = tracks.groups.firstOrNull { g ->
            g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { g.getTrackFormat(it).id?.contains(SIDE_SUBTITLE_ID) == true }
        } ?: return
        if (group.isSelected) return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
    }

    /** A file the phone couldn't play gets one more try as the server's own stream. */
    private fun recover(error: PlaybackException) {
        val state = _state.value ?: return
        val plan = state.plan
        if (plan != null && plan.playMethod == "DirectPlay" && !triedServerStream) {
            triedServerStream = true
            reload(plan.audioIndex, plan.subtitleIndex, forceServer = true)
            return
        }
        val msg = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            -> "Lost the connection to your server"
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server stopped sending the video"
            else -> "This video can't play (${error.errorCodeName})"
        }
        _state.update { it?.copy(error = msg, loading = false, isPlaying = false) }
    }

    fun togglePlay() {
        val p = player ?: return
        val s = _state.value ?: return
        if (s.ended) {
            p.seekTo(0)
            p.play()
            return
        }
        pausedForMotion = false
        if (p.isPlaying || p.playWhenReady) p.pause() else p.play()
    }

    fun seekBy(ms: Long) {
        val p = player ?: return
        val duration = p.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + ms).coerceIn(0, duration))
        report(ReportKind.Progress, "TimeUpdate")
    }

    fun chooseAudio(index: Int) {
        val plan = _state.value?.plan ?: return
        if (index == plan.audioIndex) return
        reload(index, plan.subtitleIndex, forceServer = StreamPlanner.audioNeedsServer(plan.source, index))
    }

    /** -1 turns subtitles off. */
    fun chooseSubtitle(index: Int) {
        val plan = _state.value?.plan ?: return
        if (index == plan.subtitleIndex) return
        reload(plan.audioIndex, index, forceServer = StreamPlanner.audioNeedsServer(plan.source, plan.audioIndex) || triedServerStream)
    }

    private fun reload(audio: Int?, subtitle: Int, forceServer: Boolean) {
        val state = _state.value ?: return
        val old = state.plan ?: return
        val p = player
        val position = p?.currentPosition ?: 0L
        val playing = p?.playWhenReady ?: true
        _state.update { it?.copy(loading = true, error = null) }
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                val request = PlaybackRequest(
                    maxBitrate = prefs.currentSettings.quality.bitsPerSecond,
                    audioIndex = audio,
                    subtitleIndex = subtitle,
                    mediaSourceId = old.source.id,
                    forceServerStream = forceServer,
                )
                val info = jellyfin.playbackInfo(state.item.id, request)
                val plan = StreamPlanner.plan(info, state.item.id, jellyfin, audio, subtitle)
                val oldSession = old.playSessionId
                if (old.playMethod == "Transcode" && oldSession != null) runCatching { jellyfin.stopEncoding(oldSession) }
                load(plan, position, playing)
                report(ReportKind.Progress, "TimeUpdate")
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** Stops playback and tells the server where you got to. */
    fun stop() = finish()

    private fun finish() {
        val state = _state.value
        loadJob?.cancel()
        progressJob?.cancel()
        progressJob = null
        val p = player
        if (state != null) {
            val plan = state.plan
            if (plan != null && p != null) {
                val r = reportOf(state, plan, p, null)
                val session = plan.playSessionId
                val transcoding = plan.playMethod == "Transcode"
                // Not tied to [scope] being busy: the report still goes out after the screen closes.
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { jellyfin.report(ReportKind.Stopped, r) }
                    if (transcoding && session != null) runCatching { jellyfin.stopEncoding(session) }
                }
            }
        }
        p?.removeListener(listener)
        p?.release()
        player = null
        pausedForMotion = false
        _state.value = null
        PlaybackService.stop(context)
    }

    private fun startProgress() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(10_000)
                if (player?.isPlaying == true) report(ReportKind.Progress, "TimeUpdate")
            }
        }
    }

    private fun reportOf(state: NowPlaying, plan: StreamPlan, p: Player, event: String?) = PlaybackReport(
        itemId = state.item.id,
        mediaSourceId = plan.source.id,
        playSessionId = plan.playSessionId,
        positionTicks = Ticks.fromMs(p.currentPosition),
        isPaused = !p.isPlaying,
        playMethod = plan.playMethod,
        audioIndex = plan.audioIndex,
        subtitleIndex = plan.subtitleIndex,
        event = event,
    )

    private fun report(kind: ReportKind, event: String? = null) {
        val state = _state.value ?: return
        val plan = state.plan ?: return
        val p = player ?: return
        val r = reportOf(state, plan, p, event)
        scope.launch { runCatching { jellyfin.report(kind, r) } }
    }

    private fun fail(e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        _state.update { it?.copy(error = e.message ?: "Something went wrong", loading = false, isPlaying = false) }
    }

    private fun onGuard(s: MotionGuard.Status) {
        val p = player ?: return
        if (s.reason == MotionGuard.Reason.Moving && prefs.currentSettings.whenMoving == WhenMoving.Pause && p.playWhenReady) {
            p.pause()
            pausedForMotion = true
        } else if (s.videoAllowed && pausedForMotion) {
            pausedForMotion = false
            p.play()
        }
    }

    companion object {
        const val SIDE_SUBTITLE_ID = "jellydash-subtitle"

        fun titleOf(item: Item): String = when (item.kind) {
            Kind.Episode -> item.seriesName ?: item.name
            else -> item.name
        }

        fun subtitleOf(item: Item): String? = when (item.kind) {
            Kind.Episode -> buildString {
                val s = item.parentIndex
                val e = item.index
                if (s != null && e != null) append("S${s}E$e · ") else if (e != null) append("Episode $e · ")
                append(item.name)
            }
            else -> item.year?.toString()
        }
    }
}
