package dev.tsdroid.bridge

import dev.tsdroid.data.AppLog as Log
import dev.tslib.Channel
import dev.tslib.Client
import dev.tslib.ConnectionState
import dev.tslib.Event
import dev.tslib.Identity
import dev.tslib.ServerInfo
import dev.tslib.User
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class TsFileEntry(
    val name: String,
    val size: Long,
    val datetime: Long,
    val isFile: Boolean,
)

class TsClient {

    companion object {
        private const val TAG = "TsClient"
        private const val INITIAL_CONNECT_SETTLE_MS = 300L
        private const val RECONNECT_AFTER_DISCONNECT_DELAY_MS = 1_500L
        private const val DISCONNECT_MIN_FLUSH_MS = 500L
        private const val DISCONNECT_MAX_FLUSH_MS = 2_000L
        private const val DISCONNECT_POLL_MS = 20L

        /** How often to re-request server variables (online counts, uptime). */
        private const val SERVER_VARIABLES_INTERVAL_MS = 5_000L
    }

    @Volatile
    private var nativeThread: Thread? = null

    private val nativeDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TsClientNative").also { nativeThread = it }
    }.asCoroutineDispatcher()

    @Volatile
    private var client: Client? = null

    @Volatile
    private var cachedClientId: Int? = null

    @Volatile
    var serverAddress: String? = null
        private set

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    // Chat messages get a replay buffer: the UI subscribes some time after
    // startEventLoop() begins (navigation + first composition), and events
    // emitted with no subscriber are dropped by a SharedFlow. Without replay
    // those messages are lost forever. Audio/talk events stay replay-free —
    // the service subscribes in onCreate before any connection exists.
    private val _chatEvents = MutableSharedFlow<Event>(replay = 64, extraBufferCapacity = 16)
    val chatEvents: SharedFlow<Event> = _chatEvents.asSharedFlow()

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<Int> = _state.asStateFlow()

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _users = MutableStateFlow<List<User>>(emptyList())
    val users: StateFlow<List<User>> = _users.asStateFlow()

    private val _serverInfo = MutableStateFlow<ServerInfo?>(null)
    val serverInfo: StateFlow<ServerInfo?> = _serverInfo.asStateFlow()

    private val _commandErrors = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 16)
    val commandErrors: SharedFlow<String> = _commandErrors.asSharedFlow()

    private val downloadCallbacks = ConcurrentHashMap<String, CompletableDeferred<ByteArray>>()
    private val uploadCallbacks = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    // path → channelId of the in-flight request; completion events carry only
    // the path, so the channel is resolved from here (prevents files with the
    // same name in different channels from mixing up callbacks)
    private val downloadKeys = ConcurrentHashMap<String, Long>()
    private val uploadKeys = ConcurrentHashMap<String, Long>()
    private val fileListCallbacks = ConcurrentHashMap<String, CompletableDeferred<List<TsFileEntry>>>()

    private var eventLoopJob: Job? = null
    private val clientCoroutineScope = CoroutineScope(nativeDispatcher + SupervisorJob())
    private val connectMutex = Mutex()

    val isConnected: Boolean
        get() = _state.value == ConnectionState.CONNECTED

    val clientId: Int?
        get() = cachedClientId

    suspend fun connect(
        address: String,
        identity: Identity,
        nickname: String,
        password: String? = null,
        channel: String? = null,
    ) = withContext(nativeDispatcher) {
        connectMutex.withLock {
            try {
                stopEventLoop()
                val hadExistingClient = disconnectOnNativeThread()
                // Drop chat events replayed from the previous session: a UI
                // (re)subscriber after reconnect would otherwise re-process
                // old messages and count already-read ones as unread again
                _chatEvents.resetReplayCache()

                delay(if (hadExistingClient) RECONNECT_AFTER_DISCONNECT_DELAY_MS else INITIAL_CONNECT_SETTLE_MS)

                _state.value = ConnectionState.CONNECTING

                var lastFailure: Throwable? = null
                for (attempt in 0 until MAX_NICKNAME_COLLISION_ATTEMPTS) {
                    val candidateNickname = nicknameWithCollisionSuffix(nickname, attempt)
                    var pendingClient: Client? = null
                    var pendingClientConnected = false
                    var retrying = false
                    try {
                        try {
                            identity.setNickname(candidateNickname)
                        } catch (e: Throwable) {
                            if (e is CancellationException) throw e
                            Log.w(TAG, "Failed to update identity nickname before connect", e)
                        }
                        val c = Client(address, identity, candidateNickname, password, channel)
                        pendingClient = c
                        val waitConnectedStart = System.currentTimeMillis()
                        c.waitConnected()
                        Log.i(TAG, "waitConnected took ${System.currentTimeMillis() - waitConnectedStart}ms (attempt $attempt)")
                        pendingClientConnected = true
                        // Log immediately after waitConnected
                        val users = c.users
                        val channels = c.channels
                        Log.i(TAG, "After waitConnected: ${users?.size ?: "null"} users, ${channels?.size ?: "null"} channels")
                        if (users != null) {
                            for (u in users) {
                                if (u != null) Log.d(TAG, "  User: ${u.nickname} (id=${u.id}, ch=${u.channelId})")
                            }
                        }

                        if (hasNicknameCollision(users, c.clientId, candidateNickname)) {
                            Log.w(TAG, "Nickname '$candidateNickname' is already in use; retrying with suffix")
                            lastFailure = IllegalStateException("Nickname already in use: $candidateNickname")
                            closeClient(c, "nickname collision")
                            pendingClient = null
                            retrying = true
                            continue
                        }

                        client = c
                        pendingClient = null
                        serverAddress = address
                        // A new native client starts with no audio sink —
                        // attach the requested one right here (the only point
                        // where the client is guaranteed to exist)
                        pendingAudioSink?.let { sink ->
                            try {
                                c.setAudioSink(sink)
                                Log.i(TAG, "Audio sink attached to new native client")
                            } catch (e: Throwable) {
                                Log.w(TAG, "Audio sink attach failed", e)
                            }
                        }
                        _state.value = ConnectionState.CONNECTED
                        refreshState()
                        if (client == null) {
                            throw IllegalStateException("Connection closed during initial state sync")
                        }
                        return@withLock
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        lastFailure = e
                        retrying = e.isNicknameCollisionFailure()
                        Log.w(TAG, "Connection attempt failed with nickname '$candidateNickname'", e)
                        if (!retrying || attempt == MAX_NICKNAME_COLLISION_ATTEMPTS - 1) {
                            throw e
                        }
                    } finally {
                        pendingClient?.let {
                            if (pendingClientConnected) {
                                closeClient(it, "failed connection attempt")
                            } else {
                                destroyClient(it, "failed connection attempt before connected")
                            }
                        }
                        if (retrying && attempt < MAX_NICKNAME_COLLISION_ATTEMPTS - 1) {
                            delay(200)
                        }
                    }
                }

                throw Exception(lastFailure.userMessage() ?: "Server rejected the connection", lastFailure)
            } catch (e: Throwable) {
                closeAfterNativeFailure()
                if (e is CancellationException) throw e

                Log.e("TS6_CRASH_PREVENTION", "Connection failed; native client cleaned up", e)
                throw Exception("Connection failed: ${e.message ?: "Server busy or rejected"}", e)
            }
        }
    }

    private fun Throwable?.userMessage(): String? {
        var current = this
        var fallback: String? = null
        while (current != null) {
            current.message?.takeIf { it.isNotBlank() }?.let { fallback = it }
            current = current.cause
        }
        return fallback
    }

    private fun Throwable.isNicknameCollisionFailure(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            val message = current.message?.lowercase().orEmpty()
            if (
                "nickname" in message &&
                (
                    "already" in message ||
                    "in use" in message ||
                    "taken" in message ||
                    "duplicate" in message ||
                    "exists" in message
                )
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    @Volatile private var pollServerVariables = false

    /**
     * Enable the periodic server-variables poll. Only the open server info
     * sheet turns this on — desktop clients likewise query only while the
     * connection info dialog is visible.
     */
    fun setServerVariablesPolling(enabled: Boolean) {
        pollServerVariables = enabled
    }

    fun startEventLoop() {
        // 1. Cancel any active event loop cleanly first
        stopEventLoop()

        // 2. Launch a completely fresh lifecycle track
        eventLoopJob = clientCoroutineScope.launch {
            try {
                var refreshCounter = 0
                var lastEventAt = 0L
                var lastServerVarsAt = 0L
                while (isActive && client != null) {
                    ensureActive()
                    try {
                        val c = client ?: break
                        // Blocking wait in native code: event arrival wakes
                        // the call instantly (audio hot path), the timeout
                        // only bounds silence. No polling sleep here.
                        val pollMs = if (System.currentTimeMillis() - lastEventAt < 2000) 15 else 50
                        val waitStart = System.currentTimeMillis()
                        val events = c.waitEvents(pollMs) ?: emptyArray()
                        val waited = System.currentTimeMillis() - waitStart
                        // Diagnostics: the single native thread must never be
                        // pinned for long — long blocks starve disconnect/send
                        if (waited > 250) {
                            Log.w(TAG, "waitEvents blocked ${waited}ms (timeout=$pollMs, ${events.size} events)")
                        }
                        if (events.isNotEmpty()) lastEventAt = System.currentTimeMillis()
                        for (event in events) {
                            val emitted = if (event.type == "text_message") _chatEvents.tryEmit(event)
                            else _events.tryEmit(event)
                            if (!emitted) Log.w(TAG, "Event buffer overflow, dropped ${event.type}")
                            handleEvent(event)
                        }
                        refreshCounter++
                        // Refresh on events or every ~500ms (25 * 20ms)
                        if (events.isNotEmpty() || refreshCounter >= 25) {
                            refreshState()
                            refreshCounter = 0
                        }
                        // The server only pushes counter/uptime updates when
                        // asked — poll while the info sheet is open so its
                        // values don't freeze at connect-time numbers
                        val now = System.currentTimeMillis()
                        if (pollServerVariables && now - lastServerVarsAt >= SERVER_VARIABLES_INTERVAL_MS) {
                            lastServerVarsAt = now
                            try {
                                c.updateServerVariables()
                            } catch (e: Throwable) {
                                Log.w(TAG, "updateServerVariables failed", e)
                            }
                        }
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        if (client == null) break
                        Log.e(TAG, "Event loop native failure; closing connection", e)
                        _state.value = ConnectionState.DISCONNECTED
                        closeAfterNativeFailure()
                        break
                    }
                    // No sleep: waitEvents already blocked in native code for
                    // up to pollMs — arrival wakes it instantly, silence costs
                    // one bounded native wait per tick instead of poll+delay.
                    // yield() is still mandatory: it is this loop's only
                    // suspension point, and without it the loop monopolizes
                    // the single nativeDispatcher thread — disconnect (queued
                    // behind it via runBlocking) would never run.
                    kotlinx.coroutines.yield()
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Event loop coroutine clean cancelled.")
            } catch (e: Throwable) {
                Log.e(TAG, "Gracefully trapped event loop runtime friction", e)
                _state.value = ConnectionState.DISCONNECTED
                closeAfterNativeFailure()
            }
        }
    }

    fun stopEventLoop() {
        eventLoopJob?.let {
            if (it.isActive) {
                it.cancel()
                Log.d(TAG, "Killing stagnant legacy event loop forcefully.")
            }
        }
        eventLoopJob = null
    }

    private fun handleEvent(event: Event) {
        when (event.type) {
            "disconnected" -> _state.value = ConnectionState.DISCONNECTED
            "connected" -> _state.value = ConnectionState.CONNECTED
            "file_downloaded" -> {
                val path = event.data["path"] as? String ?: return
                val data = event.data["data"] as? ByteArray ?: return
                val channelId = downloadKeys.remove(path) ?: 0L
                Log.d(TAG, "File downloaded: ch=$channelId path=$path (${data.size} bytes)")
                downloadCallbacks.remove("$channelId:$path")?.complete(data)
            }
            "file_uploaded" -> {
                val path = event.data["path"] as? String ?: return
                val channelId = uploadKeys.remove(path) ?: 0L
                Log.d(TAG, "File uploaded: ch=$channelId path=$path")
                uploadCallbacks.remove("$channelId:$path")?.complete(true)
            }
            "file_transfer_failed" -> {
                val path = event.data["path"] as? String ?: return
                val error = event.data["error"] as? String ?: "unknown"
                Log.w(TAG, "File transfer failed: $path — $error")
                val dlChannel = downloadKeys.remove(path) ?: 0L
                downloadCallbacks.remove("$dlChannel:$path")?.completeExceptionally(
                    Exception("File transfer failed: $error")
                )
                val upChannel = uploadKeys.remove(path) ?: 0L
                uploadCallbacks.remove("$upChannel:$path")?.complete(false)
            }
            "file_list_received" -> {
                val channelId = (event.data["channel_id"] as? Number)?.toLong() ?: return
                val path = event.data["path"] as? String ?: return
                val filesJson = event.data["files"] as? String ?: return
                val entries = parseFileEntries(filesJson)
                Log.d(TAG, "File list received: channel=$channelId path=$path entries=${entries.size}")
                fileListCallbacks.remove("$channelId:$path")?.complete(entries)
            }
            "command_error" -> {
                val message = event.data["message"] as? String ?: return
                Log.w(TAG, "Command error: $message")
                _commandErrors.tryEmit(message)
            }
            "channel_permissions_updated" -> {
                val channelId = (event.data["channel_id"] as? Number)?.toLong() ?: return
                val hints = (event.data["permission_hints"] as? Number)?.toLong() ?: return
                Log.i(TAG, "Channel $channelId permissions updated: ${hints.toString(16)}")
                // Force refresh to propagate updated permission_hints
                refreshState()
            }
        }
    }

    private fun refreshState() {
        val c = client ?: return
        try {
            _channels.value = c.channels?.filterNotNull() ?: emptyList()
            val rawUsers = c.users
            val filteredUsers = rawUsers?.filterNotNull() ?: emptyList()
            Log.d(TAG, "refreshState: rawUsers=${rawUsers?.size}, filtered=${filteredUsers.size}")
            if (filteredUsers.isNotEmpty()) {
                for (u in filteredUsers) {
                    Log.d(TAG, "  user: ${u.nickname} id=${u.id} ch=${u.channelId}")
                }
            }
            _users.value = filteredUsers
            _serverInfo.value = c.serverInfo
            cachedClientId = c.clientId
            val st = c.state
            if (_state.value != st) _state.value = st
        } catch (e: Throwable) {
            Log.w(TAG, "refreshState failed", e)
            closeAfterNativeFailure()
        }
    }

    private fun closeAfterNativeFailure() {
        val c = client
        client = null
        resetState()
        if (c != null) {
            closeClient(c, "native failure")
        }
    }

    private fun disconnectOnNativeThread(): Boolean {
        stopEventLoop()
        val c = client
        client = null
        resetState()
        if (c != null) {
            closeClient(c, "disconnect")
            return true
        }
        return false
    }

    private fun resetState() {
        _state.value = ConnectionState.DISCONNECTED
        _channels.value = emptyList()
        _users.value = emptyList()
        _serverInfo.value = null
        cachedClientId = null
    }

    private fun closeClient(c: Client, reason: String) {
        val t0 = System.currentTimeMillis()
        var disconnectSent = false
        try {
            c.disconnect()
            disconnectSent = true
            Log.d(TAG, "Native disconnect command sent ($reason) in ${System.currentTimeMillis() - t0}ms")
        } catch (e: Throwable) {
            Log.w(TAG, "disconnect during $reason failed after ${System.currentTimeMillis() - t0}ms", e)
        }

        if (disconnectSent) {
            flushDisconnect(c, reason)
        }

        val t1 = System.currentTimeMillis()
        destroyClient(c, reason)
        Log.d(TAG, "closeClient($reason) total ${System.currentTimeMillis() - t0}ms (destroy ${System.currentTimeMillis() - t1}ms)")
    }

    private fun flushDisconnect(c: Client, reason: String) {
        val startedAt = System.currentTimeMillis()
        val minFlushEnd = startedAt + DISCONNECT_MIN_FLUSH_MS
        val maxFlushEnd = startedAt + DISCONNECT_MAX_FLUSH_MS
        var observedDisconnected = false

        while (System.currentTimeMillis() < maxFlushEnd) {
            try {
                val events = c.processEvents() ?: emptyArray()
                if (events.any { it.type == "disconnected" }) {
                    observedDisconnected = true
                }
                if (!c.isConnected || c.state == ConnectionState.DISCONNECTED) {
                    observedDisconnected = true
                }
            } catch (e: Throwable) {
                Log.w(TAG, "disconnect flush during $reason failed", e)
                break
            }

            if (observedDisconnected && System.currentTimeMillis() >= minFlushEnd) {
                break
            }

            try {
                Thread.sleep(DISCONNECT_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        Log.d(TAG, "Disconnect flush complete ($reason, observedDisconnected=$observedDisconnected, took ${System.currentTimeMillis() - startedAt}ms)")
    }

    private fun destroyClient(c: Client, reason: String) {
        try {
            c.close()
        } catch (e: Throwable) {
            Log.w(TAG, "close during $reason failed", e)
        }
    }

    private fun launchNativeCommand(name: String, block: Client.() -> Unit) {
        clientCoroutineScope.launch {
            val c = client ?: return@launch
            try {
                c.block()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.w(TAG, "$name failed", e)
            }
        }
    }

    // Audio sink requested before a native client exists (or across
    // reconnects): re-attached to every freshly created native client
    private var pendingAudioSink: dev.tslib.AudioSink? = null

    fun setAudioSink(sink: dev.tslib.AudioSink?) {
        pendingAudioSink = sink
        launchNativeCommand("setAudioSink") {
            this.setAudioSink(sink)
        }
    }

    fun sendChannelMessage(msg: String) {
        launchNativeCommand("sendChannelMessage") {
            this.sendChannelMessage(msg)
        }
    }

    fun sendServerMessage(msg: String) {
        launchNativeCommand("sendServerMessage") {
            this.sendServerMessage(msg)
        }
    }

    fun sendPrivateMessage(userId: Int, msg: String) {
        launchNativeCommand("sendPrivateMessage") {
            this.sendPrivateMessage(userId, msg)
        }
    }

    fun sendPoke(userId: Int, msg: String) {
        launchNativeCommand("sendPoke") {
            this.sendPoke(userId, msg)
        }
    }

    /**
     * Route outgoing voice to an explicit whisper target set (TS3 密聊)。
     * Empty arrays restore normal channel talk. The capture pipeline keeps
     * calling [sendAudio] unchanged — routing happens on the native side.
     */
    fun setWhisperTargets(clients: IntArray, channels: LongArray) {
        launchNativeCommand("setWhisperTargets") {
            this.setWhisperTargets(clients, channels)
        }
    }

    fun moveToChannel(channelId: Long) {
        moveToChannel(channelId, null)
    }

    fun moveToChannel(channelId: Long, password: String?) {
        launchNativeCommand("moveToChannel") {
            this.moveToChannel(channelId, password)
        }
    }

    /**
     * Snapshot of connection quality from the native UDP layer
     * (rtt/rtt-dev in ms, loss fractions, per-second byte counts),
     * or null while not connected.
     */
    suspend fun getNetworkStats(): DoubleArray? = withContext(nativeDispatcher) {
        try {
            client?.getNetworkStats()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Log.w(TAG, "getNetworkStats failed", e)
            null
        }
    }

    fun sendAudio(data: ByteArray, codec: Int) {
        launchNativeCommand("sendAudio") {
            this.sendAudio(data, codec)
        }
    }

    fun setInputMuted(muted: Boolean) {
        Log.i(TAG, "setInputMuted($muted) client=${if (client != null) "present" else "NULL"}")
        launchNativeCommand("setInputMuted") {
            this.setInputMuted(muted)
        }
    }

    suspend fun downloadFile(channelId: Long, path: String): ByteArray? {
        val deferred = CompletableDeferred<ByteArray>()
        val key = "$channelId:$path"
        downloadKeys[path] = channelId
        downloadCallbacks[key] = deferred
        val started = withContext(nativeDispatcher) {
            try {
                val c = client ?: return@withContext false
                c.downloadFile(channelId, path)
                true
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.w(TAG, "downloadFile failed for $path", e)
                false
            }
        }
        if (!started) {
            downloadCallbacks.remove(key)
            downloadKeys.remove(path)
            return null
        }
        return withTimeoutOrNull(10_000) {
            try {
                deferred.await()
            } catch (e: Exception) {
                Log.w(TAG, "downloadFile await failed for $path", e)
                null
            }
        }.also {
            downloadCallbacks.remove(key)
            downloadKeys.remove(path)
        }
    }

    suspend fun listFiles(channelId: Long, path: String): List<TsFileEntry>? {
        val key = "$channelId:$path"
        val deferred = CompletableDeferred<List<TsFileEntry>>()
        fileListCallbacks[key] = deferred
        val started = withContext(nativeDispatcher) {
            try {
                val c = client ?: return@withContext false
                c.listFiles(channelId, path)
                true
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.w(TAG, "listFiles failed for $path", e)
                false
            }
        }
        if (!started) {
            fileListCallbacks.remove(key)
            return null
        }
        return withTimeoutOrNull(5_000) {
            try { deferred.await() } catch (e: Exception) {
                Log.w(TAG, "listFiles await failed for $path", e)
                null
            }
        }.also { fileListCallbacks.remove(key) }
    }

    fun deleteFile(channelId: Long, name: String) {
        launchNativeCommand("deleteFile") {
            this.deleteFile(channelId, name)
        }
    }

    fun renameFile(channelId: Long, oldName: String, newName: String) {
        launchNativeCommand("renameFile") {
            this.renameFile(channelId, oldName, newName)
        }
    }

    fun createDirectory(channelId: Long, dirname: String) {
        launchNativeCommand("createDirectory") {
            this.createDirectory(channelId, dirname)
        }
    }

    fun queryChannelPermissions(channelId: Long) {
        launchNativeCommand("queryChannelPermissions") {
            this.queryChannelPermissions(channelId)
        }
    }

    private fun parseFileEntries(json: String): List<TsFileEntry> {
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                TsFileEntry(
                    name = obj.getString("name"),
                    size = obj.getLong("size"),
                    datetime = obj.getLong("datetime"),
                    isFile = obj.getBoolean("is_file"),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseFileEntries failed", e)
            emptyList()
        }
    }

    suspend fun uploadFile(channelId: Long, path: String, data: ByteArray, overwrite: Boolean = true): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val key = "$channelId:$path"
        uploadKeys[path] = channelId
        uploadCallbacks[key] = deferred
        val started = withContext(nativeDispatcher) {
            try {
                val c = client ?: return@withContext false
                c.uploadFile(channelId, path, data, overwrite)
                true
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.w(TAG, "uploadFile failed for $path", e)
                false
            }
        }
        if (!started) {
            uploadCallbacks.remove(key)
            uploadKeys.remove(path)
            return false
        }
        return withTimeoutOrNull(30_000) {
            try {
                deferred.await()
            } catch (e: Exception) {
                Log.w(TAG, "uploadFile await failed for $path", e)
                false
            }
        }.also {
            uploadCallbacks.remove(key)
            uploadKeys.remove(path)
        } ?: false
    }

    fun disconnect() {
        if (Thread.currentThread() == nativeThread) {
            disconnectOnNativeThread()
        } else {
            runBlocking(nativeDispatcher) {
                disconnectOnNativeThread()
            }
        }
    }
}
