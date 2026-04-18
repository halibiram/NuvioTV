package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.content.res.Resources
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.util.Log
import com.nuvio.tv.R
import android.view.accessibility.CaptioningManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper
import androidx.media3.exoplayer.video.VideoFrameReleaseControl
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.session.MediaSession
import com.nuvio.tv.data.local.AddonSubtitleStartupMode
import com.nuvio.tv.data.local.AudioLanguageOption
import com.nuvio.tv.data.local.SUBTITLE_LANGUAGE_FORCED
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.HdrPlaybackCompatibilityMode
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.domain.model.Subtitle
import io.github.peerless2012.ass.media.type.AssRenderType
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val STARTUP_SUBTITLE_PREFETCH_TIMEOUT_MS = 10_000L
private const val MPV_AFR_SETTLE_DELAY_MS = 2_000L

internal fun isDolbyVisionVideo(format: Format): Boolean {
    return format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
}

internal fun isHdrVideo(format: Format): Boolean {
    return isDolbyVisionVideo(format) || ColorInfo.isTransferHdr(format.colorInfo)
}

internal fun isHdrCompatibilityModeEnabled(mode: HdrPlaybackCompatibilityMode): Boolean {
    return mode != HdrPlaybackCompatibilityMode.OFF
}

internal fun shouldForceDolbyAudioCompatibility(
    settings: PlayerSettings,
    currentVideoIsDolbyVision: Boolean
): Boolean {
    return settings.dolbyAudioCompatibilityMode ||
        (isHdrCompatibilityModeEnabled(settings.hdrPlaybackCompatibilityMode) && currentVideoIsDolbyVision)
}

internal fun PlayerRuntimeController.applyEffectiveDolbyPlaybackSettings(settings: PlayerSettings) {
    val effectiveDolbyAudioCompatibility = shouldForceDolbyAudioCompatibility(
        settings = settings,
        currentVideoIsDolbyVision = currentVideoIsDolbyVision
    )
    val requiresPcmForSpeed = _exoPlayer?.let(::selectedAudioRequiresPcmForSpeed) == true

    playbackSpeedAwareAudioOutputProvider?.updatePlaybackSpeed(
        _uiState.value.playbackSpeed,
        selectedAudioRequiresPcmForSpeed = requiresPcmForSpeed,
        forceDolbyCompatibilityMode = effectiveDolbyAudioCompatibility
    )

    _uiState.update {
        it.copy(tunnelingEnabled = settings.tunnelingEnabled && !effectiveDolbyAudioCompatibility)
    }
}

internal data class StartupSubtitlePreparation(
    val fetchedSubtitles: List<Subtitle>,
    val attachedSubtitles: List<Subtitle>,
    val fetchCompleted: Boolean
)

