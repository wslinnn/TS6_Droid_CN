package dev.tsdroid.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.tsdroid.han.R
import dev.tslib.BBCode
import dev.tslib.ServerInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerInfoSheet(
    info: ServerInfo?,
    address: String?,
    onDismiss: () -> Unit,
) {
    if (info == null) return

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = info.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            InfoRow(stringResource(R.string.server_address), address ?: "")
            InfoRow(
                stringResource(R.string.server_platform_version),
                listOfNotNull(
                    info.platform?.takeIf { it.isNotBlank() },
                    info.version?.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
            )
            InfoRow(
                stringResource(R.string.server_clients),
                stringResource(R.string.server_clients_format, info.clientsOnline, info.maxClients),
            )
            InfoRow(stringResource(R.string.server_channels), info.channelsOnline.toString())
            InfoRow(stringResource(R.string.server_uptime), formatUptime(info.uptime))

            val welcome = info.welcomeMessage?.takeIf { it.isNotBlank() }?.let {
                try { BBCode.strip(it) } catch (_: Throwable) { it }
            }
            if (!welcome.isNullOrBlank()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Text(
                    text = stringResource(R.string.server_welcome),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = welcome, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun formatUptime(seconds: Long): String {
    if (seconds <= 0) return ""
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}
