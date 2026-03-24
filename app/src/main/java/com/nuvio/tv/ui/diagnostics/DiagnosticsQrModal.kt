package com.nuvio.tv.ui.diagnostics

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioColors
import java.net.URI

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DiagnosticsQrModal(
    title: String,
    subtitle: String,
    detailMessage: String? = null,
    qrBitmap: Bitmap?,
    serverUrl: String?,
    reportId: String?,
    onClose: () -> Unit
) {
    Popup(properties = PopupProperties(focusable = true)) {
        DiagnosticsQrModalContent(
            title = title,
            subtitle = subtitle,
            detailMessage = detailMessage,
            qrBitmap = qrBitmap,
            serverUrl = serverUrl,
            reportId = reportId,
            onClose = onClose
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DiagnosticsQrModalContent(
    title: String,
    subtitle: String,
    detailMessage: String?,
    qrBitmap: Bitmap?,
    serverUrl: String?,
    reportId: String?,
    onClose: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    BackHandler(onBack = onClose)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            onClick = {},
            modifier = Modifier
                .fillMaxWidth(0.74f)
                .heightIn(max = 700.dp),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = NuvioColors.SurfaceVariant
            ),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(28.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = NuvioColors.Secondary.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(28.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = NuvioColors.Secondary.copy(alpha = 0.22f),
                        shape = RoundedCornerShape(28.dp)
                    )
                    .padding(horizontal = 30.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .background(
                            color = NuvioColors.Secondary.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(999.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = "LOCAL DIAGNOSTICS",
                        style = MaterialTheme.typography.labelSmall,
                        color = NuvioColors.Secondary,
                        textAlign = TextAlign.Center
                    )
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioColors.TextPrimary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(0.86f)
                )

                detailMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(0.88f)
                    )
                }

                if (qrBitmap != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(32.dp))
                            .background(Color.White)
                            .border(1.dp, Color(0xFFE5E7EB), RoundedCornerShape(32.dp))
                            .padding(18.dp)
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.diagnostics_qr_content_description),
                            modifier = Modifier.size(192.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                }

                serverUrl?.let {
                    Text(
                        text = formatDisplayAddress(it),
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioColors.TextPrimary,
                        textAlign = TextAlign.Center
                    )
                }

                reportId?.let {
                    Text(
                        text = stringResource(R.string.diagnostics_qr_report_id, it),
                        style = MaterialTheme.typography.labelSmall,
                        color = NuvioColors.TextTertiary,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.size(4.dp))

                Surface(
                    onClick = onClose,
                    modifier = Modifier.focusRequester(focusRequester),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = NuvioColors.Surface,
                        focusedContainerColor = NuvioColors.Secondary
                    ),
                    border = ClickableSurfaceDefaults.border(
                        border = Border(
                            border = BorderStroke(1.dp, NuvioColors.Border),
                            shape = RoundedCornerShape(999.dp)
                        ),
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(999.dp)
                        )
                    ),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 28.dp, vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.diagnostics_qr_close),
                            color = NuvioColors.OnSecondary,
                            style = MaterialTheme.typography.titleSmall,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

private fun formatDisplayAddress(serverUrl: String): String {
    return runCatching {
        val uri = URI(serverUrl)
        val host = uri.host ?: return serverUrl.substringBefore('/', serverUrl)
        if (uri.port > 0) "$host:${uri.port}" else host
    }.getOrElse {
        serverUrl.substringBefore('/').substringBefore('?')
    }
}
