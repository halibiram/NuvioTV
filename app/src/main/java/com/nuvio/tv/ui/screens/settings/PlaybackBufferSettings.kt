@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.History
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.data.local.BufferSettings
import com.nuvio.tv.ui.theme.NuvioColors

internal fun LazyListScope.bufferSettingsItems(
    bufferSettings: BufferSettings,
    onSetBufferMinBufferMs: (Int) -> Unit,
    onSetBufferMaxBufferMs: (Int) -> Unit,
    onSetBufferForPlaybackMs: (Int) -> Unit,
    onSetBufferForPlaybackAfterRebufferMs: (Int) -> Unit,
    onSetBufferTargetSizeMb: (Int) -> Unit,
    onSetBufferBackBufferDurationMs: (Int) -> Unit,
    onSetBufferRetainBackBufferFromKeyframe: (Boolean) -> Unit,
    onItemFocused: () -> Unit = {},
    enabled: Boolean = true
) {
    item {
        Text(
            text = "Buffer",
            style = MaterialTheme.typography.titleMedium,
            color = NuvioColors.TextSecondary,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }

    item {
        Text(
            text = "Adjust how much video is preloaded. Higher values use more memory but reduce buffering on slow connections.",
            style = MaterialTheme.typography.bodySmall,
            color = NuvioColors.TextSecondary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }

    item {
        SliderSettingsItem(
            icon = Icons.Default.Timer,
            title = "Minimum Buffer",
            subtitle = "Minimum buffered duration before playback starts",
            value = bufferSettings.minBufferMs / 1000,
            valueText = "${bufferSettings.minBufferMs / 1000}s",
            minValue = 10,
            maxValue = 120,
            step = 5,
            onValueChange = { onSetBufferMinBufferMs(it * 1000) },
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item {
        SliderSettingsItem(
            icon = Icons.Default.Storage,
            title = "Maximum Buffer",
            subtitle = "Maximum buffered duration during playback",
            value = bufferSettings.maxBufferMs / 1000,
            valueText = "${bufferSettings.maxBufferMs / 1000}s",
            minValue = 10,
            maxValue = 120,
            step = 5,
            onValueChange = { onSetBufferMaxBufferMs(it * 1000) },
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item {
        SliderSettingsItem(
            icon = Icons.Default.Speed,
            title = "Buffer for Playback",
            subtitle = "Buffer needed to start or resume playback",
            value = bufferSettings.bufferForPlaybackMs / 1000,
            valueText = "${bufferSettings.bufferForPlaybackMs / 1000}s",
            minValue = 1,
            maxValue = 30,
            step = 1,
            onValueChange = { onSetBufferForPlaybackMs(it * 1000) },
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item {
        SliderSettingsItem(
            icon = Icons.Default.Memory,
            title = "Buffer After Rebuffer",
            subtitle = "Buffer needed after a rebuffer event (stutter)",
            value = bufferSettings.bufferForPlaybackAfterRebufferMs / 1000,
            valueText = "${bufferSettings.bufferForPlaybackAfterRebufferMs / 1000}s",
            minValue = 1,
            maxValue = 60,
            step = 1,
            onValueChange = { onSetBufferForPlaybackAfterRebufferMs(it * 1000) },
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Advanced Buffer",
            style = MaterialTheme.typography.titleMedium,
            color = NuvioColors.TextSecondary,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }

    item {
        Text(
            text = "These settings affect memory usage. Set to 0 for ExoPlayer defaults.",
            style = MaterialTheme.typography.bodySmall,
            color = NuvioColors.TextSecondary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }

    item {
        SliderSettingsItem(
            icon = Icons.Default.Storage,
            title = "Target Buffer Size",
            subtitle = "Maximum buffer size in memory (0 = auto)",
            value = bufferSettings.targetBufferSizeMb,
            valueText = if (bufferSettings.targetBufferSizeMb == 0) "Auto" else "${bufferSettings.targetBufferSizeMb}MB",
            minValue = 0,
            maxValue = 500,
            step = 10,
            onValueChange = onSetBufferTargetSizeMb,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item {
        SliderSettingsItem(
            icon = Icons.Default.History,
            title = "Back Buffer Duration",
            subtitle = "Amount of video kept behind current position for seeking back (0 = disabled)",
            value = bufferSettings.backBufferDurationMs / 1000,
            valueText = if (bufferSettings.backBufferDurationMs == 0) "Off" else "${bufferSettings.backBufferDurationMs / 1000}s",
            minValue = 0,
            maxValue = 60,
            step = 5,
            onValueChange = { onSetBufferBackBufferDurationMs(it * 1000) },
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item {
        ToggleSettingsItem(
            icon = Icons.Default.Memory,
            title = "Retain Back Buffer from Keyframe",
            subtitle = "Keep back buffer aligned to video keyframes for faster seeking",
            isChecked = bufferSettings.retainBackBufferFromKeyframe,
            onCheckedChange = onSetBufferRetainBackBufferFromKeyframe,
            onFocused = onItemFocused,
            enabled = enabled && bufferSettings.backBufferDurationMs > 0
        )
    }
}
