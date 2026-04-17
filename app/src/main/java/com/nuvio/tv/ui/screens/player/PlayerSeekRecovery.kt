package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val SEEK_RECOVERY_POLL_MS = 250L
private const val SEEK_RECOVERY_INITIAL_GRACE_MS = 900L
private const val SEEK_RECOVERY_BUFFERING_STALL_TIMEOUT_MS = 3_000L
private const val SEEK_RECOVERY_READY_STALL_TIMEOUT_MS = 2_000L
private const val SEEK_RECOVERY_PROGRESS_TOLERANCE_MS = 300L
private const val SEEK_RECOVERY_MIN_BUFFER_AHEAD_MS = 1_500L
private const val SEEK_RECOVERY_MAX_RESEEKS = 1
private const val SEEK_RECOVERY_MAX_SOFT_RESETS = 1
private const val PLAYBACK_FREEZE_ARM_DELAY_MS = 500L
private const val PLAYBACK_FREEZE_STALL_TIMEOUT_MS = 2_500L
private const val PLAYBACK_FREEZE_PROGRESS_TOLERANCE_MS = 150L
private const val PLAYBACK_FREEZE_MIN_BUFFER_AHEAD_MS = 500L
private const val PLAYBACK_FREEZE_MAX_SOFT_RESETS = 1

internal fun PlayerRuntimeController.buildExoLoadControl(): LoadControl {
    return DefaultLoadControl.Builder()
        .setTargetBufferBytes(100 * 1024 * 1024)
        .setBufferDurationsMs(
            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            70_000,
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
            5_000
        )
        .build()
}

internal fun PlayerRuntimeController.performExoSeekTo(
    positionMs: Long,
    monitorRecovery: Boolean = true,
    reason: String = "user-seek"
) {
    val player = _exoPlayer ?: return
    val duration = player.duration
    val targetPositionMs = if (duration > 0L) {
        positionMs.coerceIn(0L, duration)
    } else {
        positionMs.coerceAtLeast(0L)
    }

    if (monitorRecovery) {
        beginSeekRecovery(player = player, targetPositionMs = targetPositionMs, reason = reason)
    } else {
        clearSeekRecovery()
    }

    player.seekTo(targetPositionMs)
}

internal fun PlayerRuntimeController.clearSeekRecovery() {
    seekRecoveryJob?.cancel()
    seekRecoveryJob = null
    seekRecoveryTargetPositionMs = null
    seekRecoveryArmedAtRealtimeMs = 0L
    seekRecoveryLastProgressRealtimeMs = 0L
    seekRecoveryLastObservedPositionMs = 0L
    seekRecoveryLastObservedBufferedPositionMs = 0L
    seekRecoveryReseekCount = 0
    seekRecoveryRestartCount = 0
    seekRecoveryExpectedPlayWhenReady = true
    seekRecoveryGeneration++
}

internal fun PlayerRuntimeController.monitorExoPlaybackFreeze(
    player: Player,
    currentPositionMs: Long,
    bufferedPositionMs: Long
) {
    val state = _uiState.value
    val playbackEligible =
        hasRenderedFirstFrame &&
            !state.showLoadingOverlay &&
            !userPausedManually &&
            !state.playbackEnded &&
            player.playWhenReady &&
            player.playbackState == Player.STATE_READY

    if (!playbackEligible) {
        clearPlaybackFreezeMonitor()
        return
    }

    val now = SystemClock.elapsedRealtime()
    val bufferedAheadMs = (bufferedPositionMs - currentPositionMs).coerceAtLeast(0L)
    if (playbackFreezeArmedAtRealtimeMs == 0L) {
        playbackFreezeArmedAtRealtimeMs = now
        playbackFreezeLastProgressRealtimeMs = now
        playbackFreezeLastObservedPositionMs = currentPositionMs
        playbackFreezeRecoveryCount = 0
        Log.d(
            PlayerRuntimeController.TAG,
            "Playback freeze monitor armed: pos=${currentPositionMs}ms buffered=${bufferedPositionMs}ms ahead=${bufferedAheadMs}ms overlay=${state.showLoadingOverlay}"
        )
        return
    }

    if (currentPositionMs > playbackFreezeLastObservedPositionMs + PLAYBACK_FREEZE_PROGRESS_TOLERANCE_MS) {
        if (playbackFreezeRecoveryCount > 0) {
            Log.d(
                PlayerRuntimeController.TAG,
                "Playback freeze monitor recovered: pos=${currentPositionMs}ms buffered=${bufferedPositionMs}ms ahead=${bufferedAheadMs}ms"
            )
        }
        playbackFreezeLastProgressRealtimeMs = now
        playbackFreezeLastObservedPositionMs = currentPositionMs
        playbackFreezeRecoveryCount = 0
        return
    }

    val armedForMs = now - playbackFreezeArmedAtRealtimeMs
    val stalledForMs = now - playbackFreezeLastProgressRealtimeMs
    if (
        armedForMs < PLAYBACK_FREEZE_ARM_DELAY_MS ||
            stalledForMs < PLAYBACK_FREEZE_STALL_TIMEOUT_MS ||
            bufferedAheadMs < PLAYBACK_FREEZE_MIN_BUFFER_AHEAD_MS
    ) {
        return
    }

    if (playbackFreezeRecoveryCount >= PLAYBACK_FREEZE_MAX_SOFT_RESETS) {
        playbackFreezeLastProgressRealtimeMs = now
        Log.w(
            PlayerRuntimeController.TAG,
            "Playback freeze monitor detected persistent freeze: pos=${currentPositionMs}ms buffered=${bufferedPositionMs}ms ahead=${bufferedAheadMs}ms stalled=${stalledForMs}ms"
        )
        return
    }

    playbackFreezeRecoveryCount++
    Log.w(
        PlayerRuntimeController.TAG,
        "Playback freeze monitor triggering soft reset ${playbackFreezeRecoveryCount}/$PLAYBACK_FREEZE_MAX_SOFT_RESETS: pos=${currentPositionMs}ms buffered=${bufferedPositionMs}ms ahead=${bufferedAheadMs}ms stalled=${stalledForMs}ms"
    )
    triggerPlaybackFreezeSoftReset(currentPositionMs)
}