private suspend fun PlayerRuntimeController.resolveCurrentStreamMimeType(
    url: String,
    headers: Map<String, String>
) {
    currentStreamMimeType?.let { resolvedMimeType ->
        Log.d(
            PlayerRuntimeController.TAG,
            "Resolved stream mimeType=$resolvedMimeType for url=$url"
        )
        return
    }
    currentStreamMimeType = PlayerMediaSourceFactory.probeMimeType(
        url = url,
        headers = headers,
        filename = currentFilename,
        responseHeaders = currentStreamResponseHeaders
    )
    Log.d(
        PlayerRuntimeController.TAG,
        "Resolved stream mimeType=${currentStreamMimeType ?: "unknown"} for url=$url"
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.initializePlayer(
    url: String,
    headers: Map<String, String>,
    overrideInternalPlayerEngine: InternalPlayerEngine? = null,
    allowEngineFailover: Boolean = true
) {
    if (url.isEmpty()) {
        _uiState.update { it.copy(error = context.getString(R.string.player_error_no_stream_url), showLoadingOverlay = false) }
        return
    }

    scope.launch {
        try {
            if (allowEngineFailover) {
                startupEngineFailoverTriggered = false
            }
            resetLoadingOverlayForNewStream()
            hasTriedAudioPcmFallback = false
            hasTriedAv1SoftwareFallback = false
            hasTriedDv7HevcFallback = false
            mpvDelayStartAfterAfrSwitch = false
            val playerSettings = playerSettingsDataStore.playerSettings.first()
            val effectiveDecoderPriority =
                if (
                    forceSoftwareAv1Playback &&
                    playerSettings.decoderPriority == DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                ) {
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                } else {
                    playerSettings.decoderPriority
                }
            cachedDecoderPriority = effectiveDecoderPriority
            val preferredAudioLanguages = resolvePreferredAudioLanguages(
                preferredAudioLanguage = playerSettings.preferredAudioLanguage,
                secondaryPreferredAudioLanguage = playerSettings.secondaryPreferredAudioLanguage,
                deviceLanguages = resolveDeviceAudioLanguages(),
                contentOriginalLanguage = contentLanguage
            )
            mpvPreferredAudioLanguages = preferredAudioLanguages
            mpvHardwareDecodeModeSetting = playerSettings.mpvHardwareDecodeMode
            val effectiveInternalPlayerEngine = overrideInternalPlayerEngine ?: playerSettings.internalPlayerEngine
            runtimeInternalPlayerEngineOverride = overrideInternalPlayerEngine
            currentInternalPlayerEngine = effectiveInternalPlayerEngine
            val showLoadingStatus = playerSettings.showPlayerLoadingStatus
            val effectiveDolbyAudioCompatibility = shouldForceDolbyAudioCompatibility(
                settings = playerSettings,
                currentVideoIsDolbyVision = currentVideoIsDolbyVision
            )
            _uiState.update {
                it.copy(
                    internalPlayerEngine = effectiveInternalPlayerEngine,
                    frameRateMatchingMode = playerSettings.frameRateMatchingMode,
                    resizeMode = playerSettings.resizeMode,
                    tunnelingEnabled = playerSettings.tunnelingEnabled && !effectiveDolbyAudioCompatibility,
                    loadingMessage = if (showLoadingStatus) context.getString(R.string.player_loading_detecting_format) else null
                )
            }
            val afrJob = async {
                runAfrPreflightIfEnabled(
                    url = url,
                    headers = headers,
                    frameRateMatchingMode = playerSettings.frameRateMatchingMode,
                    resolutionMatchingEnabled = playerSettings.resolutionMatchingEnabled
                )
            }
            if (effectiveInternalPlayerEngine == InternalPlayerEngine.MVP_PLAYER) {
                mpvInitializationInProgress = true
                try {
                    afrJob.await()
                    if (mpvDelayStartAfterAfrSwitch) {
                        Log.d(
                            PlayerRuntimeController.TAG,
                            "AFR display mode switched; delaying MPV start by ${MPV_AFR_SETTLE_DELAY_MS}ms"
                        )
                        delay(MPV_AFR_SETTLE_DELAY_MS)
                    }
                    initializeMpvPlayer(
                        url = url,
                        headers = headers,
                        allowEngineFailover = allowEngineFailover
                    )
                    // Keep addon subtitle discovery available on the mpv path too.
                    // Exo does this later in this method, but this branch returns early.
                    fetchAddonSubtitles()
                } finally {
                    mpvInitializationInProgress = false
                }
                return@launch
            }
            resolveCurrentStreamMimeType(
                url = url,
                headers = headers
            )
            mpvInitializationInProgress = false
            val startupSubtitlePreparation = prepareStreamStartSubtitles(playerSettings, showLoadingStatus)
            afrJob.await()
            requestedUseLibassByUser = playerSettings.useLibass
            val useLibass = when {
                !requestedUseLibassByUser -> false
                libassPipelineOverrideForCurrentStream != null -> libassPipelineOverrideForCurrentStream == true
                else -> true
            }
            val requestedLibassRenderType = playerSettings.libassRenderType.toAssRenderType()
            val libassRenderType = when {
                !useLibass -> requestedLibassRenderType
                requestedLibassRenderType == AssRenderType.OVERLAY_OPEN_GL -> AssRenderType.EFFECTS_OPEN_GL
                requestedLibassRenderType == AssRenderType.OVERLAY_CANVAS -> AssRenderType.EFFECTS_CANVAS
                else -> requestedLibassRenderType
            }
            val loadControl = buildExoLoadControl()

            
            trackSelector = DefaultTrackSelector(context).apply {
                setParameters(
                    buildUponParameters()
                        .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
                )
                if (playerSettings.tunnelingEnabled && !effectiveDolbyAudioCompatibility) {
                    setParameters(
                        buildUponParameters().setTunnelingEnabled(true)
                    )
                }

                if (preferredAudioLanguages.isNotEmpty()) {
                    setParameters(
                        buildUponParameters().setPreferredAudioLanguages(*preferredAudioLanguages.toTypedArray())
                    )
                }

                
                val appContext = this@initializePlayer.context
                val captioningManager = appContext.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
                if (captioningManager != null) {
                    if (!captioningManager.isEnabled) {
                        setParameters(
                            buildUponParameters().setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        )
                    }
                    captioningManager.locale?.let { locale ->
                        setParameters(
                            buildUponParameters().setPreferredTextLanguage(locale.isO3Language)
                        )
                    }
                }
            }

            
            val extractorsFactory = DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
                .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)

            
            subtitleDelayUs.set(_uiState.value.subtitleDelayMs.toLong() * 1000L)
            val renderersFactory = SubtitleOffsetRenderersFactory(
                context = context,
                subtitleDelayUsProvider = subtitleDelayUs::get,
                shouldNormalizeCuePositionProvider = {
                    val selectedAddonSubtitle = _uiState.value.selectedAddonSubtitle
                    selectedAddonSubtitle != null &&
                        PlayerSubtitleUtils.mimeTypeFromUrl(selectedAddonSubtitle.url) == MimeTypes.TEXT_VTT
                },
                gainAudioProcessor = gainAudioProcessor,
                playbackSpeedProvider = { _uiState.value.playbackSpeed },
                onPlaybackSpeedAwareAudioOutputProviderCreated = { playbackSpeedAwareAudioOutputProvider = it },
                preferDolbyAudioCompatibilityMode = playerSettings.dolbyAudioCompatibilityMode,
                mapDV7ToHevc = playerSettings.mapDV7ToHevc,
                disableDolbyVision = playerSettings.disableDolbyVision,
                disableDolbyVisionForDv7 = playerSettings.disableDolbyVisionForDv7,
                requestSdrToneMapping =
                    playerSettings.hdrPlaybackCompatibilityMode == HdrPlaybackCompatibilityMode.TONE_MAP_HDR_TO_SDR,
                convertHdr10PlusToHdr10 =
                    playerSettings.hdrPlaybackCompatibilityMode == HdrPlaybackCompatibilityMode.EXPERIMENTAL_CONVERT_HDR10_PLUS_TO_HDR10,
                forceSoftwareAv1Playback = forceSoftwareAv1Playback,
                forceInterpretHdrAsSdr =
                    playerSettings.hdrPlaybackCompatibilityMode == HdrPlaybackCompatibilityMode.EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR
            ).setExtensionRendererMode(effectiveDecoderPriority)
                .setMapDV7ToHevc(playerSettings.mapDV7ToHevc || forceDv7ToHevc)

            if (showLoadingStatus) _uiState.update { it.copy(loadingMessage = context.getString(R.string.player_loading_building)) }
            val buildDefaultPlayer = {
                mediaSourceFactory.configureSubtitleParsing(
                    extractorsFactory = null,
                    subtitleParserFactory = null
                )
                val playerDataSourceFactory = PlayerPlaybackNetworking.createDataSourceFactory(context, headers)
                ExoPlayer.Builder(context)
                    .setTrackSelector(trackSelector!!)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(playerDataSourceFactory, extractorsFactory))
                    .setRenderersFactory(renderersFactory)
                    .setLoadControl(loadControl)
                    .setReleaseTimeoutMs(3000)
                    .build()
            }

            _exoPlayer = if (useLibass) {
                val playerDataSourceFactory = PlayerPlaybackNetworking.createDataSourceFactory(context, headers)
                ExoPlayer.Builder(context)
                    .setLoadControl(loadControl)
                    .setTrackSelector(trackSelector!!)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(playerDataSourceFactory, extractorsFactory))
                    .setReleaseTimeoutMs(3000)
                    .buildWithAssSupportCompat(
                        context = context,
                        renderType = libassRenderType,
                        playerMediaSourceFactory = mediaSourceFactory,
                        dataSourceFactory = playerDataSourceFactory,
                        extractorsFactory = extractorsFactory,
                        renderersFactory = renderersFactory
                    )
            } else {
                buildDefaultPlayer()
            }
            activePlayerUsesLibass = useLibass
            libassPipelineSwitchInFlight = false

            _exoPlayer?.apply {
                if (
                    playerSettings.hdrPlaybackCompatibilityMode == HdrPlaybackCompatibilityMode.TONE_MAP_HDR_TO_SDR ||
                    playerSettings.hdrPlaybackCompatibilityMode == HdrPlaybackCompatibilityMode.EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR
                ) {
                    setVideoEffects(emptyList())
                }
                
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build()
                setAudioAttributes(audioAttributes, true)
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                applyEffectiveDolbyPlaybackSettings(playerSettings)
                setPlaybackSpeed(_uiState.value.playbackSpeed)

                
                if (playerSettings.skipSilence) {
                    skipSilenceEnabled = true
                }

                
                setHandleAudioBecomingNoisy(true)

                
                try {
                    currentMediaSession?.release()
                    if (canAdvertiseSession()) {
                        currentMediaSession = MediaSession.Builder(context, this).build()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                applyAudioAmplification(_uiState.value.audioAmplificationDb)

                
                notifyAudioSessionUpdate(true)

                val preferred = playerSettings.subtitleStyle.preferredLanguage
                val secondary = playerSettings.subtitleStyle.secondaryPreferredLanguage
                applySubtitlePreferences(preferred, secondary)
                applyStartupSubtitlePreparation(startupSubtitlePreparation)
                val startupSubtitleConfigurations = buildStartupSubtitleConfigurations(startupSubtitlePreparation)
                setMediaSource(
                    mediaSourceFactory.createMediaSource(
                        context = context,
                        url = url,
                        headers = headers,
                        subtitleConfigurations = startupSubtitleConfigurations,
                        filename = currentFilename,
                        responseHeaders = currentStreamResponseHeaders,
                        mimeTypeOverride = currentStreamMimeType
                    )
                )
                if (showLoadingStatus) _uiState.update { it.copy(loadingMessage = context.getString(R.string.player_loading_starting)) }
                playWhenReady = true
                prepare()

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        onExoPlaybackStateForSeekRecovery(playbackState)
                        val playerDuration = duration
                        if (playerDuration > lastKnownDuration) {
                            lastKnownDuration = playerDuration
                        }
                        val isBuffering = playbackState == Player.STATE_BUFFERING
                        updatePlaybackTimeline(duration = playerDuration.coerceAtLeast(0L))
                        _uiState.update { 
                            it.copy(
                                isBuffering = isBuffering,
                                playbackEnded = playbackState == Player.STATE_ENDED
                            )
                        }

                        if (playbackState == Player.STATE_BUFFERING && !hasRenderedFirstFrame) {
                            _uiState.update { state ->
                                if (state.loadingOverlayEnabled && !state.showLoadingOverlay) {
                                    state.copy(showLoadingOverlay = true, showControls = false, loadingMessage = if (showLoadingStatus) context.getString(R.string.player_loading_buffering) else null)
                                } else {
                                    state.copy(loadingMessage = if (showLoadingStatus) context.getString(R.string.player_loading_buffering) else null)
                                }
                            }
                        }
                    
                        
                        if (playbackState == Player.STATE_READY) {
                            if (shouldEnforceAutoplayOnFirstReady) {
                                shouldEnforceAutoplayOnFirstReady = false
                                if (!userPausedManually && !isPlaying) {
                                    if (!playWhenReady) {
                                        playWhenReady = true
                                    }
                                    play()
                                }
                            }
                            tryApplyPendingResumeProgress(this@apply)
                            _uiState.value.pendingSeekPosition?.let { position ->
                                performExoSeekTo(
                                    positionMs = position,
                                    monitorRecovery = true,
                                    reason = "pending-seek"
                                )
                                _uiState.update { it.copy(pendingSeekPosition = null) }
                            }
                            // Re-evaluate subtitle auto-selection once player is ready.
                            tryAutoSelectPreferredSubtitleFromAvailableTracks()

                            trackSelectionParameters = trackSelectionParameters.buildUpon().build()
                        }
                    
                        
                        if (playbackState == Player.STATE_ENDED) {
                            emitCompletionScrobbleStop(progressPercent = 99.5f)
                            saveWatchProgress()
                            resetNextEpisodeCardState(clearEpisode = false)
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _uiState.update { it.copy(isPlaying = isPlaying) }
                        if (isPlaying) {
                            playbackFreezeLastPlayingRealtimeMs = android.os.SystemClock.elapsedRealtime()
                            userPausedManually = false
                            cancelPauseOverlay()
                            startProgressUpdates()
                            startWatchProgressSaving()
                            scheduleHideControls()
                            tryShowParentalGuide()
                            emitScrobbleStart()
                        } else {
                            clearPlaybackFreezeMonitor()
                            if (userPausedManually) {
                                schedulePauseOverlay()
                            } else {
                                cancelPauseOverlay()
                            }
                            stopProgressUpdates()
                            stopWatchProgressSaving()
                            if (playbackState != Player.STATE_BUFFERING) {
                                emitStopScrobbleForCurrentProgress()
                            }
                            
                            saveWatchProgress()
                        }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        updateAvailableTracks(tracks)
                    }

                    override fun onRenderedFirstFrame() {
                        hasRenderedFirstFrame = true
                        clearSeekRecovery()
                        clearPlaybackFreezeMonitor()
                        resetErrorRetryState()
                        // Restore speed after PCM fallback — audio sink is already
                        // configured in PCM mode and won't revert to passthrough.
                        if (hasTriedAudioPcmFallback) {
                            _exoPlayer?.playbackParameters = PlaybackParameters(1f)
                        }
                        _uiState.update {
                            it.copy(
                                showLoadingOverlay = false,
                                loadingMessage = null,
                                // Snap the loading-logo fill to 100% so the logo
                                // appears fully filled as the overlay fades out
                                // (rather than freezing at the partial buffer %).
                                loadingProgress = if (it.loadingProgress != null) 1f else null,
                                showPlayerEngineSwitchInfo = false
                            )
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        clearSeekRecovery()
                        clearPlaybackFreezeMonitor()
                        if (isReleasingPlayer && error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT) {
                            return
                        }
                        val detailedError = error.toDisplayMessage()
                        val responseCode = error.findInvalidResponseCodeException()?.responseCode
                        if (responseCode == 416 && !hasRetriedCurrentStreamAfter416) {
                            retryCurrentStreamFromStartAfter416()
                            return
                        }
                        if (maybeAutoSwitchInternalPlayerOnStartupError(
                                detailedError = detailedError,
                                allowEngineFailover = allowEngineFailover
                            )
                        ) {
                            return
                        }
                        // Attempt automatic recovery for transient errors.
                        if (tryAudioTrackPcmFallback(error)) {
                            return
                        }
                        if (tryAv1SoftwareDecoderFallback(error)) {
                            return
                        }
                        if (tryDv7HevcFallback(error)) {
                            return
                        }
                        if (attemptStartupRecovery(error, detailedError)) {
                            return
                        }
                        if (hasRenderedFirstFrame && attemptAutoRetry(error, detailedError)) {
                            return
                        }
                        _uiState.update {
                            it.copy(
                                error = detailedError,
                                showLoadingOverlay = false,
                                showPauseOverlay = false
                            )
                        }
                    }
                })
            }
            if (!startupSubtitlePreparation.fetchCompleted) {
                fetchAddonSubtitles()
            }
        } catch (e: Exception) {
            if (
                maybeAutoSwitchInternalPlayerOnStartupError(
                    detailedError = e.message ?: "Failed to initialize player",
                    allowEngineFailover = allowEngineFailover
                )
            ) {
                return@launch
            }
            _uiState.update {
                it.copy(
                    error = e.toDisplayMessage("Failed to initialize player"),
                    showLoadingOverlay = false
                )
            }
        }
    }
}

