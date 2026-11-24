package com.gfs.iyango.feature.main.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.gfs.iyango.core.designsystem.liquidglass.LiquidAlertDialog
import com.gfs.iyango.core.designsystem.liquidglass.LiquidButton
import com.gfs.iyango.core.domain.AppUpdateRelease

/**
 * 全局应用更新提示弹窗。
 */
@Composable
fun AppUpdateDialog(
    release: AppUpdateRelease,
    onDismiss: () -> Unit,
    onIgnore: (String) -> Unit,
) {
    val context = LocalContext.current
    val versionLabel = when {
        release.versionName.isNotBlank() -> "v${release.versionName.removePrefix("v").removePrefix("V")}"
        release.versionCode > 0L -> "Build ${release.versionCode}"
        else -> "最新版本"
    }
    val actionUrl = release.actionUrl

    LiquidAlertDialog(
        onDismissRequest = {
            if (!release.forceUpdate) onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = !release.forceUpdate,
            dismissOnClickOutside = !release.forceUpdate,
        ),
        title = {
            Text(
                text = if (release.forceUpdate) "需要更新 $versionLabel" else "发现新版本 $versionLabel",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (release.publishedAt.isNotBlank()) {
                    Text(
                        text = release.publishedAt.take(10),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (release.releaseNotes.isNotBlank()) {
                    Text(
                        text = release.releaseNotes,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (actionUrl.isBlank()) {
                    Text(
                        text = "当前版本清单未附带下载链接。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Row {
                if (!release.forceUpdate) {
                    TextButton(onClick = { onIgnore(release.versionKey) }) {
                        Text("忽略此版本")
                    }
                    Spacer(Modifier.width(4.dp))
                }
                LiquidButton(
                    onClick = {
                        if (actionUrl.isNotBlank()) {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(actionUrl)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }
                        }
                        if (!release.forceUpdate) onDismiss()
                    },
                    enabled = actionUrl.isNotBlank(),
                ) {
                    Text("立即更新")
                }
            }
        },
        dismissButton = if (release.forceUpdate) null else {
            {
                TextButton(onClick = onDismiss) {
                    Text("稍后再说")
                }
            }
        },
    )
}
