package dev.tsdroid.ui.screen

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.mikepenz.markdown.m3.Markdown
import coil.compose.AsyncImage
import dev.tsdroid.bridge.IdentityFileStore
import dev.tsdroid.bridge.MicMode
import dev.tsdroid.bridge.VadGate
import dev.tsdroid.data.SettingsStore
import dev.tsdroid.han.R
import dev.tsdroid.ui.component.SettingsCacheSizeHelper
import dev.tsdroid.ui.component.WallpaperCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun SettingsPage(
    onNavigateToAbout: () -> Unit,
    autoReconnect: Boolean,
    onAutoReconnectChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore(context) }
    val showLinkThumbnails by settingsStore.showLinkThumbnails.collectAsStateWithLifecycle(initialValue = false)
    val autoLoadImages by settingsStore.autoLoadImages.collectAsStateWithLifecycle(initialValue = false)
    // Default matches AudioBridge/store default (enabled) so the switch
    // doesn't flash off→on on first composition
    val enableFloatingWindow by settingsStore.enableFloatingWindow.collectAsStateWithLifecycle(initialValue = true)
    val animeBackground by settingsStore.animeBackground.collectAsStateWithLifecycle(initialValue = false)
    val noiseSuppression by settingsStore.noiseSuppression.collectAsStateWithLifecycle(initialValue = true)
    val micMode by settingsStore.micMode.collectAsStateWithLifecycle(initialValue = MicMode.PTT)
    val vadThreshold by settingsStore.vadThresholdDb.collectAsStateWithLifecycle(initialValue = VadGate.DEFAULT_THRESHOLD_DB)
    val audioGain by settingsStore.audioGain.collectAsStateWithLifecycle(initialValue = 1.0f)

    val languageOptions = listOf(
        "zh" to stringResource(R.string.language_simplified_chinese),
        "en" to stringResource(R.string.language_english),
        "fr" to stringResource(R.string.language_french),
    )
    val selectedLanguageTag by settingsStore.language.collectAsStateWithLifecycle(initialValue = "zh")
    val selectedLanguageLabel = languageOptions.firstOrNull { it.first == selectedLanguageTag }?.second
        ?: stringResource(R.string.language_simplified_chinese)
    var languageMenuExpanded by remember { mutableStateOf(false) }
    var pendingLanguageTag by remember { mutableStateOf<String?>(null) }
    val activity = context as? Activity

    // 悬浮窗开关的权限引导（决议①）：开启且无权限时先引导授权，授权成功才真正持久化为开
    var showOverlayPermissionDialog by remember { mutableStateOf(false) }
    var awaitingOverlayGrant by remember { mutableStateOf(false) }

    pendingLanguageTag?.let { languageTag ->
        val label = languageOptions.firstOrNull { it.first == languageTag }?.second ?: languageTag
        AlertDialog(
            onDismissRequest = { pendingLanguageTag = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(stringResource(R.string.language_change_title)) },
            text = { Text(stringResource(R.string.language_change_message, label)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        settingsStore.setLanguage(languageTag)
                        activity?.recreate()
                    }
                    pendingLanguageTag = null
                }) {
                    Text(stringResource(R.string.restart))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingLanguageTag = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // 悬浮窗开关的权限引导（决议①）：说明对话框只有「去开启权限」单选路径
    if (showOverlayPermissionDialog) {
        AlertDialog(
            onDismissRequest = {
                showOverlayPermissionDialog = false
                awaitingOverlayGrant = false
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(stringResource(R.string.overlay_permission_title)) },
            text = { Text(stringResource(R.string.overlay_permission_message)) },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        awaitingOverlayGrant = true
                    } catch (_: Exception) {
                        showOverlayPermissionDialog = false
                        awaitingOverlayGrant = false
                    }
                }) {
                    Text(stringResource(R.string.overlay_permission_next))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showOverlayPermissionDialog = false
                    awaitingOverlayGrant = false
                }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // 从系统设置返回：已授权才把开关真正持久化为开；未授权/取消则维持关
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (awaitingOverlayGrant) {
            awaitingOverlayGrant = false
            showOverlayPermissionDialog = false
            if (Settings.canDrawOverlays(context)) {
                scope.launch { settingsStore.setEnableFloatingWindow(true) }
            }
        }
    }

    // ── 身份导出/导入 ──
    val identityStore = remember { IdentityFileStore(context) }
    var identityUid by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        identityUid = withContext(Dispatchers.IO) { identityStore.readUniqueId() }
    }
    val exportIdentityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            identityStore.exportTo(out)
                        } ?: false
                    } catch (_: Exception) {
                        false
                    }
                }
                Toast.makeText(
                    context,
                    if (ok) R.string.identity_export_success else R.string.identity_export_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    val importIdentityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            identityStore.importFrom(input)
                        } ?: false
                    } catch (_: Exception) {
                        false
                    }
                }
                if (ok) {
                    identityUid = withContext(Dispatchers.IO) { identityStore.readUniqueId() }
                    Toast.makeText(context, R.string.identity_import_success, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, R.string.identity_import_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // ── 外观 ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SettingsSectionTitle(stringResource(R.string.section_appearance))

                // 悬浮窗
                SettingsSwitchRow(
                    label = stringResource(R.string.enable_floating_window),
                    checked = enableFloatingWindow,
                    onCheckedChange = { checked ->
                        if (checked && !Settings.canDrawOverlays(context)) {
                            // 决议①：无权限时先引导授权，授权返回后开关才保持开；
                            // 取消或未授权则不写入，开关回退为关
                            showOverlayPermissionDialog = true
                        } else {
                            scope.launch { settingsStore.setEnableFloatingWindow(checked) }
                        }
                    },
                )

                // 动漫背景
                SettingsSwitchRow(
                    label = stringResource(R.string.anime_background),
                    checked = animeBackground,
                    onCheckedChange = { scope.launch { settingsStore.setAnimeBackground(it) } },
                )

                if (animeBackground) {
                    // 自定义背景
                    CustomBackgroundSection(context)

                    // 壁纸缓存
                    WallpaperCacheSection(context)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 音频 ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SettingsSectionTitle(stringResource(R.string.section_audio))

                // 音量增益
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    var gainValue by remember(audioGain) { mutableFloatStateOf(audioGain) }
                    Text(
                        text = "${stringResource(R.string.audio_gain)} : ${stringResource(R.string.audio_gain_value, gainValue)}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(4.dp))
                    Slider(
                        value = gainValue,
                        onValueChange = { gainValue = it },
                        onValueChangeFinished = { scope.launch { settingsStore.setAudioGain(gainValue) } },
                        valueRange = 1.0f..8.0f,
                        steps = 13,
                    )
                }

                // 麦克风降噪
                SettingsSwitchRow(
                    label = stringResource(R.string.noise_suppression),
                    checked = noiseSuppression,
                    onCheckedChange = { scope.launch { settingsStore.setNoiseSuppression(it) } },
                )

                // 麦克风模式：按住说话 / 语音激活 / 开放麦克风
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        text = stringResource(R.string.mic_mode),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = micMode == MicMode.PTT,
                            onClick = { scope.launch { settingsStore.setMicMode(MicMode.PTT) } },
                            label = { Text(stringResource(R.string.push_to_talk)) },
                        )
                        FilterChip(
                            selected = micMode == MicMode.VAD,
                            onClick = { scope.launch { settingsStore.setMicMode(MicMode.VAD) } },
                            label = { Text(stringResource(R.string.mic_mode_vad)) },
                        )
                        FilterChip(
                            selected = micMode == MicMode.OPEN,
                            onClick = { scope.launch { settingsStore.setMicMode(MicMode.OPEN) } },
                            label = { Text(stringResource(R.string.mic_mode_open)) },
                        )
                    }
                    if (micMode == MicMode.VAD) {
                        var thresholdValue by remember(vadThreshold) { mutableFloatStateOf(vadThreshold) }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.vad_threshold, thresholdValue.roundToInt()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Slider(
                            value = thresholdValue,
                            onValueChange = { thresholdValue = it },
                            onValueChangeFinished = {
                                scope.launch { settingsStore.setVadThresholdDb(thresholdValue) }
                            },
                            valueRange = -70f..-15f,
                            steps = 54,
                        )
                        Text(
                            text = stringResource(R.string.vad_threshold_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 身份 ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SettingsSectionTitle(stringResource(R.string.identity_section))
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        text = identityUid?.let { stringResource(R.string.identity_uid, it) }
                            ?: stringResource(R.string.identity_uid_none),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { exportIdentityLauncher.launch("ts6mobile_identity.ini") },
                            enabled = identityUid != null,
                        ) {
                            Text(stringResource(R.string.identity_export))
                        }
                        OutlinedButton(onClick = { importIdentityLauncher.launch(arrayOf("*/*")) }) {
                            Text(stringResource(R.string.identity_import))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.identity_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 聊天 ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SettingsSectionTitle(stringResource(R.string.section_chat))

                SettingsSwitchRow(
                    label = stringResource(R.string.auto_reconnect),
                    checked = autoReconnect,
                    onCheckedChange = onAutoReconnectChange,
                )
                SettingsSwitchRow(
                    label = stringResource(R.string.show_link_thumbnails),
                    checked = showLinkThumbnails,
                    onCheckedChange = { scope.launch { settingsStore.setShowLinkThumbnails(it) } },
                )
                SettingsSwitchRow(
                    label = stringResource(R.string.auto_load_images),
                    checked = autoLoadImages,
                    onCheckedChange = { scope.launch { settingsStore.setAutoLoadImages(it) } },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 更多 ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SettingsSectionTitle(stringResource(R.string.section_more))

                // 语言切换
                SettingsClickableRow(
                    label = stringResource(R.string.language_change_title),
                    trailing = {
                        Box {
                            Text(
                                text = selectedLanguageLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { languageMenuExpanded = true },
                            )
                            DropdownMenu(
                                expanded = languageMenuExpanded,
                                onDismissRequest = { languageMenuExpanded = false },
                            ) {
                                languageOptions.forEach { (tag, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            pendingLanguageTag = tag
                                            languageMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    },
                )

                // 关于软件
                SettingsClickableRow(
                    label = stringResource(R.string.about_software),
                    onClick = onNavigateToAbout,
                )

                // 检查更新
                UpdateCheckRow(context)
            }
        }

        Spacer(Modifier.height(32.dp))

        // 版本号
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }
        Text(
            text = "TS6 Mobile v$versionName",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), MaterialTheme.shapes.large)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

// ── 可复用组件 ──

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsClickableRow(
    label: String,
    onClick: () -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {
        Icon(Icons.AutoMirrored.Filled.NavigateNext, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    },
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        trailing()
    }
}

// ── 自定义背景区 ──

@Composable
private fun CustomBackgroundSection(context: Context) {
    var showCropScreen by remember { mutableStateOf(false) }
    var cropBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var hasCustom by remember { mutableStateOf(dev.tsdroid.background.CustomBackgroundManager.hasCustomBackground(context)) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                if (bitmap != null) {
                    cropBitmap = bitmap
                    showCropScreen = true
                }
            } catch (_: Exception) {
                Toast.makeText(context, context.getString(R.string.custom_bg_load_error), Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (showCropScreen && cropBitmap != null) {
        Dialog(
            onDismissRequest = {
                cropBitmap?.recycle()
                cropBitmap = null
                showCropScreen = false
            },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            dev.tsdroid.background.CropScreen(
                bitmap = cropBitmap!!,
                onConfirm = { left, top, right, bottom ->
                    val success = dev.tsdroid.background.CustomBackgroundManager.cropAndSave(context, cropBitmap!!, left, top, right, bottom)
                    if (success) {
                        hasCustom = true
                        dev.tsdroid.ui.component.AnimeWallpaperState.refreshCustomBackground(context)
                        Toast.makeText(context, context.getString(R.string.custom_bg_saved), Toast.LENGTH_SHORT).show()
                    }
                    cropBitmap?.recycle()
                    cropBitmap = null
                    showCropScreen = false
                },
                onDismiss = {
                    cropBitmap?.recycle()
                    cropBitmap = null
                    showCropScreen = false
                },
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(stringResource(R.string.custom_bg_delete)) },
            text = { Text(stringResource(R.string.custom_bg_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    dev.tsdroid.background.CustomBackgroundManager.deleteBackground(context)
                    hasCustom = false
                    showDeleteConfirm = false
                }) {
                    Text(stringResource(R.string.confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Text(
        text = stringResource(R.string.custom_bg_title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 2.dp),
    )
    Text(
        text = if (hasCustom) stringResource(R.string.custom_bg_active) else stringResource(R.string.custom_bg_inactive),
        style = MaterialTheme.typography.bodySmall,
        color = if (hasCustom) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = { galleryLauncher.launch("image/*") }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.custom_bg_upload))
        }
        if (hasCustom) {
            OutlinedButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.custom_bg_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ── 壁纸缓存区 ──

@Composable
private fun WallpaperCacheSection(context: Context) {
    val cacheSizeMB = remember { mutableFloatStateOf(WallpaperCacheManager.getCacheSizeMB()) }
    val cacheCount = remember { mutableIntStateOf(WallpaperCacheManager.getCachedFilesCount()) }
    val maxSize = remember { mutableLongStateOf(SettingsCacheSizeHelper.getMaxCacheSize(context)) }
    var showCacheViewer by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(stringResource(R.string.wallpaper_clear_cache)) },
            text = { Text(stringResource(R.string.wallpaper_clear_cache_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    WallpaperCacheManager.clearCache()
                    cacheSizeMB.floatValue = 0f
                    cacheCount.intValue = 0
                    showClearConfirm = false
                }) {
                    Text(stringResource(R.string.confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showCacheViewer) {
        val cachedFiles = remember { mutableStateListOf(*WallpaperCacheManager.getCachedFiles().toTypedArray()) }
        AlertDialog(
            onDismissRequest = { showCacheViewer = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("${stringResource(R.string.wallpaper_view_cache)} (${cachedFiles.size})") },
            text = {
                if (cachedFiles.isEmpty()) {
                    Text(stringResource(R.string.wallpaper_cache_empty))
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(cachedFiles.size, key = { cachedFiles[it].name }) { index ->
                            val file = cachedFiles[index]
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(MaterialTheme.shapes.medium)
                                    .clickable {
                                        WallpaperCacheManager.deleteFile(file)
                                        cachedFiles.removeAt(index)
                                        cacheSizeMB.floatValue = WallpaperCacheManager.getCacheSizeMB()
                                        cacheCount.intValue = WallpaperCacheManager.getCachedFilesCount()
                                    },
                            ) {
                                AsyncImage(model = file, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp)
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), CircleShape).padding(2.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCacheViewer = false }) { Text(stringResource(R.string.close)) }
            },
        )
    }

    Text(
        text = stringResource(R.string.wallpaper_cache_size, String.format("%.1f", cacheSizeMB.floatValue), cacheCount.intValue),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
    )
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.wallpaper_max_cache, maxSize.longValue),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = maxSize.longValue.toFloat(),
            onValueChange = { maxSize.longValue = it.toLong() },
            onValueChangeFinished = { SettingsCacheSizeHelper.setMaxCacheSize(context, maxSize.longValue) },
            valueRange = 10f..500f,
            steps = 48,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = { showCacheViewer = true }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.wallpaper_view_cache))
        }
        OutlinedButton(onClick = { showClearConfirm = true }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.wallpaper_clear_cache), color = MaterialTheme.colorScheme.error)
        }
    }
}

// ── 检查更新 ──

@Composable
private fun UpdateCheckRow(context: Context) {
    val scope = rememberCoroutineScope()
    val versionName = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (_: Exception) { "" }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<dev.tsdroid.update.UpdateInfo?>(null) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var isLatestVersion by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    if (showUpdateDialog && updateInfo != null) {
        AlertDialog(
            onDismissRequest = { if (!isDownloading) { showUpdateDialog = false } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(stringResource(R.string.update_available, updateInfo!!.versionName)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (isDownloading) {
                        if (downloadProgress >= 0f) {
                            Text("${stringResource(R.string.update_downloading)} ${(downloadProgress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth())
                        } else {
                            // Total size unknown (chunked transfer) — indeterminate progress
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.update_downloading), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        downloadError?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        val changelog = updateInfo!!.changelog
                        if (changelog.isBlank()) {
                            Text(
                                text = stringResource(R.string.update_no_changelog),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            Markdown(
                                content = changelog.take(4000),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (!isDownloading) {
                    FilledTonalButton(onClick = {
                        isDownloading = true
                        downloadError = null
                        downloadProgress = 0f
                        scope.launch {
                            dev.tsdroid.update.InAppUpdater.downloadAndInstall(
                                context = context,
                                downloadUrl = updateInfo!!.downloadUrl,
                                onProgress = { progress ->
                                    downloadProgress = progress.progress
                                    if (progress.state == dev.tsdroid.update.InAppUpdater.DownloadState.FAILED) {
                                        downloadError = progress.error
                                        isDownloading = false
                                    } else if (progress.state == dev.tsdroid.update.InAppUpdater.DownloadState.DONE) {
                                        showUpdateDialog = false
                                    }
                                }
                            )
                            isDownloading = false
                        }
                    }) { Text(stringResource(R.string.update_download)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }) { Text(stringResource(R.string.update_later)) }
            },
        )
    }

    SettingsClickableRow(
        label = stringResource(R.string.update_check),
        onClick = {
            if (!isCheckingUpdate) {
                isCheckingUpdate = true; isLatestVersion = false; updateInfo = null; updateError = null
                scope.launch {
                    val result = dev.tsdroid.update.UpdateChecker.checkForUpdate(versionName)
                    updateInfo = result.update
                    updateError = result.error
                    if (result.update != null) showUpdateDialog = true
                    else if (result.error == null) isLatestVersion = true
                    isCheckingUpdate = false
                }
            }
        },
        trailing = {
            when {
                isCheckingUpdate -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                updateInfo != null -> Text(stringResource(R.string.update_found), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                updateError != null -> Text(updateError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                isLatestVersion -> Text(stringResource(R.string.update_already_latest), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {}
            }
        },
    )
}