internal fun resolvePreferredAudioLanguages(
    preferredAudioLanguage: String,
    secondaryPreferredAudioLanguage: String?,
    deviceLanguages: List<String>,
    contentOriginalLanguage: String? = null
): List<String> {
    fun normalize(language: String?): String? {
        val normalized = language
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return when (normalized) {
            AudioLanguageOption.DEFAULT,
            AudioLanguageOption.DEVICE,
            AudioLanguageOption.ORIGINAL,
            SUBTITLE_LANGUAGE_FORCED -> null
            else -> normalized
        }
    }

    return when (preferredAudioLanguage.trim().lowercase()) {
        AudioLanguageOption.DEFAULT -> listOfNotNull(
            normalize(secondaryPreferredAudioLanguage)
        ).distinct()
        AudioLanguageOption.DEVICE -> (
            deviceLanguages
            .mapNotNull(::normalize)
            + listOfNotNull(normalize(secondaryPreferredAudioLanguage))
            ).distinct()
        AudioLanguageOption.ORIGINAL -> {
            val originalLang = normalize(contentOriginalLanguage)
            if (originalLang != null) {
                listOfNotNull(
                    originalLang,
                    normalize(secondaryPreferredAudioLanguage)
                ).distinct()
            } else {
                // Fallback to device languages when original language is unknown
                (deviceLanguages
                    .mapNotNull(::normalize)
                    + listOfNotNull(normalize(secondaryPreferredAudioLanguage))
                ).distinct()
            }
        }
        else -> listOfNotNull(
            normalize(preferredAudioLanguage),
            normalize(secondaryPreferredAudioLanguage)
        ).distinct()
    }
}