internal fun PlayerRuntimeController.clearPlaybackFreezeMonitor() {
    playbackFreezeArmedAtRealtimeMs = 0L
    playbackFreezeLastProgressRealtimeMs = 0L
    playbackFreezeLastObservedPositionMs = 0L
    playbackFreezeRecoveryCount = 0
}

internal fun PlayerRuntimeController.onExoPlaybackStateForSeekRecovery(playbackState: Int) {
    when (playbackState) {
        Player.STATE_ENDED, Player.STATE_IDLE -> {
            clearSeekRecovery()
            clearPlaybackFreezeMonitor()
        }
    }
}

private fun PlayerRuntimeController.beginSeekRecovery(
    player: Player,
    targetPositionMs: Long,
    reason: String
) {
    val now = SystemClock.elapsedRealtime()
    seekRecoveryJob?.cancel()
    seekRecoveryTargetPositionMs = targetPositionMs
    seekRecoveryArmedAtRealtimeMs = now
    seekRecoveryLastProgressRealtimeMs = now
    seekRecoveryLastObservedPositionMs = targetPositionMs
    seekRecoveryLastObservedBufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L)
    seekRecoveryReseekCount = 0
    seekRecoveryRestartCount = 0
    seekRecoveryExpectedPlayWhenReady = player.playWhenReady
    val generation = ++seekRecoveryGeneration

    Log.d(
        PlayerRuntimeController.TAG,
        "Seek recovery armed: reason=$reason target=${targetPositionMs}ms playWhenReady=${player.playWhenReady}"
    )

    seekRecoveryJob = scope.launch {
        delay(SEEK_RECOVERY_POLL_MS)
        while (isActive && generation == seekRecoveryGeneration) {
            val exoPlayer = _exoPlayer ?: break
            val activeTargetMs = seekRecoveryTargetPositionMs ?: break
            val nowRealtime = SystemClock.elapsedRealtime()
            val currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            val bufferedPositionMs = exoPlayer.bufferedPosition.coerceAtLeast(0L)
            val bufferedAheadMs = (bufferedPositionMs - currentPositionMs).coerceAtLeast(0L)
            val positionAdvanced =
                currentPositionMs > seekRecoveryLastObservedPositionMs + SEEK_RECOVERY_PROGRESS_TOLERANCE_MS
            val bufferAdvanced =
                bufferedPositionMs > seekRecoveryLastObservedBufferedPositionMs + SEEK_RECOVERY_PROGRESS_TOLERANCE_MS

            if (!seekRecoveryExpectedPlayWhenReady || !exoPlayer.playWhenReady) {
                clearSeekRecovery()
                break
            }

            when (exoPlayer.playbackState) {
                Player.STATE_READY -> {
                    if (positionAdvanced) {
                        clearSeekRecovery()
                        break
                    }

                    val armedForMs = nowRealtime - seekRecoveryArmedAtRealtimeMs
                    val stalledForMs = nowRealtime - seekRecoveryLastProgressRealtimeMs
                    if (
                        armedForMs >= SEEK_RECOVERY_INITIAL_GRACE_MS &&
                        stalledForMs >= SEEK_RECOVERY_READY_STALL_TIMEOUT_MS &&
                        bufferedAheadMs >= SEEK_RECOVERY_MIN_BUFFER_AHEAD_MS
                    ) {
                        if (recoverFromStuckSeek(exoPlayer, activeTargetMs, currentPositionMs, bufferedPositionMs, "ready-freeze")) {
                            break
                        }
                    }
                }

                Player.STATE_BUFFERING -> {
                    if (positionAdvanced || bufferAdvanced) {
                        seekRecoveryLastProgressRealtimeMs = nowRealtime
                        seekRecoveryLastObservedPositionMs = maxOf(currentPositionMs, activeTargetMs)
                        seekRecoveryLastObservedBufferedPositionMs = bufferedPositionMs
                    } else {
                        val stalledForMs = nowRealtime - seekRecoveryLastProgressRealtimeMs
                        if (stalledForMs >= SEEK_RECOVERY_BUFFERING_STALL_TIMEOUT_MS) {
                            if (recoverFromStuckSeek(exoPlayer, activeTargetMs, currentPositionMs, bufferedPositionMs, "buffering-stall")) {
                                break
                            }
                        }
                    }
                }

                Player.STATE_ENDED,
                Player.STATE_IDLE -> {
                    clearSeekRecovery()
                    break
                }
            }

            delay(SEEK_RECOVERY_POLL_MS)
        }
    }
}

