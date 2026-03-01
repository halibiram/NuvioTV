package com.nuvio.tv.ui.screens.player

import android.app.Activity
import java.lang.ref.WeakReference

internal fun PlayerRuntimeController.attachHostActivity(activity: Activity?) {
    hostActivityRef = activity?.let { WeakReference(it) }
}

internal fun PlayerRuntimeController.startInitialPlaybackIfNeeded() {
    if (initialPlaybackStarted) return
    
    // DELAY playback start until subtitles are fetched.
    // This allows us to inject ALL subtitle streams into the initial MediaSource,
    // so we can switch them seamlessly later without rebuffering.
    if (_uiState.value.isLoadingAddonSubtitles) return

    initialPlaybackStarted = true
    initializePlayer(currentStreamUrl, currentHeaders)
}

internal fun PlayerRuntimeController.currentHostActivity(): Activity? {
    return hostActivityRef?.get()
}