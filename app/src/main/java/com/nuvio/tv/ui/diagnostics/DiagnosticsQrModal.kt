package com.nuvio.tv.ui.diagnostics

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhoneAndroid
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
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioColors

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
            .background(Color.Black.copy(alpha = 0.84f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            onClick = {},
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .padding(horizontal = 36.dp),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = NuvioColors.SurfaceVariant
            ),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(NuvioColors.Secondary.copy(alpha = 0.14f))
                        .padding(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = NuvioColors.Secondary,
                        modifier = Modifier.size(26.dp)
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
                    textAlign = TextAlign.Center
                )

                detailMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(0.9f)
                    )
                }

                if (qrBitmap != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(28.dp))
                            .background(Color.White)
                            .padding(16.dp)
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.diagnostics_qr_content_description),
                            modifier = Modifier.size(240.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                }

                serverUrl?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.TextTertiary,
                        textAlign = TextAlign.Center
                    )
                }

                reportId?.let {
                    Text(
                        text = stringResource(R.string.diagnostics_qr_report_id, it),
                        style = MaterialTheme.typography.labelSmall,
                        color = NuvioColors.TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }

                Surface(
                    onClick = onClose,
                    modifier = Modifier.focusRequester(focusRequester),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = NuvioColors.Surface,
                        focusedContainerColor = NuvioColors.FocusBackground
                    ),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(50)
                        )
                    ),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = NuvioColors.TextPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.diagnostics_qr_close),
                            color = NuvioColors.TextPrimary
                        )
                    }
                }
            }
        }
    }
}
