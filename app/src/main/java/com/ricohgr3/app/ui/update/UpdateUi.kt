package com.ricohgr3.app.ui.update

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
fun AppUpdateScreen(
    currentVersion: String,
    status: UpdateStatus,
    download: DownloadState,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = GrTheme.colors.paper,
        contentColor = GrTheme.colors.ink,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.update_screen_back),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = stringResource(R.string.update_screen_eyebrow),
                        style = MaterialTheme.typography.labelSmall,
                        color = GrTheme.colors.accent,
                    )
                    Text(
                        text = stringResource(R.string.update_screen_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
            Text(
                text = stringResource(R.string.update_screen_current, currentVersion),
                style = MaterialTheme.typography.labelMedium,
                color = GrTheme.colors.inkSoft,
            )
            Spacer(Modifier.height(10.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = GrTheme.colors.paperEdge,
                border = BorderStroke(1.dp, GrTheme.colors.hair),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = GrTheme.colors.accentWash,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SystemUpdate,
                            contentDescription = null,
                            tint = GrTheme.colors.accent,
                            modifier = Modifier.padding(11.dp).size(26.dp),
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = updateStatusTitle(status),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(7.dp))
                    Text(
                        text = updateStatusMessage(status),
                        style = MaterialTheme.typography.bodyMedium,
                        color = GrTheme.colors.inkSoft,
                    )

                    val available = status as? UpdateStatus.Available
                    available?.release?.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                        Spacer(Modifier.height(18.dp))
                        Text(
                            text = notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = GrTheme.colors.ink,
                            maxLines = 10,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    updateDownloadLabel(download)?.let { label ->
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (download is DownloadState.Failed) {
                                GrTheme.colors.accent
                            } else {
                                GrTheme.colors.ink
                            },
                        )
                    }

                    Spacer(Modifier.height(22.dp))
                    when {
                        status is UpdateStatus.Checking -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = GrTheme.colors.accent,
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = stringResource(R.string.update_screen_checking_title),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = GrTheme.colors.inkSoft,
                                )
                            }
                        }

                        available != null -> {
                            if (download.canStartDownload) {
                                Button(
                                    onClick = onInstall,
                                    modifier = Modifier.fillMaxWidth().height(52.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = GrTheme.colors.accent,
                                    ),
                                ) {
                                    Text(
                                        text = stringResource(R.string.update_action_install),
                                        color = GrTheme.colors.paper,
                                    )
                                }
                            }
                            if (download is DownloadState.Idle || download is DownloadState.Failed) {
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = onCheck,
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                ) {
                                    Text(stringResource(R.string.update_screen_check_again))
                                }
                            }
                        }

                        else -> {
                            Button(
                                onClick = onCheck,
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = GrTheme.colors.accent,
                                ),
                            ) {
                                Text(
                                    text = if (status is UpdateStatus.Idle) {
                                        stringResource(R.string.update_screen_check)
                                    } else {
                                        stringResource(R.string.update_screen_check_again)
                                    },
                                    color = GrTheme.colors.paper,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.update_screen_source),
                style = MaterialTheme.typography.bodySmall,
                color = GrTheme.colors.inkSoft,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun updateStatusTitle(status: UpdateStatus): String = when (status) {
    UpdateStatus.Idle -> stringResource(R.string.update_screen_idle_title)
    UpdateStatus.Checking -> stringResource(R.string.update_screen_checking_title)
    UpdateStatus.UpToDate -> stringResource(R.string.update_screen_current_title)
    is UpdateStatus.Available ->
        stringResource(R.string.update_screen_available_title, status.release.versionName)
    is UpdateStatus.Failed -> stringResource(R.string.update_screen_failed_title)
}

@Composable
private fun updateStatusMessage(status: UpdateStatus): String = when (status) {
    UpdateStatus.Idle -> stringResource(R.string.update_screen_idle_message)
    UpdateStatus.Checking -> stringResource(R.string.update_screen_checking_message)
    UpdateStatus.UpToDate -> stringResource(R.string.update_screen_current_message)
    is UpdateStatus.Available ->
        stringResource(R.string.update_banner_message, status.release.versionName)
    is UpdateStatus.Failed -> status.reason
}

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
