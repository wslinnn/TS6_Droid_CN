package dev.tsdroid.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.widget.Toast
import dev.tsdroid.han.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val CRASH_FILE = "last_crash.txt"

/**
 * 上次崩溃报告浮层：启动时读取 filesDir/last_crash.txt（由 TsDroidApp 的
 * 崩溃陷阱写入），存在则悬浮展示，支持一键复制回传排障、删除清除。
 */
@Composable
fun CrashReportOverlay(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var crashText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        crashText = withContext(Dispatchers.IO) {
            val file = File(context.filesDir, CRASH_FILE)
            if (file.exists()) runCatching { file.readText() }.getOrNull() else null
        }
    }

    crashText?.let { crash ->
        Card(
            modifier = modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.crash_report_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Row {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(crash))
                            Toast.makeText(context, R.string.common_copied, Toast.LENGTH_SHORT).show()
                        }) {
                            Text(
                                stringResource(R.string.common_copy),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        TextButton(onClick = {
                            runCatching { context.filesDir.resolve(CRASH_FILE).delete() }
                            crashText = null
                        }) {
                            Text(
                                stringResource(R.string.close),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
                Text(
                    text = crash.takeLast(2000),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 10,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
