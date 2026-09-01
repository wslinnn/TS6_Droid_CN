package dev.tsdroid.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tsdroid.data.BookmarkStore
import dev.tsdroid.han.R
import kotlinx.coroutines.launch

/**
 * 通话中的设置面板。只保留连接中有意义的分区（外观/音频/聊天）；
 * 身份在连接时已绑定本会话、更多（语言需重启）留在连接页的设置里。
 */
@Composable
fun ServerSettingsPanel(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bookmarkStore = remember { BookmarkStore(context) }
    val autoReconnect by bookmarkStore.autoReconnect.collectAsStateWithLifecycle(initialValue = false)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
        ) {
            // Header: title + close（与 ChatPanel 同款面板框架）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.tab_settings),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                AppearanceSection()
                Spacer(Modifier.height(12.dp))
                AudioSection()
                Spacer(Modifier.height(12.dp))
                ChatSection(
                    autoReconnect = autoReconnect,
                    onAutoReconnectChange = { checked ->
                        scope.launch { bookmarkStore.setAutoReconnect(checked) }
                    },
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