private fun PlayerRuntimeController.recoverFromStuckSeek(
    exoPlayer: Player,
    targetPositionMs: Long,
    currentPositionMs: Long,
    bufferedPositionMs: Long,
    reason: String
): Boolean {
    val nowRealtime = SystemClock.elapsedRealtime()
    if (seekRecoveryReseekCount < SEEK_RECOVERY_MAX_RESEEKS) {
        seekRecoveryReseekCount++
        seekRecoveryArmedAtRealtimeMs = nowRealtime
        seekRecoveryLastProgressRealtimeMs = nowRealtime
        seekRecoveryLastObservedPositionMs = targetPositionMs
        seekRecoveryLastObservedBufferedPositionMs = bufferedPositionMs
        Log.w(
            PlayerRuntimeController.TAG,
            "Seek recovery re-seek ${seekRecoveryReseekCount}/$SEEK_RECOVERY_MAX_RESEEKS: reason=$reason target=${targetPositionMs}ms current=${currentPositionMs}ms buffered=${bufferedPositionMs}ms"
        )
        exoPlayer.seekTo(targetPositionMs)
        return false
    }

    if (seekRecoveryExpectedPlayWhenReady && seekRecoveryRestartCount < SEEK_RECOVERY_MAX_SOFT_RESETS) {
        seekRecoveryRestartCount++
        Log.w(
            PlayerRuntimeController.TAG,
            "Seek recovery soft reset ${seekRecoveryRestartCount}/$SEEK_RECOVERY_MAX_SOFT_RESETS: reason=$reason target=${targetPositionMs}ms current=${currentPositionMs}ms buffered=${bufferedPositionMs}ms"
        )
        softRecoverPlaybackAfterStuckSeek(targetPositionMs)
        return true
    }

    clearSeekRecovery()
    return true
}

private fun PlayerRuntimeController.softRecoverPlaybackAfterStuckSeek(targetPositionMs: Long) {
    val player = _exoPlayer ?: run {
        clearSeekRecovery()
        return
    }
    val now = SystemClock.elapsedRealtime()
    seekRecoveryArmedAtRealtimeMs = now
    seekRecoveryLastProgressRealtimeMs = now
    seekRecoveryLastObservedPositionMs = targetPositionMs
    seekRecoveryLastObservedBufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L)
    runCatching {
        player.pause()
        player.seekTo(targetPositionMs)
        player.prepare()
        player.playWhenReady = seekRecoveryExpectedPlayWhenReady
        if (seekRecoveryExpectedPlayWhenReady) {
            player.play()
        }
    }.onFailure { error ->
        Log.w(
            PlayerRuntimeController.TAG,
            "Seek recovery soft reset failed at ${targetPositionMs}ms",
            error
        )
        clearSeekRecovery()
    }
}

private fun PlayerRuntimeController.triggerPlaybackFreezeSoftReset(targetPositionMs: Long) {
    val player = _exoPlayer ?: return
    val now = SystemClock.elapsedRealtime()
    seekRecoveryTargetPositionMs = targetPositionMs
    seekRecoveryExpectedPlayWhenReady = player.playWhenReady || player.isPlaying
    seekRecoveryArmedAtRealtimeMs = now
    seekRecoveryLastProgressRealtimeMs = now
    seekRecoveryLastObservedPositionMs = targetPositionMs
    seekRecoveryLastObservedBufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L)
    playbackFreezeArmedAtRealtimeMs = now
    playbackFreezeLastProgressRealtimeMs = now
    playbackFreezeLastObservedPositionMs = targetPositionMs
    softRecoverPlaybackAfterStuckSeek(targetPositionMs)
}
