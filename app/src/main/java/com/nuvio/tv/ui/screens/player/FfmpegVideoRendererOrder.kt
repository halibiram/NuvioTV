package com.nuvio.tv.ui.screens.player

import androidx.media3.exoplayer.Renderer

internal fun isFfmpegVideoRenderer(renderer: Renderer): Boolean =
    renderer.javaClass.simpleName.contains("FfmpegVideo")

/** Keeps FFmpeg video behind the platform decoders so it only takes what they cannot play. */
internal fun moveFfmpegVideoRenderersLast(renderers: MutableList<Renderer>) {
    val ffmpegRenderers = renderers.filter(::isFfmpegVideoRenderer)
    if (ffmpegRenderers.isEmpty()) return
    renderers.removeAll { isFfmpegVideoRenderer(it) }
    renderers.addAll(ffmpegRenderers)
}
