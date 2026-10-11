package com.nuvio.tv.ui.screens.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.MappingTrackSelector

/** True when the selected video track plays through the FFmpeg video renderer, which draws into the surface itself. */
@OptIn(UnstableApi::class)
internal fun ExoPlayer.isFfmpegVideoSelected(tracks: Tracks): Boolean {
    val mappedTrackInfo = (trackSelector as? MappingTrackSelector)?.currentMappedTrackInfo ?: return false
    return mappedTrackInfo.isFfmpegVideoSelected(tracks)
}

@OptIn(UnstableApi::class)
internal fun MappingTrackSelector.MappedTrackInfo.isFfmpegVideoSelected(tracks: Tracks): Boolean {
    val selectedVideoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
    if (selectedVideoGroups.isEmpty()) return false
    return (0 until rendererCount).any { rendererIndex ->
        getRendererType(rendererIndex) == C.TRACK_TYPE_VIDEO &&
            getRendererName(rendererIndex).contains("FfmpegVideo") &&
            selectedVideoGroups.any { group ->
                getTrackGroups(rendererIndex).indexOf(group.mediaTrackGroup) != C.INDEX_UNSET
            }
    }
}
