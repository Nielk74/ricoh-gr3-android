package com.ricohgr3.app.ui.update

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ricohgr3.app.R
import com.ricohgr3.app.ui.theme.GrTheme
import com.ricohgr3.app.update.DownloadState
import com.ricohgr3.app.update.UpdateStatus
import com.ricohgr3.app.update.canStartDownload

@Composable
fun UpdateBanner(
    status: UpdateStatus,
    download: DownloadState,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val available = status as? UpdateStatus.Available ?: return

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = GrTheme.colors.paperEdge,
        contentColor = GrTheme.colors.ink,
        border = BorderStroke(1.dp, GrTheme.colors.accent.copy(alpha = 0.45f)),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.SystemUpdate,
                contentDescription = null,
                tint = GrTheme.colors.accent,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.update_banner_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = updateDownloadLabel(download)
                        ?: stringResource(
                            R.string.update_banner_message,
                            available.release.versionName,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = GrTheme.colors.inkSoft,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (download.canStartDownload) {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = GrTheme.colors.inkSoft),
                ) {
                    Text(stringResource(R.string.update_action_dismiss))
                }
                TextButton(
                    onClick = onInstall,
                    colors = ButtonDefaults.textButtonColors(contentColor = GrTheme.colors.accent),
                ) {
                    Text(stringResource(R.string.update_action_install))
                }
            }
        }
    }
}

@Composable
private fun updateDownloadLabel(download: DownloadState): String? = when (download) {
    is DownloadState.Downloading ->
        stringResource(R.string.update_downloading, (download.fraction * 100).toInt())
    DownloadState.Verifying -> stringResource(R.string.update_verifying)
    DownloadState.ReadyToInstall -> stringResource(R.string.update_ready)
    is DownloadState.Failed -> stringResource(R.string.update_failed, download.reason)
    DownloadState.Idle -> null
}