internal fun resolveDeviceAudioLanguages(): List<String> {
    return if (Build.VERSION.SDK_INT >= 24) {
        val localeList = Resources.getSystem().configuration.locales
        List(localeList.size()) { localeList[it].isO3Language }
    } else {
        listOf(Resources.getSystem().configuration.locale.isO3Language)
    }
}

internal suspend fun PlayerRuntimeController.prepareStartupSubtitles(
    mode: AddonSubtitleStartupMode,
    preferredLanguage: String,
    secondaryLanguage: String?,
    showLoadingStatus: Boolean = true
): StartupSubtitlePreparation {
    if (mode == AddonSubtitleStartupMode.FAST_STARTUP) {
        return StartupSubtitlePreparation(
            fetchedSubtitles = emptyList(),
            attachedSubtitles = emptyList(),
            fetchCompleted = false
        )
    }

    if (buildSubtitleFetchRequest() == null) {
        return StartupSubtitlePreparation(
            fetchedSubtitles = emptyList(),
            attachedSubtitles = emptyList(),
            fetchCompleted = false
        )
    }

    val preferredTargets = when (PlayerSubtitleUtils.normalizeLanguageCode(preferredLanguage)) {
        "none" -> listOfNotNull(
            secondaryLanguage
                ?.takeIf { it.isNotBlank() }
        )
        else -> listOfNotNull(
            preferredLanguage,
            secondaryLanguage?.takeIf { it.isNotBlank() }
        )
    }.map { PlayerSubtitleUtils.normalizeLanguageCode(it) }
        .distinct()

    if (mode == AddonSubtitleStartupMode.PREFERRED_ONLY && preferredTargets.isEmpty()) {
        return StartupSubtitlePreparation(
            fetchedSubtitles = emptyList(),
            attachedSubtitles = emptyList(),
            fetchCompleted = false
        )
    }

    _uiState.update { it.copy(isLoadingAddonSubtitles = true, addonSubtitlesError = null) }

    val fetchedSubtitles = withTimeoutOrNull(STARTUP_SUBTITLE_PREFETCH_TIMEOUT_MS) {
        fetchAddonSubtitlesNow(
            onProgress = if (showLoadingStatus) { completed, total, addonName ->
                val msg = if (completed == 0) {
                    context.getString(R.string.player_loading_subtitles_from, total)
                } else if (addonName != null) {
                    context.getString(R.string.player_loading_subtitles_addon, addonName, completed, total)
                } else {
                    context.getString(R.string.player_loading_subtitles_progress, completed, total)
                }
                _uiState.update { it.copy(loadingMessage = msg) }
            } else null
        )
    } ?: return StartupSubtitlePreparation(
        fetchedSubtitles = emptyList(),
        attachedSubtitles = emptyList(),
        fetchCompleted = false
    )

    val attachedSubtitles = when (mode) {
        AddonSubtitleStartupMode.ALL_SUBTITLES -> fetchedSubtitles
        AddonSubtitleStartupMode.PREFERRED_ONLY -> fetchedSubtitles.filter { subtitle ->
            preferredTargets.any { target ->
                PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, target)
            }
        }
        AddonSubtitleStartupMode.FAST_STARTUP -> emptyList()
    }

    return StartupSubtitlePreparation(
        fetchedSubtitles = fetchedSubtitles,
        attachedSubtitles = attachedSubtitles,
        fetchCompleted = true
    )
}

