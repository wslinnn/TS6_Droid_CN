package dev.tsdroid.service

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.tsdroid.bridge.TsClient
import dev.tslib.User

/**
 * 密聊 (Whisper) 单例状态管理器。
 *
 * 管理向特定用户进行密聊的状态。
 * 语音密聊由 Rust 层路由：目标集下发后，采集管线原样调用 sendAudio，
 * Rust 侧把语音包封装为 C2SWhisper 发给目标集（TS3 语义：密聊与频道发言互斥）。
 * 文字私信仍作为补充通道发给同一目标集。
 *
 * 使用方法:
 *   // 先初始化（连接建立后调用）
 *   WhisperManager.init(tsClient)
 *
 *   // 对某个用户发起密聊
 *   WhisperManager.startWhisper(targetUserId)
 *
 *   // 结束密聊
 *   WhisperManager.stopWhisper()
 *
 * 状态观察:
 *   WhisperManager.isWhisperActive — 是否有活跃密聊
 *   WhisperManager.whisperTargets — 当前密聊目标用户列表
 */
object WhisperManager {

    private const val TAG = "WhisperManager"

    /** 最大同时密聊目标数 */
    private const val MAX_WHISPER_TARGETS = 10

    private var tsClient: TsClient? = null

    // ── 响应式状态 ──────────────────────────────────────────

    /** 是否处于密聊模式 */
    @JvmStatic
    var isWhisperActive: Boolean by mutableStateOf(false)
        private set

    /**
     * 当前密聊目标键列表。键为对端 UID（跨重连稳定）；
     * 无 UID 的对端退化为 "clid:<n>"。
     */
    private val _whisperTargets = mutableListOf<String>()
    val whisperTargets: List<String> get() = _whisperTargets.toList()

    /** 当前密聊目标用户的名称列表（给 UI 渲染） */
    private val _whisperTargetNames = mutableListOf<String>()
    val whisperTargetNames: List<String> get() = _whisperTargetNames.toList()

    // ── 初始化 ──────────────────────────────────────────────

    /**
     * 初始化 WhiperManager。
     * 必须在连接建立后、UI 显示前调用。
     */
    fun init(client: TsClient) {
        tsClient = client
        Log.i(TAG, "WhisperManager initialized")
    }

    /**
     * 断开连接时重置状态。
     */
    fun reset() {
        _whisperTargets.clear()
        _whisperTargetNames.clear()
        isWhisperActive = false
        tsClient = null
        Log.d(TAG, "WhisperManager reset")
    }

    // ── 核心操作 ────────────────────────────────────────────

    /** 会话键：UID 优先，无 UID 退化为 "clid:<n>"。 */
    private fun keyOf(user: User): String =
        user.uid?.takeIf { it.isNotEmpty() } ?: "clid:${user.id}"

    /**
     * 向指定用户发起密聊。
     *
     * 目标集会同步下发到 Rust 层，之后采集管线的语音包由 Rust 封装为
     * C2SWhisper 发往目标集（密聊与频道发言互斥）；文字私信仍可发同一目标集。
     *
     * @param targetKey 目标会话键（UID 或 "clid:<n>"）
     */
    fun startWhisper(targetKey: String) {
        val client = tsClient ?: run {
            Log.w(TAG, "Cannot whisper: TsClient not initialized")
            return
        }

        val targetUser = client.users.value.find { keyOf(it) == targetKey }
        if (targetUser == null && targetKey.startsWith("clid:")) {
            Log.w(TAG, "Cannot whisper: target $targetKey not found")
            return
        }

        // 检查限制
        if (_whisperTargets.size >= MAX_WHISPER_TARGETS) {
            Log.w(TAG, "Max whisper targets ($MAX_WHISPER_TARGETS) reached")
            return
        }

        // 如果已经在密聊此用户，不重复添加
        if (_whisperTargets.contains(targetKey)) {
            Log.d(TAG, "Already whispering to ${targetUser?.nickname ?: targetKey}")
            return
        }

        Log.i(TAG, "Starting whisper to ${targetUser?.nickname ?: targetKey}")

        // 更新状态并下发语音路由
        _whisperTargets.add(targetKey)
        _whisperTargetNames.add(targetUser?.nickname ?: targetKey)
        isWhisperActive = true
        applyVoiceTargets()

        Log.d(TAG, "Whisper target added: ${targetUser?.nickname ?: targetKey}")
    }

