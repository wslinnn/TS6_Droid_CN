package dev.tsdroid.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tsdroid.han.R
import dev.tslib.User
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserPanelSheet(
    user: User?,
    avatar: ImageBitmap?,
    volumeDb: Float,
    isMuted: Boolean,
    onVolumeChange: (Float) -> Unit,
    onVolumeCommit: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onPrivateMessage: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (user == null) return

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PanelAvatar(user.nickname, avatar)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = user.nickname,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }

            UserInfoRow(stringResource(R.string.user_uid), user.uid ?: "")
            UserInfoRow(stringResource(R.string.user_platform), user.platform ?: "")
            UserInfoRow(stringResource(R.string.user_version), user.version ?: "")
            UserInfoRow(stringResource(R.string.user_country), user.country ?: "")
            UserInfoRow(stringResource(R.string.user_talkpower), user.talkPower.toString())

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            VolumeSlider(
                initialDb = volumeDb,
                defaultLabel = stringResource(R.string.volume_default),
                onVolumeChange = onVolumeChange,
                onVolumeCommit = onVolumeCommit,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(if (isMuted) R.string.unmute_user else R.string.mute_user),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = isMuted, onCheckedChange = { onToggleMute() })
            }

            TextButton(onClick = onPrivateMessage) {
                Text(stringResource(R.string.private_message))
            }
        }
    }
}

@Composable
private fun PanelAvatar(nickname: String, avatar: ImageBitmap?) {
    Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        if (avatar != null) {
            Image(
                bitmap = avatar,
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            val bgColor = Color(0xFF5C6BC0)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(bgColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = nickname.firstOrNull()?.uppercase() ?: "?",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun UserInfoRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun VolumeSlider(
    initialDb: Float,
    defaultLabel: String,
    onVolumeChange: (Float) -> Unit,
    onVolumeCommit: (Float) -> Unit,
) {
    var db by remember(initialDb) { mutableFloatStateOf(initialDb) }
    Column {
        Text(
            text = stringResource(R.string.user_volume) + " " + formatDb(db, defaultLabel),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = db,
            onValueChange = {
                db = it
                onVolumeChange(it)
            },
            onValueChangeFinished = { onVolumeCommit(db) },
            valueRange = -30f..15f,
            steps = 44,
        )
        TextButton(onClick = {
            db = 0f
            onVolumeChange(0f)
            onVolumeCommit(0f)
        }) {
            Text(stringResource(R.string.volume_reset))
        }
    }
}

private fun formatDb(db: Float, defaultLabel: String): String {
    val rounded = db.roundToInt()
    return if (rounded == 0) {
        "0 dB ($defaultLabel)"
    } else {
        (if (rounded > 0) "+$rounded" else "$rounded") + " dB"
    }
}