internal fun PlayerRuntimeController.resetAddonSubtitleStateForNewStream() {
    logSwitchTrace(
        stage = "reset-addon-state-new-stream",
        message = "autoSubtitleSelectedBefore=$autoSubtitleSelected " +
            "subtitleDisabledByPersistedPreference=$subtitleDisabledByPersistedPreference " +
            "subtitleAddonRestoredByPersistedPreference=$subtitleAddonRestoredByPersistedPreference " +
            "explicitSelectionBefore=${explicitSubtitleSelectionForEngineSwitch?.selection?.javaClass?.simpleName ?: "none"} " +
            "effectiveSelectionBefore=${effectiveSubtitleSelectionForEngineSwitch?.selection?.javaClass?.simpleName ?: "none"}"
    )
    autoSubtitleSelected = subtitleDisabledByPersistedPreference || subtitleAddonRestoredByPersistedPreference
    hasScannedTextTracksOnce = false
    pendingAddonSubtitleLanguage = null
    pendingAddonSubtitleTrackId = null
    pendingAudioSelectionAfterSubtitleRefresh = null
    explicitSubtitleSelectionForEngineSwitch = null
    effectiveSubtitleSelectionForEngineSwitch = null
    attachedAddonSubtitleKeys = emptySet()
    logSwitchTrace(
        stage = "reset-addon-state-new-stream",
        message = "autoSubtitleSelectedAfter=$autoSubtitleSelected explicitSelectionAfter=none effectiveSelectionAfter=none"
    )
    _uiState.update {
        it.copy(
            addonSubtitles = emptyList(),
            selectedAddonSubtitle = null,
            selectedSubtitleTrackIndex = -1,
            isLoadingAddonSubtitles = false,
            addonSubtitlesError = null
        )
    }
}

internal suspend fun PlayerRuntimeController.prepareStreamStartSubtitles(
    playerSettings: PlayerSettings,
    showLoadingStatus: Boolean = true
): StartupSubtitlePreparation {
    requestedUseLibassByUser = playerSettings.useLibass
    if (libassPipelineDecisionStreamUrl != currentStreamUrl) {
        libassPipelineDecisionStreamUrl = currentStreamUrl
        libassPipelineOverrideForCurrentStream = null
        libassPipelineSwitchInFlight = false
        hasDetectedAssSsaTrackForCurrentStream = false
    }
    resetAddonSubtitleStateForNewStream()
    return prepareStartupSubtitles(
        mode = playerSettings.addonSubtitleStartupMode,
        preferredLanguage = playerSettings.subtitleStyle.preferredLanguage,
        secondaryLanguage = playerSettings.subtitleStyle.secondaryPreferredLanguage,
        showLoadingStatus = showLoadingStatus
    )
}

internal fun PlayerRuntimeController.applyStartupSubtitlePreparation(
    startupSubtitlePreparation: StartupSubtitlePreparation
) {
    attachedAddonSubtitleKeys = startupSubtitlePreparation.attachedSubtitles
        .distinctBy { addonSubtitleKey(it) }
        .map(::addonSubtitleKey)
        .toSet()
    if (!startupSubtitlePreparation.fetchCompleted) return

    _uiState.update {
        it.copy(
            addonSubtitles = startupSubtitlePreparation.fetchedSubtitles,
            isLoadingAddonSubtitles = false,
            addonSubtitlesError = null
        )
    }
}

internal fun PlayerRuntimeController.buildStartupSubtitleConfigurations(
    startupSubtitlePreparation: StartupSubtitlePreparation
): List<androidx.media3.common.MediaItem.SubtitleConfiguration> {
    return startupSubtitlePreparation.attachedSubtitles
        .distinctBy { "${it.id}|${it.url}" }
        .map(::toSubtitleConfiguration)
}

internal fun PlayerRuntimeController.resetLoadingOverlayForNewStream() {
    hasRenderedFirstFrame = false
    shouldEnforceAutoplayOnFirstReady = true
    userPausedManually = false
    lastKnownDuration = 0L
    _uiState.update { state ->
        state.copy(
            showLoadingOverlay = state.loadingOverlayEnabled,
            showControls = false,
            loadingProgress = null
        )
    }
}