    /**
     * 结束当前密聊，恢复正常频道讲话。
     */
    fun stopWhisper() {
        if (!isWhisperActive) return

        Log.i(TAG, "Stopping whisper, restoring normal channel talk")

        // 重置状态并恢复语音路由
        _whisperTargets.clear()
        _whisperTargetNames.clear()
        isWhisperActive = false
        applyVoiceTargets()

        Log.d(TAG, "Whisper mode ended")
    }

    /**
     * 切换对指定目标的密聊状态。
     */
    fun toggleWhisper(targetKey: String) {
        if (_whisperTargets.contains(targetKey)) {
            // 如果正在密聊此用户且是唯一目标 → 结束全部密聊
            // 如果还有其它目标 → 只移除这一个
            if (_whisperTargets.size == 1) {
                stopWhisper()
            } else {
                removeTarget(targetKey)
            }
        } else {
            startWhisper(targetKey)
        }
    }

    /**
     * 向当前密聊目标发送一条文字消息（与语音密聊并行的补充通道）。
     * 目标键在发送时解析为当前在线的客户端编号——对端重连后依然可达。
     */
    fun sendWhisperMessage(text: String) {
        val client = tsClient ?: return
        if (!isWhisperActive || _whisperTargets.isEmpty()) return

        // 向所有密聊目标发送私信
        for (targetKey in _whisperTargets) {
            val clid = client.users.value.find { keyOf(it) == targetKey }?.id
                ?: targetKey.removePrefix("clid:").toIntOrNull()
            if (clid != null) {
                client.sendPrivateMessage(clid, text)
            } else {
                Log.w(TAG, "Whisper target offline: $targetKey")
            }
        }

        Log.d(TAG, "Whisper text sent to ${_whisperTargets.size} targets")
    }

    /**
     * 获取可以作为密聊候选的用户列表（同频道、非自身）。
     */
    fun getCandidateUsers(): List<User> {
        val client = tsClient ?: return emptyList()
        val myId = client.clientId ?: return emptyList()
        val me = client.users.value.find { it.id == myId } ?: return emptyList()
        val myChannelId = me.channelId

        return client.users.value
            .filter { it.id != myId && it.channelId == myChannelId }
            .sortedBy { it.nickname }
    }

    // ── 内部方法 ────────────────────────────────────────────

    /**
     * 从密聊目标列表中移除一个目标。
     */
    private fun removeTarget(targetKey: String) {
        val idx = _whisperTargets.indexOf(targetKey)
        if (idx >= 0) {
            _whisperTargets.removeAt(idx)
            if (idx < _whisperTargetNames.size) {
                _whisperTargetNames.removeAt(idx)
            }
            Log.d(TAG, "Removed whisper target $targetKey")
        }
        if (_whisperTargets.isEmpty()) {
            isWhisperActive = false
        }
        applyVoiceTargets()
    }

    /**
     * 把当前密聊目标集解析为客户端编号并下发到 Rust 层的语音路由；
     * 空目标集 = 关闭密聊恢复频道讲话；全部目标离线时路由到 clid 0
     * （服务器虚拟客户端，密语包被服务器直接丢弃）——绝不能回落成
     * 频道广播，否则 UI 显示密聊而实际全频道都听得到。
     */
    private fun applyVoiceTargets() {
        val client = tsClient ?: return
        if (_whisperTargets.isEmpty()) {
            client.setWhisperTargets(IntArray(0), LongArray(0))
            Log.d(TAG, "Voice whisper off, back to channel talk")
            return
        }
        val clids = _whisperTargets.mapNotNull { key ->
            client.users.value.find { keyOf(it) == key }?.id
                ?: key.removePrefix("clid:").toIntOrNull()
        }
        if (clids.isEmpty()) {
            Log.w(TAG, "All whisper targets offline; dropping voice (clid 0 sentinel) instead of channel broadcast")
            client.setWhisperTargets(intArrayOf(0), LongArray(0))
            return
        }
        client.setWhisperTargets(clids.toIntArray(), LongArray(0))
        Log.d(TAG, "Voice whisper targets applied: $clids")
    }
}