private class SubtitleOffsetRenderersFactory(
    context: Context,
    private val subtitleDelayUsProvider: () -> Long,
    private val shouldNormalizeCuePositionProvider: () -> Boolean,
    private val gainAudioProcessor: GainAudioProcessor,
    private val playbackSpeedProvider: () -> Float,
    private val onPlaybackSpeedAwareAudioOutputProviderCreated: (PlaybackSpeedAwareAudioOutputProvider) -> Unit,
    private val preferDolbyAudioCompatibilityMode: Boolean,
    private val mapDV7ToHevc: Boolean,
    private val disableDolbyVision: Boolean,
    private val disableDolbyVisionForDv7: Boolean,
    private val requestSdrToneMapping: Boolean,
    private val convertHdr10PlusToHdr10: Boolean,
    private val forceSoftwareAv1Playback: Boolean,
    private val forceInterpretHdrAsSdr: Boolean
) : DefaultRenderersFactory(context) {

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        if (!preferDolbyAudioCompatibilityMode) {
            super.buildAudioRenderers(
                context,
                extensionRendererMode,
                mediaCodecSelector,
                enableDecoderFallback,
                audioSink,
                eventHandler,
                eventListener,
                out
            )
            return
        }

        val effectiveExtensionRendererMode =
            if (extensionRendererMode == EXTENSION_RENDERER_MODE_OFF) {
                EXTENSION_RENDERER_MODE_ON
            } else {
                extensionRendererMode
            }

        val dolbyCompatibilitySelector = MediaCodecSelector {
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder ->
            if (isDolbyCompatibilityMimeType(mimeType)) {
                emptyList()
            } else {
                mediaCodecSelector.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder
                )
            }
        }

        super.buildAudioRenderers(
            context,
            effectiveExtensionRendererMode,
            dolbyCompatibilitySelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out
        )
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        if (
            !disableDolbyVision &&
            !disableDolbyVisionForDv7 &&
            !requestSdrToneMapping &&
            !convertHdr10PlusToHdr10 &&
            !forceSoftwareAv1Playback &&
            !forceInterpretHdrAsSdr
        ) {
            super.buildVideoRenderers(
                context,
                extensionRendererMode,
                mediaCodecSelector,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                allowedVideoJoiningTimeMs,
                out
            )
            return
        }

        val videoRendererBuilder = MediaCodecVideoRenderer.Builder(context)
            .setCodecAdapterFactory(getCodecAdapterFactory())
            .setMediaCodecSelector(mediaCodecSelector)
            .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
            .setEnableDecoderFallback(enableDecoderFallback)
            .setEventHandler(eventHandler)
            .setEventListener(eventListener)
            .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
            .setMapDV7ToHevc(mapDV7ToHevc)

        out.add(
            NuvioMediaCodecVideoRenderer(
                context = context,
                builder = videoRendererBuilder,
                mapDV7ToHevc = mapDV7ToHevc,
                disableDolbyVision = disableDolbyVision,
                disableDolbyVisionForDv7 = disableDolbyVisionForDv7,
                requestSdrToneMapping = requestSdrToneMapping,
                convertHdr10PlusToHdr10 = convertHdr10PlusToHdr10,
                forceSoftwareAv1Playback = forceSoftwareAv1Playback,
                forceInterpretHdrAsSdr = forceInterpretHdrAsSdr
            )
        )

        if (extensionRendererMode == EXTENSION_RENDERER_MODE_OFF) {
            return
        }

        var extensionRendererIndex = out.size
        if (extensionRendererMode == EXTENSION_RENDERER_MODE_PREFER) {
            extensionRendererIndex--
        }

        extensionRendererIndex =
            addOptionalVideoRenderer(
                out = out,
                extensionRendererIndex = extensionRendererIndex,
                className = "androidx.media3.decoder.vp9.LibvpxVideoRenderer",
                allowedVideoJoiningTimeMs = allowedVideoJoiningTimeMs,
                eventHandler = eventHandler,
                eventListener = eventListener
            )
        extensionRendererIndex =
            addOptionalVideoRenderer(
                out = out,
                extensionRendererIndex = extensionRendererIndex,
                className = "androidx.media3.decoder.av1.Libdav1dVideoRenderer",
                allowedVideoJoiningTimeMs = allowedVideoJoiningTimeMs,
                eventHandler = eventHandler,
                eventListener = eventListener
            )
        addOptionalVideoRenderer(
            out = out,
            extensionRendererIndex = extensionRendererIndex,
            className = "androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer",
            allowedVideoJoiningTimeMs = allowedVideoJoiningTimeMs,
            eventHandler = eventHandler,
            eventListener = eventListener
        )
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        val baseAudioOutputProvider = AudioTrackAudioOutputProvider.Builder(context)
            .setMaxPlaybackSpeed(PLAYBACK_SPEEDS.maxOrNull() ?: 2f)
            .build()
        val audioOutputProvider = PlaybackSpeedAwareAudioOutputProvider(baseAudioOutputProvider)
        audioOutputProvider.updatePlaybackSpeed(
            playbackSpeedProvider(),
            forceDolbyCompatibilityMode = preferDolbyAudioCompatibilityMode
        )
        onPlaybackSpeedAwareAudioOutputProviderCreated(audioOutputProvider)

        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(gainAudioProcessor))
            .setAudioOutputProvider(audioOutputProvider)
            .build()
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: android.os.Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
        val normalizingOutput = CueNormalizingTextOutput(
            delegate = output,
            shouldNormalizeCuePositionProvider = shouldNormalizeCuePositionProvider
        )
        val startIndex = out.size
        super.buildTextRenderers(context, normalizingOutput, outputLooper, extensionRendererMode, out)
        for (index in startIndex until out.size) {
            out[index] = SubtitleOffsetRenderer(out[index], subtitleDelayUsProvider)
        }
    }
}

private fun isDolbyCompatibilityMimeType(mimeType: String): Boolean {
    return mimeType == MimeTypes.AUDIO_AC3 ||
        mimeType == MimeTypes.AUDIO_E_AC3 ||
        mimeType == MimeTypes.AUDIO_E_AC3_JOC ||
        mimeType == MimeTypes.AUDIO_TRUEHD
}

private fun addOptionalVideoRenderer(
    out: ArrayList<Renderer>,
    extensionRendererIndex: Int,
    className: String,
    allowedVideoJoiningTimeMs: Long,
    eventHandler: Handler,
    eventListener: VideoRendererEventListener
): Int {
    return try {
        val clazz = Class.forName(className)
        val constructor = clazz.getConstructor(
            Long::class.javaPrimitiveType,
            Handler::class.java,
            VideoRendererEventListener::class.java,
            Int::class.javaPrimitiveType
        )
        val renderer = constructor.newInstance(
            allowedVideoJoiningTimeMs,
            eventHandler,
            eventListener,
            DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
        ) as Renderer
        out.add(extensionRendererIndex, renderer)
        extensionRendererIndex + 1
    } catch (_: ClassNotFoundException) {
        extensionRendererIndex
    }
}

private class NuvioMediaCodecVideoRenderer(
    context: Context,
    builder: MediaCodecVideoRenderer.Builder,
    private val mapDV7ToHevc: Boolean,
    private val disableDolbyVision: Boolean,
    private val disableDolbyVisionForDv7: Boolean,
    private val requestSdrToneMapping: Boolean,
    private val convertHdr10PlusToHdr10: Boolean,
    private val forceSoftwareAv1Playback: Boolean,
    private val forceInterpretHdrAsSdr: Boolean
) : MediaCodecVideoRenderer(builder) {

    private val appContext = context.applicationContext
    private var rendererTunnelingEnabled = false
    private var lastConfiguredInputFormat: Format? = null

    override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int {
        if (shouldForceSoftwareAv1Playback(format)) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        }
        if (!shouldForceDolbyVisionFallback(format)) {
            return super.supportsFormat(mediaCodecSelector, format)
        }

        val decoderQueryFormat = getDecoderQueryFormat(format)

        val requiresSecureDecryption = format.drmInitData != null
        var decoderInfos =
            getAlternativeDecoderInfos(
                mediaCodecSelector = mediaCodecSelector,
                format = format,
                requiresSecureDecoder = requiresSecureDecryption,
                requiresTunnelingDecoder = false
            )
        if (requiresSecureDecryption && decoderInfos.isEmpty()) {
            decoderInfos =
                getAlternativeDecoderInfos(
                    mediaCodecSelector = mediaCodecSelector,
                    format = format,
                    requiresSecureDecoder = false,
                    requiresTunnelingDecoder = false
                )
        }
        if (decoderInfos.isEmpty()) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        }
        if (!supportsFormatDrm(format)) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM)
        }

        var decoderInfo = decoderInfos.first()
        var isFormatSupported = decoderInfo.isFormatSupported(appContext, decoderQueryFormat)
        if (!isFormatSupported) {
            for (index in 1 until decoderInfos.size) {
                val otherDecoderInfo = decoderInfos[index]
                if (otherDecoderInfo.isFormatSupported(appContext, decoderQueryFormat)) {
                    decoderInfo = otherDecoderInfo
                    isFormatSupported = true
                    break
                }
            }
        }

        val formatSupport = if (isFormatSupported) C.FORMAT_HANDLED else C.FORMAT_EXCEEDS_CAPABILITIES
        val adaptiveSupport =
            if (decoderInfo.isSeamlessAdaptationSupported(decoderQueryFormat)) {
                RendererCapabilities.ADAPTIVE_SEAMLESS
            } else {
                RendererCapabilities.ADAPTIVE_NOT_SEAMLESS
            }
        val hardwareAccelerationSupport =
            if (decoderInfo.hardwareAccelerated) {
                RendererCapabilities.HARDWARE_ACCELERATION_SUPPORTED
            } else {
                RendererCapabilities.HARDWARE_ACCELERATION_NOT_SUPPORTED
            }
        val tunnelingSupport =
            if (isFormatSupported &&
                getAlternativeDecoderInfos(
                    mediaCodecSelector = mediaCodecSelector,
                    format = format,
                    requiresSecureDecoder = requiresSecureDecryption,
                    requiresTunnelingDecoder = true
                ).firstOrNull()?.let { tunnelingDecoderInfo ->
                    tunnelingDecoderInfo.isFormatSupported(appContext, decoderQueryFormat) &&
                        tunnelingDecoderInfo.isSeamlessAdaptationSupported(decoderQueryFormat)
                } == true
            ) {
                RendererCapabilities.TUNNELING_SUPPORTED
            } else {
                RendererCapabilities.TUNNELING_NOT_SUPPORTED
            }

        return RendererCapabilities.create(
            formatSupport,
            adaptiveSupport,
            tunnelingSupport,
            hardwareAccelerationSupport,
            RendererCapabilities.DECODER_SUPPORT_FALLBACK_MIMETYPE
        )
    }

    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean
    ): List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
        if (shouldForceSoftwareAv1Playback(format)) {
            return emptyList()
        }
        if (!shouldForceDolbyVisionFallback(format)) {
            return super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
        }

        return getAlternativeDecoderInfos(
            mediaCodecSelector = mediaCodecSelector,
            format = format,
            requiresSecureDecoder = requiresSecureDecoder,
            requiresTunnelingDecoder = rendererTunnelingEnabled
        )
    }

    override fun onEnabled(joining: Boolean, mayRenderStartOfStream: Boolean) {
        rendererTunnelingEnabled = getConfiguration().tunneling
        super.onEnabled(joining, mayRenderStartOfStream)
    }

    override fun onDisabled() {
        rendererTunnelingEnabled = false
        super.onDisabled()
    }

    override fun createPlaybackVideoGraphWrapper(
        context: Context,
        videoFrameReleaseControl: VideoFrameReleaseControl
    ): PlaybackVideoGraphWrapper {
        return super.createPlaybackVideoGraphWrapper(context, videoFrameReleaseControl).apply {
            if (requestSdrToneMapping) {
                setRequestOpenGlToneMapping(true)
            }
            if (forceInterpretHdrAsSdr) {
                setIsInputSdrToneMapped(true)
            }
        }
    }

    override fun handleInputBufferSupplementalData(buffer: DecoderInputBuffer) {
        if (
            (convertHdr10PlusToHdr10 && lastConfiguredInputFormat?.let(::isHdrVideo) == true) ||
            lastConfiguredInputFormat?.let(::shouldUseAppLevelDv7HdrFallback) == true
        ) {
            return
        }
        super.handleInputBufferSupplementalData(buffer)
    }

    override fun getMediaFormat(
        format: Format,
        codecMimeType: String,
        codecMaxValues: CodecMaxValues,
        codecOperatingRate: Float,
        deviceNeedsNoPostProcessWorkaround: Boolean,
        tunnelingAudioSessionId: Int
    ): MediaFormat {
        lastConfiguredInputFormat = format
        val decoderFormat =
            if (
                forceInterpretHdrAsSdr &&
                isHdrVideo(format)
            ) {
                getDecoderQueryFormat(format).buildUpon().setColorInfo(ColorInfo.SDR_BT709_LIMITED).build()
            } else {
                getDecoderQueryFormat(format)
            }
        val mediaFormat = super.getMediaFormat(
            decoderFormat,
            codecMimeType,
            codecMaxValues,
            codecOperatingRate,
            deviceNeedsNoPostProcessWorkaround,
            tunnelingAudioSessionId
        )
        return mediaFormat
    }

    private fun shouldForceDolbyVisionFallback(format: Format): Boolean {
        if (!isDolbyVisionVideo(format)) {
            return false
        }

        val hdrCompatibilityRequestsFallback =
            (requestSdrToneMapping || convertHdr10PlusToHdr10 || forceInterpretHdrAsSdr)

        return disableDolbyVision ||
            (disableDolbyVisionForDv7 && isDolbyVisionProfile7(format)) ||
            hdrCompatibilityRequestsFallback
    }

    private fun shouldForceSoftwareAv1Playback(format: Format): Boolean {
        return forceSoftwareAv1Playback &&
            format.sampleMimeType == MimeTypes.VIDEO_AV1 &&
            format.cryptoType == C.CRYPTO_TYPE_NONE &&
            format.drmInitData == null
    }

    private fun getAlternativeDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean
    ): List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
        if (!shouldForceDolbyVisionFallback(format)) {
            return emptyList()
        }

        val decoderQueryFormat = getDecoderQueryFormat(format)
        if (shouldUseAppLevelDv7HdrFallback(format)) {
            val sampleMimeType = decoderQueryFormat.sampleMimeType ?: return emptyList()
            val decoderInfos = mediaCodecSelector.getDecoderInfos(
                sampleMimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )
            return MediaCodecUtil.getDecoderInfosSortedByFormatSupport(
                appContext,
                decoderInfos,
                decoderQueryFormat
            )
        }

        val alternativeDecoderInfos = MediaCodecUtil.getAlternativeDecoderInfos(
            mediaCodecSelector,
            format,
            requiresSecureDecoder,
            requiresTunnelingDecoder,
            mapDV7ToHevc
        )
        return MediaCodecUtil.getDecoderInfosSortedByFormatSupport(
            appContext,
            alternativeDecoderInfos,
            decoderQueryFormat
        )
    }

    private fun getDecoderQueryFormat(format: Format): Format {
        return if (shouldUseAppLevelDv7HdrFallback(format)) {
            buildDv7HdrFallbackFormat(format)
        } else {
            format
        }
    }

    private fun shouldUseAppLevelDv7HdrFallback(format: Format): Boolean {
        return mapDV7ToHevc && isHdr10CompatibleDolbyVisionProfile7(format)
    }
}

internal fun buildDv7HdrFallbackFormat(format: Format): Format {
    return format.buildUpon()
        .setSampleMimeType(MimeTypes.VIDEO_H265)
        .setCodecs(extractHevcFallbackCodecs(format.codecs))
        .build()
}

internal fun buildDv7HevcFallbackFormat(format: Format): Format = buildDv7HdrFallbackFormat(format)

internal fun extractHevcFallbackCodecs(codecs: String?): String? {
    val codecEntries = codecs
        ?.split(',')
        ?.asSequence()
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.toList()
        ?: return null

    return codecEntries.firstOrNull { codec ->
        codec.startsWith("hev1", ignoreCase = true) || codec.startsWith("hvc1", ignoreCase = true)
    } ?: codecEntries.firstOrNull { codec ->
        !DOLBY_VISION_CODEC_REGEX.matches(codec)
    }
}

internal fun isDolbyVisionProfile7(format: Format): Boolean {
    if (format.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) {
        return false
    }

    return format.codecs
        ?.split(',')
        ?.asSequence()
        ?.map(String::trim)
        ?.mapNotNull { codec -> DOLBY_VISION_PROFILE_REGEX.find(codec)?.groupValues?.getOrNull(1)?.toIntOrNull() }
        ?.any { profile -> profile == 7 }
        ?: false
}

internal fun isHdr10CompatibleDolbyVisionProfile7(format: Format): Boolean {
    if (!isDolbyVisionProfile7(format)) {
        return false
    }

    val colorInfo = format.colorInfo ?: return false
    return colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084
}

private val DOLBY_VISION_CODEC_REGEX = Regex("""(?i)(?:dvhe|dvh1)\..+""")
private val DOLBY_VISION_PROFILE_REGEX = Regex("""(?i)(?:^|\b)(?:dvhe|dvh1)\.(\d{1,2})(?:\.|$)""")

private class CueNormalizingTextOutput(
    private val delegate: TextOutput,
    private val shouldNormalizeCuePositionProvider: () -> Boolean
) : TextOutput {

    override fun onCues(cueGroup: CueGroup) {
        if (!shouldNormalizeCuePositionProvider()) {
            delegate.onCues(cueGroup)
            return
        }
        delegate.onCues(CueGroup(cueGroup.cues.map(::normalizeCuePosition), cueGroup.presentationTimeUs))
    }

    @Deprecated("Uses the deprecated Media3 callback for text outputs.")
    override fun onCues(cues: List<Cue>) {
        if (!shouldNormalizeCuePositionProvider()) {
            delegate.onCues(cues)
            return
        }
        delegate.onCues(cues.map(::normalizeCuePosition))
    }

    private fun normalizeCuePosition(cue: Cue): Cue {
        if (cue.bitmap != null || cue.verticalType != Cue.TYPE_UNSET || cue.line == Cue.DIMEN_UNSET) {
            return cue
        }
        return cue.buildUpon()
            .setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET)
            .setLineAnchor(Cue.TYPE_UNSET)
            .build()
    }
}

private class SubtitleOffsetRenderer(
    private val baseRenderer: Renderer,
    private val subtitleDelayUsProvider: () -> Long
) : ForwardingRenderer(baseRenderer) {

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val offset = subtitleDelayUsProvider()
        val adjustedPositionUs = (positionUs - offset).coerceAtLeast(0L)
        
        super.render(adjustedPositionUs, elapsedRealtimeUs)
    }
}

